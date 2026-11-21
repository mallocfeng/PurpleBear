package com.mallocgfw.app.xray

import android.content.Context
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.TlsPinCache
import com.mallocgfw.app.model.TlsPinTarget
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.mallocgfw.app.model.tr

/**
 * Turns `allowInsecure` nodes into certificate-pinned ones right before the
 * core starts (see [TlsPinCache] for why). The certificate is fetched fresh on
 * every connect so a renewed certificate is picked up; the stored pin is only
 * a fallback when the fetch fails.
 */
object InsecureTlsPinner {
    private const val PREFS = "tls_pins"
    private const val KEY_PINS = "pins"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val TOTAL_TIMEOUT_MS = 6_000L
    // Skip re-fetching when the same endpoint was pinned moments ago (reconnects).
    private const val FRESH_MS = 60_000L

    @Volatile
    private var loaded = false
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    /** Pins the nodes reached directly from the device. */
    suspend fun refresh(context: Context, nodes: Collection<ServerNode>) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val targets = pending(nodes)
        if (targets.isEmpty()) return@withContext
        coroutineScope {
            targets.map { (node, target) -> async { pin(context, node, target, proxy = null) } }.awaitAll()
        }
    }

    /**
     * Nodes behind a pre-proxy: the exit is often unreachable from the device
     * (that is why there is a pre-proxy), and dialing it directly would also
     * expose the device's own address to it. [proxy] reaches the node through
     * its pre-proxy chain; see XrayCoreManager.fetchChainedPins.
     */
    suspend fun refreshThrough(context: Context, nodes: Collection<ServerNode>, proxy: Proxy) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        pending(nodes).forEach { (node, target) -> pin(context, node, target, proxy) }
    }

    /** True when one of [nodes] needs a certificate fetch now. */
    fun hasPending(context: Context, nodes: Collection<ServerNode>): Boolean {
        ensureLoaded(context)
        return pending(nodes).isNotEmpty()
    }

    /** Nodes of [nodes] that need a certificate fetch now. */
    private fun pending(nodes: Collection<ServerNode>): List<Pair<ServerNode, TlsPinTarget>> =
        nodes.mapNotNull { node ->
            runCatching { ManualNodeFactory.insecureTlsTarget(node) }.getOrNull()?.let { node to it }
        }.distinctBy { it.second.key }.filter { (_, target) ->
            val last = fetchedAt[target.key]
            !(last != null && System.currentTimeMillis() - last < FRESH_MS && TlsPinCache.get(target) != null)
        }

    private suspend fun pin(context: Context, node: ServerNode, target: TlsPinTarget, proxy: Proxy?) {
        val pin = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { runCatching { fetchLeafSha256(target, proxy) } }
        val result = pin?.getOrNull()
        if (result != null) {
            val previous = TlsPinCache.get(target)
            TlsPinCache.put(target, result)
            fetchedAt[target.key] = System.currentTimeMillis()
            if (previous != result) {
                AppLogManager.append(
                    context,
                    "TLS",
                    "${node.name} 跳过了证书校验，已改为固定服务器证书 SHA-256 ${result.take(12)}…" +
                        if (proxy != null) "（经前置代理获取）" else "",
                )
            }
        } else {
            val reason = pin?.exceptionOrNull()?.message ?: "超时"
            val fallback = if (TlsPinCache.get(target) != null) "，沿用上次保存的证书" else ""
            val via = if (proxy != null) "经前置代理" else ""
            AppLogManager.append(
                context,
                "TLS",
                "${node.name} ${via}获取服务器证书失败（$reason）$fallback",
            )
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            runCatching {
                val json = JSONObject(prefs.getString(KEY_PINS, "{}") ?: "{}")
                TlsPinCache.restore(json.keys().asSequence().associateWith { json.getString(it) })
            }
            TlsPinCache.onChanged = { pins ->
                prefs.edit().putString(KEY_PINS, JSONObject(pins as Map<*, *>).toString()).apply()
            }
            loaded = true
        }
    }

    /**
     * Opens a TLS connection only to read the server's leaf certificate. No
     * data is sent, so accepting any certificate here is safe; the hash is
     * what Xray then verifies on every real connection.
     */
    private fun fetchLeafSha256(target: TlsPinTarget, proxy: Proxy?): String {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(CaptureOnlyTrustManager), SecureRandom())
        (if (proxy == null) Socket() else Socket(proxy)).use { raw ->
            // Through the proxy the name is resolved at the far end, as Xray does.
            val address = if (proxy == null) {
                InetSocketAddress(target.host, target.port)
            } else {
                InetSocketAddress.createUnresolved(target.host, target.port)
            }
            raw.connect(address, CONNECT_TIMEOUT_MS)
            raw.soTimeout = CONNECT_TIMEOUT_MS
            (context.socketFactory.createSocket(raw, target.host, target.port, false) as SSLSocket).use { socket ->
                if (!target.serverName.isIpLiteral()) {
                    socket.sslParameters = socket.sslParameters.apply {
                        serverNames = listOf(SNIHostName(target.serverName))
                    }
                }
                socket.startHandshake()
                val leaf = socket.session.peerCertificates.firstOrNull()
                    ?: error(tr("服务器未返回证书", "The server returned no certificate", "Сервер не вернул сертификат"))
                return MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it) }
            }
        }
    }

    private fun String.isIpLiteral(): Boolean {
        return contains(':') || all { it.isDigit() || it == '.' }
    }

    @Suppress("CustomX509TrustManager")
    private object CaptureOnlyTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
