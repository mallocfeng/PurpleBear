package com.mallocgfw.app.core

import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingRouteSelection
import com.mallocgfw.app.model.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateOpsTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String, group: String, name: String = id, host: String = "$id.example.com"): ServerNode =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$host:443?security=tls&sni=$host#$id")
            .copy(id = id, groupId = group, name = name)

    private val local = ServerGroup("local", "Local", ServerGroupType.Local, updatedAt = "刚刚")
    private val sub = ServerGroup("sub1", "Airport", ServerGroupType.Subscription, sourceUrl = "https://sub.example.com/x", updatedAt = "刚刚")

    private fun state(vararg nodes: ServerNode, selected: String = nodes.first().id) = PersistedAppState(
        serverGroups = listOf(local, sub),
        servers = nodes.toList(),
        subscriptions = listOf(SubscriptionItem("sub1", "Airport", "Subscription", 2, "刚刚", nextSync = "手动", status = "已同步", autoUpdate = true, sourceUrl = sub.sourceUrl)),
        ruleSources = emptyList(),
        recentImports = emptyList(),
        protectedApps = emptyList(),
        selectedServerId = selected,
        proxyMode = ProxyMode.Smart,
        settings = AppStateStore.defaultSettings(),
    )

    @Test
    fun preProxyMustPointAtAnotherRunnableNode() {
        val s = state(node("a", "local"), node("b", "local"))
        assertNotNull(StateOps.setPreProxy(s, "a", "a").error)
        assertNotNull(StateOps.setPreProxy(s, "a", "missing").error)
        assertNotNull(StateOps.setPreProxy(s, "missing", "b").error)
        val ok = StateOps.setPreProxy(s, "a", "b")
        assertNull(ok.error)
        assertEquals("b", ok.state.servers.first { it.id == "a" }.preProxyNodeId)
        val cleared = StateOps.setPreProxy(ok.state, "a", "")
        assertEquals("", cleared.state.servers.first { it.id == "a" }.preProxyNodeId)
    }

    @Test
    fun removingANodeDropsLinksAndMediaRoutesToIt() {
        val s = state(node("a", "local").copy(preProxyNodeId = "b", fallbackNodeId = "b"), node("b", "local"), selected = "b")
            .let { it.copy(settings = it.settings.copy(streamingSelections = listOf(StreamingRouteSelection("netflix", "b")))) }
        val next = StateOps.removeNode(s, "b")!!
        val a = next.servers.single()
        assertEquals("", a.preProxyNodeId)
        assertEquals("", a.fallbackNodeId)
        assertEquals("a", next.selectedServerId)
        assertTrue(next.settings.streamingSelections.isEmpty())
        assertNull(StateOps.removeNode(s, "missing"))
    }

    @Test
    fun onlySubscriptionGroupsCanBeRemoved() {
        val s = state(node("a", "local"), node("s1", "sub1"), node("s2", "sub1"), selected = "s1")
        assertNull(StateOps.removeSubscriptionGroup(s, "local"))
        val next = StateOps.removeSubscriptionGroup(s, "sub1")!!
        assertEquals(listOf("a"), next.servers.map { it.id })
        assertTrue(next.subscriptions.isEmpty())
        assertEquals("a", next.selectedServerId)
    }

    @Test
    fun refreshedSubscriptionKeepsTheSelectionByNameAndAsksToReconnect() {
        val s = state(node("a", "local"), node("s1", "sub1", name = "HK 01"), node("s2", "sub1", name = "JP 01"), selected = "s1")
        val preview = ImportPreview(
            input = "https://sub.example.com/x",
            group = sub,
            nodes = listOf(node("n1", "sub1", name = "HK 01", host = "new-hk.example.com"), node("n2", "sub1", name = "US 01")),
            subscription = s.subscriptions.single(),
            summary = "",
        )
        val outcome = StateOps.applyImport(s, preview, connectedServerId = "s1")
        assertEquals(listOf("a", "n1", "n2"), outcome.state.servers.map { it.id })
        assertEquals("n1", outcome.state.selectedServerId)
        assertEquals("n1", outcome.reconnectServer?.id)
        assertEquals(2, outcome.state.subscriptions.single().nodes)
        val notConnected = StateOps.applyImport(s, preview, connectedServerId = null)
        assertNull(notConnected.reconnectServer)
    }

    @Test
    fun localImportGivesClashingIdsAFreshOne() {
        val s = state(node("local_1", "local", host = "old.example.com"))
        val preview = ImportPreview(
            input = "vless://…",
            group = local,
            nodes = listOf(node("local_1", "local", name = "new", host = "new.example.com")),
            summary = "",
        )
        val next = StateOps.applyImport(s, preview, null).state
        assertEquals(2, next.servers.size)
        assertEquals(2, next.servers.map { it.id }.toSet().size)
    }

    @Test
    fun editorSaveKeepsLinksAndStatsOfTheEditedNode() {
        val s = state(node("a", "local").copy(preProxyNodeId = "b", latencyMs = 42, favorite = true), node("b", "local"))
        val edited = node("x", "local", name = "renamed", host = "new.example.com")
        val next = StateOps.saveLocalNode(s, edited, editingId = "a", editedDescription = "edited")
        val a = next.servers.first { it.id == "a" }
        assertEquals("renamed", a.name)
        assertEquals("b", a.preProxyNodeId)
        assertEquals(42, a.latencyMs)
        assertTrue(a.favorite)
        assertEquals("a", next.selectedServerId)
        val added = StateOps.saveLocalNode(s, edited, editingId = null, editedDescription = "")
        assertEquals("x", added.servers.first().id)
        assertEquals("x", added.selectedServerId)
    }

    @Test
    fun customDnsNeedsAValueAndLanguageCarriesTheDefaultRegion() {
        val s = state(node("a", "local"))
        assertNull(StateOps.setDns(s, com.mallocgfw.app.model.AppDnsMode.Custom, "  "))
        assertEquals("8.8.8.8", StateOps.setDns(s, com.mallocgfw.app.model.AppDnsMode.Custom, " 8.8.8.8 ")!!.settings.customDnsValue)
        val zh = s.copy(settings = s.settings.copy(language = com.mallocgfw.app.model.AppLanguage.Chinese, geoRoutingRegion = com.mallocgfw.app.model.AppGeoRoutingRegion.China))
        val ru = StateOps.setLanguage(zh, com.mallocgfw.app.model.AppLanguage.Russian)
        assertEquals(com.mallocgfw.app.model.AppGeoRoutingRegion.Russia, ru.settings.geoRoutingRegion)
        val custom = zh.copy(settings = zh.settings.copy(geoRoutingRegion = com.mallocgfw.app.model.AppGeoRoutingRegion.English))
        assertEquals(com.mallocgfw.app.model.AppGeoRoutingRegion.English, StateOps.setLanguage(custom, com.mallocgfw.app.model.AppLanguage.Russian).settings.geoRoutingRegion)
        assertEquals(5, StateOps.setHeartbeat(s, 7).settings.heartbeatIntervalMinutes)
    }

    @Test
    fun builtInRuleSourcesAreResetAndOthersRemoved() {
        val builtIn = com.mallocgfw.app.model.RuleSourceManager.defaultSources.first().copy(convertedRules = 10, enabled = false)
        val custom = com.mallocgfw.app.model.RuleSourceManager.createTextDraft("mine", "DOMAIN,a.example,PROXY", com.mallocgfw.app.model.RuleSourceType.Auto)
        val s = state(node("a", "local")).copy(ruleSources = listOf(builtIn, custom))
        val reset = StateOps.removeRuleSource(s, builtIn.id)!!.ruleSources.first { it.id == builtIn.id }
        assertTrue(reset.enabled)
        assertEquals(0, reset.convertedRules)
        assertEquals(listOf(builtIn.id), StateOps.removeRuleSource(s, custom.id)!!.ruleSources.map { it.id })
        val disabled = StateOps.setRuleSourceEnabled(s, custom.id, false)!!.ruleSources.first { it.id == custom.id }
        assertEquals(com.mallocgfw.app.model.RuleSourceStatus.Disabled, disabled.status)
        assertNull(StateOps.setRuleSourceEnabled(s, "missing", true))
    }

    @Test
    fun tailscaleSubnetsAreCanonicalAndTurningOffClearsTheExitNode() {
        val s = state(node("a", "local")).let { it.copy(settings = it.settings.copy(tailscaleEnabled = true, tailscaleExitNodeId = "n1")) }
        val routes = StateOps.setTailscaleSubnets(s, listOf("192.168.10.7/24", "192.168.10.0/24", "8.8.8.0/24")).settings.tailscaleSubnetRoutes
        assertEquals(listOf("192.168.10.0/24"), routes)
        val off = StateOps.setTailscaleEnabled(s, false).settings
        assertEquals("", off.tailscaleExitNodeId)
    }
}
