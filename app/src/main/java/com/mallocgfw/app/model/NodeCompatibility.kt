package com.mallocgfw.app.model

import java.net.InetAddress
import java.util.Locale
import org.json.JSONObject

/** One proxy for the mihomo sidecar config (a Clash proxy map). */
data class MihomoProxy(val name: String, val config: JSONObject) {
    /**
     * YAML for the sidecar's `proxies:` list (JSON is valid YAML flow syntax).
     * A Clash dialer-proxy chain stored under [DIALER_KEY] becomes extra
     * entries named "<name>-f1", "<name>-f2"… linked by `dialer-proxy`.
     */
    val yamlLines: List<String>
        get() {
            val lines = mutableListOf<String>()
            var current = JSONObject(config.toString()).put("name", name)
            var hop = 0
            while (true) {
                val front = current.optJSONObject(DIALER_KEY)
                current.remove(DIALER_KEY)
                if (front == null || hop >= 4) {
                    lines += "  - $current"
                    return lines
                }
                val frontName = "$name-f${++hop}"
                lines += "  - " + current.put("dialer-proxy", frontName)
                current = JSONObject(front.toString()).put("name", frontName)
            }
        }

    companion object {
        const val DIALER_KEY = "pb-dialer"
    }
}

/**
 * Decides how a node runs with the bundled cores:
 * - directly in Xray (the usual case),
 * - in the mihomo sidecar ([mihomoProxy]) when Xray can't: protocols Xray
 *   lacks (SSR, TUIC, AnyTLS, Hysteria v1, Snell, Shadowsocks plugins…),
 *   configs Xray 26 refuses (HTTP/2, plain VLESS/Trojan to public hosts,
 *   legacy flows and ciphers) and Hysteria2 without certificate verification,
 * - or not at all ([coreIncompatibility]) when neither core can.
 */
object NodeCompatibility {
    private val XRAY_SS_METHODS = setOf(
        "aes-128-gcm", "aead_aes_128_gcm",
        "aes-256-gcm", "aead_aes_256_gcm",
        "chacha20-poly1305", "aead_chacha20_poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "aead_xchacha20_poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )
    private val VLESS_FLOWS = setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    private val REMOVED_TRANSPORTS = setOf("http", "h2", "h3", "quic")
    private val PRIVATE_DOMAIN_SUFFIXES = listOf("lan", "localdomain", "example", "invalid", "localhost", "test", "local", "home.arpa", "internal")

    /** Why no bundled core can run this node, or null when one can. */
    fun coreIncompatibility(server: ServerNode): String? {
        if (mihomoProxy(server) != null) return null
        if (server.mihomoProxyJson.isNotBlank()) {
            return runCatching { JSONObject(server.mihomoProxyJson) }.getOrNull()?.let(MihomoNodes::sidecarRejection)
                ?: "节点配置无法解析"
        }
        val outbound = runCatching { ManualNodeFactory.inspectOutbound(server) }.getOrNull() ?: return null
        return reasonFor(outbound)
    }

    /** True when the node runs in the mihomo sidecar rather than in Xray. */
    fun runsInSidecar(server: ServerNode): Boolean = mihomoProxy(server) != null

    /** Why Xray 26 refuses this outbound, or null when it accepts it. */
    internal fun reasonFor(outbound: JSONObject): String? {
        val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
        val stream = outbound.optJSONObject("streamSettings")
        val network = stream?.optString("network").orEmpty().lowercase(Locale.ROOT)
        val security = stream?.optString("security").orEmpty().lowercase(Locale.ROOT)
        if (network in REMOVED_TRANSPORTS) {
            return if (network == "quic") "QUIC 传输已被 Xray 内核移除" else "HTTP/2 传输已被 Xray 内核移除，请改用 XHTTP"
        }
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val endpoint = firstEndpoint(settings)
        val user = endpoint?.optJSONArray("users")?.optJSONObject(0) ?: endpoint ?: settings
        if (security == "xtls") return "旧版 XTLS 已被 Xray 内核移除"
        val transportSecured = security == "tls" || security == "reality"
        when (protocol) {
            "vless" -> {
                val flow = user.optString("flow").lowercase(Locale.ROOT)
                if (flow !in VLESS_FLOWS) return "旧版 XTLS 流控（$flow）已被 Xray 内核移除"
                val encryption = user.optString("encryption").ifBlank { "none" }
                if (!transportSecured && encryption == "none" && isPublicHost(endpoint?.optString("address").orEmpty())) {
                    return "未加密的 VLESS 不能连接公网服务器（Xray 安全限制）"
                }
            }
            "trojan" -> {
                if (user.optString("flow").isNotBlank()) return "Trojan 的 flow 已被 Xray 内核移除"
                if (!transportSecured && isPublicHost(endpoint?.optString("address").orEmpty())) {
                    return "未加密的 Trojan 不能连接公网服务器（Xray 安全限制）"
                }
            }
            "shadowsocks" -> {
                val method = user.optString("method").ifBlank { settings.optString("method") }.lowercase(Locale.ROOT)
                if (method.isNotBlank() && method !in XRAY_SS_METHODS) {
                    return "Xray 内核不支持 Shadowsocks 加密方式 $method"
                }
            }
        }
        return null
    }

    /** The sidecar proxy for a node Xray can't run directly, or null. */
    fun mihomoProxy(server: ServerNode): MihomoProxy? {
        val name = sidecarName(server)
        server.mihomoProxyJson.takeIf { it.isNotBlank() }?.let { raw ->
            val config = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (config.optString("type").isBlank()) return null
            // Saved by an older version: re-checked, so a node the sidecar refuses
            // can't take the whole sidecar (and the VPN) down.
            val fitted = MihomoNodes.fitForSidecar(config) ?: return null
            return MihomoProxy(name, fitted.put("name", name))
        }
        ManualNodeFactory.ssrEndpointFromServer(server)?.let { return ssrProxy(it, name) }
        val outbound = runCatching { ManualNodeFactory.inspectOutbound(server) }.getOrNull() ?: return null
        insecureHysteriaProxy(name, outbound)?.let { return it }
        if (reasonFor(outbound) == null) return null
        val converted = MihomoNodes.fromXrayOutbound(outbound, name) ?: return null
        return MihomoNodes.sanitize(converted, name)?.let { MihomoProxy(name, it) }
    }

    /** Stable, YAML-safe proxy name for [server] inside the sidecar config. */
    fun sidecarName(server: ServerNode): String {
        val key = server.id.ifBlank { server.rawUri.ifBlank { server.name } }
        return "pb-" + Integer.toHexString(key.hashCode()) + Integer.toHexString(key.reversed().hashCode())
    }

    internal fun ssrProxy(endpoint: SsrEndpoint, name: String = endpoint.name.ifBlank { "ssr" }): MihomoProxy {
        val config = JSONObject().apply {
            put("name", name)
            put("type", "ssr")
            put("server", endpoint.server)
            put("port", endpoint.port)
            put("cipher", endpoint.method)
            put("password", endpoint.password)
            put("obfs", endpoint.obfs)
            put("protocol", endpoint.protocol)
            endpoint.obfsParam.takeIf { it.isNotBlank() }?.let { put("obfs-param", it) }
            endpoint.protocolParam.takeIf { it.isNotBlank() }?.let { put("protocol-param", it) }
            put("udp", endpoint.udp)
        }
        return MihomoProxy(name, config)
    }

    private fun insecureHysteriaProxy(name: String, outbound: JSONObject): MihomoProxy? {
        if (outbound.optString("protocol") != "hysteria") return null
        val stream = outbound.optJSONObject("streamSettings") ?: return null
        val tls = stream.optJSONObject("tlsSettings") ?: return null
        val skipVerify = tls.optBoolean("allowInsecure") &&
            tls.optString("pinnedPeerCertSha256").isBlank() &&
            tls.optString("verifyPeerCertByName").isBlank()
        if (!skipVerify) return null
        val settings = outbound.optJSONObject("settings") ?: return null
        val hysteria = stream.optJSONObject("hysteriaSettings") ?: JSONObject()
        val obfsPassword = stream.optJSONObject("finalmask")?.optJSONArray("udp")?.let { masks ->
            (0 until masks.length()).mapNotNull { masks.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "salamander" }
                ?.optJSONObject("settings")?.optString("password")
        }.orEmpty()
        val config = JSONObject().apply {
            put("name", name)
            put("type", "hysteria2")
            put("server", settings.optString("address"))
            put("port", settings.optInt("port"))
            put("password", hysteria.optString("auth"))
            tls.optString("serverName").takeIf { it.isNotBlank() }?.let { put("sni", it) }
            put("skip-cert-verify", true)
            tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { put("alpn", it) }
            if (obfsPassword.isNotBlank()) {
                put("obfs", "salamander")
                put("obfs-password", obfsPassword)
            }
            put("udp", true)
        }
        return MihomoProxy(name, config)
    }

    private fun firstEndpoint(settings: JSONObject): JSONObject? {
        if (settings.has("address")) return settings
        return settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
    }

    /** Mirrors Xray's requiresTransportSecurity(): private IPs and private domains are allowed. */
    internal fun isPublicHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').trimEnd('.').lowercase(Locale.ROOT)
        if (h.isBlank()) return true
        val isIpLiteral = h.all { it.isDigit() || it == '.' } || ':' in h
        if (isIpLiteral) {
            val ip = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
            return !(ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || isUniqueLocalV6(ip))
        }
        if ('.' !in h) return false // dotless names count as private
        return PRIVATE_DOMAIN_SUFFIXES.none { h == it || h.endsWith(".$it") }
    }

    private fun isUniqueLocalV6(ip: InetAddress): Boolean {
        val bytes = ip.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }
}
