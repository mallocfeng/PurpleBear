package com.mallocgfw.app.model

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * "One-tap import" links that subscription pages put behind their
 * "Import to Clash / v2rayNG / sing-box / Hiddify …" buttons. Each carries
 * the subscription (or node) link and often a display name. Opening one
 * only fills the import screen; nothing is saved until the user confirms.
 */
object ImportLinks {
    data class Request(val url: String, val name: String?)

    /** URI schemes registered for these links in AndroidManifest.xml. */
    val SCHEMES = setOf(
        "clash", "clashmeta", "mihomo", "flclash", "stash",
        "v2rayng", "v2rayn",
        "sing-box", "sfa", "hiddify", "karing",
        "shadowrocket",
        "purplebear",
    )

    fun parse(uri: Uri?): Request? = uri?.toString()?.let(::parse)

    /** The import a link asks for, or null when [link] isn't one (or carries no usable URL). */
    fun parse(link: String): Request? {
        val scheme = link.substringBefore("://", "").lowercase()
        if (scheme !in SCHEMES) return null
        val rest = link.substringAfter("://")
        val fragment = rest.substringAfter('#', "").takeIf { it.isNotBlank() }?.let(::decode)
        val beforeFragment = rest.substringBefore('#')
        val path = beforeFragment.substringBefore('?')
        val query = parseQuery(beforeFragment.substringAfter('?', ""))
        val name = (query["name"] ?: query["remarks"] ?: query["remark"] ?: query["title"] ?: fragment)
            ?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH)

        val url = when {
            // shadowrocket://add/sub://<base64 url>?remarks=…
            scheme == "shadowrocket" -> {
                val encoded = path.substringAfter("sub://", "").takeIf { it.isNotBlank() } ?: return null
                decodeBase64(encoded) ?: return null
            }
            // hiddify://import/<url>#name: the URL is the rest of the link, its own query included.
            path.startsWith("import/", ignoreCase = true) -> {
                val tail = rest.substringAfter("/").substringBefore('#')
                if (tail.startsWith("http%3A", ignoreCase = true) || tail.startsWith("https%3A", ignoreCase = true)) decode(tail) else tail
            }
            // clash://install-config?url=…, v2rayng://install-sub?url=…, sing-box://import-remote-profile?url=…
            query["url"] != null -> query.getValue("url")
            else -> return null
        }.trim()
        if (!isImportable(url)) return null
        return Request(url = url, name = name)
    }

    /** A subscription URL over http(s), or a node share link; never a local file or another app's scheme. */
    private fun isImportable(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return false
        return scheme == "http" || scheme == "https" || scheme in NODE_SCHEMES
    }

    private val NODE_SCHEMES = setOf(
        "vless", "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "hy2", "tuic",
        "anytls", "socks", "socks5", "http", "https", "wireguard", "wg", "snell", "mieru",
    )

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = decode(part.substringBefore('=')).lowercase()
            val value = decode(part.substringAfter('=', ""))
            key to value
        }
        .toMap()

    private fun decode(value: String): String = runCatching {
        // A "+" in a subscription token is a literal plus far more often than a space.
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = decode(value).trim()
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { String(Base64.getUrlDecoder().decode(padded.replace('+', '-').replace('/', '_')), Charsets.UTF_8) }
            .getOrNull()
            ?.trim()
            ?.takeIf { isImportable(it) }
    }

    private const val MAX_NAME_LENGTH = 60
}
