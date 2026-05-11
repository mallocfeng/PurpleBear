package com.mallocgfw.app.model

import java.net.URLDecoder
import java.util.Base64
import org.json.JSONObject

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
    fun buildConfig(endpoint: SsrEndpoint, socksPort: Int = SSR_LOCAL_SOCKS_PORT): String {
        return buildString {
            appendLine("mixed-port: $socksPort")
            appendLine("allow-lan: false")
            appendLine("bind-address: 127.0.0.1")
            appendLine("mode: global")
            appendLine("log-level: warning")
            appendLine("ipv6: true")
            appendLine("proxies:")
            appendLine("  - name: ${yamlQuote(endpoint.name.ifBlank { "ssr" })}")
            appendLine("    type: ssr")
            appendLine("    server: ${yamlQuote(endpoint.server)}")
            appendLine("    port: ${endpoint.port}")
            appendLine("    cipher: ${yamlQuote(endpoint.method)}")
            appendLine("    password: ${yamlQuote(endpoint.password)}")
            appendLine("    obfs: ${yamlQuote(endpoint.obfs)}")
            appendLine("    protocol: ${yamlQuote(endpoint.protocol)}")
            endpoint.obfsParam.takeIf { it.isNotBlank() }?.let {
                appendLine("    obfs-param: ${yamlQuote(it)}")
            }
            endpoint.protocolParam.takeIf { it.isNotBlank() }?.let {
                appendLine("    protocol-param: ${yamlQuote(it)}")
            }
            appendLine("    udp: ${endpoint.udp}")
            appendLine("proxy-groups:")
            appendLine("  - name: GLOBAL")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - ${yamlQuote(endpoint.name.ifBlank { "ssr" })}")
            appendLine("rules:")
            appendLine("  - MATCH,GLOBAL")
        }
    }

    fun localSocksOutbound(): JSONObject {
        return JSONObject().apply {
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("address", "127.0.0.1")
                    put("port", SSR_LOCAL_SOCKS_PORT)
                },
            )
        }
    }

    private fun yamlQuote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
