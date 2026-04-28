package com.mallocgfw.app.model

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class LocalNodeProtocol(
    val wireValue: String,
    val displayName: String,
    val subtitle: String,
    val defaultPort: Int,
) {
    VLESS("vless", "VLESS", "适合 TLS / REALITY / Vision 一类节点", 443),
    VMESS("vmess", "VMess", "兼容旧订阅体系，支持常见 TCP / WS / gRPC", 443),
    TROJAN("trojan", "Trojan", "基于 TLS 的密码节点，适合伪装成普通 HTTPS", 443),
    SHADOWSOCKS("shadowsocks", "Shadowsocks", "适合传统机场节点，支持 2022 和 AEAD", 8388),
    SOCKS("socks", "SOCKS5", "对接现成 Socks5 代理，通常只支持 TCP / UDP 转发", 1080),
    HTTP("http", "HTTP Proxy", "对接现成 HTTP 代理，通常只适合 TCP", 8080),
    WIREGUARD("wireguard", "WireGuard", "内建 WireGuard 出站，适合直连隧道型节点", 51820),
    HYSTERIA("hysteria", "Hysteria2", "Xray 的 hysteria 出站，底层走 QUIC 传输", 443),
}

enum class LocalNodeTransport(
    val wireValue: String,
    val displayName: String,
) {
    TCP("tcp", "TCP"),
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    HTTP2("http", "HTTP/2"),
    HTTP_UPGRADE("httpupgrade", "HTTPUpgrade"),
    XHTTP("xhttp", "XHTTP"),
}

enum class LocalNodeSecurity(
    val wireValue: String,
    val displayName: String,
) {
    NONE("none", "无加密"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY"),
}

enum class LocalNodeShadowsocksMethod(
    val wireValue: String,
    val displayName: String,
) {
    SS2022_AES_128("2022-blake3-aes-128-gcm", "2022-blake3-aes-128-gcm"),
    SS2022_AES_256("2022-blake3-aes-256-gcm", "2022-blake3-aes-256-gcm"),
    SS2022_CHACHA20("2022-blake3-chacha20-poly1305", "2022-blake3-chacha20-poly1305"),
    AES_256_GCM("aes-256-gcm", "aes-256-gcm"),
    AES_128_GCM("aes-128-gcm", "aes-128-gcm"),
    CHACHA20_POLY1305("chacha20-poly1305", "chacha20-poly1305"),
    XCHACHA20_POLY1305("xchacha20-poly1305", "xchacha20-poly1305"),
    NONE("none", "none / plain"),
}

enum class LocalNodeWireGuardDomainStrategy(
    val wireValue: String,
    val displayName: String,
) {
    FORCE_IP("ForceIP", "ForceIP"),
    FORCE_IPV4("ForceIPv4", "ForceIPv4"),
    FORCE_IPV6("ForceIPv6", "ForceIPv6"),
    FORCE_IPV4V6("ForceIPv4v6", "ForceIPv4v6"),
    FORCE_IPV6V4("ForceIPv6v4", "ForceIPv6v4"),
}

data class LocalNodeDraft(
    val protocol: LocalNodeProtocol = LocalNodeProtocol.VLESS,
    val nodeName: String = "",
    val address: String = "",
    val port: String = LocalNodeProtocol.VLESS.defaultPort.toString(),
    val userId: String = "",
    val password: String = "",
    val username: String = "",
    val vlessEncryption: String = "none",
    val vlessFlow: String = "",
    val vmessSecurity: String = "auto",
    val transport: LocalNodeTransport = LocalNodeTransport.TCP,
    val security: LocalNodeSecurity = LocalNodeSecurity.NONE,
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val serverName: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val shadowsocksMethod: LocalNodeShadowsocksMethod = LocalNodeShadowsocksMethod.CHACHA20_POLY1305,
    val shadowsocksUot: Boolean = false,
    val shadowsocksUotVersion: String = "2",
    val httpHeaders: String = "",
    val wireGuardSecretKey: String = "",
    val wireGuardPublicKey: String = "",
    val wireGuardPreSharedKey: String = "",
    val wireGuardLocalAddresses: String = "",
    val wireGuardAllowedIps: String = "",
    val wireGuardReserved: String = "",
    val wireGuardMtu: String = "1420",
    val wireGuardWorkers: String = "",
    val wireGuardKeepAlive: String = "",
    val wireGuardDomainStrategy: LocalNodeWireGuardDomainStrategy = LocalNodeWireGuardDomainStrategy.FORCE_IP,
    val wireGuardNoKernelTun: Boolean = false,
    val hysteriaAuth: String = "",
    val hysteriaUdpIdleTimeout: String = "60",
)

object ManualNodeFactory {
    private const val GRPC_CLIENT_USER_AGENT = "Shadowrocket/2.2.62"
    private val supportedShareLinkPrefixes = listOf(
        "vless://",
        "vmess://",
        "trojan://",
        "ss://",
        "socks://",
        "socks5://",
        "http://",
        "https://",
        "hy2://",
        "hysteria2://",
    )

    val protocolOptions = LocalNodeProtocol.entries.toList()

    fun supportsShareLink(raw: String): Boolean {
        val trimmed = raw.trim()
        return supportedShareLinkPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    fun buildNodeFromShareLink(raw: String): ServerNode {
        return buildServerNode(prefillFromShareLink(raw))
    }

    fun applyProtocolDefaults(
        current: LocalNodeDraft,
        protocol: LocalNodeProtocol,
    ): LocalNodeDraft {
        return current.copy(
            protocol = protocol,
            port = protocol.defaultPort.toString(),
            transport = when (protocol) {
                LocalNodeProtocol.HYSTERIA,
                LocalNodeProtocol.WIREGUARD,
                LocalNodeProtocol.HTTP,
                LocalNodeProtocol.SOCKS,
                -> LocalNodeTransport.TCP

                else -> current.transport
            },
            security = when (protocol) {
                LocalNodeProtocol.TROJAN,
                LocalNodeProtocol.HYSTERIA,
                -> LocalNodeSecurity.TLS

                LocalNodeProtocol.WIREGUARD -> LocalNodeSecurity.NONE
                LocalNodeProtocol.VMESS,
                LocalNodeProtocol.SHADOWSOCKS,
                LocalNodeProtocol.HTTP,
                LocalNodeProtocol.SOCKS,
                LocalNodeProtocol.VLESS,
                -> if (current.security == LocalNodeSecurity.REALITY &&
                    protocol !in listOf(LocalNodeProtocol.VLESS)
                ) {
                    LocalNodeSecurity.NONE
                } else {
                    current.security
                }
            },
        )
    }

    fun securityOptions(protocol: LocalNodeProtocol): List<LocalNodeSecurity> {
        return when (protocol) {
            LocalNodeProtocol.VLESS -> listOf(LocalNodeSecurity.NONE, LocalNodeSecurity.TLS, LocalNodeSecurity.REALITY)
            LocalNodeProtocol.VMESS,
            LocalNodeProtocol.SHADOWSOCKS,
            LocalNodeProtocol.SOCKS,
            LocalNodeProtocol.HTTP,
            -> listOf(LocalNodeSecurity.NONE, LocalNodeSecurity.TLS)

            LocalNodeProtocol.TROJAN,
            LocalNodeProtocol.HYSTERIA,
            -> listOf(LocalNodeSecurity.TLS)

            LocalNodeProtocol.WIREGUARD -> listOf(LocalNodeSecurity.NONE)
        }
    }

    fun transportOptions(protocol: LocalNodeProtocol): List<LocalNodeTransport> {
        return when (protocol) {
            LocalNodeProtocol.VLESS,
            LocalNodeProtocol.VMESS,
            LocalNodeProtocol.TROJAN,
            LocalNodeProtocol.SHADOWSOCKS,
            -> LocalNodeTransport.entries.toList()

            else -> emptyList()
        }
    }

    fun supportsTransport(protocol: LocalNodeProtocol): Boolean = transportOptions(protocol).isNotEmpty()

    fun supportsStreamSecurity(protocol: LocalNodeProtocol): Boolean {
        return protocol !in listOf(LocalNodeProtocol.WIREGUARD)
    }

    fun buildOutboundConfig(server: ServerNode): JSONObject? {
        server.outboundJson.trim().takeIf { it.isNotBlank() }?.let { outboundJson ->
            return runCatching { JSONObject(outboundJson) }.getOrNull()
        }
        val rawUri = server.rawUri.trim()
        if (rawUri.isBlank() || !supportsShareLink(rawUri)) return null
        return runCatching {
            val draft = prefillFromShareLink(rawUri)
            buildOutboundConfig(
                draft = draft,
                address = draft.address.trim(),
                port = parsePort(draft.port, field = "端口"),
            )
        }.getOrNull()
    }

    fun supportsPreProxy(server: ServerNode): Boolean {
        return !server.hiddenUnsupported && buildOutboundConfig(server) != null
    }

    fun buildServerNode(draft: LocalNodeDraft): ServerNode {
        validateDraft(draft)
        val name = draft.nodeName.trim().ifBlank {
            "${draft.protocol.displayName} ${draft.address.trim().ifBlank { "节点" }}"
        }
        val address = draft.address.trim()
        val port = parsePort(draft.port, field = "端口")
        require(address.isNotBlank()) { "请填写服务端地址。" }

        val outboundJson = buildOutboundConfig(draft, address, port)

        return ServerNode(
            id = "local_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            groupId = ImportParser.LOCAL_GROUP_ID,
            name = name,
            code = buildCode(address, name),
            subscription = "Local",
            region = inferRegion(name, address),
            latencyMs = 0,
            protocol = draft.protocol.displayName.uppercase(Locale.ROOT),
            security = securityLabel(draft),
            transport = transportLabel(draft),
            description = "手动新建的 ${draft.protocol.displayName} 客户端节点",
            address = address,
            port = port.toString(),
            flow = flowLabel(draft),
            stable = false,
            favorite = false,
            rawUri = "",
            outboundJson = outboundJson.toString(2),
        )
    }

    private fun buildOutboundConfig(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        return when (draft.protocol) {
            LocalNodeProtocol.VLESS -> buildVlessOutbound(draft, address, port)
            LocalNodeProtocol.VMESS -> buildVmessOutbound(draft, address, port)
            LocalNodeProtocol.TROJAN -> buildTrojanOutbound(draft, address, port)
            LocalNodeProtocol.SHADOWSOCKS -> buildShadowsocksOutbound(draft, address, port)
            LocalNodeProtocol.SOCKS -> buildSocksOutbound(draft, address, port)
            LocalNodeProtocol.HTTP -> buildHttpOutbound(draft, address, port)
            LocalNodeProtocol.WIREGUARD -> buildWireGuardOutbound(draft, address, port)
            LocalNodeProtocol.HYSTERIA -> buildHysteriaOutbound(draft, address, port)
        }
    }

    fun validateDraft(draft: LocalNodeDraft) {
        val address = draft.address.trim()
        require(address.isNotBlank()) { "请填写服务端地址。" }
        parsePort(draft.port, field = "端口")

        when (draft.protocol) {
            LocalNodeProtocol.VLESS,
            LocalNodeProtocol.VMESS,
            -> validateUuid(draft.userId)

            LocalNodeProtocol.TROJAN -> require(draft.password.trim().isNotBlank()) { "Trojan 需要填写密码。" }
            LocalNodeProtocol.SHADOWSOCKS -> {
                require(draft.password.trim().isNotBlank()) { "Shadowsocks 需要填写密码或密钥。" }
                draft.shadowsocksUotVersion.trim().takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it > 0 }
                    ?: error("UoT 版本需要是正整数。")
            }

            LocalNodeProtocol.SOCKS,
            LocalNodeProtocol.HTTP,
            -> {
                val username = draft.username.trim()
                val password = draft.password.trim()
                require((username.isBlank() && password.isBlank()) || (username.isNotBlank() && password.isNotBlank())) {
                    "HTTP / SOCKS 认证需要用户名和密码一起填写，或者都留空。"
                }
                if (draft.protocol == LocalNodeProtocol.HTTP) {
                    parseHttpHeaders(draft.httpHeaders)
                }
            }

            LocalNodeProtocol.WIREGUARD -> {
                require(draft.wireGuardSecretKey.trim().isNotBlank()) { "WireGuard 需要填写客户端私钥。" }
                require(draft.wireGuardPublicKey.trim().isNotBlank()) { "WireGuard 需要填写对端公钥。" }
                parsePort(draft.port, field = "对端端口")
                draft.wireGuardLocalAddresses.trim().takeIf { it.isNotBlank() }?.let { validateIpList(it, requireCidr = true, field = "本地地址列表") }
                draft.wireGuardAllowedIps.trim().takeIf { it.isNotBlank() }?.let { validateIpList(it, requireCidr = true, field = "Allowed IPs") }
                draft.wireGuardReserved.trim().takeIf { it.isNotBlank() }?.let { parseReserved(it) }
                draft.wireGuardMtu.trim().takeIf { it.isNotBlank() }?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 576..9200 }
                        ?: error("WireGuard MTU 需要是 576-9200 之间的数字。")
                }
                if (draft.wireGuardWorkers.isNotBlank()) {
                    draft.wireGuardWorkers.trim().toIntOrNull()?.takeIf { it > 0 }
                        ?: error("WireGuard workers 需要是正整数。")
                }
                if (draft.wireGuardKeepAlive.isNotBlank()) {
                    draft.wireGuardKeepAlive.trim().toIntOrNull()?.takeIf { it >= 0 }
                        ?: error("WireGuard keepAlive 需要是 0 或正整数。")
                }
            }

            LocalNodeProtocol.HYSTERIA -> {
                require(draft.hysteriaAuth.trim().isNotBlank()) { "Hysteria2 需要填写认证口令。" }
                require(draft.serverName.trim().isNotBlank()) { "Hysteria2 需要填写 TLS Server Name / SNI。" }
                draft.hysteriaUdpIdleTimeout.trim().takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it > 0 }
                    ?: error("UDP 空闲超时需要是正整数。")
            }
        }

        if (draft.protocol in listOf(LocalNodeProtocol.VLESS, LocalNodeProtocol.VMESS, LocalNodeProtocol.TROJAN, LocalNodeProtocol.SHADOWSOCKS)) {
            if (draft.security == LocalNodeSecurity.TLS) {
                draft.alpn.trim().takeIf { it.isNotBlank() }?.let { validateCsvValues(it, "ALPN") }
            }
            if (draft.security == LocalNodeSecurity.REALITY) {
                require(draft.serverName.trim().isNotBlank()) { "REALITY 需要填写 Server Name / SNI。" }
                require(draft.realityPublicKey.trim().isNotBlank()) { "REALITY 需要填写公钥。" }
            }
        }
    }

    fun exampleName(protocol: LocalNodeProtocol): String {
        return when (protocol) {
            LocalNodeProtocol.VLESS -> "例如 Netflix 香港 / 自建 VLESS"
            LocalNodeProtocol.VMESS -> "例如 VMess 日本 Tokyo"
            LocalNodeProtocol.TROJAN -> "例如 Trojan 备用线"
            LocalNodeProtocol.SHADOWSOCKS -> "例如 SS 新加坡低延迟"
            LocalNodeProtocol.SOCKS -> "例如 Office SOCKS5"
            LocalNodeProtocol.HTTP -> "例如 HTTP 上游代理"
            LocalNodeProtocol.WIREGUARD -> "例如 WARP / WG 美国"
            LocalNodeProtocol.HYSTERIA -> "例如 Hy2 家宽加速"
        }
    }

    fun exampleAddress(protocol: LocalNodeProtocol): String {
        return when (protocol) {
            LocalNodeProtocol.WIREGUARD -> "例如 engage.cloudflareclient.com"
            LocalNodeProtocol.HTTP -> "例如 proxy.example.com"
            LocalNodeProtocol.SOCKS -> "例如 10.0.0.2"
            else -> "域名、IPv4 或 IPv6"
        }
    }

    fun examplePort(protocol: LocalNodeProtocol): String {
        return when (protocol) {
            LocalNodeProtocol.VLESS,
            LocalNodeProtocol.VMESS,
            LocalNodeProtocol.TROJAN,
            LocalNodeProtocol.HYSTERIA,
            -> "例如 443"

            LocalNodeProtocol.SHADOWSOCKS -> "例如 8388"
            LocalNodeProtocol.SOCKS -> "例如 1080"
            LocalNodeProtocol.HTTP -> "例如 8080 / 3128"
            LocalNodeProtocol.WIREGUARD -> "例如 2408 / 51820"
        }
    }

    fun exampleUuid(protocol: LocalNodeProtocol): String {
        return if (protocol == LocalNodeProtocol.VLESS) {
            "例如 123e4567-e89b-12d3-a456-426614174000"
        } else {
            "VMess 用户 UUID"
        }
    }

    fun exampleServerName(protocol: LocalNodeProtocol): String {
        return when (protocol) {
            LocalNodeProtocol.HYSTERIA -> "例如 bing.com 或你的证书域名"
            else -> "例如 cdn.example.com"
        }
    }

    fun exampleDraft(protocol: LocalNodeProtocol): LocalNodeDraft {
        return when (protocol) {
            LocalNodeProtocol.VLESS -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "VLESS 香港示例",
                address = "hk.example.com",
                port = "443",
                userId = "123e4567-e89b-12d3-a456-426614174000",
                vlessEncryption = "none",
                transport = LocalNodeTransport.WS,
                security = LocalNodeSecurity.TLS,
                host = "cdn.example.com",
                path = "/ws",
                serverName = "cdn.example.com",
                alpn = "h2,http/1.1",
            )

            LocalNodeProtocol.VMESS -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "VMess 日本示例",
                address = "jp.example.com",
                port = "443",
                userId = "123e4567-e89b-12d3-a456-426614174000",
                vmessSecurity = "auto",
                transport = LocalNodeTransport.GRPC,
                security = LocalNodeSecurity.TLS,
                serviceName = "grpc",
                serverName = "cdn.example.com",
            )

            LocalNodeProtocol.TROJAN -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "Trojan 美国示例",
                address = "us.example.com",
                port = "443",
                password = "replace-with-password",
                transport = LocalNodeTransport.TCP,
                security = LocalNodeSecurity.TLS,
                serverName = "us.example.com",
            )

            LocalNodeProtocol.SHADOWSOCKS -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "SS 新加坡示例",
                address = "sg.example.com",
                port = "8388",
                password = "replace-with-secret",
                shadowsocksMethod = LocalNodeShadowsocksMethod.CHACHA20_POLY1305,
                transport = LocalNodeTransport.TCP,
                security = LocalNodeSecurity.NONE,
            )

            LocalNodeProtocol.SOCKS -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "SOCKS5 示例",
                address = "10.0.0.2",
                port = "1080",
                username = "demo",
                password = "demo-pass",
                security = LocalNodeSecurity.NONE,
            )

            LocalNodeProtocol.HTTP -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "HTTP Proxy 示例",
                address = "proxy.example.com",
                port = "8080",
                username = "demo",
                password = "demo-pass",
                security = LocalNodeSecurity.NONE,
                httpHeaders = "User-Agent: Mozilla/5.0",
            )

            LocalNodeProtocol.WIREGUARD -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "WireGuard 示例",
                address = "engage.cloudflareclient.com",
                port = "2408",
                wireGuardSecretKey = "replace-with-private-key",
                wireGuardPublicKey = "replace-with-peer-public-key",
                wireGuardLocalAddresses = "172.16.0.2/32, 2606:4700:110:8765::2/128",
                wireGuardAllowedIps = "0.0.0.0/0, ::0/0",
                wireGuardReserved = "1,2,3",
            )

            LocalNodeProtocol.HYSTERIA -> LocalNodeDraft(
                protocol = protocol,
                nodeName = "Hysteria2 示例",
                address = "hy2.example.com",
                port = "443",
                security = LocalNodeSecurity.TLS,
                serverName = "bing.com",
                hysteriaAuth = "replace-with-auth",
                hysteriaUdpIdleTimeout = "60",
            )
        }
    }

    fun prefillFromShareLink(raw: String): LocalNodeDraft {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "请先粘贴分享链接。" }
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVlessDraft(trimmed)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmessDraft(trimmed)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojanDraft(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocksDraft(trimmed)
            trimmed.startsWith("socks://", ignoreCase = true) -> parseProxyDraft(trimmed, LocalNodeProtocol.SOCKS)
            trimmed.startsWith("socks5://", ignoreCase = true) -> parseProxyDraft(trimmed, LocalNodeProtocol.SOCKS)
            trimmed.startsWith("http://", ignoreCase = true) -> parseProxyDraft(trimmed, LocalNodeProtocol.HTTP)
            trimmed.startsWith("https://", ignoreCase = true) ->
                parseProxyDraft(trimmed, LocalNodeProtocol.HTTP).copy(security = LocalNodeSecurity.TLS)
            trimmed.startsWith("hy2://", ignoreCase = true) || trimmed.startsWith("hysteria2://", ignoreCase = true) ->
                parseHysteriaDraft(trimmed)

            else -> error("当前预填支持 VLESS / VMess / Trojan / Shadowsocks / SOCKS / HTTP / Hysteria2 分享链接。")
        }
    }

    fun prefillFromServerNode(server: ServerNode): LocalNodeDraft {
        if (server.outboundJson.isBlank()) {
            return prefillFromLegacyServerNode(server)
        }
        val outbound = runCatching { JSONObject(server.outboundJson) }.getOrElse {
            return prefillFromLegacyServerNode(server)
        }
        val protocol = when (outbound.optString("protocol").lowercase(Locale.ROOT)) {
            "vless" -> LocalNodeProtocol.VLESS
            "vmess" -> LocalNodeProtocol.VMESS
            "trojan" -> LocalNodeProtocol.TROJAN
            "shadowsocks" -> LocalNodeProtocol.SHADOWSOCKS
            "socks" -> LocalNodeProtocol.SOCKS
            "http" -> LocalNodeProtocol.HTTP
            "wireguard" -> LocalNodeProtocol.WIREGUARD
            "hysteria" -> LocalNodeProtocol.HYSTERIA
            else -> error("当前节点协议暂不支持编辑。")
        }
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val streamSettings = outbound.optJSONObject("streamSettings")
        val tlsSettings = streamSettings?.optJSONObject("tlsSettings")
        val realitySettings = streamSettings?.optJSONObject("realitySettings")
        val grpcSettings = streamSettings?.optJSONObject("grpcSettings")
        val wsSettings = streamSettings?.optJSONObject("wsSettings")
        val httpSettings = streamSettings?.optJSONObject("httpSettings")
        val httpUpgradeSettings = streamSettings?.optJSONObject("httpupgradeSettings")
        val xhttpSettings = streamSettings?.optJSONObject("xhttpSettings")
        val hysteriaSettings = streamSettings?.optJSONObject("hysteriaSettings")

        val security = when {
            realitySettings != null -> LocalNodeSecurity.REALITY
            streamSettings?.optString("security").equals("tls", ignoreCase = true) -> LocalNodeSecurity.TLS
            protocol == LocalNodeProtocol.HYSTERIA -> LocalNodeSecurity.TLS
            else -> LocalNodeSecurity.NONE
        }
        val transport = when (protocol) {
            LocalNodeProtocol.WIREGUARD,
            LocalNodeProtocol.HTTP,
            LocalNodeProtocol.SOCKS,
            LocalNodeProtocol.HYSTERIA,
            -> LocalNodeTransport.TCP

            else -> transportFrom(streamSettings?.optString("network"), LocalNodeTransport.TCP)
        }
        val serverName = when {
            realitySettings != null -> realitySettings.optString("serverName")
            tlsSettings != null -> tlsSettings.optString("serverName")
            else -> ""
        }
        val fingerprint = when {
            realitySettings != null -> realitySettings.optString("fingerprint")
            tlsSettings != null -> tlsSettings.optString("fingerprint")
            else -> ""
        }
        val alpn = jsonArrayToCsv(
            realitySettings?.optJSONArray("alpn")
                ?: tlsSettings?.optJSONArray("alpn"),
        )
        val allowInsecure = tlsSettings?.optBoolean("allowInsecure") == true
        val authority = grpcSettings?.optString("authority").orEmpty()
        val baseDraft = applyProtocolDefaults(LocalNodeDraft(), protocol).copy(
            nodeName = server.name,
            address = settings.optString("address").ifBlank { server.address },
            port = jsonNumberToString(settings, "port", server.port.ifBlank { protocol.defaultPort.toString() }),
            transport = transport,
            security = security,
            host = when (transport) {
                LocalNodeTransport.WS -> wsSettings?.optJSONObject("headers")?.optString("Host").orEmpty()
                LocalNodeTransport.HTTP2 -> httpSettings?.optJSONArray("host")?.optString(0).orEmpty()
                LocalNodeTransport.HTTP_UPGRADE -> httpUpgradeSettings?.optString("host").orEmpty()
                LocalNodeTransport.XHTTP -> xhttpSettings?.optString("host").orEmpty()
                LocalNodeTransport.GRPC -> authority.ifBlank { serverName }
                LocalNodeTransport.TCP -> ""
            },
            path = when (transport) {
                LocalNodeTransport.WS -> wsSettings?.optString("path").orEmpty()
                LocalNodeTransport.HTTP2 -> httpSettings?.optString("path").orEmpty()
                LocalNodeTransport.HTTP_UPGRADE -> httpUpgradeSettings?.optString("path").orEmpty()
                LocalNodeTransport.XHTTP -> xhttpSettings?.optString("path").orEmpty()
                else -> ""
            },
            serviceName = grpcSettings?.optString("serviceName").orEmpty(),
            serverName = serverName,
            fingerprint = fingerprint,
            alpn = alpn,
            allowInsecure = allowInsecure,
            realityPublicKey = realitySettings?.optString("publicKey").orEmpty(),
            realityShortId = realitySettings?.optString("shortId").orEmpty(),
            realitySpiderX = realitySettings?.optString("spiderX").orEmpty(),
        )

        return when (protocol) {
            LocalNodeProtocol.VLESS -> baseDraft.copy(
                userId = settings.optString("id"),
                vlessEncryption = settings.optString("encryption").ifBlank { "none" },
                vlessFlow = settings.optString("flow"),
            )

            LocalNodeProtocol.VMESS -> baseDraft.copy(
                userId = settings.optString("id"),
                vmessSecurity = settings.optString("security").ifBlank { "auto" },
            )

            LocalNodeProtocol.TROJAN -> baseDraft.copy(
                password = settings.optString("password"),
            )

            LocalNodeProtocol.SHADOWSOCKS -> baseDraft.copy(
                password = settings.optString("password"),
                shadowsocksMethod = shadowsocksMethodFrom(settings.optString("method")),
                shadowsocksUot = settings.optBoolean("uot"),
                shadowsocksUotVersion = jsonNumberToString(settings, "uotVersion", "2"),
            )

            LocalNodeProtocol.SOCKS -> baseDraft.copy(
                username = settings.optString("user"),
                password = settings.optString("pass"),
            )

            LocalNodeProtocol.HTTP -> baseDraft.copy(
                username = settings.optString("user"),
                password = settings.optString("pass"),
                httpHeaders = jsonObjectToHeaderLines(settings.optJSONObject("headers")),
            )

            LocalNodeProtocol.WIREGUARD -> {
                val peer = settings.optJSONArray("peers")?.optJSONObject(0)
                val endpoint = peer?.optString("endpoint").orEmpty()
                val endpointSeparator = endpoint.lastIndexOf(':')
                baseDraft.copy(
                    address = endpoint.takeIf { endpointSeparator > 0 }?.substring(0, endpointSeparator)?.removeSurrounding("[", "]").orEmpty()
                        .ifBlank { server.address },
                    port = endpoint.takeIf { endpointSeparator > 0 }?.substring(endpointSeparator + 1).orEmpty()
                        .ifBlank { server.port.ifBlank { protocol.defaultPort.toString() } },
                    wireGuardSecretKey = settings.optString("secretKey"),
                    wireGuardPublicKey = peer?.optString("publicKey").orEmpty(),
                    wireGuardPreSharedKey = peer?.optString("preSharedKey").orEmpty(),
                    wireGuardLocalAddresses = jsonArrayToCsv(settings.optJSONArray("address")),
                    wireGuardAllowedIps = jsonArrayToCsv(peer?.optJSONArray("allowedIPs")),
                    wireGuardReserved = jsonArrayToCsv(settings.optJSONArray("reserved"), delimiter = ","),
                    wireGuardMtu = jsonNumberToString(settings, "mtu", "1420"),
                    wireGuardWorkers = jsonNumberToString(settings, "workers", ""),
                    wireGuardKeepAlive = jsonNumberToString(peer, "keepAlive", ""),
                    wireGuardDomainStrategy = LocalNodeWireGuardDomainStrategy.entries.firstOrNull {
                        it.wireValue.equals(settings.optString("domainStrategy"), ignoreCase = true)
                    } ?: LocalNodeWireGuardDomainStrategy.FORCE_IP,
                    wireGuardNoKernelTun = settings.optBoolean("noKernelTun"),
                )
            }

            LocalNodeProtocol.HYSTERIA -> baseDraft.copy(
                serverName = tlsSettings?.optString("serverName").orEmpty().ifBlank { server.address },
                hysteriaAuth = hysteriaSettings?.optString("auth").orEmpty(),
                hysteriaUdpIdleTimeout = jsonNumberToString(hysteriaSettings, "udpIdleTimeout", "60"),
            )
        }
    }

    fun requiresCompatibilityEditWarning(server: ServerNode): Boolean {
        if (server.outboundJson.isBlank()) return true
        return runCatching { JSONObject(server.outboundJson) }.isFailure
    }

    private fun prefillFromLegacyServerNode(server: ServerNode): LocalNodeDraft {
        val rawUri = server.rawUri.trim()
        if (rawUri.isNotBlank() && supportsShareLink(rawUri)) {
            return prefillFromShareLink(rawUri).copy(
                nodeName = server.name.ifBlank { decodeFragment(URI(rawUri).rawFragment) },
            )
        }
        val protocol = protocolFromServerLabel(server.protocol)
        val inferredSecurity = when (server.security.trim().uppercase(Locale.ROOT)) {
            "TLS" -> LocalNodeSecurity.TLS
            "REALITY" -> LocalNodeSecurity.REALITY
            else -> when (protocol) {
                LocalNodeProtocol.TROJAN,
                LocalNodeProtocol.HYSTERIA,
                -> LocalNodeSecurity.TLS

                else -> LocalNodeSecurity.NONE
            }
        }
        val inferredTransport = when (server.transport.trim().lowercase(Locale.ROOT)) {
            "grpc", "gprc" -> LocalNodeTransport.GRPC
            "websocket", "ws" -> LocalNodeTransport.WS
            "http", "h2", "http/2" -> LocalNodeTransport.HTTP2
            "httpupgrade" -> LocalNodeTransport.HTTP_UPGRADE
            "xhttp" -> LocalNodeTransport.XHTTP
            else -> LocalNodeTransport.TCP
        }
        return applyProtocolDefaults(LocalNodeDraft(), protocol).copy(
            nodeName = server.name,
            address = server.address,
            port = server.port.ifBlank { protocol.defaultPort.toString() },
            security = inferredSecurity,
            transport = when (protocol) {
                LocalNodeProtocol.WIREGUARD,
                LocalNodeProtocol.HTTP,
                LocalNodeProtocol.SOCKS,
                LocalNodeProtocol.HYSTERIA,
                -> LocalNodeTransport.TCP

                else -> inferredTransport
            },
            serverName = if (inferredSecurity == LocalNodeSecurity.TLS || inferredSecurity == LocalNodeSecurity.REALITY) {
                server.address
            } else {
                ""
            },
            vlessFlow = if (protocol == LocalNodeProtocol.VLESS && server.flow != "none") server.flow else "",
            vmessSecurity = if (protocol == LocalNodeProtocol.VMESS) {
                server.flow.takeUnless { it.isBlank() || it.equals("无", ignoreCase = true) } ?: "auto"
            } else {
                "auto"
            },
            shadowsocksMethod = if (protocol == LocalNodeProtocol.SHADOWSOCKS) {
                shadowsocksMethodFrom(server.flow)
            } else {
                LocalNodeShadowsocksMethod.CHACHA20_POLY1305
            },
        )
    }

    private fun buildVlessOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val userId = draft.userId.trim()
        require(userId.isNotBlank()) { "VLESS 需要填写 UUID。" }
        return JSONObject().apply {
            put("protocol", "vless")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("id", userId)
                    put("encryption", draft.vlessEncryption.trim().ifBlank { "none" })
                    draft.vlessFlow.trim().takeIf { it.isNotBlank() }?.let { put("flow", it) }
                },
            )
            put("streamSettings", buildCommonStreamSettings(draft))
        }
    }

    private fun buildVmessOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val userId = draft.userId.trim()
        require(userId.isNotBlank()) { "VMess 需要填写 UUID。" }
        return JSONObject().apply {
            put("protocol", "vmess")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("id", userId)
                    put("security", draft.vmessSecurity.trim().ifBlank { "auto" })
                },
            )
            put("streamSettings", buildCommonStreamSettings(draft))
        }
    }

    private fun buildTrojanOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val password = draft.password.trim()
        require(password.isNotBlank()) { "Trojan 需要填写密码。" }
        return JSONObject().apply {
            put("protocol", "trojan")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("password", password)
                },
            )
            put("streamSettings", buildCommonStreamSettings(draft.copy(security = LocalNodeSecurity.TLS)))
        }
    }

    private fun buildShadowsocksOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val password = draft.password.trim()
        require(password.isNotBlank()) { "Shadowsocks 需要填写密码或密钥。" }
        return JSONObject().apply {
            put("protocol", "shadowsocks")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("method", draft.shadowsocksMethod.wireValue)
                    put("password", password)
                    put("uot", draft.shadowsocksUot)
                    put("uotVersion", draft.shadowsocksUotVersion.trim().toIntOrNull() ?: 2)
                },
            )
            put("streamSettings", buildCommonStreamSettings(draft))
        }
    }

    private fun buildSocksOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        return JSONObject().apply {
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    draft.username.trim().takeIf { it.isNotBlank() }?.let { put("user", it) }
                    draft.password.trim().takeIf { it.isNotBlank() }?.let { put("pass", it) }
                },
            )
            if (draft.security == LocalNodeSecurity.TLS) {
                put("streamSettings", buildPlainTlsSettings(draft))
            }
        }
    }

    private fun buildHttpOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        return JSONObject().apply {
            put("protocol", "http")
            put(
                "settings",
                JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    draft.username.trim().takeIf { it.isNotBlank() }?.let { put("user", it) }
                    draft.password.trim().takeIf { it.isNotBlank() }?.let { put("pass", it) }
                    val headers = parseHttpHeaders(draft.httpHeaders)
                    if (headers.isNotEmpty()) {
                        put(
                            "headers",
                            JSONObject().apply {
                                headers.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                },
            )
            if (draft.security == LocalNodeSecurity.TLS) {
                put("streamSettings", buildPlainTlsSettings(draft))
            }
        }
    }

    private fun buildWireGuardOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val secretKey = draft.wireGuardSecretKey.trim()
        val publicKey = draft.wireGuardPublicKey.trim()
        require(secretKey.isNotBlank()) { "WireGuard 需要填写客户端私钥。" }
        require(publicKey.isNotBlank()) { "WireGuard 需要填写对端公钥。" }
        return JSONObject().apply {
            put("protocol", "wireguard")
            put(
                "settings",
                JSONObject().apply {
                    put("secretKey", secretKey)
                    val addresses = parseList(draft.wireGuardLocalAddresses)
                    if (addresses.isNotEmpty()) {
                        put(
                            "address",
                            JSONArray().apply { addresses.forEach(::put) },
                        )
                    }
                    put(
                        "peers",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("endpoint", "$address:$port")
                                    put("publicKey", publicKey)
                                    draft.wireGuardPreSharedKey.trim().takeIf { it.isNotBlank() }?.let { put("preSharedKey", it) }
                                    draft.wireGuardKeepAlive.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { put("keepAlive", it) }
                                    val allowedIps = parseList(draft.wireGuardAllowedIps)
                                    if (allowedIps.isNotEmpty()) {
                                        put("allowedIPs", JSONArray().apply { allowedIps.forEach(::put) })
                                    }
                                },
                            )
                        },
                    )
                    put("noKernelTun", draft.wireGuardNoKernelTun)
                    draft.wireGuardMtu.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
                    val reserved = parseReserved(draft.wireGuardReserved)
                    if (reserved.isNotEmpty()) {
                        put("reserved", JSONArray().apply { reserved.forEach(::put) })
                    }
                    draft.wireGuardWorkers.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("workers", it) }
                    put("domainStrategy", draft.wireGuardDomainStrategy.wireValue)
                },
            )
        }
    }

    private fun buildHysteriaOutbound(
        draft: LocalNodeDraft,
        address: String,
        port: Int,
    ): JSONObject {
        val auth = draft.hysteriaAuth.trim()
        require(auth.isNotBlank()) { "Hysteria2 需要填写认证口令。" }
        val serverName = draft.serverName.trim()
        require(serverName.isNotBlank()) { "Hysteria2 建议填写 TLS Server Name / SNI。" }
        return JSONObject().apply {
            put("protocol", "hysteria")
            put(
                "settings",
                JSONObject().apply {
                    put("version", 2)
                    put("address", address)
                    put("port", port)
                },
            )
            put(
                "streamSettings",
                JSONObject().apply {
                    put("network", "hysteria")
                    put("security", "tls")
                    put(
                        "tlsSettings",
                        buildTlsSettings(
                            serverName = draft.serverName,
                            fingerprint = draft.fingerprint,
                            alpn = draft.alpn,
                            allowInsecure = draft.allowInsecure,
                        ),
                    )
                    put(
                        "hysteriaSettings",
                        JSONObject().apply {
                            put("version", 2)
                            put("auth", auth)
                            draft.hysteriaUdpIdleTimeout.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("udpIdleTimeout", it) }
                        },
                    )
                },
            )
        }
    }

    private fun buildCommonStreamSettings(draft: LocalNodeDraft): JSONObject {
        val security = draft.security
        if (security == LocalNodeSecurity.REALITY) {
            require(draft.serverName.trim().isNotBlank()) { "使用 REALITY 时请填写 Server Name / SNI。" }
            require(draft.realityPublicKey.trim().isNotBlank()) { "使用 REALITY 时请填写公钥。" }
        }
        return JSONObject().apply {
            put("network", draft.transport.wireValue)
            put("security", security.wireValue)
            when (draft.transport) {
                LocalNodeTransport.WS -> put(
                    "wsSettings",
                    JSONObject().apply {
                        draft.path.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                        draft.host.trim().takeIf { it.isNotBlank() }?.let { hostValue ->
                            put(
                                "headers",
                                JSONObject().apply {
                                    put("Host", hostValue)
                                },
                            )
                        }
                    },
                )

                LocalNodeTransport.GRPC -> put(
                    "grpcSettings",
                    JSONObject().apply {
                        draft.serviceName.trim().takeIf { it.isNotBlank() }?.let { put("serviceName", it) }
                        (draft.host.trim().takeIf { it.isNotBlank() }
                            ?: draft.serverName.trim().takeIf { it.isNotBlank() })?.let { put("authority", it) }
                        put("user_agent", GRPC_CLIENT_USER_AGENT)
                    },
                )

                LocalNodeTransport.HTTP2 -> put(
                    "httpSettings",
                    JSONObject().apply {
                        draft.host.trim().takeIf { it.isNotBlank() }?.let {
                            put("host", JSONArray().put(it))
                        }
                        draft.path.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                LocalNodeTransport.HTTP_UPGRADE -> put(
                    "httpupgradeSettings",
                    JSONObject().apply {
                        draft.host.trim().takeIf { it.isNotBlank() }?.let { put("host", it) }
                        draft.path.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                LocalNodeTransport.XHTTP -> put(
                    "xhttpSettings",
                    JSONObject().apply {
                        draft.host.trim().takeIf { it.isNotBlank() }?.let { put("host", it) }
                        draft.path.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                    },
                )

                LocalNodeTransport.TCP -> Unit
            }

            when (security) {
                LocalNodeSecurity.NONE -> Unit
                LocalNodeSecurity.TLS -> put(
                    "tlsSettings",
                    buildTlsSettings(
                        serverName = draft.serverName,
                        fingerprint = draft.fingerprint,
                        alpn = draft.alpn,
                        allowInsecure = draft.allowInsecure,
                        defaultAlpn = if (draft.transport == LocalNodeTransport.GRPC) "h2,http/1.1" else null,
                    ),
                )

                LocalNodeSecurity.REALITY -> put(
                    "realitySettings",
                    JSONObject().apply {
                        put("serverName", draft.serverName.trim())
                        draft.fingerprint.trim().takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                        if (draft.transport == LocalNodeTransport.GRPC) {
                            put(
                                "alpn",
                                JSONArray().apply {
                                    val values = draft.alpn
                                        .split(",")
                                        .map(String::trim)
                                        .filter(String::isNotBlank)
                                        .ifEmpty { listOf("h2", "http/1.1") }
                                    values.forEach(::put)
                                },
                            )
                        }
                        put("publicKey", draft.realityPublicKey.trim())
                        draft.realityShortId.trim().takeIf { it.isNotBlank() }?.let { put("shortId", it) }
                        draft.realitySpiderX.trim().takeIf { it.isNotBlank() }?.let { put("spiderX", it) }
                    },
                )
            }
        }
    }

    private fun buildPlainTlsSettings(draft: LocalNodeDraft): JSONObject {
        return JSONObject().apply {
            put("network", "tcp")
            put("security", "tls")
            put(
                "tlsSettings",
                buildTlsSettings(
                    serverName = draft.serverName,
                    fingerprint = draft.fingerprint,
                    alpn = draft.alpn,
                    allowInsecure = draft.allowInsecure,
                ),
            )
        }
    }

    private fun buildTlsSettings(
        serverName: String,
        fingerprint: String,
        alpn: String,
        allowInsecure: Boolean,
        defaultAlpn: String? = null,
    ): JSONObject {
        return JSONObject().apply {
            serverName.trim().takeIf { it.isNotBlank() }?.let { put("serverName", it) }
            fingerprint.trim().takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
            if (allowInsecure) {
                put("allowInsecure", true)
            }
            val effectiveAlpn = alpn.takeIf { it.isNotBlank() } ?: defaultAlpn.orEmpty()
            val values = effectiveAlpn.split(",").map(String::trim).filter(String::isNotBlank)
            if (values.isNotEmpty()) {
                put("alpn", JSONArray().apply { values.forEach(::put) })
            }
        }
    }

    private fun parseHttpHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .associate { line ->
                val separator = line.indexOf(':')
                require(separator > 0 && separator < line.lastIndex) {
                    "HTTP 头请按 “Header: Value” 的格式填写。"
                }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(key.isNotBlank() && value.isNotBlank()) {
                    "HTTP 头请按 “Header: Value” 的格式填写。"
                }
                key to value
            }
    }

    private fun parseVlessDraft(raw: String): LocalNodeDraft {
        val uri = URI(raw)
        val query = parseQuery(uri.rawQuery)
        return applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VLESS).copy(
            nodeName = decodeFragment(uri.rawFragment),
            address = uri.host.orEmpty(),
            port = (if (uri.port == -1) 443 else uri.port).toString(),
            userId = uri.userInfo.orEmpty(),
            vlessEncryption = query["encryption"] ?: "none",
            vlessFlow = query["flow"].orEmpty(),
            transport = transportFrom(query["type"], LocalNodeTransport.TCP),
            security = securityFrom(query["security"], LocalNodeSecurity.NONE),
            host = query["host"].orEmpty(),
            path = query["path"].orEmpty(),
            serviceName = query["serviceName"].orEmpty(),
            serverName = query["sni"].orEmpty(),
            fingerprint = query["fp"].orEmpty(),
            alpn = query["alpn"].orEmpty(),
            realityPublicKey = query["pbk"].orEmpty(),
            realityShortId = query["sid"].orEmpty(),
            realitySpiderX = query["spx"].orEmpty(),
        )
    }

    private fun parseVmessDraft(raw: String): LocalNodeDraft {
        val body = raw.removePrefix("vmess://")
        val authPart = body.substringBefore("?")
        val queryPart = body.substringAfter("?", "")
        val decoded = decodeBase64Flexible(authPart, "VMess 分享链接").trim()
        val query = parseQuery(queryPart)
        val queryName = query["remark"] ?: query["remarks"] ?: query["ps"]
        if (decoded.startsWith("{")) {
            val json = JSONObject(decoded)
            return applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VMESS).copy(
                nodeName = json.optString("ps").ifBlank { queryName.orEmpty() },
                address = json.optString("add"),
                port = json.optString("port").ifBlank { "443" },
                userId = json.optString("id"),
                vmessSecurity = json.optString("scy").ifBlank { "auto" },
                transport = transportFrom(json.optString("net"), LocalNodeTransport.TCP),
                security = when (json.optString("tls").lowercase(Locale.ROOT)) {
                    "tls" -> LocalNodeSecurity.TLS
                    else -> LocalNodeSecurity.NONE
                },
                host = json.optString("host"),
                path = json.optString("path"),
                serviceName = json.optString("path").takeIf { transportFrom(json.optString("net"), LocalNodeTransport.TCP) == LocalNodeTransport.GRPC }
                    .orEmpty(),
                serverName = json.optString("sni"),
                fingerprint = json.optString("fp"),
                alpn = json.optString("alpn"),
            )
        }

        val securityMethod = decoded.substringBefore(":", "auto").ifBlank { "auto" }
        val credentialPart = decoded.substringAfter(":", decoded)
        val userId = credentialPart.substringBefore("@")
        val hostPort = credentialPart.substringAfter("@", "")
        val address = hostPort.substringBeforeLast(":", hostPort)
        val port = hostPort.substringAfterLast(":", "443")
        return applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.VMESS).copy(
            nodeName = queryName.orEmpty(),
            address = address,
            port = port,
            userId = userId,
            vmessSecurity = securityMethod,
            transport = when (query["obfs"]?.lowercase(Locale.ROOT) ?: query["type"]?.lowercase(Locale.ROOT)) {
                "websocket" -> LocalNodeTransport.WS
                else -> transportFrom(query["obfs"] ?: query["type"], LocalNodeTransport.TCP)
            },
            security = when (query["tls"]?.lowercase(Locale.ROOT)) {
                "1", "true", "tls" -> LocalNodeSecurity.TLS
                else -> securityFrom(query["security"], LocalNodeSecurity.NONE)
            },
            host = query["host"] ?: query["obfsParam"].orEmpty(),
            path = query["path"].orEmpty(),
            serviceName = query["serviceName"].orEmpty(),
            serverName = query["peer"] ?: query["sni"].orEmpty(),
            fingerprint = query["fp"].orEmpty(),
            alpn = query["alpn"].orEmpty(),
        )
    }

    private fun parseTrojanDraft(raw: String): LocalNodeDraft {
        val uri = URI(raw)
        val query = parseQuery(uri.rawQuery)
        return applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.TROJAN).copy(
            nodeName = decodeFragment(uri.rawFragment),
            address = uri.host.orEmpty(),
            port = (if (uri.port == -1) 443 else uri.port).toString(),
            password = uri.userInfo.orEmpty(),
            transport = transportFrom(query["type"], LocalNodeTransport.TCP),
            security = LocalNodeSecurity.TLS,
            host = query["host"].orEmpty(),
            path = query["path"].orEmpty(),
            serviceName = query["serviceName"] ?: query["service-name"].orEmpty(),
            serverName = query["sni"] ?: query["peer"] ?: query["servername"] ?: query["serverName"].orEmpty(),
            fingerprint = query["fp"] ?: query["fingerprint"].orEmpty(),
            alpn = query["alpn"].orEmpty(),
            allowInsecure = query["allowInsecure"] == "1" || query["allowInsecure"] == "true",
        )
    }

    private fun parseShadowsocksDraft(raw: String): LocalNodeDraft {
        val withoutScheme = raw.removePrefix("ss://")
        val fragment = withoutScheme.substringAfter("#", "")
        val beforeFragment = withoutScheme.substringBefore("#")
        val mainPart = beforeFragment.substringBefore("?")
        val query = parseQuery(beforeFragment.substringAfter("?", ""))
        val decodedMain = if (mainPart.contains("@")) {
            val encodedUserInfo = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@")
            val userInfo = if (encodedUserInfo.contains(":")) {
                encodedUserInfo
            } else {
                decodeBase64Flexible(encodedUserInfo, "Shadowsocks 分享链接")
            }
            "$userInfo@$hostPort"
        } else {
            decodeBase64Flexible(mainPart, "Shadowsocks 分享链接")
        }
        val methodPassword = decodedMain.substringBefore("@")
        val hostPort = decodedMain.substringAfter("@")
        val method = methodPassword.substringBefore(":")
        val password = methodPassword.substringAfter(":", "")
        val address = hostPort.substringBeforeLast(":", hostPort)
        val port = hostPort.substringAfterLast(":", "8388")
        val baseDraft = applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.SHADOWSOCKS).copy(
            nodeName = decodeFragment(fragment),
            address = address,
            port = port,
            password = password,
            shadowsocksMethod = shadowsocksMethodFrom(method),
        )
        val pluginSpec = query["plugin"].orEmpty()
        if (pluginSpec.isBlank()) return baseDraft
        return applyShadowsocksPlugin(baseDraft, pluginSpec) ?: baseDraft
    }

    private fun parseProxyDraft(raw: String, protocol: LocalNodeProtocol): LocalNodeDraft {
        val uri = URI(raw)
        val userInfo = uri.userInfo.orEmpty()
        return applyProtocolDefaults(LocalNodeDraft(), protocol).copy(
            nodeName = decodeFragment(uri.rawFragment),
            address = uri.host.orEmpty(),
            port = (if (uri.port == -1) protocol.defaultPort else uri.port).toString(),
            username = userInfo.substringBefore(":", ""),
            password = userInfo.substringAfter(":", ""),
        )
    }

    private fun parseHysteriaDraft(raw: String): LocalNodeDraft {
        val normalized = if (raw.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2" + raw.removePrefix("hy2")
        } else {
            raw
        }
        val uri = URI(normalized)
        val query = parseQuery(uri.rawQuery)
        return applyProtocolDefaults(LocalNodeDraft(), LocalNodeProtocol.HYSTERIA).copy(
            nodeName = decodeFragment(uri.rawFragment),
            address = uri.host.orEmpty(),
            port = (if (uri.port == -1) 443 else uri.port).toString(),
            hysteriaAuth = uri.userInfo.orEmpty().ifBlank { query["auth"] ?: query["password"].orEmpty() },
            serverName = query["sni"] ?: query["peer"].orEmpty(),
            alpn = query["alpn"].orEmpty(),
            allowInsecure = query["insecure"] == "1" || query["insecure"] == "true",
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                key to value
            }
            .toMap()
    }

    private fun decodeFragment(fragment: String?): String = fragment?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

    private fun protocolFromServerLabel(value: String): LocalNodeProtocol {
        return when (value.trim().uppercase(Locale.ROOT)) {
            "VLESS" -> LocalNodeProtocol.VLESS
            "VMESS" -> LocalNodeProtocol.VMESS
            "TROJAN" -> LocalNodeProtocol.TROJAN
            "SHADOWSOCKS", "SS" -> LocalNodeProtocol.SHADOWSOCKS
            "SOCKS", "SOCKS5" -> LocalNodeProtocol.SOCKS
            "HTTP", "HTTP PROXY" -> LocalNodeProtocol.HTTP
            "WIREGUARD" -> LocalNodeProtocol.WIREGUARD
            "HYSTERIA2", "HYSTERIA" -> LocalNodeProtocol.HYSTERIA
            else -> error("当前节点协议暂不支持编辑。")
        }
    }

    private fun jsonArrayToCsv(array: JSONArray?, delimiter: String = ", "): String {
        if (array == null || array.length() == 0) return ""
        return buildList {
            for (index in 0 until array.length()) {
                add(array.opt(index)?.toString().orEmpty())
            }
        }.filter { it.isNotBlank() }
            .joinToString(delimiter)
    }

    private fun jsonNumberToString(json: JSONObject?, key: String, fallback: String): String {
        if (json == null || !json.has(key) || json.isNull(key)) return fallback
        return json.opt(key)?.toString().orEmpty().ifBlank { fallback }
    }

    private fun jsonObjectToHeaderLines(headers: JSONObject?): String {
        if (headers == null || headers.length() == 0) return ""
        return headers.keys().asSequence()
            .toList()
            .sorted()
            .joinToString("\n") { key -> "$key: ${headers.optString(key)}" }
    }

    private fun transportFrom(raw: String?, fallback: LocalNodeTransport): LocalNodeTransport {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (value) {
            "", "tcp", "none" -> fallback
            "ws", "websocket" -> LocalNodeTransport.WS
            "grpc" -> LocalNodeTransport.GRPC
            "http", "h2" -> LocalNodeTransport.HTTP2
            "httpupgrade" -> LocalNodeTransport.HTTP_UPGRADE
            "xhttp", "splithttp" -> LocalNodeTransport.XHTTP
            else -> fallback
        }
    }

    private fun securityFrom(raw: String?, fallback: LocalNodeSecurity): LocalNodeSecurity {
        return when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "none", "" -> fallback
            "tls" -> LocalNodeSecurity.TLS
            "reality" -> LocalNodeSecurity.REALITY
            else -> fallback
        }
    }

    private fun shadowsocksMethodFrom(raw: String): LocalNodeShadowsocksMethod {
        return LocalNodeShadowsocksMethod.entries.firstOrNull {
            it.wireValue.equals(raw, ignoreCase = true)
        } ?: LocalNodeShadowsocksMethod.CHACHA20_POLY1305
    }

    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    private fun decodeBase64Flexible(value: String, label: String): String {
        val normalized = padBase64(value.trim())
        return runCatching {
            Base64.getDecoder().decode(normalized).decodeToString()
        }.recoverCatching {
            Base64.getUrlDecoder().decode(normalized).decodeToString()
        }.getOrElse {
            error("$label 无法解码。")
        }
    }

    private fun applyShadowsocksPlugin(
        draft: LocalNodeDraft,
        pluginSpec: String,
    ): LocalNodeDraft? {
        val segments = pluginSpec.split(';').map(String::trim).filter(String::isNotBlank)
        val pluginName = segments.firstOrNull().orEmpty()
        if (!pluginName.equals("v2ray-plugin", ignoreCase = true)) return null
        val options = segments.drop(1).associate { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) {
                entry to "true"
            } else {
                entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
            }
        }
        val mode = options["mode"].orEmpty().ifBlank { "websocket" }
        if (mode !in listOf("websocket", "ws")) return null
        val tlsEnabled = options["tls"]?.equals("true", ignoreCase = true) == true || options.containsKey("tls")
        return draft.copy(
            transport = LocalNodeTransport.WS,
            security = if (tlsEnabled) LocalNodeSecurity.TLS else LocalNodeSecurity.NONE,
            host = options["host"].orEmpty(),
            path = options["path"].orEmpty(),
            serverName = options["host"].orEmpty(),
        )
    }

    private fun validateUuid(raw: String) {
        val value = raw.trim()
        require(value.isNotBlank()) { "请填写 UUID。" }
        runCatching { UUID.fromString(value) }.getOrElse {
            error("UUID 格式不正确。")
        }
    }

    private fun validateIpList(raw: String, requireCidr: Boolean, field: String) {
        parseList(raw).forEach { entry ->
            if (requireCidr) {
                require('/' in entry) { "$field 里的每一项都需要带 CIDR，例如 10.0.0.2/32。" }
            }
            require(entry.length >= 3) { "$field 里存在无效项。"}
        }
    }

    private fun validateCsvValues(raw: String, field: String) {
        val values = raw.split(",").map(String::trim).filter(String::isNotBlank)
        require(values.isNotEmpty()) { "$field 不能为空。"}
        require(values.none { it.contains(' ') }) { "$field 里的值请用英文逗号分隔，不要夹空格块。"}
    }

    private fun parseReserved(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        val values = raw.split(",").map(String::trim).filter(String::isNotBlank).map {
            it.toIntOrNull()?.takeIf { value -> value in 0..255 }
                ?: error("WireGuard reserved 需要是 0-255 的三个数字，例如 1,2,3。")
        }
        require(values.size == 3) { "WireGuard reserved 需要刚好三个数字，例如 1,2,3。" }
        return values
    }

    private fun parseList(raw: String): List<String> {
        return raw.split(",", "\n")
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun parsePort(value: String, field: String): Int {
        val port = value.trim().toIntOrNull() ?: error("$field 需要是 1-65535 的数字。")
        require(port in 1..65535) { "$field 需要是 1-65535 的数字。" }
        return port
    }

    private fun securityLabel(draft: LocalNodeDraft): String {
        return when (draft.protocol) {
            LocalNodeProtocol.WIREGUARD -> "内建加密"
            LocalNodeProtocol.HYSTERIA -> "TLS"
            else -> draft.security.displayName
        }
    }

    private fun transportLabel(draft: LocalNodeDraft): String {
        return when (draft.protocol) {
            LocalNodeProtocol.WIREGUARD -> "WireGuard"
            LocalNodeProtocol.HTTP,
            LocalNodeProtocol.SOCKS,
            -> "TCP"

            LocalNodeProtocol.HYSTERIA -> "QUIC / Hysteria"
            else -> draft.transport.displayName
        }
    }

    private fun flowLabel(draft: LocalNodeDraft): String {
        return when (draft.protocol) {
            LocalNodeProtocol.VLESS -> draft.vlessFlow.trim().ifBlank { "none" }
            LocalNodeProtocol.VMESS -> draft.vmessSecurity.trim().ifBlank { "auto" }
            LocalNodeProtocol.TROJAN -> "password"
            LocalNodeProtocol.SHADOWSOCKS -> draft.shadowsocksMethod.displayName
            LocalNodeProtocol.SOCKS,
            LocalNodeProtocol.HTTP,
            -> if (draft.username.trim().isNotBlank()) "密码鉴权" else "无鉴权"

            LocalNodeProtocol.WIREGUARD -> draft.wireGuardDomainStrategy.displayName
            LocalNodeProtocol.HYSTERIA -> "version=2"
        }
    }

    private fun buildCode(address: String, name: String): String {
        val seed = name.ifBlank { address }
        val prefix = seed
            .filter { it.isLetterOrDigit() }
            .take(3)
            .uppercase(Locale.ROOT)
            .ifBlank { "LOC" }
        return "$prefix-${(address.length + name.length).toString().padStart(2, '0')}"
    }

    private fun inferRegion(name: String, address: String): String {
        val source = "$name $address".lowercase(Locale.ROOT)
        return when {
            listOf("jp", "japan", "tokyo", "日本").any(source::contains) -> "日本"
            listOf("hk", "hong kong", "hongkong", "香港").any(source::contains) -> "香港"
            listOf("sg", "singapore", "新加坡").any(source::contains) -> "新加坡"
            listOf("us", "america", "los angeles", "united states", "美国").any(source::contains) -> "北美"
            listOf("uk", "eu", "frankfurt", "london", "europe", "欧洲").any(source::contains) -> "欧洲"
            else -> "未标注"
        }
    }
}
