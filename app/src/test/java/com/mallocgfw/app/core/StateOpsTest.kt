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
    fun directMediaRouteSurvivesNodeChanges() {
        val s = state(node("a", "local"), node("b", "local"), selected = "a")
        val direct = StateOps.setStreamingRoute(s, "netflix", com.mallocgfw.app.model.StreamingMediaManager.DIRECT)
        val next = StateOps.removeNode(direct, "b")!!
        assertEquals(
            listOf(StreamingRouteSelection("netflix", com.mallocgfw.app.model.StreamingMediaManager.DIRECT)),
            next.settings.streamingSelections,
        )
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
        val ids = outcome.state.servers.map { it.id }
        assertEquals("a", ids[0])
        // New nodes of a refresh get fresh ids (see refreshCanNotHandARemovedNodesIdToAnotherServer).
        assertTrue(ids[1], ids[1].startsWith("n1_"))
        assertTrue(ids[2], ids[2].startsWith("n2_"))
        assertEquals(ids[1], outcome.state.selectedServerId)
        assertEquals(ids[1], outcome.reconnectServer?.id)
        assertEquals(2, outcome.state.subscriptions.single().nodes)
        val notConnected = StateOps.applyImport(s, preview, connectedServerId = null)
        assertNull(notConnected.reconnectServer)
    }

    @Test
    fun refreshCanNotHandARemovedNodesIdToAnotherServer() {
        // Refresh 1: A is in the subscription and an app is pinned to it.
        val a = node("sub1_1_x", "sub1", name = "HK 01", host = "a.example.com")
        val base = state(node("local", "local"), a, selected = "local")
        val app = com.mallocgfw.app.model.ProtectedApp("com.example.video", "Video", "Media", enabled = true, serverId = a.id)
        val s = base.copy(protectedApps = listOf(app))
        fun preview(vararg nodes: ServerNode) = ImportPreview(
            input = "https://sub.example.com/x", group = sub, nodes = nodes.toList(),
            subscription = s.subscriptions.single(), summary = "",
        )
        // Refresh 2: A is gone; the app's binding must go with it.
        val other = node("sub1_1_y", "sub1", name = "JP 01", host = "jp.example.com")
        val second = StateOps.applyImport(s, preview(other), connectedServerId = null).state
        assertTrue(second.servers.none { it.id == a.id })
        assertEquals("", second.protectedApps.single().serverId)
        // Refresh 3: another server shows up with A's name and position (so A's parsed id).
        val impostor = node(a.id, "sub1", name = "HK 01", host = "b.example.com")
        val third = StateOps.applyImport(second, preview(impostor), connectedServerId = null).state
        val newNode = third.servers.single { it.groupId == "sub1" && it.address == "b.example.com" }
        assertTrue("got the removed node's id", newNode.id != a.id)
        // Even a binding that survived from older versions doesn't follow the id.
        val stale = second.copy(protectedApps = listOf(app))
        val fromStale = StateOps.applyImport(stale, preview(impostor), connectedServerId = null).state
        assertEquals("", fromStale.protectedApps.single().serverId)
    }

    @Test
    fun removingNodesClearsAppsPinnedToThem() {
        val apps = listOf(
            com.mallocgfw.app.model.ProtectedApp("p.one", "One", "x", enabled = true, serverId = "b"),
            com.mallocgfw.app.model.ProtectedApp("p.two", "Two", "x", enabled = true, serverId = "a"),
            com.mallocgfw.app.model.ProtectedApp("p.three", "Three", "x", enabled = true, serverId = "s1"),
        )
        val s = state(node("a", "local"), node("b", "local"), node("s1", "sub1")).copy(protectedApps = apps)
        val afterNode = StateOps.removeNode(s, "b")!!
        assertEquals(listOf("", "a", "s1"), afterNode.protectedApps.map { it.serverId })
        val afterGroup = StateOps.removeSubscriptionGroup(afterNode, "sub1")!!
        assertEquals(listOf("", "a", ""), afterGroup.protectedApps.map { it.serverId })
        assertTrue(afterGroup.protectedApps.all { it.enabled })
    }

    @Test
    fun removalClearsTheLastConnectedNodeAndSelectsAUsableOne() {
        val hidden = node("h", "local").copy(hiddenUnsupported = true)
        val s = state(hidden, node("a", "local"), node("b", "local"), selected = "a")
            .let { it.copy(settings = it.settings.copy(lastConnectedServerId = "a")) }
        val next = StateOps.removeNode(s, "a")!!
        assertEquals("b", next.selectedServerId)
        assertEquals("", StateOps.normalizeNodeReferences(next).settings.lastConnectedServerId)
        val group = state(node("a", "local"), node("s1", "sub1"), selected = "s1")
            .let { it.copy(settings = it.settings.copy(lastConnectedServerId = "s1")) }
        val afterGroup = StateOps.normalizeNodeReferences(StateOps.removeSubscriptionGroup(group, "sub1")!!)
        assertEquals("a", afterGroup.selectedServerId)
        assertEquals("", afterGroup.settings.lastConnectedServerId)
    }

    @Test
    fun removingTheConnectedNodeSwitchesOrDisconnects() {
        val s = state(node("a", "local"), node("b", "local"), selected = "a")
        val effect = StateOps.removalEffect(s, StateOps.removeNode(s, "a")!!, connectedServerId = "a")
        assertEquals("b", (effect as StateOps.RemovalEffect.Reconnect).server.id)
        // Not connected: nothing to do.
        assertEquals(StateOps.RemovalEffect.None, StateOps.removalEffect(s, StateOps.removeNode(s, "a")!!, connectedServerId = null))
        // Last usable node gone: disconnect.
        val only = state(node("a", "local"))
        assertTrue(StateOps.removalEffect(only, StateOps.removeNode(only, "a")!!, "a") is StateOps.RemovalEffect.Disconnect)
        // Whole group with the connected node.
        val g = state(node("a", "local"), node("s1", "sub1"), selected = "s1")
        val afterGroup = StateOps.removeSubscriptionGroup(g, "sub1")!!
        assertEquals("a", (StateOps.removalEffect(g, afterGroup, "s1") as StateOps.RemovalEffect.Reconnect).server.id)
    }

    @Test
    fun removingANodeTheTunnelUsesReconnectsOnTheSameNode() {
        val base = state(node("a", "local").copy(preProxyNodeId = "p"), node("p", "local"), node("m", "local"), node("x", "local"), node("u", "local"), selected = "a")
        // Pre-proxy of the connected node.
        val r1 = StateOps.removalEffect(base, StateOps.removeNode(base, "p")!!, "a")
        assertEquals("a", (r1 as StateOps.RemovalEffect.Reconnect).server.id)
        // Media route node, only while media routing is on.
        val media = StateOps.setStreamingRoute(base, "netflix", "m").let { it.copy(settings = it.settings.copy(streamingRoutingEnabled = true)) }
        assertTrue(StateOps.removalEffect(media, StateOps.removeNode(media, "m")!!, "a") is StateOps.RemovalEffect.Reconnect)
        val mediaOff = media.copy(settings = media.settings.copy(streamingRoutingEnabled = false))
        assertEquals(StateOps.RemovalEffect.None, StateOps.removalEffect(mediaOff, StateOps.removeNode(mediaOff, "m")!!, "a"))
        // Per-app node, only in per-app mode.
        val apps = base.copy(
            proxyMode = ProxyMode.PerApp,
            protectedApps = listOf(com.mallocgfw.app.model.ProtectedApp("p.one", "One", "x", enabled = true, serverId = "x")),
        )
        assertTrue(StateOps.removalEffect(apps, StateOps.removeNode(apps, "x")!!, "a") is StateOps.RemovalEffect.Reconnect)
        // A node nothing uses: the connection stays.
        assertEquals(StateOps.RemovalEffect.None, StateOps.removalEffect(apps, StateOps.removeNode(apps, "u")!!, "a"))
    }

    @Test
    fun customDnsIsSavedInAscii() {
        val s = state(node("a", "local"))
        val next = StateOps.setDns(s, com.mallocgfw.app.model.AppDnsMode.Custom, " 8。8。8。8 ")!!
        assertEquals("8.8.8.8", next.settings.customDnsValue)
    }

    @Test
    fun removalNoticeListsEverySettingItCleared() {
        val s = state(
            node("a", "local", name = "HK 01").copy(preProxyNodeId = "p", fallbackNodeId = "p"),
            node("b", "local", name = "JP 01").copy(preProxyNodeId = "p"),
            node("p", "local", name = "Relay"),
            node("u", "local", name = "Unused"),
            selected = "a",
        ).let {
            StateOps.setStreamingRoute(it, "netflix", "p").copy(
                protectedApps = listOf(
                    com.mallocgfw.app.model.ProtectedApp("org.telegram", "Telegram", "x", enabled = true, serverId = "p"),
                    com.mallocgfw.app.model.ProtectedApp("p.other", "Other", "x", enabled = true, serverId = "a"),
                ),
            )
        }
        val after = StateOps.removeNode(s, "p")!!
        val notice = StateOps.removalNotice(s, after)!!
        listOf("Relay", "HK 01", "JP 01", "Telegram", "Netflix").forEach { assertTrue(it, it in notice) }
        assertTrue("an app on a kept node isn't listed", "Other" !in notice)
        // The pre-proxy line tells what changes for the node.
        assertTrue(notice, "前置代理" in notice || "pre-proxy" in notice)
        // Nothing pointed at the removed node: no notice.
        assertNull(StateOps.removalNotice(s, StateOps.removeNode(s, "u")!!))
        // A removed node's own links aren't reported.
        assertNull(StateOps.removalNotice(after, StateOps.removeNode(after, "b")!!))
    }

    private fun localRules(name: String, text: String, enabled: Boolean = true) = com.mallocgfw.app.model.RuleSourceItem(
        id = "rule_$name", name = name, url = "", type = com.mallocgfw.app.model.RuleSourceType.Auto,
        policy = com.mallocgfw.app.model.RuleTargetPolicy.Proxy, enabled = enabled, systemDefault = false,
        updatedAt = "刚刚", status = com.mallocgfw.app.model.RuleSourceStatus.Ready, totalRules = 1, convertedRules = 1,
        skippedRules = 0, sourceKind = com.mallocgfw.app.model.RuleSourceKind.LocalText, content = text,
    )

    @Test
    fun nodeRuleTargetsCountForNoticeAndReconnect() {
        val target = node("t", "local", name = "Rule Node")
        val token = com.mallocgfw.app.model.RuleSourceManager.nodePolicyToken(target)
        val base = state(node("a", "local"), target, selected = "a")
            .copy(ruleSources = listOf(localRules("My Rules", "DOMAIN-SUFFIX,example.com,$token")))
        val after = StateOps.removeNode(base, "t")!!
        val notice = StateOps.removalNotice(base, after)!!
        assertTrue(notice, "My Rules" in notice && "Rule Node" in notice)
        // The tunnel built a rule outbound for it: reconnect on the same node.
        assertEquals("a", (StateOps.removalEffect(base, after, "a") as StateOps.RemovalEffect.Reconnect).server.id)
        // Global mode builds no rules, so nothing to drop from the tunnel.
        val global = base.copy(settings = base.settings.copy(globalProxyEnabled = true))
        assertEquals(StateOps.RemovalEffect.None, StateOps.removalEffect(global, StateOps.removeNode(global, "t")!!, "a"))
        // A disabled source routes nothing and isn't reported.
        val disabled = base.copy(ruleSources = listOf(localRules("My Rules", "DOMAIN-SUFFIX,example.com,$token", enabled = false)))
        assertNull(StateOps.removalNotice(disabled, StateOps.removeNode(disabled, "t")!!))
        // The same server back under a new id (refresh): the token still resolves by server key.
        val replaced = after.copy(servers = after.servers + target.copy(id = "t2"))
        assertNull(StateOps.removalNotice(base, replaced))
    }

    @Test
    fun refreshThatDropsANodeUsedElsewhereReconnectsAndReports() {
        // Connected to "a" (local); its pre-proxy and an app pin are in the subscription.
        val relay = node("s1", "sub1", name = "Relay")
        val base = state(node("a", "local", name = "Main").copy(preProxyNodeId = "s1"), relay, node("s2", "sub1", name = "Keep"), selected = "a")
            .copy(
                proxyMode = ProxyMode.PerApp,
                protectedApps = listOf(com.mallocgfw.app.model.ProtectedApp("org.telegram", "Telegram", "x", enabled = true, serverId = "s1")),
            )
        // The refresh no longer lists Relay.
        val preview = ImportPreview(
            input = "https://sub.example.com/x", group = sub,
            nodes = listOf(node("n2", "sub1", name = "Keep", host = "s2.example.com")),
            subscription = base.subscriptions.single(), summary = "",
        )
        val outcome = StateOps.applyImport(base, preview, connectedServerId = "a")
        assertTrue(outcome.state.servers.none { it.id == "s1" })
        assertEquals("", outcome.state.servers.single { it.id == "a" }.preProxyNodeId)
        assertEquals("", outcome.state.protectedApps.single().serverId)
        assertEquals("a", outcome.reconnectServer?.id)
        val notice = StateOps.removalNotice(base, outcome.state)!!
        assertTrue(notice, "Main" in notice && "Telegram" in notice)
        // Not connected: no reconnect, the notice still applies.
        assertNull(StateOps.applyImport(base, preview, connectedServerId = null).reconnectServer)
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
        assertEquals(
            "俄罗斯网站直连",
            StateOps.setLanguage(zh.copy(ruleSources = com.mallocgfw.app.model.RuleSourceManager.defaultSources), com.mallocgfw.app.model.AppLanguage.Russian)
                .ruleSources.first { it.id == com.mallocgfw.app.model.RuleSourceManager.HOME_SITES_ID }.name,
        )
        // English keeps China direct; Russian moves to Russia and back.
        val en = StateOps.setLanguage(zh, com.mallocgfw.app.model.AppLanguage.English)
        assertEquals(com.mallocgfw.app.model.AppGeoRoutingRegion.China, en.settings.geoRoutingRegion)
        assertEquals(
            com.mallocgfw.app.model.AppGeoRoutingRegion.China,
            StateOps.setLanguage(ru, com.mallocgfw.app.model.AppLanguage.English).settings.geoRoutingRegion,
        )
        val oldEnglish = zh.copy(settings = zh.settings.copy(language = com.mallocgfw.app.model.AppLanguage.English, geoRoutingRegion = com.mallocgfw.app.model.AppGeoRoutingRegion.English))
        assertEquals(com.mallocgfw.app.model.AppGeoRoutingRegion.China, com.mallocgfw.app.model.AppStateStore.migrateEnglishRegion(oldEnglish).settings.geoRoutingRegion)
        assertEquals(custom, com.mallocgfw.app.model.AppStateStore.migrateEnglishRegion(custom))
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
