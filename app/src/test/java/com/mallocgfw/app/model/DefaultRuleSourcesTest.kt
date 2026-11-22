package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRuleSourcesTest {
    private val defaults = RuleSourceManager.defaultSources

    @Test
    fun oneChinaListOnAndOptionalOnesOff() {
        assertEquals(listOf("rule_default_shadowrocket"), defaults.filter { it.enabled }.map { it.id })
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
        assertEquals(1, defaults.count { it.url.contains("China_Domain") })
        defaults.forEach {
            assertTrue(it.systemDefault)
            assertNotNull("${it.id} needs a hint", RuleSourceManager.builtInHint(it.id))
            assertTrue(it.url.startsWith("https://raw.githubusercontent.com/blackmatrix7/"))
        }
    }

    @Test
    fun oldSurgeCopyIsMergedAway() {
        val oldSurge = RuleSourceItem(
            id = "rule_default_surge", name = "Surge 默认规则", url = "https://example.com/China_Domain.list",
            type = RuleSourceType.Surge, policy = RuleTargetPolicy.Direct, enabled = true, systemDefault = true,
            updatedAt = "", status = RuleSourceStatus.Ready, totalRules = 3689, convertedRules = 3689, skippedRules = 0,
        )
        val oldShadowrocket = defaults.single { it.id == "rule_default_shadowrocket" }
            .copy(name = "Shadowrocket 默认规则", enabled = false, convertedRules = 3689, status = RuleSourceStatus.Ready)
        val custom = RuleSourceManager.createDraft("Mine", "https://example.com/a.list", RuleSourceType.Auto)
        val merged = RuleSourceManager.ensureDefaults(listOf(custom, oldSurge, oldShadowrocket))

        assertEquals(defaults.map { it.id } + custom.id, merged.map { it.id })
        val china = merged.single { it.id == "rule_default_shadowrocket" }
        assertTrue("only the Surge copy was on, so China stays direct", china.enabled)
        assertEquals("国内网站直连", china.name)
        assertEquals(3689, china.convertedRules)
        listOf("rule_default_ads", "rule_default_httpdns", "rule_default_steam", "rule_default_tracker").forEach { id ->
            assertFalse("$id should start off", merged.single { it.id == id }.enabled)
        }
    }

    @Test
    fun chinaListStaysOffWhenBothWereOff() {
        val oldShadowrocket = defaults.single { it.id == "rule_default_shadowrocket" }.copy(enabled = false)
        val merged = RuleSourceManager.ensureDefaults(listOf(oldShadowrocket))
        assertFalse(merged.single { it.id == "rule_default_shadowrocket" }.enabled)
    }

    @Test
    fun optionalSourcesUseTheRightAction() {
        val policy = defaults.associate { it.id to it.policy }
        assertEquals(RuleTargetPolicy.Block, policy["rule_default_ads"])
        assertEquals(RuleTargetPolicy.Block, policy["rule_default_httpdns"])
        assertEquals(RuleTargetPolicy.Direct, policy["rule_default_steam"])
        assertEquals(RuleTargetPolicy.Direct, policy["rule_default_tracker"])
    }
}
