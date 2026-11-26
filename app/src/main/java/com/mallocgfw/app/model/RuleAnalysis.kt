package com.mallocgfw.app.model

import java.math.BigInteger
import java.net.InetAddress

/**
 * Rule analysis: which of the user's routing rules really decide where traffic
 * goes. Xray takes the first rule that matches, in the order
 * [com.mallocgfw.app.xray.XrayConfigFactory] writes them (priority, highest
 * first, then list order), so a rule lower down only gets what the rules above
 * it left. [RuleAnalyzer] replays that order and works out, for each entry,
 * how much of it is taken by a higher rule that sends the traffic elsewhere.
 */
enum class RuleLayer {
    /** The top-priority rules: before everything else, global mode included. */
    Top,
    /** Per-app nodes: an app's whole traffic, before any domain rule. */
    PerApp,
    /** The user's own rule sources (text and URL). */
    Custom,
    /** Media routing: a service's domains to a chosen node. */
    Streaming,
    /** Built-in rule sources (home sites, Steam, trackers, block lists). */
    Default,
}

data class RouteTarget(val kind: Kind, val nodeName: String? = null) {
    enum class Kind { Direct, Proxy, Node, Block, Tailscale }

    companion object {
        val Direct = RouteTarget(Kind.Direct)
        val Block = RouteTarget(Kind.Block)
    }
}

enum class InactiveReason {
    /** Switched off in the rules list. */
    Disabled,
    /** Global mode skips rule sources and media routing. */
    GlobalMode,
    /** The list was never downloaded (or failed to compile). */
    NotDownloaded,
    /** The chosen node no longer exists or can't run. */
    NodeMissing,
    /** Per-app nodes only apply in per-app mode. */
    NotPerAppMode,
    /** Outside China the home-sites list comes from the bundled Geo data. */
    ReplacedByGeoData,
    /** Media routing is switched off. */
    StreamingOff,
}

data class RuleAnalysisInput(
    val id: String,
    val layer: RuleLayer,
    val name: String,
    val priority: Int,
    val segments: List<Segment> = emptyList(),
    val inactiveReason: InactiveReason? = null,
    /** Per-app entries: the apps (display names) the node applies to. */
    val apps: List<String> = emptyList(),
    /** NODE: policies whose node is gone; those lines use the source's policy. */
    val missingNodes: List<String> = emptyList(),
) {
    data class Segment(val target: RouteTarget, val rule: XrayRoutingRule)
}

enum class RuleEntryStatus {
    /** Every entry it holds decides traffic. */
    Effective,
    /** Some of its entries are taken first by a higher rule going elsewhere. */
    Partial,
    /** All of its entries are taken first by higher rules going elsewhere. */
    Overridden,
    /** All of its entries are already covered by higher rules going the same way. */
    Redundant,
    /** It isn't in the running rules at all; see [RuleAnalysisInput.inactiveReason]. */
    Inactive,
}

/** Entries of a lower rule that a higher one takes first, sending them elsewhere. */
data class RuleConflict(
    val winnerId: String,
    val winnerName: String,
    val winnerLayer: RuleLayer,
    val winnerTarget: RouteTarget,
    val loserTarget: RouteTarget,
    val count: Int,
    val examples: List<String>,
)

data class RuleEntryResult(
    val input: RuleAnalysisInput,
    /** 1-based place among the active entries; 0 when inactive. */
    val order: Int,
    val status: RuleEntryStatus,
    /** Domains, IP ranges (or apps) it holds. */
    val matcherCount: Int,
    /** Of those, taken first by a higher rule going elsewhere. */
    val overriddenCount: Int,
    /** Of those, already covered by a higher rule going the same way. */
    val redundantCount: Int,
    /** Entries that decide traffic, per target. */
    val effectiveTargets: List<Pair<RouteTarget, Int>>,
    val lostTo: List<RuleConflict>,
    /** Regex entries: whether one is covered can't be worked out. */
    val unanalyzedCount: Int,
) {
    val effectiveCount: Int get() = matcherCount - overriddenCount - redundantCount
}

object RuleAnalyzer {
    private const val MAX_EXAMPLES = 3

    /**
     * Analyzes [inputs], given in the order the VPN joins them (per-app,
     * media, rule sources). Active results come first in rule order, then the
     * inactive ones in input order.
     */
    fun analyze(inputs: List<RuleAnalysisInput>): List<RuleEntryResult> {
        val active = inputs.withIndex()
            .filter { it.value.inactiveReason == null }
            .sortedWith(compareByDescending<IndexedValue<RuleAnalysisInput>> { it.value.priority }.thenBy { it.index })
            .map { it.value }
        val index = ClaimIndex()
        val results = active.mapIndexed { position, input ->
            val result = analyzeEntry(input, position + 1, index)
            input.segments.forEach { segment -> index.claim(segment.rule, Claim(input, segment.target, position)) }
            result
        }
        val inactive = inputs.filter { it.inactiveReason != null }.map { input ->
            RuleEntryResult(
                input = input,
                order = 0,
                status = RuleEntryStatus.Inactive,
                matcherCount = input.segments.sumOf { it.rule.matcherCount() } + input.apps.size,
                overriddenCount = 0,
                redundantCount = 0,
                effectiveTargets = emptyList(),
                lostTo = emptyList(),
                unanalyzedCount = 0,
            )
        }
        return results + inactive
    }

    private fun analyzeEntry(input: RuleAnalysisInput, order: Int, index: ClaimIndex): RuleEntryResult {
        var total = input.apps.size
        var overridden = 0
        var redundant = 0
        var unanalyzed = 0
        val effective = linkedMapOf<RouteTarget, Int>()
        val conflicts = linkedMapOf<Pair<String, RouteTarget>, ConflictBuilder>()

        fun record(matcher: String, claim: Claim?, target: RouteTarget) {
            total += 1
            when {
                claim == null -> effective[target] = (effective[target] ?: 0) + 1
                claim.input.id == input.id -> effective[target] = (effective[target] ?: 0) + 1
                claim.target == target -> redundant += 1
                else -> {
                    overridden += 1
                    conflicts.getOrPut(claim.input.id to claim.target) { ConflictBuilder(claim, target) }.add(matcher)
                }
            }
        }

        if (input.apps.isNotEmpty()) {
            val target = input.segments.firstOrNull()?.target
            if (target != null) effective[target] = (effective[target] ?: 0) + input.apps.size
        }
        input.segments.forEach { segment ->
            val rule = segment.rule
            val target = segment.target
            rule.domainSuffixes.forEach { record(it, index.findForSuffix(it), target) }
            rule.fullDomains.forEach { record(it, index.findForFull(it), target) }
            rule.domainKeywords.forEach { record(it, index.findForKeyword(it), target) }
            rule.domainRegexes.forEach { _ ->
                total += 1
                unanalyzed += 1
                effective[target] = (effective[target] ?: 0) + 1
            }
            (rule.ipCidrs + rule.ipCidrs6).forEach { record(it, index.findForIp(it), target) }
        }

        val analyzable = total - unanalyzed
        val status = when {
            overridden > 0 && overridden + redundant >= analyzable -> RuleEntryStatus.Overridden
            overridden > 0 -> RuleEntryStatus.Partial
            redundant > 0 && redundant >= analyzable -> RuleEntryStatus.Redundant
            else -> RuleEntryStatus.Effective
        }
        return RuleEntryResult(
            input = input,
            order = order,
            status = status,
            matcherCount = total,
            overriddenCount = overridden,
            redundantCount = redundant,
            effectiveTargets = effective.entries.filter { it.value > 0 }.map { it.key to it.value },
            lostTo = conflicts.values.map { it.build() }.sortedByDescending { it.count },
            unanalyzedCount = unanalyzed,
        )
    }

    private fun XrayRoutingRule.matcherCount(): Int =
        domainSuffixes.size + fullDomains.size + domainKeywords.size + domainRegexes.size + ipCidrs.size + ipCidrs6.size

    /** [order]: the entry's place in the rules, so the higher of two claims can be told. */
    private class Claim(val input: RuleAnalysisInput, val target: RouteTarget, val order: Int)

    private class ConflictBuilder(val claim: Claim, val loserTarget: RouteTarget) {
        var count = 0
        val examples = mutableListOf<String>()

        fun add(matcher: String) {
            count += 1
            if (examples.size < MAX_EXAMPLES) examples += matcher
        }

        fun build() = RuleConflict(
            winnerId = claim.input.id,
            winnerName = claim.input.name,
            winnerLayer = claim.input.layer,
            winnerTarget = claim.target,
            loserTarget = loserTarget,
            count = count,
            examples = examples.toList(),
        )
    }

    /**
     * What the rules so far catch, first claim wins (as in Xray). Domain
     * entries are only checked against domain entries and IP ranges against
     * IP ranges: which IP a domain resolves to isn't known here.
     */
    private class ClaimIndex {
        private val suffixes = HashMap<String, Claim>()
        private val fulls = HashMap<String, Claim>()
        private val keywords = mutableListOf<Pair<String, Claim>>()
        private val regexes = mutableListOf<Pair<Regex, Claim>>()
        private val geoip = HashMap<String, Claim>()
        /** Prefix length → network (as a number) → claim, per address family. */
        private val v4 = HashMap<Int, HashMap<BigInteger, Claim>>()
        private val v6 = HashMap<Int, HashMap<BigInteger, Claim>>()

        fun claim(rule: XrayRoutingRule, claim: Claim) {
            rule.domainSuffixes.forEach { suffixes.putIfAbsent(it.normalizedDomain(), claim) }
            rule.fullDomains.forEach { fulls.putIfAbsent(it.normalizedDomain(), claim) }
            rule.domainKeywords.forEach { keyword -> keywords += keyword.lowercase() to claim }
            rule.domainRegexes.forEach { pattern ->
                runCatching { Regex(pattern) }.getOrNull()?.let { regexes += it to claim }
            }
            (rule.ipCidrs + rule.ipCidrs6).forEach { value ->
                if (value.startsWith("geoip:", ignoreCase = true)) {
                    geoip.putIfAbsent(value.lowercase(), claim)
                } else {
                    val cidr = Cidr.parse(value) ?: return@forEach
                    val table = if (cidr.v6) v6 else v4
                    table.getOrPut(cidr.prefix) { HashMap() }.putIfAbsent(cidr.network, claim)
                }
            }
        }

        /** A suffix rule covers the domain and all its subdomains. */
        fun findForSuffix(value: String): Claim? {
            val domain = value.normalizedDomain()
            return earliest(parentSuffixClaim(domain), keywordClaim(domain), regexClaim(domain))
        }

        fun findForFull(value: String): Claim? {
            val domain = value.normalizedDomain()
            return earliest(fulls[domain], parentSuffixClaim(domain), keywordClaim(domain), regexClaim(domain))
        }

        /** Any domain containing [value] also contains a shorter keyword inside it. */
        fun findForKeyword(value: String): Claim? {
            val keyword = value.lowercase()
            return keywords.firstOrNull { (higher, _) -> keyword.contains(higher) }?.second
        }

        fun findForIp(value: String): Claim? {
            if (value.startsWith("geoip:", ignoreCase = true)) return geoip[value.lowercase()]
            val cidr = Cidr.parse(value) ?: return null
            val table = if (cidr.v6) v6 else v4
            var found: Claim? = null
            // A wider range (shorter prefix) holding this one takes it first.
            for (prefix in 0..cidr.prefix) {
                val claims = table[prefix] ?: continue
                val claim = claims[cidr.maskedTo(prefix)] ?: continue
                found = earliest(found, claim)
            }
            return found
        }

        private fun parentSuffixClaim(domain: String): Claim? {
            var found: Claim? = null
            var current = domain
            while (true) {
                found = earliest(found, suffixes[current])
                val dot = current.indexOf('.')
                if (dot < 0) break
                current = current.substring(dot + 1)
            }
            return found
        }

        private fun keywordClaim(domain: String): Claim? =
            keywords.firstOrNull { (keyword, _) -> domain.contains(keyword) }?.second

        private fun regexClaim(domain: String): Claim? =
            regexes.firstOrNull { (regex, _) -> regex.containsMatchIn(domain) }?.second

        /** Of several claims, the one Xray meets first: the higher entry. */
        private fun earliest(vararg claims: Claim?): Claim? = claims.filterNotNull().minByOrNull { it.order }
    }

    private fun String.normalizedDomain(): String = trim().trimEnd('.').lowercase()

    private class Cidr(val v6: Boolean, val network: BigInteger, val prefix: Int) {
        private val bits get() = if (v6) 128 else 32

        fun maskedTo(length: Int): BigInteger = mask(network, length, bits)

        companion object {
            fun parse(value: String): Cidr? {
                val text = value.trim()
                val address = text.substringBefore('/')
                if (address.isEmpty() || !(address.contains(':') || address.all { it.isDigit() || it == '.' })) return null
                val bytes = runCatching { InetAddress.getByName(address).address }.getOrNull() ?: return null
                val v6 = bytes.size == 16
                val bits = if (v6) 128 else 32
                val prefix = text.substringAfter('/', "").toIntOrNull() ?: bits
                if (prefix !in 0..bits) return null
                return Cidr(v6, mask(BigInteger(1, bytes), prefix, bits), prefix)
            }

            private fun mask(value: BigInteger, length: Int, bits: Int): BigInteger {
                if (length <= 0) return BigInteger.ZERO
                val hostBits = bits - length
                return value.shiftRight(hostBits).shiftLeft(hostBits)
            }
        }
    }
}
