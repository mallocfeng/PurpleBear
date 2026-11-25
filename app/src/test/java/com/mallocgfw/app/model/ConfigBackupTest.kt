package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigBackupTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String, group: String) =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$id.example.com:443?security=tls&sni=$id.example.com#$id")
            .copy(id = id, groupId = group)

    private fun state(): PersistedAppState {
        val sub = ServerGroup("sub1", "Airport", ServerGroupType.Subscription, sourceUrl = "https://sub.example.com/x", updatedAt = "刚刚")
        return AppStateStore.normalize(
            PersistedAppState(
                serverGroups = listOf(ServerGroup(ImportParser.LOCAL_GROUP_ID, "Local", ServerGroupType.Local, updatedAt = "刚刚"), sub),
                servers = listOf(node("a", ImportParser.LOCAL_GROUP_ID), node("s1", "sub1").copy(favorite = true, preProxyNodeId = "a")),
                subscriptions = listOf(
                    SubscriptionItem(
                        "sub1", "Airport", "订阅", 1, "刚刚", updatedAtMs = 1_000L, nextSync = "手动", status = "已同步", autoUpdate = true,
                        sourceUrl = sub.sourceUrl, usage = SubscriptionUsage(1L, 2L, 100L, 1_798_675_200L),
                    ),
                ),
                ruleSources = emptyList(),
                recentImports = emptyList(),
                protectedApps = listOf(ProtectedApp("org.telegram.messenger", "Telegram", "社交", enabled = true, serverId = "s1")),
                selectedServerId = "s1",
                proxyMode = ProxyMode.PerApp,
                settings = AppStateStore.defaultSettings().copy(resumeConnectionOnLaunch = true, vpnMtu = 1400),
            ),
        )
    }

    @Test
    fun aBackupRestoresTheSameNodesSubscriptionsAndSettings() {
        val original = state()
        val restored = ConfigBackup.parse(ConfigBackup.export(original, "0.9.3.2", nowMs = 1_800_000_000_000L))
        assertEquals(original.servers, restored.servers)
        assertEquals(original.subscriptions, restored.subscriptions)
        assertEquals(original.protectedApps, restored.protectedApps)
        assertEquals(original.proxyMode, restored.proxyMode)
        assertEquals("s1", restored.selectedServerId)
        assertEquals(1400, restored.settings.vpnMtu)
        // Nothing to resume on the phone it is restored to.
        assertFalse(restored.settings.resumeConnectionOnLaunch)
    }

    @Test
    fun otherFilesAreRefusedWithAReason() {
        listOf("", "not json", "{}", """{"app":"Other","format":1,"state":{}}""", """{"app":"PurpleBear","format":1}""").forEach { text ->
            try {
                ConfigBackup.parse(text)
                fail("accepted: $text")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().isNotBlank())
            }
        }
        // From a newer version: says to update instead of half-restoring.
        try {
            ConfigBackup.parse("""{"app":"PurpleBear","format":${ConfigBackup.FORMAT + 1},"state":{}}""")
            fail("accepted a newer format")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }
}
