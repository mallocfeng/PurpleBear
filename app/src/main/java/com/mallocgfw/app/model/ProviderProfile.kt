package com.mallocgfw.app.model

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * What a subscription response says about its provider, beyond the nodes:
 * - `profile-title` (Hiddify style; plain text or `base64:…`),
 * - else the file name in `content-disposition` (V2Board, XBoard and most
 *   panels send their site name there, often RFC 5987 encoded),
 * - `profile-web-page-url`: the provider's website.
 */
data class ProviderProfile(val title: String?, val webPageUrl: String?) {
    companion object {
        /** Null when [headers] (names in lower case) carry none of it. */
        fun parse(headers: Map<String, String>): ProviderProfile? {
            val title = headers["profile-title"]?.let(::decodeTitle)?.let(::cleanTitle)
                ?: headers["content-disposition"]?.let(::fileName)?.let(::cleanTitle)
            val site = headers["profile-web-page-url"]?.trim()
                ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
                ?.takeIf { it.length <= 512 && it.none(Char::isWhitespace) }
            return if (title == null && site == null) null else ProviderProfile(title, site)
        }

        private fun decodeTitle(raw: String): String {
            val value = raw.trim()
            if (!value.startsWith("base64:", ignoreCase = true)) return value
            return runCatching { String(Base64.getDecoder().decode(value.substring(7).trim()), Charsets.UTF_8) }.getOrDefault("")
        }

        /** `attachment; filename*=UTF-8''%E6%9C%BA%E5%9C%BA` or `attachment; filename="name"`. */
        internal fun fileName(header: String): String? {
            val parts = header.split(';').map { it.trim() }
            parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
                val value = part.substringAfter('=').trim().trim('"')
                val encoded = value.substringAfter("''", value)
                return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrNull()
            }
            return parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.trim('"')
        }

        private val FILE_EXTENSIONS = Regex("""\.(ya?ml|txt|json|conf|ini|list)$""", RegexOption.IGNORE_CASE)

        /** Trimmed, without a file extension or control characters; null when nothing useful is left. */
        private fun cleanTitle(raw: String): String? {
            val text = raw.filterNot(Char::isISOControl).trim().replace(FILE_EXTENSIONS, "").trim()
            if (text.isEmpty() || text.length > 60) return null
            // Panels that know no name send placeholders like "clash" or "sub".
            if (text.lowercase() in GENERIC_NAMES) return null
            return text
        }

        private val GENERIC_NAMES = setOf(
            "clash", "clash.meta", "mihomo", "sub", "subscribe", "subscription", "config", "profile",
            "v2ray", "v2rayn", "v2rayng", "sing-box", "singbox", "shadowrocket", "surge", "stash", "attachment",
        )
    }
}
