package com.mallocgfw.app.model

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversions into Clash/mihomo proxy maps.
 *
 * Every format the importer understands ends up either as an Xray outbound or
 * as one of these maps. Maps run in the bundled mihomo sidecar, which covers
 * protocols Xray doesn't have (TUIC, AnyTLS, Hysteria v1, Snell, SSH, Mieru,
 * Shadowsocks plugins…) and the transports Xray 26 removed (HTTP/2, plain
 * VLESS to public hosts, legacy ciphers).
 */
object MihomoNodes {
    /** Proxy types the bundled mihomo runs (adapter/parser.go), minus non-proxy types. */
    val PROXY_TYPES = setOf(
        "ss", "ssr", "socks5", "http", "vmess", "vless", "snell", "trojan", "hysteria", "hysteria2",
        "wireguard", "tuic", "ssh", "mieru", "anytls", "sudoku", "shadowquic", "masque", "trusttunnel",
    )

    /** Keys that point at other proxies or local interfaces; meaningless in the sidecar. */
    private val DROPPED_KEYS = setOf("dialer-proxy", "interface-name", "routing-mark", "ip-version")

    fun normalizedType(raw: String?): String? {
        val type = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        val canonical = when (type) {
            "shadowsocks" -> "ss"
            "shadowsocksr" -> "ssr"
            "socks", "socks5-tls" -> "socks5"
            "https" -> "http"
            "hy2" -> "hysteria2"
            "wg" -> "wireguard"
            else -> type
        }
        return canonical.takeIf { it in PROXY_TYPES }
    }

    /** A sidecar-ready copy of [map] with [name]; null when mihomo can't run it. */
    fun sanitize(map: Map<*, *>, name: String): JSONObject? {
        val type = normalizedType(map["type"]?.toString()) ?: return null
        val server = map["server"]?.toString()?.trim().orEmpty()
        if (server.isBlank() && type != "wireguard") return null
        val network = map["network"]?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (type in setOf("vmess", "vless", "trojan") && network !in setOf("", "tcp", "ws", "grpc", "h2", "http", "xhttp")) return null
        val json = JSONObject()
        map.forEach { (key, value) ->
            val k = key?.toString() ?: return@forEach
            if (k in DROPPED_KEYS || value == null) return@forEach
            json.put(k, toJsonValue(value))
        }
        json.put("type", type)
        json.put("name", name)
        if (type in setOf("socks5", "http") && map["type"].toString().lowercase(Locale.ROOT).let { it == "https" || it == "socks5-tls" }) {
            json.put("tls", true)
        }
        return fitForSidecar(json)
    }

    /**
     * Why the bundled mihomo refuses [config], or null. Checked against its own
     * config test (mihomo -t): Snell runs versions 1 to 5 (none means 1).
     * One refused node fails the whole sidecar, so such a node is shown as
     * unsupported instead of being offered.
     */
    fun sidecarRejection(config: JSONObject): String? = when (config.optString("type")) {
        "snell" -> {
            val version = snellVersion(config)
            if (version == null || version !in 1..5) "兼容内核不支持 Snell 版本 ${config.opt("version")}" else null
        }
        else -> null
    }

    /**
     * [config] as the sidecar accepts it, or null when it can't run (see
     * [sidecarRejection]). Snell 1 and 2 have no UDP and mihomo refuses the
     * node when udp is on (Surge lines and older imports set it): turned off.
     */
    fun fitForSidecar(config: JSONObject): JSONObject? {
        if (sidecarRejection(config) != null) return null
        if (config.optString("type") == "snell" && (snellVersion(config) ?: 1) < 3 && config.optBoolean("udp")) {
            config.put("udp", false)
        }
        return config
    }

    private fun snellVersion(config: JSONObject): Int? =
        if (!config.has("version") || config.isNull("version")) 1 else config.opt("version").toString().trim().toIntOrNull()

    fun protocolLabel(type: String): String = when (type) {
        "ss" -> "SHADOWSOCKS"
        "ssr" -> "SHADOWSOCKSR"
        "anytls" -> "ANYTLS"
        else -> type.uppercase(Locale.ROOT)
    }

    fun securityLabel(map: JSONObject): String {
        val type = map.optString("type")
        return when {
            map.has("reality-opts") -> "REALITY"
            type == "ssr" -> "SSR"
            type == "wireguard" -> "内建加密"
            type in setOf("trojan", "anytls", "tuic", "hysteria", "hysteria2", "shadowquic", "masque", "trusttunnel") -> "TLS"
            map.optBoolean("tls") -> "TLS"
            map.optString("plugin") == "shadow-tls" -> "ShadowTLS"
            else -> "NONE"
        }
    }

    fun transportLabel(map: JSONObject): String {
        val type = map.optString("type")
        return when (type) {
            "tuic", "hysteria", "hysteria2", "shadowquic", "masque" -> "QUIC"
            "wireguard" -> "WireGuard"
            "ssh" -> "SSH"
            else -> when (val plugin = map.optString("plugin")) {
                "obfs" -> "obfs-" + (map.optJSONObject("plugin-opts")?.optString("mode")?.ifBlank { "http" } ?: "http")
                "" -> when (map.optString("network").lowercase(Locale.ROOT)) {
                    "ws" -> "WebSocket"
                    "grpc" -> "gRPC"
                    "h2" -> "HTTP/2"
                    "http" -> "HTTP"
                    "xhttp" -> "XHTTP"
                    else -> "TCP"
                }
                else -> plugin
            }
        }
    }

    // ---------------------------------------------------------------- links

    /** Share links Xray can't express; returns a Clash map or null. */
    fun fromShareLink(raw: String): Map<String, Any?>? {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase(Locale.ROOT)
        return runCatching {
            when (scheme) {
                "tuic" -> tuicLink(link)
                "anytls" -> anytlsLink(link)
                "hysteria", "hy" -> hysteriaLink(link)
                "wireguard", "wg" -> wireguardLink(link)
                "ss" -> shadowsocksPluginLink(link)
                "hysteria2", "hy2" -> hysteria2HoppingLink(link)
                "socks5-tls", "https" -> null
                else -> null
            }
        }.getOrNull()
    }

    fun handlesShareLink(raw: String): Boolean {
        val scheme = raw.trim().substringBefore("://", "").lowercase(Locale.ROOT)
        return scheme in setOf("tuic", "anytls", "hysteria", "hy", "wireguard", "wg") || isHysteria2HoppingLink(raw)
    }

    /** Hysteria2 links with port hopping (`mport`, or a port range in the address). */
    fun isHysteria2HoppingLink(raw: String): Boolean {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme != "hysteria2" && scheme != "hy2") return false
        return linkQuery(link)["mport"].orEmpty().isNotBlank() || hostPortSpec(link).second.any { it == '-' || it == ',' }
    }

    /** host and port part ("443", "20000-30000", "443,8443") of a link's authority. */
    private fun hostPortSpec(link: String): Pair<String, String> {
        val authority = link.substringAfter("://").substringBefore('#').substringBefore('?').substringBefore('/')
        val hostPort = authority.substringAfterLast('@')
        return if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfter("]:", "")
        } else {
            hostPort.substringBeforeLast(':') to hostPort.substringAfterLast(':', "")
        }
    }

    private fun hysteria2HoppingLink(link: String): Map<String, Any?> {
        val q = linkQuery(link)
        val authority = link.substringAfter("://").substringBefore('#').substringBefore('?').substringBefore('/')
        val (host, portSpec) = hostPortSpec(link)
        val ports = q.first("mport") ?: portSpec
        val firstPort = Regex("\\d+").find(portSpec.ifBlank { ports })?.value?.toIntOrNull() ?: 443
        return linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "hysteria2",
            "server" to host,
            "port" to firstPort,
            "ports" to ports,
            "password" to decode(authority.substringBeforeLast('@', "")),
            "udp" to true,
        ).apply {
            q.first("sni", "peer")?.let { put("sni", it) }
            q.first("obfs")?.let { put("obfs", it) }
            q.first("obfs-password", "obfs_password", "obfsPassword")?.let { put("obfs-password", it) }
            q.first("alpn")?.let { put("alpn", it.split(',').filter(String::isNotBlank)) }
            q.first("pinSHA256", "pinsha256")?.let { put("fingerprint", it.replace(":", "").lowercase(Locale.ROOT)) }
            q.first("hop-interval", "hopInterval", "hop_interval")?.toIntOrNull()?.let { put("hop-interval", it) }
            if (q.truthy("insecure", "allowInsecure", "skip-cert-verify")) put("skip-cert-verify", true)
        }
    }

    /** ss:// links whose plugin only mihomo implements (anything but v2ray-plugin). */
    fun isShadowsocksPluginLink(raw: String): Boolean {
        if (!raw.trim().startsWith("ss://", ignoreCase = true)) return false
        val plugin = linkQuery(raw)["plugin"].orEmpty().substringBefore(';').trim()
        return plugin.isNotBlank() && !plugin.equals("v2ray-plugin", ignoreCase = true)
    }

    private fun tuicLink(link: String): Map<String, Any?> {
        val uri = URI(link.substringBefore('#'))
        val q = linkQuery(link)
        val userInfo = decode(uri.rawUserInfo.orEmpty())
        return linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "tuic",
            "server" to uri.host,
            "port" to uri.port,
        ).apply {
            if (':' in userInfo) {
                put("uuid", userInfo.substringBefore(':'))
                put("password", userInfo.substringAfter(':'))
            } else {
                put("token", userInfo)
            }
            q.first("sni", "peer")?.let { put("sni", it) }
            q.first("alpn")?.let { put("alpn", it.split(',').filter(String::isNotBlank)) }
            q.first("congestion_control", "congestion-control", "congestion_controller")?.let { put("congestion-controller", it) }
            q.first("udp_relay_mode", "udp-relay-mode")?.let { put("udp-relay-mode", it) }
            if (q.truthy("disable_sni", "disable-sni")) put("disable-sni", true)
            if (q.truthy("reduce_rtt", "zero_rtt_handshake")) put("reduce-rtt", true)
            if (q.truthy("allow_insecure", "allowInsecure", "insecure", "skip-cert-verify")) put("skip-cert-verify", true)
            put("udp", true)
        }
    }

    private fun anytlsLink(link: String): Map<String, Any?> {
        val uri = URI(link.substringBefore('#'))
        val q = linkQuery(link)
        return linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "anytls",
            "server" to uri.host,
            "port" to uri.port,
            "password" to decode(uri.rawUserInfo.orEmpty()),
        ).apply {
            q.first("sni", "peer", "serverName")?.let { put("sni", it) }
            q.first("fp", "fingerprint", "client-fingerprint")?.let { put("client-fingerprint", it) }
            q.first("alpn")?.let { put("alpn", it.split(',').filter(String::isNotBlank)) }
            if (q.truthy("insecure", "allowInsecure", "allow_insecure", "skip-cert-verify")) put("skip-cert-verify", true)
            put("udp", true)
        }
    }

    private fun hysteriaLink(link: String): Map<String, Any?> {
        val uri = URI(link.substringBefore('#'))
        val q = linkQuery(link)
        return linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "hysteria",
            "server" to uri.host,
            "port" to uri.port,
        ).apply {
            (q.first("auth", "auth_str", "auth-str") ?: uri.rawUserInfo?.let(::decode))?.let { put("auth-str", it) }
            q.first("peer", "sni")?.let { put("sni", it) }
            q.first("protocol")?.let { put("protocol", it) }
            q.first("alpn")?.let { put("alpn", it.split(',').filter(String::isNotBlank)) }
            put("up", q.first("upmbps", "up", "up_mbps") ?: "10")
            put("down", q.first("downmbps", "down", "down_mbps") ?: "50")
            q.first("obfsParam", "obfs-param", "obfs_param")?.let { put("obfs", it) }
                ?: q.first("obfs")?.takeIf { !it.equals("xplus", ignoreCase = true) }?.let { put("obfs", it) }
            q.first("mport", "ports")?.let { put("ports", it) }
            if (q.truthy("insecure", "allowInsecure", "skip-cert-verify")) put("skip-cert-verify", true)
            put("udp", true)
        }
    }

    private fun wireguardLink(link: String): Map<String, Any?> {
        val uri = URI(link.substringBefore('#'))
        val q = linkQuery(link)
        val addresses = q.first("address", "ip").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        return linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "wireguard",
            "server" to uri.host,
            "port" to uri.port,
            "private-key" to (q.first("privatekey", "private_key", "secretKey") ?: decode(uri.rawUserInfo.orEmpty())),
            "public-key" to q.first("publickey", "public_key", "peer_public_key"),
        ).apply {
            addresses.firstOrNull { ':' !in it }?.let { put("ip", it.substringBefore('/')) }
            addresses.firstOrNull { ':' in it }?.let { put("ipv6", it.substringBefore('/')) }
            q.first("presharedkey", "pre_shared_key", "psk")?.let { put("pre-shared-key", it) }
            q.first("mtu")?.toIntOrNull()?.let { put("mtu", it) }
            q.first("reserved")?.let { reserved ->
                val values = reserved.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (values.size == 3) put("reserved", values)
            }
            put("udp", true)
        }
    }

    private fun shadowsocksPluginLink(link: String): Map<String, Any?>? {
        val body = link.trim().substringAfter("://").substringBefore('#')
        val beforeQuery = body.substringBefore('?').trimEnd('/')
        val q = linkQuery(link)
        val (userInfo, hostPort) = if ('@' in beforeQuery) {
            val info = beforeQuery.substringBeforeLast('@')
            val decodedInfo = if (':' in decode(info)) decode(info) else base64(info) ?: return null
            decodedInfo to beforeQuery.substringAfterLast('@')
        } else {
            val decoded = base64(beforeQuery) ?: return null
            decoded.substringBeforeLast('@') to decoded.substringAfterLast('@')
        }
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        val pluginSpec = q["plugin"].orEmpty()
        val map = linkedMapOf<String, Any?>(
            "name" to fragment(link),
            "type" to "ss",
            "server" to host,
            "port" to port,
            "cipher" to userInfo.substringBefore(':'),
            "password" to userInfo.substringAfter(':'),
            "udp" to true,
        )
        if (pluginSpec.isNotBlank()) applySip003Plugin(map, pluginSpec) ?: return null
        return map
    }

    /** SIP003 "name;key=value;flag" → Clash plugin + plugin-opts. */
    fun applySip003Plugin(map: MutableMap<String, Any?>, spec: String): MutableMap<String, Any?>? {
        val parts = spec.split(';').map(String::trim).filter(String::isNotBlank)
        val name = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return map
        val opts = parts.drop(1).associate { part -> part.substringBefore('=') to part.substringAfter('=', "true") }
        when (name) {
            "obfs-local", "simple-obfs", "obfs" -> {
                map["plugin"] = "obfs"
                map["plugin-opts"] = linkedMapOf(
                    "mode" to (opts["obfs"] ?: opts["mode"] ?: "http"),
                    "host" to (opts["obfs-host"] ?: opts["host"] ?: "bing.com"),
                )
            }
            "v2ray-plugin" -> {
                map["plugin"] = "v2ray-plugin"
                map["plugin-opts"] = linkedMapOf<String, Any?>(
                    "mode" to "websocket",
                    "tls" to ("tls" in opts),
                    "host" to opts["host"].orEmpty(),
                    "path" to (opts["path"] ?: "/"),
                    "mux" to (opts["mux"]?.let { it != "0" && it != "false" } ?: false),
                )
            }
            "shadow-tls" -> {
                map["plugin"] = "shadow-tls"
                map["plugin-opts"] = linkedMapOf<String, Any?>(
                    "host" to opts["host"].orEmpty(),
                    "password" to opts["password"].orEmpty(),
                    "version" to (opts["version"]?.toIntOrNull() ?: 3),
                ).apply {
                    if (opts["skip-cert-verify"] == "true" || opts["insecure"] == "true" || opts["insecure"] == "1") put("skip-cert-verify", true)
                }
            }
            "restls" -> {
                map["plugin"] = "restls"
                map["plugin-opts"] = linkedMapOf<String, Any?>(
                    "host" to opts["host"].orEmpty(),
                    "password" to opts["password"].orEmpty(),
                    "version-hint" to (opts["version-hint"] ?: "tls13"),
                )
            }
            "kcptun" -> {
                map["plugin"] = "kcptun"
                map["plugin-opts"] = LinkedHashMap<String, Any?>(opts)
            }
            else -> return null
        }
        return map
    }

    // -------------------------------------------------------------- sing-box

    /**
     * sing-box outbounds Xray can't run, as Clash maps. [byTag] resolves
     * `detour` references (Shadowsocks over shadow-tls).
     */
    fun fromSingBox(item: Map<*, *>, byTag: Map<String, Map<*, *>> = emptyMap()): Map<String, Any?>? {
        val type = item["type"]?.toString()?.lowercase(Locale.ROOT) ?: return null
        val name = item["tag"]?.toString().orEmpty()
        val server = item["server"]?.toString() ?: return null
        val port = (item["server_port"] as? Number)?.toInt() ?: item["server_port"]?.toString()?.toIntOrNull()
            ?: (item["server_ports"] as? List<*>)?.firstOrNull()?.toString()?.substringBefore(':')?.toIntOrNull()
            ?: return null
        val tls = item["tls"] as? Map<*, *>
        val map = linkedMapOf<String, Any?>("name" to name, "server" to server, "port" to port, "udp" to true)
        fun applyTls(sniKey: String) {
            if (tls == null) return
            (tls["server_name"] as? String)?.takeIf { it.isNotBlank() }?.let { map[sniKey] = it }
            if (tls["insecure"] == true) map["skip-cert-verify"] = true
            (tls["alpn"] as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }?.let { map["alpn"] = it }
            ((tls["utls"] as? Map<*, *>)?.get("fingerprint") as? String)?.let { map["client-fingerprint"] = it }
        }
        when (type) {
            "tuic" -> {
                map["type"] = "tuic"
                map["uuid"] = item["uuid"]
                map["password"] = item["password"]
                (item["congestion_control"] as? String)?.let { map["congestion-controller"] = it }
                (item["udp_relay_mode"] as? String)?.let { map["udp-relay-mode"] = it }
                if (item["zero_rtt_handshake"] == true) map["reduce-rtt"] = true
                applyTls("sni")
            }
            "anytls" -> {
                map["type"] = "anytls"
                map["password"] = item["password"]
                applyTls("sni")
            }
            "hysteria2" -> {
                map["type"] = "hysteria2"
                map["password"] = item["password"]
                (item["server_ports"] as? List<*>)?.mapNotNull { it?.toString()?.replace(':', '-') }
                    ?.takeIf { it.isNotEmpty() }?.let { map["ports"] = it.joinToString(",") }
                (item["hop_interval"] as? String)?.removeSuffix("s")?.toIntOrNull()?.let { map["hop-interval"] = it }
                (item["obfs"] as? Map<*, *>)?.let { obfs ->
                    (obfs["type"] as? String)?.let { map["obfs"] = it }
                    (obfs["password"] as? String)?.let { map["obfs-password"] = it }
                }
                applyTls("sni")
            }
            "hysteria" -> {
                map["type"] = "hysteria"
                (item["auth_str"] as? String)?.let { map["auth-str"] = it }
                (item["auth"] as? String)?.let { map["auth"] = it }
                map["up"] = (item["up_mbps"] ?: item["up"] ?: 10).toString()
                map["down"] = (item["down_mbps"] ?: item["down"] ?: 50).toString()
                (item["obfs"] as? String)?.let { map["obfs"] = it }
                applyTls("sni")
            }
            "shadowsocks" -> {
                map["type"] = "ss"
                map["cipher"] = item["method"]
                map["password"] = item["password"]
                (item["plugin"] as? String)?.takeIf { it.isNotBlank() }?.let { plugin ->
                    applySip003Plugin(map, plugin + ";" + (item["plugin_opts"] as? String).orEmpty()) ?: return null
                }
                val detour = (item["detour"] as? String)?.let(byTag::get)
                if (detour?.get("type") == "shadowtls") {
                    map["server"] = detour["server"]
                    map["port"] = (detour["server_port"] as? Number)?.toInt()
                    map["plugin"] = "shadow-tls"
                    val detourTls = detour["tls"] as? Map<*, *>
                    map["plugin-opts"] = linkedMapOf<String, Any?>(
                        "host" to (detourTls?.get("server_name") ?: ""),
                        "password" to (detour["password"] ?: ""),
                        "version" to ((detour["version"] as? Number)?.toInt() ?: 3),
                    ).apply { if (detourTls?.get("insecure") == true) put("skip-cert-verify", true) }
                    ((detourTls?.get("utls") as? Map<*, *>)?.get("fingerprint") as? String)?.let { map["client-fingerprint"] = it }
                }
            }
            "ssh" -> {
                map["type"] = "ssh"
                map.remove("udp")
                map["username"] = item["user"] ?: "root"
                (item["password"] as? String)?.let { map["password"] = it }
                (item["private_key"] as? String)?.let { map["private-key"] = it }
                (item["host_key"] as? List<*>)?.let { map["host-key"] = it }
            }
            else -> return null
        }
        return map
    }

    // ----------------------------------------------------- Xray-refused nodes

    /**
     * Xray outbound → Clash map, used for nodes Xray 26 refuses (HTTP/2,
     * unencrypted VLESS/Trojan to public hosts, legacy flows and ciphers).
     * Null when the outbound uses something mihomo doesn't have either.
     */
    fun fromXrayOutbound(outbound: JSONObject, name: String): Map<String, Any?>? {
        val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
        val settings = outbound.optJSONObject("settings") ?: return null
        val endpoint = when {
            settings.has("address") -> settings
            else -> settings.optJSONArray("vnext")?.optJSONObject(0) ?: settings.optJSONArray("servers")?.optJSONObject(0)
        } ?: return null
        val user = endpoint.optJSONArray("users")?.optJSONObject(0) ?: endpoint
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "server" to endpoint.optString("address").ifBlank { return null },
            "port" to endpoint.optInt("port").takeIf { it in 1..65535 },
            "udp" to true,
        )
        if (map["port"] == null) return null
        when (protocol) {
            "vless" -> {
                map["type"] = "vless"
                map["uuid"] = user.optString("id")
                // Only Vision survives; legacy XTLS flows fall back to plain TLS.
                user.optString("flow").takeIf { it.startsWith("xtls-rprx-vision") }?.let { map["flow"] = "xtls-rprx-vision" }
                user.optString("encryption").takeIf { it.isNotBlank() && it != "none" }?.let { map["encryption"] = it }
            }
            "vmess" -> {
                map["type"] = "vmess"
                map["uuid"] = user.optString("id")
                map["alterId"] = user.optInt("alterId", 0)
                map["cipher"] = user.optString("security").ifBlank { "auto" }
            }
            "trojan" -> {
                map["type"] = "trojan"
                map["password"] = user.optString("password")
            }
            "shadowsocks" -> {
                map["type"] = "ss"
                map["cipher"] = user.optString("method").ifBlank { settings.optString("method") }
                map["password"] = user.optString("password")
                if (user.optBoolean("uot")) map["udp-over-tcp"] = true
            }
            else -> return null
        }
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val security = stream.optString("security").lowercase(Locale.ROOT)
        val sniKey = if (protocol == "trojan") "sni" else "servername"
        when (security) {
            "tls", "xtls" -> {
                val tls = stream.optJSONObject("tlsSettings") ?: stream.optJSONObject("xtlsSettings") ?: JSONObject()
                if (protocol != "trojan") map["tls"] = true
                tls.optString("serverName").takeIf { it.isNotBlank() }?.let { map[sniKey] = it }
                tls.optString("fingerprint").takeIf { it.isNotBlank() }?.let { map["client-fingerprint"] = it }
                tls.optJSONArray("alpn")?.let { alpn -> map["alpn"] = List(alpn.length()) { alpn.optString(it) } }
                val pin = tls.optString("pinnedPeerCertSha256").replace(":", "").takeIf { it.isNotBlank() }
                when {
                    pin != null -> map["fingerprint"] = pin.lowercase(Locale.ROOT)
                    tls.optBoolean("allowInsecure") -> map["skip-cert-verify"] = true
                }
            }
            "reality" -> {
                val reality = stream.optJSONObject("realitySettings") ?: return null
                if (protocol != "trojan") map["tls"] = true
                reality.optString("serverName").takeIf { it.isNotBlank() }?.let { map[sniKey] = it }
                map["client-fingerprint"] = reality.optString("fingerprint").ifBlank { "chrome" }
                map["reality-opts"] = linkedMapOf(
                    "public-key" to reality.optString("publicKey").ifBlank { reality.optString("password") },
                    "short-id" to reality.optString("shortId"),
                )
            }
            "", "none" -> if (protocol == "trojan") return null
            else -> return null
        }
        when (val network = stream.optString("network").lowercase(Locale.ROOT).ifBlank { "tcp" }) {
            "tcp", "raw" -> {
                val header = (stream.optJSONObject("tcpSettings") ?: stream.optJSONObject("rawSettings"))
                    ?.optJSONObject("header")?.optString("type").orEmpty()
                if (header == "http") return null
            }
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                map["network"] = "ws"
                map["ws-opts"] = linkedMapOf<String, Any?>("path" to ws.optString("path").ifBlank { "/" }).apply {
                    val host = ws.optString("host").ifBlank { ws.optJSONObject("headers")?.optString("Host").orEmpty() }
                    if (host.isNotBlank()) put("headers", linkedMapOf("Host" to host))
                }
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                map["network"] = "ws"
                map["ws-opts"] = linkedMapOf<String, Any?>(
                    "path" to hu.optString("path").ifBlank { "/" },
                    "v2ray-http-upgrade" to true,
                ).apply {
                    hu.optString("host").takeIf { it.isNotBlank() }?.let { put("headers", linkedMapOf("Host" to it)) }
                }
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                map["network"] = "grpc"
                map["grpc-opts"] = linkedMapOf("grpc-service-name" to grpc.optString("serviceName"))
            }
            "http", "h2" -> {
                val h2 = stream.optJSONObject("httpSettings") ?: JSONObject()
                map["network"] = "h2"
                map["h2-opts"] = linkedMapOf<String, Any?>("path" to h2.optString("path").ifBlank { "/" }).apply {
                    h2.optJSONArray("host")?.let { hosts -> put("host", List(hosts.length()) { hosts.optString(it) }) }
                }
            }
            "xhttp", "splithttp" -> {
                val x = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings") ?: JSONObject()
                map["network"] = "xhttp"
                map["xhttp-opts"] = linkedMapOf<String, Any?>("path" to x.optString("path").ifBlank { "/" }).apply {
                    x.optString("host").takeIf { it.isNotBlank() }?.let { put("host", it) }
                    x.optString("mode").takeIf { it.isNotBlank() }?.let { put("mode", it) }
                }
            }
            else -> return null // QUIC, mKCP: mihomo has neither
        }
        return map
    }

    // ------------------------------------------------ Surge / Loon / QX lines

    /**
     * One proxy line from a Surge, Loon or Quantumult X config, as a Clash
     * map. Returns null for lines that aren't proxies.
     */
    fun fromConfLine(rawLine: String): Map<String, Any?>? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//") || line.startsWith("[")) return null
        val head = line.substringBefore('=').trim().lowercase(Locale.ROOT)
        // Quantumult X: "type=host:port, key=value, …, tag=name".
        if (head in QX_TYPES && ':' in line.substringAfter('=').substringBefore(',')) {
            return runCatching { quantumultLine(head, line.substringAfter('=')) }.getOrNull()
        }
        if ('=' !in line) return null
        val name = line.substringBefore('=').trim()
        val parts = splitConf(line.substringAfter('='))
        if (parts.size < 3) return null
        return runCatching {
            if (parts[0].trim().firstOrNull()?.isUpperCase() == true && parts[0].trim().lowercase(Locale.ROOT) in LOON_TYPES) {
                loonLine(name, parts)
            } else {
                surgeLine(name, parts)
            }
        }.getOrNull()
    }

    /** Surge `[WireGuard name]` sections: name → key/value lines ("peer" kept raw). */
    fun surgeWireGuardSections(text: String): Map<String, Map<String, String>> {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        text.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("[") -> current = Regex("^\\[WireGuard\\s+(.+)]$", RegexOption.IGNORE_CASE).find(line)
                    ?.groupValues?.get(1)?.trim()?.let { name -> mutableMapOf<String, String>().also { sections[name] = it } }
                current != null && '=' in line && !line.startsWith("#") ->
                    current!![line.substringBefore('=').trim().lowercase(Locale.ROOT)] = line.substringAfter('=').trim()
            }
        }
        return sections
    }

    /** `name = wireguard, section-name = X` with its `[WireGuard X]` section, as a Clash map. */
    fun surgeWireGuardLine(rawLine: String, sections: Map<String, Map<String, String>>): Map<String, Any?>? {
        val line = rawLine.trim()
        if ('=' !in line || line.startsWith("[")) return null
        val parts = splitConf(line.substringAfter('='))
        if (parts.firstOrNull()?.trim()?.lowercase(Locale.ROOT) != "wireguard") return null
        val kv = parts.drop(1).filter { '=' in it }.associate {
            it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim().trim('"')
        }
        val section = sections[kv["section-name"] ?: return null] ?: return null
        val peerBody = section["peer"]?.trim()?.removePrefix("(")?.removeSuffix(")") ?: return null
        val peer = splitConf(peerBody).filter { '=' in it }.associate {
            it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim().trim('"')
        }
        val endpoint = peer["endpoint"] ?: return null
        val host = endpoint.substringBeforeLast(':').trim('[', ']')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        return linkedMapOf<String, Any?>(
            "name" to line.substringBefore('=').trim(),
            "type" to "wireguard",
            "server" to host,
            "port" to port,
            "private-key" to (section["private-key"] ?: return null),
            "public-key" to (peer["public-key"] ?: return null),
            "udp" to true,
        ).apply {
            section["self-ip"]?.let { put("ip", it) }
            section["self-ip-v6"]?.let { put("ipv6", it) }
            section["mtu"]?.toIntOrNull()?.let { put("mtu", it) }
            peer["preshared-key"]?.let { put("pre-shared-key", it) }
            peer["client-id"]?.split('/')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.let { put("reserved", it) }
        }
    }

    private val QX_TYPES = setOf("shadowsocks", "vmess", "vless", "trojan", "http", "socks5")
    private val LOON_TYPES = setOf("shadowsocks", "shadowsocksr", "vmess", "vless", "trojan", "http", "https", "socks5", "hysteria2", "wireguard")

    private fun surgeLine(name: String, parts: List<String>): Map<String, Any?>? {
        val type = parts[0].trim().lowercase(Locale.ROOT)
        val server = parts[1].trim()
        val port = parts[2].trim().toIntOrNull() ?: return null
        val positional = parts.drop(3).takeWhile { '=' !in it }.map { it.trim().trim('"') }
        val kv = parts.drop(3).filter { '=' in it }.associate {
            it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim().trim('"')
        }
        val map = linkedMapOf<String, Any?>("name" to name, "server" to server, "port" to port)
        fun tlsCommon(sniKey: String) {
            kv["sni"]?.let { map[sniKey] = it }
            if (kv["skip-cert-verify"].isTrue()) map["skip-cert-verify"] = true
            kv["server-cert-fingerprint-sha256"]?.let { map["fingerprint"] = it.replace(":", "").lowercase(Locale.ROOT) }
        }
        when (type) {
            "ss", "shadowsocks" -> {
                map["type"] = "ss"
                map["cipher"] = kv["encrypt-method"] ?: return null
                map["password"] = kv["password"] ?: return null
                kv["obfs"]?.let { mode ->
                    map["plugin"] = "obfs"
                    map["plugin-opts"] = linkedMapOf("mode" to mode, "host" to (kv["obfs-host"] ?: "bing.com"))
                }
                kv["shadow-tls-password"]?.let { password ->
                    map["plugin"] = "shadow-tls"
                    map["plugin-opts"] = linkedMapOf<String, Any?>(
                        "host" to kv["shadow-tls-sni"].orEmpty(),
                        "password" to password,
                        "version" to (kv["shadow-tls-version"]?.toIntOrNull() ?: 3),
                    )
                }
                map["udp"] = true
            }
            "vmess" -> {
                map["type"] = "vmess"
                map["uuid"] = kv["username"] ?: return null
                map["alterId"] = if (kv["vmess-aead"] == "false") 1 else 0
                map["cipher"] = kv["encrypt-method"] ?: "auto"
                if (kv["tls"].isTrue()) map["tls"] = true
                tlsCommon("servername")
                applySurgeWs(map, kv)
                map["udp"] = true
            }
            "trojan" -> {
                map["type"] = "trojan"
                map["password"] = kv["password"] ?: return null
                tlsCommon("sni")
                applySurgeWs(map, kv)
                map["udp"] = true
            }
            "http", "https" -> {
                map["type"] = "http"
                (kv["username"] ?: positional.getOrNull(0))?.let { map["username"] = it }
                (kv["password"] ?: positional.getOrNull(1))?.let { map["password"] = it }
                if (type == "https") { map["tls"] = true; tlsCommon("sni") }
            }
            "socks5", "socks5-tls" -> {
                map["type"] = "socks5"
                (kv["username"] ?: positional.getOrNull(0))?.let { map["username"] = it }
                (kv["password"] ?: positional.getOrNull(1))?.let { map["password"] = it }
                if (type == "socks5-tls") { map["tls"] = true; tlsCommon("sni") }
                map["udp"] = true
            }
            "snell" -> {
                map["type"] = "snell"
                map["psk"] = kv["psk"] ?: return null
                kv["version"]?.toIntOrNull()?.let { map["version"] = it }
                kv["obfs"]?.let { mode -> map["obfs-opts"] = linkedMapOf("mode" to mode, "host" to (kv["obfs-host"] ?: "bing.com")) }
                kv["shadow-tls-password"]?.let { password ->
                    map["obfs-opts"] = linkedMapOf<String, Any?>(
                        "mode" to "shadow-tls",
                        "host" to kv["shadow-tls-sni"].orEmpty(),
                        "password" to password,
                        "version" to (kv["shadow-tls-version"]?.toIntOrNull() ?: 3),
                    )
                }
                map["udp"] = true
            }
            "tuic", "tuic-v5" -> {
                map["type"] = "tuic"
                kv["token"]?.let { map["token"] = it }
                kv["uuid"]?.let { map["uuid"] = it }
                kv["password"]?.let { map["password"] = it }
                kv["alpn"]?.let { map["alpn"] = it.split(',').map(String::trim) }
                tlsCommon("sni")
                map["udp"] = true
            }
            "hysteria2" -> {
                map["type"] = "hysteria2"
                map["password"] = kv["password"] ?: return null
                tlsCommon("sni")
                kv["download-bandwidth"]?.let { map["down"] = it }
                map["udp"] = true
            }
            "anytls" -> {
                map["type"] = "anytls"
                map["password"] = kv["password"] ?: return null
                tlsCommon("sni")
                map["udp"] = true
            }
            else -> return null
        }
        return map
    }

    private fun applySurgeWs(map: MutableMap<String, Any?>, kv: Map<String, String>) {
        if (!kv["ws"].isTrue()) return
        map["network"] = "ws"
        map["ws-opts"] = linkedMapOf<String, Any?>("path" to (kv["ws-path"] ?: "/")).apply {
            kv["ws-headers"]?.let { headers ->
                val host = headers.split('|').map(String::trim)
                    .firstOrNull { it.startsWith("host:", ignoreCase = true) }?.substringAfter(':')?.trim()
                if (!host.isNullOrBlank()) put("headers", linkedMapOf("Host" to host))
            }
        }
    }

    private fun loonLine(name: String, parts: List<String>): Map<String, Any?>? {
        val type = parts[0].trim().lowercase(Locale.ROOT)
        val server = parts[1].trim()
        val port = parts[2].trim().toIntOrNull() ?: return null
        val positional = parts.drop(3).takeWhile { '=' !in it }.map { it.trim().trim('"') }
        val kv = parts.drop(3).filter { '=' in it }.associate {
            it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim().trim('"')
        }
        val map = linkedMapOf<String, Any?>("name" to name, "server" to server, "port" to port, "udp" to true)
        fun tls(sniKey: String) {
            kv["tls-name"]?.let { map[sniKey] = it }
            if (kv["skip-cert-verify"].isTrue()) map["skip-cert-verify"] = true
        }
        fun transport() {
            when (kv["transport"]?.lowercase(Locale.ROOT)) {
                "ws" -> {
                    map["network"] = "ws"
                    map["ws-opts"] = linkedMapOf<String, Any?>("path" to (kv["path"] ?: "/")).apply {
                        kv["host"]?.let { put("headers", linkedMapOf("Host" to it)) }
                    }
                }
                "http" -> {
                    map["network"] = "http"
                    map["http-opts"] = linkedMapOf<String, Any?>("path" to listOf(kv["path"] ?: "/")).apply {
                        kv["host"]?.let { put("headers", linkedMapOf("Host" to listOf(it))) }
                    }
                }
                "grpc" -> {
                    map["network"] = "grpc"
                    map["grpc-opts"] = linkedMapOf("grpc-service-name" to (kv["grpc-service-name"] ?: kv["path"].orEmpty()))
                }
            }
        }
        when (type) {
            "shadowsocks" -> {
                map["type"] = "ss"
                map["cipher"] = positional.getOrNull(0) ?: return null
                map["password"] = positional.getOrNull(1) ?: return null
                kv["obfs-name"]?.let { mode ->
                    map["plugin"] = "obfs"
                    map["plugin-opts"] = linkedMapOf("mode" to mode, "host" to (kv["obfs-host"] ?: "bing.com"))
                }
            }
            "shadowsocksr" -> {
                map["type"] = "ssr"
                map["cipher"] = positional.getOrNull(0) ?: return null
                map["password"] = positional.getOrNull(1) ?: return null
                map["protocol"] = kv["protocol"] ?: "origin"
                kv["protocol-param"]?.let { map["protocol-param"] = it }
                map["obfs"] = kv["obfs"] ?: "plain"
                kv["obfs-param"]?.let { map["obfs-param"] = it }
            }
            "vmess" -> {
                map["type"] = "vmess"
                map["cipher"] = positional.getOrNull(0) ?: "auto"
                map["uuid"] = positional.getOrNull(1) ?: return null
                map["alterId"] = kv["alterid"]?.toIntOrNull() ?: 0
                if (kv["over-tls"].isTrue()) map["tls"] = true
                tls("servername")
                transport()
            }
            "vless" -> {
                map["type"] = "vless"
                map["uuid"] = positional.getOrNull(0) ?: return null
                kv["flow"]?.let { map["flow"] = it }
                if (kv["over-tls"].isTrue()) map["tls"] = true
                kv["public-key"]?.let { key ->
                    map["tls"] = true
                    map["client-fingerprint"] = "chrome"
                    map["reality-opts"] = linkedMapOf("public-key" to key, "short-id" to (kv["short-id"] ?: ""))
                }
                tls("servername")
                transport()
            }
            "trojan" -> {
                map["type"] = "trojan"
                map["password"] = positional.getOrNull(0) ?: return null
                tls("sni")
                transport()
            }
            "http", "https" -> {
                map["type"] = "http"
                positional.getOrNull(0)?.let { map["username"] = it }
                positional.getOrNull(1)?.let { map["password"] = it }
                if (type == "https" || kv["over-tls"].isTrue()) { map["tls"] = true; tls("sni") }
                map.remove("udp")
            }
            "socks5" -> {
                map["type"] = "socks5"
                positional.getOrNull(0)?.let { map["username"] = it }
                positional.getOrNull(1)?.let { map["password"] = it }
                if (kv["over-tls"].isTrue()) { map["tls"] = true; tls("sni") }
            }
            "hysteria2" -> {
                map["type"] = "hysteria2"
                map["password"] = positional.getOrNull(0) ?: return null
                tls("sni")
            }
            else -> return null
        }
        return map
    }

    private fun quantumultLine(type: String, body: String): Map<String, Any?>? {
        val parts = splitConf(body)
        val hostPort = parts.firstOrNull()?.trim() ?: return null
        val kv = parts.drop(1).filter { '=' in it }.associate {
            it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim()
        }
        val server = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        val map = linkedMapOf<String, Any?>(
            "name" to (kv["tag"] ?: "$server:$port"),
            "server" to server,
            "port" to port,
            "udp" to true,
        )
        val obfs = kv["obfs"]?.lowercase(Locale.ROOT)
        fun tls(sniKey: String) {
            (kv["tls-host"] ?: kv["obfs-host"])?.let { map[sniKey] = it }
            if (kv["tls-verification"] == "false") map["skip-cert-verify"] = true
        }
        fun ws() {
            if (obfs == "ws" || obfs == "wss") {
                map["network"] = "ws"
                map["ws-opts"] = linkedMapOf<String, Any?>("path" to (kv["obfs-uri"] ?: "/")).apply {
                    kv["obfs-host"]?.let { put("headers", linkedMapOf("Host" to it)) }
                }
            }
        }
        when (type) {
            "shadowsocks" -> {
                map["type"] = "ss"
                map["cipher"] = kv["method"] ?: return null
                map["password"] = kv["password"] ?: return null
                when (obfs) {
                    "http", "tls" -> {
                        map["plugin"] = "obfs"
                        map["plugin-opts"] = linkedMapOf("mode" to obfs, "host" to (kv["obfs-host"] ?: "bing.com"))
                    }
                    "ws", "wss" -> {
                        map["plugin"] = "v2ray-plugin"
                        map["plugin-opts"] = linkedMapOf<String, Any?>(
                            "mode" to "websocket",
                            "tls" to (obfs == "wss"),
                            "host" to kv["obfs-host"].orEmpty(),
                            "path" to (kv["obfs-uri"] ?: "/"),
                        )
                    }
                }
            }
            "vmess", "vless" -> {
                map["type"] = type
                map["uuid"] = kv["password"] ?: return null
                if (type == "vmess") {
                    map["cipher"] = kv["method"]?.takeIf { it != "none" } ?: "auto"
                    map["alterId"] = 0
                }
                kv["vless-flow"]?.let { map["flow"] = it }
                if (obfs == "over-tls" || obfs == "wss") { map["tls"] = true; tls("servername") }
                kv["reality-base64-pubkey"]?.let { key ->
                    map["tls"] = true
                    map["client-fingerprint"] = "chrome"
                    map["reality-opts"] = linkedMapOf("public-key" to key, "short-id" to (kv["reality-hex-shortid"] ?: ""))
                }
                ws()
            }
            "trojan" -> {
                map["type"] = "trojan"
                map["password"] = kv["password"] ?: return null
                tls("sni")
                ws()
            }
            "http", "socks5" -> {
                map["type"] = type
                kv["username"]?.let { map["username"] = it }
                kv["password"]?.let { map["password"] = it }
                if (kv["over-tls"] == "true") { map["tls"] = true; tls("sni") }
            }
            else -> return null
        }
        return map
    }

    /** Splits on commas outside double quotes. */
    private fun splitConf(body: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        body.forEach { c ->
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { out += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun String?.isTrue(): Boolean = this != null && (equals("true", ignoreCase = true) || this == "1")

    private fun linkQuery(link: String): Map<String, String> {
        val query = link.substringBefore('#').substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun Map<String, String>.first(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }

    private fun Map<String, String>.truthy(vararg keys: String): Boolean =
        keys.any { key -> this[key]?.let { it == "1" || it.equals("true", ignoreCase = true) } == true }

    private fun fragment(link: String): String =
        link.substringAfter('#', "").let(::decode)

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun base64(value: String): String? {
        val trimmed = value.trim()
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        return runCatching { String(Base64.getUrlDecoder().decode(padded)) }
            .recoverCatching { String(Base64.getDecoder().decode(padded)) }
            .getOrNull()
    }

    /** A Clash map as JSON (nested maps and lists included). */
    fun jsonOf(map: Map<*, *>): JSONObject = toJsonValue(map) as JSONObject

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> if (k != null && v != null) put(k.toString(), toJsonValue(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }
}
