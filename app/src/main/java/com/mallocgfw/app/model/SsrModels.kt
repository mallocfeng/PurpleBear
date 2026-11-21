package com.mallocgfw.app.model

import com.mallocgfw.app.xray.LocalProxyAllocation
import com.mallocgfw.app.xray.LocalProxyEndpoint
import com.mallocgfw.app.xray.LocalProxyRuntime
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Placeholder port for SSR outbound previews; real runs use a random port. */
const val SSR_LOCAL_SOCKS_PORT = 17891

data class SsrEndpoint(
    val name: String,
    val server: String,
    val port: Int,
    val protocol: String,
    val method: String,
    val obfs: String,
    val password: String,
    val obfsParam: String = "",
    val protocolParam: String = "",
    val udp: Boolean = true,
)

object SsrLinkCodec {
    fun isSsrShareLink(raw: String): Boolean = raw.trim().startsWith("ssr://", ignoreCase = true)

    fun parse(raw: String): SsrEndpoint {
        val encoded = raw.trim().substringAfter("://", raw.trim())
        val decoded = decodeBase64Url(encoded, "SSR 分享链接").trim()
        val mainPart = decoded.substringBefore("/?")
        val query = parseQuery(decoded.substringAfter("/?", ""))
        val parts = mainPart.split(":", limit = 6)
        require(parts.size == 6) { "SSR 分享链接格式不完整。" }
        return SsrEndpoint(
            name = decodeOptionalBase64(query["remarks"]).ifBlank { parts[0] },
            server = parts[0],
            port = parts[1].toIntOrNull() ?: error("SSR 端口格式不正确。"),
            protocol = parts[2],
            method = parts[3],
            obfs = parts[4],
            password = decodeBase64Url(parts[5], "SSR 密码"),
            obfsParam = decodeOptionalBase64(query["obfsparam"]),
            protocolParam = decodeOptionalBase64(query["protoparam"]),
            udp = query["udp"]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true,
        )
    }

    fun encode(endpoint: SsrEndpoint): String {
        val main = buildString {
            append(endpoint.server)
            append(':')
            append(endpoint.port)
            append(':')
            append(endpoint.protocol)
            append(':')
            append(endpoint.method)
            append(':')
            append(endpoint.obfs)
            append(':')
            append(encodeBase64Url(endpoint.password))
            append("/?")
            append("obfsparam=")
            append(encodeBase64Url(endpoint.obfsParam))
            append("&protoparam=")
            append(encodeBase64Url(endpoint.protocolParam))
            append("&remarks=")
            append(encodeBase64Url(endpoint.name))
            append("&udp=")
            append(if (endpoint.udp) "1" else "0")
        }
        return "ssr://${encodeBase64Url(main)}"
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                key to value
            }
            .toMap()
    }

    private fun decodeOptionalBase64(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return decodeBase64Url(value, "SSR 参数")
    }

    private fun encodeBase64Url(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    }

    private fun decodeBase64Url(value: String, label: String): String {
        val normalized = value.trim() + "=".repeat((4 - value.trim().length % 4) % 4)
        return runCatching {
            Base64.getUrlDecoder().decode(normalized).decodeToString()
        }.recoverCatching {
            Base64.getDecoder().decode(normalized).decodeToString()
        }.getOrElse {
            error("$label 无法解码。")
        }
    }
}

object SsrMihomoConfigFactory {
    fun buildConfig(endpoint: SsrEndpoint, listener: LocalProxyEndpoint): String =
        buildConfig(NodeCompatibility.ssrProxy(endpoint), listener)

    fun buildConfig(proxy: MihomoProxy, listener: LocalProxyEndpoint): String =
        buildConfig(listOf(proxy to listener))

    /**
     * Sidecar config: each proxy gets its own authenticated loopback listener
     * whose TCP and UDP traffic goes straight to that proxy.
     */
    fun buildConfig(proxies: List<Pair<MihomoProxy, LocalProxyEndpoint>>): String {
        require(proxies.isNotEmpty()) { "mihomo sidecar 没有可运行的节点。" }
        return buildString {
            appendLine("allow-lan: false")
            appendLine("bind-address: ${yamlQuote(LocalProxyRuntime.LOOPBACK_HOST)}")
            appendLine("mode: rule")
            appendLine("log-level: warning")
            appendLine("ipv6: true")
            appendLine("unified-delay: true")
            appendLine("proxies:")
            proxies.forEach { (proxy, _) -> proxy.yamlLines.forEach(::appendLine) }
            appendLine("listeners:")
            proxies.forEachIndexed { index, (proxy, listener) ->
                // Credentials are required from every client, loopback included,
                // so other apps on the device cannot reuse the sidecar.
                val entry = JSONObject().apply {
                    put("name", "pb-in-$index")
                    put("type", "mixed")
                    put("listen", listener.host)
                    put("port", listener.port)
                    put("udp", true)
                    put("users", JSONArray().put(JSONObject().put("username", listener.username).put("password", listener.password)))
                    put("proxy", proxy.name)
                }
                appendLine("  - $entry")
            }
            appendLine("rules:")
            appendLine("  - MATCH,REJECT")
        }
    }

    /** Sidecar nodes of one run, in listener-slot order: main node first. */
    fun sidecarNodes(main: ServerNode, others: List<ServerNode>): List<ServerNode> =
        (listOf(main) + others).distinctBy { it.id }.filter(NodeCompatibility::runsInSidecar)

    fun endpointFor(node: ServerNode, sidecarNodes: List<ServerNode>, allocation: LocalProxyAllocation): LocalProxyEndpoint {
        val index = sidecarNodes.indexOfFirst { it.id == node.id }
        require(index >= 0) { "${node.name} 需要兼容内核运行，但没有为它启动兼容内核。" }
        return allocation.sidecar.getOrNull(index)
            ?: error("同时使用的兼容内核节点过多（最多 ${allocation.sidecar.size} 个），请减少分流规则里的此类节点。")
    }

    /**
     * With [listener] null this is the credential-free placeholder stored in
     * the node; XrayConfigFactory swaps in the run's authenticated listener.
     */
    fun localSocksOutbound(listener: LocalProxyEndpoint? = null): JSONObject {
        return JSONObject().apply {
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("address", listener?.host ?: LocalProxyRuntime.LOOPBACK_HOST)
                    put("port", listener?.port ?: SSR_LOCAL_SOCKS_PORT)
                    if (listener != null) {
                        put("user", listener.username)
                        put("pass", listener.password)
                    }
                },
            )
        }
    }

    private fun yamlQuote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
