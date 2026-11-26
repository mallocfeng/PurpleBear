package com.mallocgfw.app.mcp

import com.mallocgfw.app.core.RuleAnalysisReport
import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.InactiveReason
import com.mallocgfw.app.model.RouteTarget
import com.mallocgfw.app.model.RuleAnalysisInput
import com.mallocgfw.app.model.RuleAnalyzer
import com.mallocgfw.app.model.RuleLayer
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.XrayRoutingRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleAnalysisJsonTest {
    private fun rule(vararg suffixes: String) = XrayRoutingRule(
        target = RuleTargetPolicy.Proxy,
        domainSuffixes = suffixes.toList(),
        fullDomains = emptyList(),
        domainKeywords = emptyList(),
        ipCidrs = emptyList(),
        ipCidrs6 = emptyList(),
    )

    private fun report(): RuleAnalysisReport {
        val main = RouteTarget(RouteTarget.Kind.Proxy, "Main")
        val inputs = listOf(
            RuleAnalysisInput(
                id = RuleSourceManager.TOP_RULE_ID,
                layer = RuleLayer.Top,
                name = "最高优先级规则",
                priority = RuleSourceManager.TOP_RULE_PRIORITY,
                segments = listOf(RuleAnalysisInput.Segment(RouteTarget.Direct, rule("a.com"))),
            ),
            RuleAnalysisInput(
                id = "mine",
                layer = RuleLayer.Custom,
                name = "Mine",
                priority = RuleSourceManager.USER_RULE_PRIORITY,
                segments = listOf(RuleAnalysisInput.Segment(main, rule("a.com", "b.com"))),
            ),
            RuleAnalysisInput(
                id = "ads",
                layer = RuleLayer.Default,
                name = "Ads",
                priority = RuleSourceManager.SYSTEM_RULE_PRIORITY,
                inactiveReason = InactiveReason.Disabled,
            ),
        )
        return RuleAnalysisReport(
            globalMode = false,
            perAppMode = false,
            vpnAppNames = emptyList(),
            region = AppGeoRoutingRegion.China,
            mainNodeName = "Main",
            entries = RuleAnalyzer.analyze(inputs),
            quicBlocked = true,
            tailscaleEnabled = false,
            tailscaleSubnetCount = 0,
            homeSitesDirect = true,
            fallback = main,
            generatedAtMs = 0L,
        )
    }

    @Test
    fun listsActiveGroupsInOrderWithWhoOverridesThem() {
        val json = RuleAnalysisJson.build(report())

        val active = json.getJSONArray("active")
        assertEquals(2, active.length())
        val top = active.getJSONObject(0)
        assertEquals("top_priority", top.getString("layer"))
        assertEquals(1, top.getInt("order"))
        val mine = active.getJSONObject(1)
        assertEquals("partly_overridden", mine.getString("status"))
        assertEquals(1, mine.getInt("overridden"))
        val by = mine.getJSONArray("overridden_by").getJSONObject(0)
        assertEquals(RuleSourceManager.TOP_RULE_ID, by.getString("id"))
        assertEquals(1, by.getInt("order"))
        assertEquals("direct", by.getJSONObject("goes").getString("target"))
        assertEquals("a.com", by.getJSONArray("examples").getString(0))
    }

    @Test
    fun inactiveGroupsCarryAReason() {
        val json = RuleAnalysisJson.build(report())

        val inactive = json.getJSONArray("inactive").getJSONObject(0)
        assertEquals("ads", inactive.getString("id"))
        assertEquals("switched_off", inactive.getString("reason"))
        assertEquals(1, json.getJSONObject("summary").getInt("inactive"))
        assertEquals("top_priority", json.getJSONArray("matching_order").getString(0))
    }
}
