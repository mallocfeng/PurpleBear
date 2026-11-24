package com.mallocgfw.app.xray

import com.mallocgfw.app.core.StateOps
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.MihomoImportTestNodes
import com.mallocgfw.app.model.NodeCompatibility
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.ServerNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Loops and over-long Xray pre-proxy chains: one reading of the chain
 * (XrayConfigFactory.preProxyChain) for outbounds, sidecar listeners and the
 * checks at import and in the settings; never run with a hop left out.
 */
class PreProxyChainLimitsTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String, preProxy: String = ""): ServerNode =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$id.example.com:443?security=tls&sni=$id.example.com#$id")
            .copy(id = id, groupId = "local", name = id, preProxyNodeId = preProxy)

    /** main → f1 → … → fN; the last front is [last] when given. */
    private fun chain(fronts: Int, last: ServerNode? = null): List<ServerNode> {
        val nodes = (0..fronts).map { i -> node(if (i == 0) "main" else "f$i", preProxy = if (i < fronts) "f${i + 1}" else "") }
        return if (last == null) nodes else nodes.dropLast(1) + last.copy(id = "f$fronts", name = "f$fronts")
    }

    private fun buildFails(node: ServerNode, servers: List<ServerNode>): String {
        try {
            XrayConfigFactory.build(node, servers)
        } catch (e: IllegalStateException) {
            return e.message.orEmpty()
        }
        fail("built a config for a chain that can't run")
        return ""
    }

    @Test
    fun aLoopIsRefusedInsteadOfCutOpen() {
        val servers = listOf(node("a", preProxy = "b"), node("b", preProxy = "a"))
        assertTrue(XrayConfigFactory.preProxyChain(servers[0], servers).problem!!.contains("a → b → a"))
        // Never built with B dialing directly.
        assertTrue(buildFails(servers[0], servers).contains("a → b → a"))
        assertTrue(buildFails(servers[1], servers).contains("b → a → b"))
        // Self-reference is the shortest loop.
        val self = node("s", preProxy = "s")
        assertTrue(XrayConfigFactory.preProxyChain(self, listOf(self)).problem!!.contains("s → s"))
        // Closing the loop in the settings is refused, with the reason.
        val open = listOf(node("a", preProxy = "b"), node("b"))
        val state = PersistedAppState(
            serverGroups = emptyList(), servers = open, subscriptions = emptyList(), ruleSources = emptyList(),
            recentImports = emptyList(), protectedApps = emptyList(), selectedServerId = "a",
            proxyMode = ProxyMode.Smart, settings = AppStateStore.defaultSettings(),
        )
        val outcome = StateOps.setPreProxy(state, "b", "a")
        assertFalse(outcome.changed)
        assertTrue(outcome.error!!.contains("b → a → b"))
        assertFalse(StateOps.isValidPreProxy(open, open[1], "a"))
    }

    @Test
    fun eightFrontsRunEvenWithASidecarLastAndANinthIsRefused() {
        // The eighth front runs in the sidecar: it gets a listener and an outbound.
        val servers = chain(8, last = MihomoImportTestNodes.snellNode("snell"))
        val main = servers.first()
        assertNull(XrayConfigFactory.preProxyChain(main, servers).problem)
        assertTrue(NodeCompatibility.runsInSidecar(servers.last()))
        assertEquals(listOf("f8"), XrayConfigFactory.sidecarNodesFor(main, emptyList(), servers).map { it.id })
        val outbounds = JSONObject(XrayConfigFactory.build(main, servers)).getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.associateBy { it.optString("tag") }
        val tags = generateSequence("proxy") { tag ->
            byTag.getValue(tag).optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { it.isNotBlank() }
        }.toList()
        assertEquals(9, tags.size)
        assertEquals("socks", byTag.getValue(tags.last()).getString("protocol"))

        val tooLong = chain(9, last = MihomoImportTestNodes.snellNode("snell"))
        assertTrue(XrayConfigFactory.preProxyChain(tooLong.first(), tooLong).problem!!.contains("8"))
        assertTrue(buildFails(tooLong.first(), tooLong).contains("8"))
        // A ninth front can't be added in the settings either.
        val extended = chain(8) + node("f9")
        assertFalse(StateOps.isValidPreProxy(extended, extended.single { it.id == "f8" }, "f9"))
        assertTrue(StateOps.isValidPreProxy(chain(7) + node("f8"), chain(7).last(), "f8"))
    }

    @Test
    fun anImportedLoopIsLeftOutWithTheReason() {
        fun outbound(tag: String, dialer: String?) = JSONObject()
            .put("tag", tag)
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject().put("address", "$tag.example.com").put("port", 443)
                            .put("users", JSONArray().put(JSONObject().put("id", uuid).put("encryption", "none"))),
                    ),
                ),
            )
            .put(
                "streamSettings",
                JSONObject().put("network", "tcp").put("security", "tls")
                    .put("tlsSettings", JSONObject().put("serverName", "$tag.example.com"))
                    .apply { dialer?.let { put("sockopt", JSONObject().put("dialerProxy", it)) } },
            )
        val config = JSONObject().put(
            "outbounds",
            JSONArray().put(outbound("A", "B")).put(outbound("B", "A")).put(outbound("C", null)).put(outbound("D", "C")),
        )
        val preview = ImportParser.buildFilePreview("x.json", config.toString())
        assertEquals(setOf("C", "D"), preview.nodes.map { it.name }.toSet())
        assertTrue(preview.summary, preview.summary.contains("前置代理链") && preview.summary.contains("A → B → A"))
        assertEquals(preview.nodes.single { it.name == "C" }.id, preview.nodes.single { it.name == "D" }.preProxyNodeId)
    }
}
