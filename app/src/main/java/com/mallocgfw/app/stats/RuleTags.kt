package com.mallocgfw.app.stats

import com.mallocgfw.app.model.tr
import org.json.JSONArray

/**
 * Names for the routing rules in the Xray config (`ruleTag`), so the
 * connection view can say which rule a connection hit: "kind" or
 * "kind|label", the label being the rule list, media service or node.
 */
object RuleTags {
    enum class Kind(val id: String) {
        PerApp("app"),
        Media("media"),
        Rule("rule"),
        Dns("dns"),
        Tailscale("tailscale"),
        Quic("quic"),
        Play("play"),
        Private("private"),
        HomeSites("homesite"),
        HomeIp("homeip"),
        Final("final"),
        Internal("internal"),
    }

    private const val MAX_LABEL = 64

    fun tag(kind: Kind, label: String? = null): String {
        val clean = label?.replace('|', '/')?.replace('~', '-')?.trim()?.take(MAX_LABEL)
        return if (clean.isNullOrEmpty()) kind.id else "${kind.id}|$clean"
    }

    fun parse(tag: String?): Pair<Kind, String?>? {
        if (tag.isNullOrBlank()) return null
        val base = tag.replace(UNIQUE_SUFFIX, "")
        val id = base.substringBefore('|')
        val kind = Kind.entries.firstOrNull { it.id == id } ?: return null
        return kind to base.substringAfter('|', "").takeIf(String::isNotBlank)
    }

    /**
     * Xray refuses a config with two rules of the same tag, and one list
     * makes several rules (domains, IPs, apps): later ones get "~2", "~3" ….
     */
    fun makeUnique(rules: JSONArray) {
        val seen = HashMap<String, Int>()
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val tag = rule.optString("ruleTag").takeIf(String::isNotEmpty) ?: continue
            val count = (seen[tag] ?: 0) + 1
            seen[tag] = count
            if (count > 1) rule.put("ruleTag", "$tag~$count")
        }
    }

    private val UNIQUE_SUFFIX = Regex("~\\d+$")

    /** What the connection view shows for a rule tag; null tag: the fallback. */
    fun describe(tag: String?): String {
        val (kind, label) = parse(tag) ?: return if (tag.isNullOrBlank()) {
            tr("默认规则", "Fallback", "По умолчанию")
        } else {
            tag
        }
        val name = when (kind) {
            Kind.PerApp -> tr("应用独立节点", "Per-app node", "Узел приложения")
            Kind.Media -> tr("流媒体", "Media", "Медиа")
            Kind.Rule -> tr("规则", "Rule", "Правило")
            Kind.Dns -> "DNS"
            Kind.Tailscale -> "Tailscale"
            Kind.Quic -> tr("屏蔽 QUIC", "QUIC blocked", "QUIC заблокирован")
            Kind.Play -> tr("Google Play 下载", "Google Play downloads", "Загрузки Google Play")
            Kind.Private -> tr("局域网地址", "Private network", "Локальная сеть")
            Kind.HomeSites -> tr("国内网站", "Home sites", "Местные сайты")
            Kind.HomeIp -> tr("国内 IP", "Home IPs", "Местные IP")
            Kind.Final -> tr("默认规则", "Fallback", "По умолчанию")
            Kind.Internal -> tr("内部", "Internal", "Внутреннее")
        }
        return if (label == null) name else tr("$name：$label", "$name: $label", "$name: $label")
    }
}
