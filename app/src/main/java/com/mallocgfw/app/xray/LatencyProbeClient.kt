package com.mallocgfw.app.xray

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for [LatencyProbeService]: one request through one node (and its
 * pre-proxy chain), timed by libXray's `pingBatch`.
 *
 * Every failure of the plumbing (the process can't start, dies, answers
 * something unexpected) is a [ProbeUnavailable], so callers can fall back to
 * the older TCP test; only a node that didn't answer is a result.
 */
object LatencyProbeClient {
    /** libXray merges at most this many configs into one probe core. */
    const val MAX_BATCH = 5

    /** The probe couldn't be run at all (as opposed to: the node didn't answer). */
    class ProbeUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    class Item(val xrayJson: String, val outboundTag: String = "proxy")

    /** [delayMs] when the node answered; else [timedOut] or [error]. */
    class Result(val delayMs: Int?, val timedOut: Boolean, val error: String?)

    private const val LIBXRAY_API_VERSION = 3
    private const val BIND_TIMEOUT_MS = 8_000L
    private const val IDLE_UNBIND_MS = 45_000L
    private val bindMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var probe: ILatencyProbe? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null

    suspend fun pingBatch(context: Context, items: List<Item>, timeoutSeconds: Int, url: String): List<Result> {
        require(items.isNotEmpty() && items.size <= MAX_BATCH)
        val request = JSONObject()
            .put("apiVersion", LIBXRAY_API_VERSION)
            .put("method", "pingBatch")
            .put(
                "payload",
                JSONObject()
                    .put("configs", JSONArray().apply { items.forEach { put(JSONObject().put("xrayJson", it.xrayJson).put("outboundTag", it.outboundTag)) } })
                    .put("timeout", timeoutSeconds)
                    .put("url", url),
            )
            .toString()
        val raw = try {
            val service = service(context.applicationContext)
            withContext(Dispatchers.IO) {
                // The binder call blocks until every probe is done or timed out.
                withTimeout(timeoutSeconds * 1000L + 10_000L) {
                    kotlinx.coroutines.runInterruptible { service.invoke(request) }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            drop()
            throw ProbeUnavailable("probe process did not answer", error)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            drop()
            throw ProbeUnavailable(error.message ?: "probe process failed", error)
        } finally {
            scheduleIdleUnbind(context.applicationContext)
        }
        return parse(raw, items.size)
    }

    /** internal for tests. */
    internal fun parse(raw: String, expected: Int): List<Result> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw ProbeUnavailable("unreadable probe response")
        if (!json.optBoolean("success", false)) throw ProbeUnavailable(json.optString("error").ifBlank { "probe failed" })
        val results = json.optJSONObject("data")?.optJSONArray("results")
            ?: throw ProbeUnavailable("probe response has no results")
        if (results.length() != expected) throw ProbeUnavailable("probe answered ${results.length()} of $expected nodes")
        return List(expected) { index ->
            val item = results.optJSONObject(index) ?: return@List Result(null, timedOut = false, error = "no result")
            val delay = item.optLong("delay", PING_DELAY_ERROR)
            if (item.optBoolean("success", false)) {
                Result(delay.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(), timedOut = false, error = null)
            } else {
                Result(null, timedOut = delay == PING_DELAY_TIMEOUT, error = item.optString("error").ifBlank { null })
            }
        }
    }

    // libXray nodep/measure.go
    private const val PING_DELAY_TIMEOUT = 11_000L
    private const val PING_DELAY_ERROR = 10_000L

    private suspend fun service(context: Context): ILatencyProbe {
        probe?.let { return it }
        return bindMutex.withLock {
            probe ?: withTimeout(BIND_TIMEOUT_MS) { bind(context) }
        }
    }

    private suspend fun bind(context: Context): ILatencyProbe = suspendCancellableCoroutine { continuation ->
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val bound = ILatencyProbe.Stub.asInterface(binder)
                probe = bound
                if (continuation.isActive) continuation.resume(bound)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                probe = null
            }

            override fun onBindingDied(name: ComponentName) {
                probe = null
            }
        }
        val bound = runCatching {
            context.bindService(Intent(context, LatencyProbeService::class.java), next, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) {
            synchronized(this) { connection = next }
            continuation.invokeOnCancellation { runCatching { context.unbindService(next) } }
        } else if (continuation.isActive) {
            runCatching { context.unbindService(next) }
            continuation.resumeWithException(IllegalStateException("could not start the probe process"))
        }
    }

    /** Forget a binding that failed, so the next call starts a fresh process. */
    private fun drop() {
        probe = null
    }

    /** The probe process holds a whole Go runtime: let it go once tests stop. */
    private fun scheduleIdleUnbind(context: Context) {
        synchronized(this) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_UNBIND_MS)
                val bound = synchronized(this@LatencyProbeClient) { connection.also { connection = null } }
                probe = null
                bound?.let { runCatching { context.unbindService(it) } }
            }
        }
    }
}
