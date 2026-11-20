package com.mallocgfw.app.xray

import android.content.Context
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.TlsPinCache
import com.mallocgfw.app.model.TlsPinTarget
import java.net.InetSocketAddress
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

    suspend fun refresh(context: Context, nodes: Collection<ServerNode>) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val targets = nodes.mapNotNull { node ->
            runCatching { ManualNodeFactory.insecureTlsTarget(node) }.getOrNull()?.let { node to it }
        }.distinctBy { it.second.key }
        if (targets.isEmpty()) return@withContext
        coroutineScope {
            targets.map { (node, target) ->
                async {
                    val last = fetchedAt[target.key]
                    if (last != null && System.currentTimeMillis() - last < FRESH_MS && TlsPinCache.get(target) != null) {
                        return@async
                    }
                    val pin = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { runCatching { fetchLeafSha256(target) } }
                    val result = pin?.getOrNull()
                    if (result != null) {
                        val previous = TlsPinCache.get(target)
                        TlsPinCache.put(target, result)
                        fetchedAt[target.key] = System.currentTimeMillis()
                        if (previous != result) {
                            AppLogManager.append(
                                context,
                                "TLS",
                                "${node.name} 跳过了证书校验，已改为固定服务器证书 SHA-256 ${result.take(12)}…",
                            )
                        }
                    } else {
                        val reason = pin?.exceptionOrNull()?.message ?: "超时"
                        val fallback = if (TlsPinCache.get(target) != null) "，沿用上次保存的证书" else ""
                        AppLogManager.append(
                            context,
                            "TLS",
                            "${node.name} 获取服务器证书失败（$reason）$fallback",
                        )
                    }
                }
            }.awaitAll()
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
    private fun fetchLeafSha256(target: TlsPinTarget): String {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(CaptureOnlyTrustManager), SecureRandom())
        Socket().use { raw ->
            raw.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = CONNECT_TIMEOUT_MS
            (context.socketFactory.createSocket(raw, target.host, target.port, false) as SSLSocket).use { socket ->
                if (!target.serverName.isIpLiteral()) {
                    socket.sslParameters = socket.sslParameters.apply {
                        serverNames = listOf(SNIHostName(target.serverName))
                    }
                }
                socket.startHandshake()
                val leaf = socket.session.peerCertificates.firstOrNull()
                    ?: error("服务器未返回证书")
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
