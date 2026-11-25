package com.mallocgfw.app.xray

import com.mallocgfw.app.core.StateOps
import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.MihomoImportTestNodes
import com.mallocgfw.app.model.NodeCompatibility
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.PersistedAppState
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import com.mallocgfw.app.model.XrayRoutingRule
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

    private fun buildFails(node: ServerNode, servers: List<ServerNode>, extras: List<XrayNamedOutbound> = emptyList()): String {
        try {
            XrayConfigFactory.build(node, servers, additionalOutbounds = extras)
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

    private fun rule(tag: String, suffix: String) = XrayRoutingRule(
        target = RuleTargetPolicy.Proxy, domainSuffixes = listOf(suffix), fullDomains = emptyList(),
        domainKeywords = emptyList(), ipCidrs = emptyList(), ipCidrs6 = emptyList(), outboundTag = tag,
    )

    @Test
    fun aRoutedNodeWithABrokenChainNeverKeepsTheMainNodeOff() {
        // Saved by an older version: a media route through a looped node.
        val servers = listOf(node("main"), node("a", preProxy = "b"), node("b", preProxy = "a"), node("ok"))
        val looped = XrayNamedOutbound("stream_netflix", servers[1])
        val fine = XrayNamedOutbound("app_0", servers[3])
        val rules = listOf(
            rule("stream_netflix", "netflix.com"),
            rule("app_0", "example.org"),
        )
        assertTrue(buildFails(servers[0], servers, listOf(looped, fine)).contains("a → b → a"))
        val broken = RoutedOutboundFallback.brokenChains(listOf(looped, fine), servers)
        assertEquals(listOf(looped), broken.map { it.first })
        assertTrue(broken.single().second.contains("a → b → a"))
        // Without it the main node starts; the media rule goes by its policy, the other route stays.
        val kept = RoutedOutboundFallback.withoutOutbounds(rules, broken.map { it.first })
        assertNull(kept[0].outboundTag)
        assertEquals("app_0", kept[1].outboundTag)
        val outbounds = JSONObject(XrayConfigFactory.build(servers[0], servers, routingRules = kept, additionalOutbounds = listOf(fine)))
            .getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }
        assertTrue(tags.contains("proxy") && tags.contains("app_0") && !tags.contains("stream_netflix"))
    }

    @Test
    fun anImportedFrontThatIsntThereLeavesTheNodeOut() {
        val config = JSONObject().put(
            "outbounds",
            JSONArray()
                .put(xrayOutbound("A", "missing-front"))
                .put(xrayOutbound("B", "block"))
                .put(xrayOutbound("C", "A")) // its front is left out: so is it
                .put(xrayOutbound("D", "direct")) // plain freedom: direct, as without a front
                .put(xrayOutbound("E", null))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")),
        )
        val preview = ImportParser.buildFilePreview("x.json", config.toString())
        assertEquals(setOf("D", "E"), preview.nodes.map { it.name }.toSet())
        assertTrue(preview.summary, preview.summary.contains("missing-front"))
        assertTrue(preview.summary, preview.summary.contains("3 个节点的前置代理链"))
        assertEquals("", preview.nodes.single { it.name == "D" }.preProxyNodeId)
        // Only a missing front: nothing to import, and the message says why.
        val only = JSONObject().put("outbounds", JSONArray().put(xrayOutbound("A", "missing-front")))
        try {
            ImportParser.buildFilePreview("y.json", only.toString())
            fail("imported a node without its front")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("missing-front"))
        }
    }

    @Test
    fun onlyAFreedomFrontThatDoesNothingCountsAsDirect() {
        fun freedom(tag: String, settings: JSONObject?) = JSONObject().put("tag", tag).put("protocol", "freedom")
            .apply { settings?.let { put("settings", it) } }
        val config = JSONObject().put(
            "outbounds",
            JSONArray()
                .put(xrayOutbound("A", "plain"))
                .put(xrayOutbound("B", "asis"))
                .put(xrayOutbound("C", "redirect"))
                .put(xrayOutbound("D", "fragment"))
                .put(xrayOutbound("E", "useip"))
                .put(xrayOutbound("F", "chained"))
                .put(freedom("plain", null))
                .put(freedom("asis", JSONObject().put("domainStrategy", "AsIs").put("userLevel", 0)))
                .put(freedom("redirect", JSONObject().put("redirect", "127.0.0.1:3366")))
                .put(freedom("fragment", JSONObject().put("fragment", JSONObject().put("packets", "tlshello"))))
                .put(freedom("useip", JSONObject().put("domainStrategy", "UseIPv4")))
                .put(freedom("chained", null).put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "X")))),
        )
        val preview = ImportParser.buildFilePreview("f.json", config.toString())
        assertEquals(setOf("A", "B"), preview.nodes.map { it.name }.toSet())
        assertTrue(preview.summary, preview.summary.contains("redirect"))
        assertTrue(preview.summary, preview.summary.contains("4 个节点的前置代理链"))
    }

    @Test
    fun eachFreedomDefaultAloneStillCountsAsDirect() {
        // One key at a time, as a config would write it: the chain still imports.
        listOf(
            JSONObject().put("proxyProtocol", 0),
            JSONObject().put("proxyProtocol", "0"),
            JSONObject().put("targetStrategy", "AsIs"),
            JSONObject().put("domainStrategy", "AsIs"),
        ).forEach { settings ->
            val config = JSONObject().put(
                "outbounds",
                JSONArray().put(xrayOutbound("A", "direct")).put(xrayOutbound("B", "A"))
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", settings)),
            )
            assertEquals("$settings", setOf("A", "B"), ImportParser.buildFilePreview("e.json", config.toString()).nodes.map { it.name }.toSet())
        }
    }

    @Test
    fun explicitFreedomDefaultsStillCountAsDirect() {
        fun freedom(tag: String) = JSONObject().put("tag", tag).put("protocol", "freedom")
        val defaults = freedom("direct")
            .put(
                "settings",
                JSONObject().put("domainStrategy", "AsIs").put("targetStrategy", "AsIs").put("redirect", "").put("proxyProtocol", 0)
                    .put("noises", JSONArray()).put("finalRules", JSONArray()).put("userLevel", 0),
            )
            .put("streamSettings", JSONObject())
            .put("mux", JSONObject().put("enabled", false))
        // Two levels: B → A → direct. Both import, as with empty settings.
        val config = JSONObject().put("outbounds", JSONArray().put(xrayOutbound("A", "direct")).put(xrayOutbound("B", "A")).put(defaults))
        val preview = ImportParser.buildFilePreview("d.json", config.toString())
        assertEquals(setOf("A", "B"), preview.nodes.map { it.name }.toSet())
        assertEquals(preview.nodes.single { it.name == "A" }.id, preview.nodes.single { it.name == "B" }.preProxyNodeId)
        // A value that changes the connection still doesn't.
        listOf(
            JSONObject().put("proxyProtocol", 2),
            JSONObject().put("redirect", "1.2.3.4:443"),
            JSONObject().put("domainStrategy", "UseIP"),
            JSONObject().put("targetStrategy", "UseIPv4"),
            JSONObject().put("finalRules", JSONArray().put(JSONObject().put("action", "block").put("port", "22"))),
        ).forEach { settings ->
            val changed = JSONObject().put(
                "outbounds",
                JSONArray().put(xrayOutbound("A", "direct")).put(xrayOutbound("B", "A")).put(freedom("direct").put("settings", settings)),
            )
            try {
                ImportParser.buildFilePreview("c.json", changed.toString())
                fail("imported through $settings")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("direct"))
            }
        }
        val muxOn = JSONObject().put(
            "outbounds",
            JSONArray().put(xrayOutbound("A", "direct")).put(freedom("direct").put("mux", JSONObject().put("enabled", true))),
        )
        try {
            ImportParser.buildFilePreview("m.json", muxOn.toString())
            fail("imported through a freedom front with mux on")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("direct"))
        }
    }

    private fun xrayOutbound(tag: String, dialer: String?) = JSONObject()
        .put("tag", tag)
        .put("protocol", "vless")
        .put(
            "settings",
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject().put("address", "${tag.lowercase()}.example.com").put("port", 443)
                        .put("users", JSONArray().put(JSONObject().put("id", uuid).put("encryption", "none"))),
                ),
            ),
        )
        .put(
            "streamSettings",
            JSONObject().put("network", "tcp").put("security", "tls")
                .put("tlsSettings", JSONObject().put("serverName", "${tag.lowercase()}.example.com"))
                .apply { dialer?.let { put("sockopt", JSONObject().put("dialerProxy", it)) } },
        )

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
            JSONArray().put(outbound("A", "B")).put(outbound("B", "A")).put(outbound("C", null)).put(outbound("D", "C"))
                .put(outbound("E", "E")),
        )
        val preview = ImportParser.buildFilePreview("x.json", config.toString())
        assertEquals(setOf("C", "D"), preview.nodes.map { it.name }.toSet())
        assertTrue(preview.summary, preview.summary.contains("前置代理链") && preview.summary.contains("A → B → A"))
        // Its own front (E → E) is a loop too, not a direct node: left out as well (A, B, E).
        assertTrue(preview.summary, preview.summary.contains("3 个节点的前置代理链"))
        assertEquals(preview.nodes.single { it.name == "C" }.id, preview.nodes.single { it.name == "D" }.preProxyNodeId)
    }
}
