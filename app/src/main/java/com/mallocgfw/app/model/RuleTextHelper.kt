package com.mallocgfw.app.model

/**
 * Plain-language helpers for the text rule editor: build a rule line from a
 * pasted link or IP, describe existing lines, and apply templates. The text
 * itself stays the source of truth.
 */
object RuleTextHelper {
    enum class Match(val keyword: String) {
        Suffix("DOMAIN-SUFFIX"),
        Exact("DOMAIN"),
        Keyword("DOMAIN-KEYWORD"),
        IpRange("IP-CIDR"),
    }

    enum class Action(val keyword: String) {
        Proxy("PROXY"),
        Direct("DIRECT"),
        Reject("REJECT"),
    }

    /** What a line does, for the readable list. */
    enum class Kind { Suffix, Exact, Keyword, IpRange, GeoIp, Regex, Other }

    data class LineView(
        /** 1-based line number in the text. */
        val lineNumber: Int,
        val raw: String,
        val kind: Kind,
        val target: String,
        /** PROXY / DIRECT / REJECT, or null when the line names a node or has no policy. */
        val action: Action?,
        /** Node name for NODE: policies. */
        val nodeLabel: String?,
    )

    data class Template(val id: String, val lines: List<String>)

    private val ipv4 = Regex("""^(\d{1,3})(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val ipv6 = Regex("""^[0-9a-fA-F:]+:[0-9a-fA-F:]*(/\d{1,3})?$""")

    /**
     * Turns what a user pastes into a rule target: a link becomes its host,
     * "www." is dropped for whole-site rules, a bare IP gets a /32 or /128.
     */
    fun normalizeTarget(input: String, match: Match): String {
        val trimmed = input.trim()
        if (match == Match.Keyword) return trimmed.lowercase()
        var value = trimmed.substringAfter("://")
        // "1.2.3.0/24" is a range, not a path: keep it whole.
        if (!looksLikeIp(value)) {
            value = value.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
            if (!looksLikeIp(value)) value = value.substringBefore(':')
        }
        value = value.trim().trimEnd('.').lowercase()
        if (match == Match.Suffix) value = value.removePrefix("*.").removePrefix(".").removePrefix("www.")
        if (match == Match.IpRange && !value.contains('/')) {
            value += if (value.contains(':')) "/128" else "/32"
        }
        return value
    }

    fun looksLikeIp(value: String): Boolean {
        val v = value.trim()
        return ipv4.matches(v) || (v.count { it == ':' } >= 2 && ipv6.matches(v))
    }

    /** The match a fresh input most likely means. */
    fun detectMatch(input: String, current: Match): Match {
        val rest = input.trim().substringAfter("://")
        return when {
            looksLikeIp(rest) || looksLikeIp(rest.substringBefore('/')) -> Match.IpRange
            current == Match.IpRange -> Match.Suffix
            else -> current
        }
    }

    /** One rule line, or null when the target is unusable. */
    fun buildLine(input: String, match: Match, action: Action?, nodeToken: String? = null): String? {
        val target = normalizeTarget(input, match)
        if (target.isBlank() || target.contains(',') || target.contains(' ')) return null
        if (match == Match.IpRange && !looksLikeIp(target)) return null
        if (match == Match.Suffix || match == Match.Exact) {
            if (!target.contains('.') || target.startsWith('.') || target.endsWith('.')) return null
        }
        val policy = nodeToken ?: action?.keyword ?: return null
        val kind = if (match == Match.IpRange && target.contains(':')) "IP-CIDR6" else match.keyword
        val noResolve = if (match == Match.IpRange) ",no-resolve" else ""
        return "$kind,$target,$policy$noResolve"
    }

    /** Readable view of every rule line (comments and blank lines skipped). */
    fun describe(text: String): List<LineView> =
        text.lines().mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) return@mapIndexedNotNull null
            val parts = line.split(',').map { it.trim() }
            val kind = when (parts[0].uppercase()) {
                "DOMAIN-SUFFIX" -> Kind.Suffix
                "DOMAIN" -> Kind.Exact
                "DOMAIN-KEYWORD" -> Kind.Keyword
                "IP-CIDR", "IP-CIDR6" -> Kind.IpRange
                "GEOIP" -> Kind.GeoIp
                "URL-REGEX", "DOMAIN-REGEX" -> Kind.Regex
                else -> Kind.Other
            }
            val policy = parts.getOrNull(2)?.takeUnless { it.equals("no-resolve", ignoreCase = true) }.orEmpty()
            val action = Action.entries.firstOrNull { it.keyword.equals(policy, ignoreCase = true) }
            val nodeLabel = policy.takeIf { it.startsWith("NODE:", ignoreCase = true) }
                ?.substringAfter(':')?.split(':', limit = 3)?.getOrNull(2)?.replace('_', ' ')
            LineView(
                lineNumber = index + 1,
                raw = line,
                kind = kind,
                target = if (kind == Kind.Other) line else parts.getOrNull(1).orEmpty(),
                action = action ?: if (policy.isBlank() && nodeLabel == null) Action.Proxy else null,
                nodeLabel = nodeLabel,
            )
        }

    /** Appends lines that aren't already present (case-insensitive). */
    fun appendLines(text: String, lines: List<String>): Pair<String, Int> {
        val existing = text.lines().map { it.trim().lowercase() }.toSet()
        val added = lines.filter { it.trim().lowercase() !in existing }.distinct()
        if (added.isEmpty()) return text to 0
        val base = text.trimEnd('\n', ' ')
        val joined = if (base.isBlank()) added.joinToString("\n") else base + "\n" + added.joinToString("\n")
        return joined to added.size
    }

    /** Removes the given 1-based line. */
    fun removeLine(text: String, lineNumber: Int): String =
        text.lines().filterIndexed { index, _ -> index + 1 != lineNumber }.joinToString("\n")

    /** A default name from the first target, e.g. "openai.com 等 3 条". */
    fun suggestName(text: String): String? {
        val views = describe(text)
        val first = views.firstOrNull { it.kind != Kind.Other }?.target ?: return null
        return if (views.size == 1) first else tr("$first 等 ${views.size} 条", "$first and ${views.size - 1} more", "$first и ещё ${views.size - 1}")
    }

    private fun suffix(vararg domains: String, policy: String = "PROXY") = domains.map { "DOMAIN-SUFFIX,$it,$policy" }

    val templates = listOf(
        Template(
            "ai",
            suffix(
                "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "sora.com",
                "anthropic.com", "claude.ai", "claude.com",
                "gemini.google.com", "aistudio.google.com", "notebooklm.google.com", "generativelanguage.googleapis.com",
                "perplexity.ai", "x.ai", "grok.com", "poe.com", "character.ai", "mistral.ai",
                "huggingface.co", "midjourney.com", "copilot.microsoft.com",
            ),
        ),
        Template(
            "google",
            suffix(
                "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com",
                "youtube.com", "ytimg.com", "googlevideo.com", "youtu.be", "gmail.com",
            ),
        ),
        Template(
            "telegram",
            suffix("telegram.org", "t.me", "telegram.me", "telesco.pe", "tdesktop.com") + listOf(
                "IP-CIDR,91.108.4.0/22,PROXY,no-resolve",
                "IP-CIDR,91.108.8.0/22,PROXY,no-resolve",
                "IP-CIDR,91.108.12.0/22,PROXY,no-resolve",
                "IP-CIDR,91.108.16.0/22,PROXY,no-resolve",
                "IP-CIDR,91.108.56.0/22,PROXY,no-resolve",
                "IP-CIDR,149.154.160.0/20,PROXY,no-resolve",
                "IP-CIDR6,2001:67c:4e8::/48,PROXY,no-resolve",
                "IP-CIDR6,2001:b28:f23d::/48,PROXY,no-resolve",
                "IP-CIDR6,2001:b28:f23f::/48,PROXY,no-resolve",
            ),
        ),
        Template("github", suffix("github.com", "githubusercontent.com", "githubassets.com", "github.io", "githubcopilot.com")),
        Template("apple", suffix("apple.com", "icloud.com", "icloud-content.com", "mzstatic.com", "cdn-apple.com", "apple-cloudkit.com", policy = "DIRECT")),
        Template("cn", listOf("GEOIP,CN,DIRECT,no-resolve")),
    )
}

/** Well-known rule lists for the URL page. Lists without policies use [policy]. */
data class RuleUrlPreset(val id: String, val name: String, val url: String, val policy: RuleTargetPolicy)

object RuleUrlPresets {
    private fun bm(folder: String) =
        "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Surge/$folder/$folder.list"

    val all = listOf(
        RuleUrlPreset("openai", "OpenAI / ChatGPT", bm("OpenAI"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("claude", "Claude", bm("Claude"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("gemini", "Gemini", bm("Gemini"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("google", "Google", bm("Google"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("youtube", "YouTube", bm("YouTube"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("telegram", "Telegram", bm("Telegram"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("twitter", "X / Twitter", bm("Twitter"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("github", "GitHub", bm("GitHub"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("tiktok", "TikTok", bm("TikTok"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("netflix", "Netflix", bm("Netflix"), RuleTargetPolicy.Proxy),
        RuleUrlPreset("apple", "Apple", bm("Apple"), RuleTargetPolicy.Direct),
        RuleUrlPreset("ads", "广告拦截（精简）", bm("AdvertisingLite"), RuleTargetPolicy.Block),
    )

    /**
     * Cleans a pasted rule link: trims it and turns GitHub page links
     * (github.com/u/r/blob/branch/path) into raw file links.
     */
    fun normalizeUrl(raw: String): String {
        val url = raw.trim()
        val github = Regex("""^https?://github\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$""").find(url)
        if (github != null) {
            val (user, repo, rest) = github.destructured
            return "https://raw.githubusercontent.com/$user/$repo/$rest"
        }
        return url
    }
}
