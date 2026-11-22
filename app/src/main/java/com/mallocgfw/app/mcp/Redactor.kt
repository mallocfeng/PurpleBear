package com.mallocgfw.app.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strips credentials from anything the MCP server hands to an AI client:
 * node passwords / UUIDs / keys, subscription URL paths and queries,
 * Tailscale auth keys, local proxy accounts.
 */
object Redactor {
    private const val MASK = "[redacted]"
    const val MAX_NAME = 64

    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val tailscaleKey = Regex("""tskey-[A-Za-z0-9_-]+""")
    private val url = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]{1,15})://([^\s"'<>]+)""")
    private val keyValue = Regex(
        """(?i)\b(password|passwd|pass|pwd|uuid|token|secret|auth[_-]?key|private[_-]?key|privatekey|public[_-]?key|publickey|pbk|psk|pre[_-]?shared[_-]?key|sid|short[_-]?id|obfs[_-]?password|auth)\s*([=:])\s*("?)[^\s,;&"]+""",
    )
    private val longToken = Regex("""\b[A-Za-z0-9+/_-]{40,}={0,2}""")

    /** Keys whose values are secrets in node configs (Xray outbounds, Clash proxies). */
    private val secretKeys = setOf(
        "id", "uuid", "password", "pass", "passwd", "auth", "auth-str", "auth_str", "authkey", "token",
        "privatekey", "private-key", "private_key", "publickey", "public-key", "public_key", "pbk",
        "presharedkey", "pre-shared-key", "psk", "secretkey", "shortid", "short-id", "sid",
        "obfs-password", "obfspassword", "obfs_password", "username", "user", "key", "secret",
        "spiderx", "seed", "reserved",
    )

    /** "https://host/path?q" → "https://host/…"; userinfo dropped. */
    fun url(value: String?): String? {
        if (value.isNullOrBlank()) return value
        val match = url.matchEntire(value.trim()) ?: return MASK
        return redactUrlMatch(match)
    }

    private fun redactUrlMatch(match: MatchResult): String {
        val scheme = match.groupValues[1].lowercase()
        val rest = match.groupValues[2]
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@')
        val hasMore = rest.length > authority.length
        return when (scheme) {
            "http", "https" -> "$scheme://$host" + if (hasMore) "/…" else ""
            // Share links (vless://, ss:// …) carry the credentials themselves.
            else -> "$scheme://$MASK"
        }
    }

    /** Free text such as log lines. */
    fun text(value: String): String {
        var out = url.replace(value) { redactUrlMatch(it) }
        out = tailscaleKey.replace(out, "tskey-$MASK")
        out = keyValue.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        out = uuid.replace(out, MASK)
        out = longToken.replace(out, MASK)
        return out
    }

    /** Third-party text (node / group names): shortened, one line. */
    fun name(value: String): String {
        val single = value.replace(Regex("""[\r\n\t]+"""), " ").trim()
        return if (single.length <= MAX_NAME) single else single.take(MAX_NAME - 1) + "…"
    }

    /** A copy of a node config with every secret value masked. */
    fun config(value: Any?, extraSecretKeys: Set<String> = emptySet()): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { key ->
                val normalized = key.lowercase()
                val child = value.opt(key)
                out.put(
                    key,
                    when {
                        (normalized in secretKeys || normalized in extraSecretKeys) &&
                            (child is String || child is Number || child is JSONArray) -> MASK
                        child is String -> text(child)
                        else -> config(child, extraSecretKeys)
                    },
                )
            }
        }
        is JSONArray -> JSONArray().also { out ->
            for (i in 0 until value.length()) out.put(config(value.opt(i), extraSecretKeys))
        }
        is String -> text(value)
        else -> value
    }
}
