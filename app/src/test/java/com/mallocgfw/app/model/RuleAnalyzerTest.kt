package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAnalyzerTest {
    private val proxy = RouteTarget(RouteTarget.Kind.Proxy, "Main")
    private val japan = RouteTarget(RouteTarget.Kind.Node, "Japan")

    private fun rule(
        suffixes: List<String> = emptyList(),
        fulls: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
        regexes: List<String> = emptyList(),
        ips: List<String> = emptyList(),
        ips6: List<String> = emptyList(),
    ) = XrayRoutingRule(
        target = RuleTargetPolicy.Proxy,
        domainSuffixes = suffixes,
        fullDomains = fulls,
        domainKeywords = keywords,
        domainRegexes = regexes,
        ipCidrs = ips,
        ipCidrs6 = ips6,
    )

    private fun input(
        id: String,
        layer: RuleLayer,
        priority: Int,
        target: RouteTarget,
        rule: XrayRoutingRule,
        reason: InactiveReason? = null,
    ) = RuleAnalysisInput(
        id = id,
        layer = layer,
        name = id,
        priority = priority,
        segments = listOf(RuleAnalysisInput.Segment(target, rule)),
        inactiveReason = reason,
    )

    @Test
    fun customRuleOutranksStreamingWhateverTheInputOrder() {
        val streaming = input("netflix", RuleLayer.Streaming, 1_000, japan, rule(suffixes = listOf("netflix.com", "nflxvideo.net")))
        val custom = input("mine", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("netflix.com")))

        val results = RuleAnalyzer.analyze(listOf(streaming, custom))

        assertEquals(listOf("mine", "netflix"), results.map { it.input.id })
        val netflix = results.single { it.input.id == "netflix" }
        assertEquals(2, netflix.order)
        assertEquals(RuleEntryStatus.Partial, netflix.status)
        assertEquals(1, netflix.overriddenCount)
        val conflict = netflix.lostTo.single()
        assertEquals("mine", conflict.winnerId)
        assertEquals(RouteTarget.Direct, conflict.winnerTarget)
        assertEquals(listOf("netflix.com"), conflict.examples)
        assertEquals(listOf(japan to 1), netflix.effectiveTargets)
    }

    @Test
    fun parentSuffixCoversSubdomainsAndFullDomains() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("example.com")))
        val low = input(
            "low",
            RuleLayer.Default,
            0,
            proxy,
            rule(suffixes = listOf("cdn.example.com"), fulls = listOf("www.example.com"), keywords = listOf("other")),
        )

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(2, result.overriddenCount)
        assertEquals(RuleEntryStatus.Partial, result.status)
    }

    @Test
    fun aSubdomainRuleDoesNotCoverItsParent() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("cdn.example.com")))
        val low = input("low", RuleLayer.Default, 0, proxy, rule(suffixes = listOf("example.com")))

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(0, result.overriddenCount)
        assertEquals(RuleEntryStatus.Effective, result.status)
    }

    @Test
    fun keywordCoversDomainsContainingIt() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Block, rule(keywords = listOf("ads")))
        val low = input("low", RuleLayer.Default, 0, RouteTarget.Direct, rule(suffixes = listOf("ads.example.cn"), keywords = listOf("myads")))

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(RuleEntryStatus.Overridden, result.status)
        assertEquals(2, result.overriddenCount)
    }

    @Test
    fun sameTargetOverlapIsRedundantNotAConflict() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("qq.com")))
        val low = input("low", RuleLayer.Default, 0, RouteTarget.Direct, rule(suffixes = listOf("qq.com", "weixin.qq.com")))

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(RuleEntryStatus.Redundant, result.status)
        assertEquals(2, result.redundantCount)
        assertTrue(result.lostTo.isEmpty())
    }

    @Test
    fun widerIpRangeCoversNarrowerOne() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(ips = listOf("10.0.0.0/8"), ips6 = listOf("2001:db8::/32")))
        val low = input(
            "low",
            RuleLayer.Default,
            0,
            proxy,
            rule(ips = listOf("10.1.2.0/24", "11.0.0.0/8"), ips6 = listOf("2001:db8:1::/48")),
        )

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(2, result.overriddenCount)
        assertEquals(listOf(proxy to 1), result.effectiveTargets)
    }

    @Test
    fun earlierEntryWinsOverLaterWithSamePriority() {
        val first = input("first", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("a.com")))
        val second = input("second", RuleLayer.Custom, 2_000, RouteTarget.Block, rule(suffixes = listOf("a.com")))
        val third = input("third", RuleLayer.Custom, 2_000, proxy, rule(fulls = listOf("x.a.com")))

        val results = RuleAnalyzer.analyze(listOf(first, second, third))

        assertEquals("first", results.single { it.input.id == "second" }.lostTo.single().winnerId)
        assertEquals("first", results.single { it.input.id == "third" }.lostTo.single().winnerId)
    }

    @Test
    fun inactiveEntriesTakeNothingAndComeLast() {
        val off = input("off", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("a.com")), reason = InactiveReason.Disabled)
        val on = input("on", RuleLayer.Default, 0, proxy, rule(suffixes = listOf("a.com")))

        val results = RuleAnalyzer.analyze(listOf(off, on))

        assertEquals(listOf("on", "off"), results.map { it.input.id })
        assertEquals(RuleEntryStatus.Effective, results[0].status)
        assertEquals(1, results[0].order)
        assertEquals(RuleEntryStatus.Inactive, results[1].status)
        assertEquals(0, results[1].order)
    }

    @Test
    fun perAppEntryCountsItsAppsAndComesFirst() {
        val app = RuleAnalysisInput(
            id = "app_jp",
            layer = RuleLayer.PerApp,
            name = "Japan",
            priority = PerAppRouting.PRIORITY,
            segments = listOf(RuleAnalysisInput.Segment(japan, rule())),
            apps = listOf("Netflix", "Abema"),
        )
        val custom = input("mine", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("a.com")))

        val results = RuleAnalyzer.analyze(listOf(custom, app))

        assertEquals("app_jp", results.first().input.id)
        assertEquals(2, results.first().matcherCount)
        assertEquals(listOf(japan to 2), results.first().effectiveTargets)
    }

    @Test
    fun regexEntriesAreCountedButNotJudged() {
        val high = input("high", RuleLayer.Custom, 2_000, RouteTarget.Direct, rule(suffixes = listOf("a.com")))
        val low = input("low", RuleLayer.Default, 0, proxy, rule(regexes = listOf("^.*\\.a\\.com$")))

        val result = RuleAnalyzer.analyze(listOf(high, low)).single { it.input.id == "low" }

        assertEquals(1, result.unanalyzedCount)
        assertEquals(RuleEntryStatus.Effective, result.status)
    }

    @Test
    fun topPriorityRuleOutranksEverything() {
        val top = input("top", RuleLayer.Top, RuleSourceManager.TOP_RULE_PRIORITY, RouteTarget.Direct, rule(suffixes = listOf("a.com")))
        val app = RuleAnalysisInput(
            id = "app_jp",
            layer = RuleLayer.PerApp,
            name = "Japan",
            priority = PerAppRouting.PRIORITY,
            segments = listOf(RuleAnalysisInput.Segment(japan, rule())),
            apps = listOf("Netflix"),
        )
        val custom = input("mine", RuleLayer.Custom, RuleSourceManager.USER_RULE_PRIORITY, proxy, rule(suffixes = listOf("a.com")))

        val results = RuleAnalyzer.analyze(listOf(app, custom, top))

        assertEquals(listOf("top", "app_jp", "mine"), results.map { it.input.id })
        assertEquals("top", results.single { it.input.id == "mine" }.lostTo.single().winnerId)
    }

    @Test
    fun topRuleSourceGetsTheTopPriority() {
        val draft = RuleSourceManager.createTopRuleDraft("DOMAIN-SUFFIX,a.com,DIRECT", RuleSourceType.Auto)
        assertTrue(RuleSourceManager.isTopRule(draft))
        assertEquals(RuleSourceManager.TOP_RULE_PRIORITY, RuleSourceManager.priorityOf(draft))
        assertTrue(RuleSourceManager.TOP_RULE_PRIORITY > PerAppRouting.PRIORITY)
        assertEquals(RuleSourceManager.USER_RULE_PRIORITY, RuleSourceManager.priorityOf(draft.copy(id = "rule_other")))
    }
}
