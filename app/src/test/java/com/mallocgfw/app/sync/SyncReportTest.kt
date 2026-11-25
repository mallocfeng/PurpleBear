package com.mallocgfw.app.sync

import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ImportPreview
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProtectedApp
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleSourceItem
import com.mallocgfw.app.model.RuleSourceKind
import com.mallocgfw.app.model.RuleSourceStatus
import com.mallocgfw.app.model.RuleSourceType
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.ServerGroup
import com.mallocgfw.app.model.ServerGroupType
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.xray.GeoDataSnapshot
import com.mallocgfw.app.xray.GeoDataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sync merges what it fetched into the state as it is when it ends. */
class SyncReportTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String, group: String, name: String = id, host: String = "$id.example.com"): ServerNode =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$host:443?security=tls&sni=$host#$id")
            .copy(id = id, groupId = group, name = name)

    private val local = ServerGroup("local", "Local", ServerGroupType.Local, updatedAt = "刚刚")
    private val sub = ServerGroup("sub1", "Airport", ServerGroupType.Subscription, sourceUrl = "https://sub.example.com/x", updatedAt = "刚刚")
    private val subItem = SubscriptionItem("sub1", "Airport", "Subscription", 1, "刚刚", nextSync = "手动", status = "已同步", autoUpdate = true, sourceUrl = sub.sourceUrl)
    private val rules = RuleSourceItem(
        id = "r1", name = "Mine", url = "", type = RuleSourceType.Auto, policy = RuleTargetPolicy.Proxy, enabled = true,
        systemDefault = false, updatedAt = "刚刚", status = RuleSourceStatus.Ready, totalRules = 1, convertedRules = 1,
        skippedRules = 0, sourceKind = RuleSourceKind.LocalText, content = "DOMAIN,a.com,PROXY",
    )

    private fun snapshot() = PersistedAppState(
        serverGroups = listOf(local, sub),
        servers = listOf(node("a", "local"), node("s1", "sub1", name = "HK 01")),
        subscriptions = listOf(subItem),
        ruleSources = listOf(rules),
        recentImports = emptyList(),
        protectedApps = listOf(ProtectedApp("org.telegram", "Telegram", "x", enabled = false)),
        selectedServerId = "a",
        proxyMode = ProxyMode.Smart,
        settings = AppStateStore.defaultSettings(),
    )

    private fun compiled(source: RuleSourceItem) =
        com.mallocgfw.app.model.RuleSourceManager.compileText(source.id, source.content, source.type, source.policy)

    private fun report(state: PersistedAppState) = SyncReport(
        subscriptions = listOf(
            SyncReport.SubscriptionFetch(
                state.subscriptions.single(),
                ImportPreview(input = sub.sourceUrl!!, group = sub, nodes = listOf(node("n1", "sub1", name = "HK 01", host = "s1.example.com"), node("n2", "sub1", name = "JP 01")), subscription = subItem, summary = ""),
                fetchStartedAtMs = 100L,
            ),
        ),
        ruleSources = listOf(SyncReport.RuleSourceFetch(rules, rules.copy(totalRules = 9, convertedRules = 9), compiled(rules), fetchStartedAtMs = 100L)),
        geoDataSnapshot = GeoDataSnapshot(GeoDataStatus.Ready, "刚刚", "test", 0, 0),
        refreshedSubscriptions = 1, refreshedRuleSources = 1, refreshedStreamingRules = 0, failedItems = 0, summary = "",
    )

    @Test
    fun aRuleListThatCantBeWrittenFailsOnlyThatSource() {
        val atStart = snapshot()
        val merged = report(atStart).applyTo(atStart) { _, _ -> throw java.io.IOException("No space left on device") }
        // The subscription refresh is still saved.
        assertTrue(merged.servers.any { it.name == "JP 01" })
        assertEquals("已同步", merged.subscriptions.single().status)
        // The rule source is marked failed and keeps the list it had.
        val source = merged.ruleSources.single()
        assertTrue(source.status != RuleSourceStatus.Ready)
        assertEquals(1, source.convertedRules)
    }

    @Test
    fun changesMadeDuringTheSyncAreKept() {
        val atStart = snapshot()
        val fetched = report(atStart)
        // Meanwhile: a new local node, a favorite, a per-app change.
        val now = atStart.copy(
            servers = atStart.servers.map { if (it.id == "a") it.copy(favorite = true) else it } + node("x", "local"),
            protectedApps = listOf(ProtectedApp("org.telegram", "Telegram", "x", enabled = true)),
        )
        val merged = fetched.applyTo(now)
        assertTrue(merged.servers.single { it.id == "a" }.favorite)
        assertTrue(merged.servers.any { it.id == "x" })
        assertTrue(merged.protectedApps.single().enabled)
        // The sync's own work is there: the new node, the refreshed rules.
        assertTrue(merged.servers.any { it.name == "JP 01" })
        assertEquals(9, merged.ruleSources.single().convertedRules)
    }

    @Test
    fun aSubscriptionDeletedDuringTheSyncDoesNotComeBack() {
        val atStart = snapshot()
        val fetched = report(atStart)
        val now = atStart.copy(
            serverGroups = listOf(local),
            subscriptions = emptyList(),
            servers = atStart.servers.filterNot { it.groupId == "sub1" },
            ruleSources = emptyList(),
        )
        val merged = fetched.applyTo(now)
        assertTrue(merged.subscriptions.isEmpty())
        assertTrue(merged.servers.none { it.groupId == "sub1" })
        assertTrue(merged.serverGroups.none { it.id == "sub1" })
        assertTrue(merged.ruleSources.isEmpty())
    }

    @Test
    fun aRuleSourceEditedDuringTheSyncKeepsTheEditAndItsCache() {
        val atStart = snapshot()
        val fetched = report(atStart)
        val edited = rules.copy(content = "DOMAIN,b.com,PROXY")
        val committed = mutableListOf<String>()
        val merged = fetched.applyTo(atStart.copy(ruleSources = listOf(edited))) { source, _ -> committed += source.id }
        assertEquals("DOMAIN,b.com,PROXY", merged.ruleSources.single().content)
        assertEquals(1, merged.ruleSources.single().convertedRules)
        // The old compiled list isn't written over the edited source's cache.
        assertTrue(committed.isEmpty())
        // Unchanged: merged and its list committed.
        fetched.applyTo(atStart) { source, _ -> committed += source.id }
        assertEquals(listOf("r1"), committed)
    }

    @Test
    fun anOlderSyncDoesNotRollBackANewerRefresh() {
        val atStart = snapshot()
        val fetched = report(atStart) // started at 100
        // A single refresh that started later (200) already committed a newer list.
        val newer = ImportPreview(input = sub.sourceUrl!!, group = sub, nodes = listOf(node("z1", "sub1", name = "SG 01")), subscription = subItem, summary = "")
        val afterRefresh = com.mallocgfw.app.core.StateOps.applySubscriptionRefresh(atStart, "sub1", sub.sourceUrl, newer, 200L, null)!!.state
        val merged = fetched.applyTo(afterRefresh)
        assertEquals(listOf("SG 01"), merged.servers.filter { it.groupId == "sub1" }.map { it.name })
        assertEquals(200L, merged.subscriptions.single().updatedAtMs)
    }

    @Test
    fun theCacheKeyFollowsWhatTheListIsBuiltFrom() {
        val key = com.mallocgfw.app.model.RuleSourceManager::definitionKey
        // A refresh fills in the detected type: same list, same key.
        assertEquals(key(rules), key(rules.copy(type = RuleSourceType.Surge, convertedRules = 9, updatedAt = "x")))
        // The text, the link or the default policy changes the list.
        assertTrue(key(rules) != key(rules.copy(content = "DOMAIN,b.com,PROXY")))
        assertTrue(key(rules) != key(rules.copy(url = "https://x.example.com/r.list")))
        assertTrue(key(rules) != key(rules.copy(policy = RuleTargetPolicy.Direct)))
    }

    @Test
    fun anOlderSyncDoesNotRollBackANewerRuleRefresh() {
        val atStart = snapshot()
        val fetched = report(atStart) // rules fetched at 100
        // A single refresh of the same source that started later (200) committed first.
        val newer = com.mallocgfw.app.core.StateOps.applyRuleSourceRefresh(
            atStart, rules, rules.copy(totalRules = 20, convertedRules = 20), 200L, succeeded = true,
        )!!
        val committed = mutableListOf<String>()
        val merged = fetched.applyTo(newer) { source, _ -> committed += source.id }
        assertEquals(20, merged.ruleSources.single().convertedRules)
        assertEquals(200L, merged.ruleSources.single().updatedAtMs)
        // Its old list isn't written to the cache either.
        assertTrue(committed.isEmpty())
    }

    @Test
    fun theRefreshOrderSurvivesSavingAndLoading() {
        val saved = snapshot().copy(ruleSources = listOf(rules.copy(updatedAtMs = 1234L)))
        val loaded = AppStateStore.parseState(AppStateStore.serializeState(saved))
        assertEquals(1234L, loaded.ruleSources.single().updatedAtMs)
        // Saved by an older version: no order yet, any refresh applies.
        val old = AppStateStore.serializeState(snapshot())
        assertEquals(null, AppStateStore.parseState(old).ruleSources.single().updatedAtMs)
    }
}
