package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRuleSourcesTest {
    private val defaults = RuleSourceManager.defaultSources

    @Test
    fun directListsOnAndBlockListsOff() {
        assertEquals(
            listOf("rule_default_shadowrocket", "rule_default_steam", "rule_default_tracker"),
            defaults.filter { it.enabled }.map { it.id },
        )
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
        listOf("rule_default_ads", "rule_default_httpdns").forEach { id ->
            assertFalse("$id should start off", merged.single { it.id == id }.enabled)
        }
        listOf("rule_default_steam", "rule_default_tracker").forEach { id ->
            assertTrue("$id should start on", merged.single { it.id == id }.enabled)
        }
    }

    @Test
    fun homeListFollowsTheDirectRegion() {
        val home = RuleSourceManager.ensureDefaults(emptyList(), AppGeoRoutingRegion.Russia)
            .single { it.id == RuleSourceManager.HOME_SITES_ID }
        assertEquals("俄罗斯网站直连", home.name)
        assertTrue(RuleSourceManager.usesGeoData(home, AppGeoRoutingRegion.Russia))
        assertFalse(RuleSourceManager.usesGeoData(home, AppGeoRoutingRegion.China))
        assertEquals("国内网站直连", RuleSourceManager.ensureDefaults(listOf(home)).single { it.id == home.id }.name)
    }

    @Test
    fun englishKeepsChinaDirect() {
        assertEquals(AppGeoRoutingRegion.China, AppGeoRoutingRegion.defaultForLanguage(AppLanguage.English))
        assertEquals(AppGeoRoutingRegion.China, AppGeoRoutingRegion.defaultForLanguage(AppLanguage.Chinese))
        assertEquals(AppGeoRoutingRegion.Russia, AppGeoRoutingRegion.defaultForLanguage(AppLanguage.Russian))
    }

    @Test
    fun migrationTurnsOnSteamAndTrackerOnly() {
        val old = defaults.map { it.copy(enabled = false) }
        val migrated = RuleSourceManager.enableDirectDefaults(old).associate { it.id to it.enabled }
        assertEquals(
            mapOf(
                "rule_default_shadowrocket" to false,
                "rule_default_ads" to false,
                "rule_default_httpdns" to false,
                "rule_default_steam" to true,
                "rule_default_tracker" to true,
            ),
            migrated,
        )
        val downloadedButOff = defaults.single { it.id == "rule_default_steam" }
            .copy(enabled = false, status = RuleSourceStatus.Disabled, convertedRules = 17)
        assertEquals(RuleSourceStatus.Ready, RuleSourceManager.enableDirectDefaults(listOf(downloadedButOff)).single().status)
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

    @Test
    fun aBuiltInWhoseLinkChangedIsNoLongerShownAsReady() {
        val current = RuleSourceManager.ensureDefaults(emptyList()).first { it.sourceKind == RuleSourceKind.RemoteUrl && it.enabled }
        val ready = current.copy(status = RuleSourceStatus.Ready, convertedRules = 120, totalRules = 120)
        // Same definition: kept as it is.
        assertEquals(RuleSourceStatus.Ready, RuleSourceManager.ensureDefaults(listOf(ready)).single { it.id == ready.id }.status)
        // Saved with an older link: reset, so it downloads again on launch.
        val old = ready.copy(url = "https://old.example.com/list.txt")
        val reset = RuleSourceManager.ensureDefaults(listOf(old)).single { it.id == ready.id }
        assertEquals(current.url, reset.url)
        assertEquals(RuleSourceStatus.Idle, reset.status)
        assertEquals(0, reset.convertedRules)
    }
}
