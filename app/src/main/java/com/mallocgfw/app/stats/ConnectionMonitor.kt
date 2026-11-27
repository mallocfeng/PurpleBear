package com.mallocgfw.app.stats

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libXray.LibXray

/** A connection with the names the lists show. */
data class LabeledConnection(
    val connection: TrackedConnection,
    val appKey: String,
    val appLabel: String,
    val nodeLabel: String,
    val ruleLabel: String,
    /** The node key ([TrafficLabels.nodeKey]): a node name or a [TrafficHistory.Route]. */
    val nodeKey: String = "",
    /** The rule kind ([RuleTags.Kind.id]); null for the fallback. */
    val ruleKind: String? = null,
)

/** Upload and download speed at [timeMs], bytes per second. */
data class SpeedSample(val timeMs: Long, val up: Long, val down: Long)

data class ConnectionView(
    /** The VPN is up and connections are being tracked. */
    val running: Boolean = false,
    /** Newest first. */
    val active: List<LabeledConnection> = emptyList(),
    /** Newest first, at most [ConnectionMonitor.MAX_CLOSED]. */
    val closed: List<LabeledConnection> = emptyList(),
    /** Bytes per second over the last look. */
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val updatedMs: Long = 0,
    /** Recent speeds, oldest first, at most [ConnectionMonitor.MAX_SAMPLES]. */
    val speedHistory: List<SpeedSample> = emptyList(),
)

/**
 * Reads the core's connection tracker while the VPN runs: publishes the
 * connection list ([view]) and adds the bytes moved to [TrafficStatsStore].
 * Looks every [IDLE_INTERVAL_MS] in the background and every second while
 * someone watches ([touch]: the statistics screen, the remote panel).
 */
object ConnectionMonitor {
    private const val TAG = "ConnectionMonitor"
    const val MAX_CLOSED = 200
    const val MAX_SAMPLES = 150
    private const val LIVE_INTERVAL_MS = 1_000L
    private const val IDLE_INTERVAL_MS = 30_000L
    private const val LIVE_HOLD_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val delta = TotalsDelta()
    private val closed = ArrayDeque<LabeledConnection>()
    private val samples = ArrayDeque<SpeedSample>()
    private var lastClosedId = 0L
    private var lastPollMs = 0L
    private var job: Job? = null

    @Volatile
    private var liveUntilMs = 0L

    private val _view = MutableStateFlow(ConnectionView())
    val view: StateFlow<ConnectionView> = _view.asStateFlow()

    /** Someone is looking: poll every second for the next few seconds. */
    fun touch() {
        val wasIdle = System.currentTimeMillis() > liveUntilMs
        liveUntilMs = System.currentTimeMillis() + LIVE_HOLD_MS
        if (wasIdle) wake.trySend(Unit)
    }

    /** The VPN is up: track its connections. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        runCatching { LibXray.connTrackSetEnabled(true) }
            .onFailure { Log.w(TAG, "Connection tracking unavailable", it) }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                poll(app)
                val interval = if (System.currentTimeMillis() < liveUntilMs) LIVE_INTERVAL_MS else IDLE_INTERVAL_MS
                withTimeoutOrNull(interval) { wake.receive() }
            }
        }
    }

    /** The VPN stopped: count what is left, then go quiet. */
    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            job?.cancel()
            job = null
        }
        poll(app)
        runCatching { LibXray.connTrackSetEnabled(false) }
        synchronized(this) { samples.clear() }
        _view.value = _view.value.copy(running = false, active = emptyList(), upSpeed = 0, downSpeed = 0, speedHistory = emptyList())
        TrafficStatsStore.flush(app)
    }

    @Synchronized
    private fun poll(context: Context) {
        val snapshot = runCatching { ConnectionSnapshot.parse(LibXray.connTrackSnapshot(lastClosedId)) }
            .onFailure { Log.w(TAG, "Couldn't read the connection list", it) }
            .getOrNull() ?: return
        val now = System.currentTimeMillis()

        val moved = delta.next(snapshot)
        TrafficStatsStore.add(
            context,
            moved.map { Triple(TrafficLabels.appKey(context, it.uid), TrafficLabels.nodeKey(it.outbound), TrafficCount(it.up, it.down)) },
        )
        val seconds = ((now - lastPollMs).coerceAtLeast(1) / 1000.0).takeIf { lastPollMs > 0 }
        lastPollMs = now

        snapshot.closed.forEach { connection ->
            closed.addFirst(label(context, connection))
            lastClosedId = maxOf(lastClosedId, connection.id)
        }
        while (closed.size > MAX_CLOSED) closed.removeLast()

        val upSpeed = seconds?.let { (moved.sumOf { it.up } / it).toLong() } ?: 0
        val downSpeed = seconds?.let { (moved.sumOf { it.down } / it).toLong() } ?: 0
        if (seconds != null) {
            samples.addLast(SpeedSample(now, upSpeed, downSpeed))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }

        _view.value = ConnectionView(
            running = snapshot.enabled,
            active = snapshot.active.sortedByDescending { it.id }.map { label(context, it) },
            closed = closed.toList(),
            upSpeed = upSpeed,
            downSpeed = downSpeed,
            updatedMs = now,
            speedHistory = samples.toList(),
        )
    }

    private fun label(context: Context, connection: TrackedConnection): LabeledConnection {
        val appKey = TrafficLabels.appKey(context, connection.uid)
        val nodeKey = TrafficLabels.nodeKey(connection.outbound)
        return LabeledConnection(
            connection = connection,
            appKey = appKey,
            appLabel = TrafficLabels.appLabel(context, appKey),
            nodeLabel = TrafficLabels.nodeLabel(nodeKey),
            ruleLabel = RuleTags.describe(connection.rule),
            nodeKey = nodeKey,
            ruleKind = RuleTags.parse(connection.rule)?.first?.id,
        )
    }
}
