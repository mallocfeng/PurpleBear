package com.mallocgfw.app.model

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Where to fetch a certificate for a node that asked to skip TLS verification.
 * [serverName] is the SNI sent in the handshake (falls back to [host]).
 */
data class TlsPinTarget(
    val host: String,
    val port: Int,
    val serverName: String,
) {
    val key: String
        get() = listOf(host, port.toString(), serverName).joinToString("|") { it.lowercase(Locale.ROOT) }
}

/**
 * Leaf-certificate SHA-256 pins learned for nodes that set `allowInsecure`.
 *
 * Xray-core v26.10+ refuses `allowInsecure`; the replacement is
 * `pinnedPeerCertSha256`. Share links and subscriptions still carry
 * `allowInsecure=1` (usually for self-signed certificates), so the app fetches
 * the server's certificate right before connecting and pins it. That is
 * stricter than the old "accept anything" behaviour and keeps such nodes working.
 *
 * The cache is in-memory and pure JVM so config building stays testable; the
 * Android side persists it (see InsecureTlsPinner).
 */
object TlsPinCache {
    private val pins = ConcurrentHashMap<String, String>()

    @Volatile
    var onChanged: ((Map<String, String>) -> Unit)? = null

    fun get(target: TlsPinTarget): String? = pins[target.key]

    fun put(target: TlsPinTarget, sha256Hex: String) {
        val normalized = sha256Hex.lowercase(Locale.ROOT)
        if (pins.put(target.key, normalized) != normalized) {
            onChanged?.invoke(HashMap(pins))
        }
    }

    fun restore(saved: Map<String, String>) {
        pins.putAll(saved)
    }

    fun clear() {
        pins.clear()
    }
}
