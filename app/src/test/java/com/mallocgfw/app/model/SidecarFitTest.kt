package com.mallocgfw.app.model

import com.mallocgfw.app.xray.RoutedOutboundFallback
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the bundled mihomo accepts, checked with its own config test (mihomo -t)
 * on the device: Snell 1 to 5 (none means 1); 1 and 2 refuse udp.
 */
class SidecarFitTest {
    private fun snell(version: Any?, udp: Boolean) = linkedMapOf<String, Any?>(
        "name" to "s", "type" to "snell", "server" to "s.example.com", "port" to 443, "psk" to "abc", "udp" to udp,
    ).also { map -> version?.let { map["version"] = it } }

    @Test
    fun snellVersionsTheSidecarRefusesAreNotOffered() {
        assertNull(MihomoNodes.sanitize(snell(99, udp = false), "s"))
        assertNull(MihomoNodes.sanitize(snell(0, udp = false), "s"))
        assertNull(MihomoNodes.sanitize(snell("x", udp = false), "s"))
        listOf(1, 2, 3, 4, 5).forEach { assertNotNull("v$it", MihomoNodes.sanitize(snell(it, udp = false), "s")) }
    }

    @Test
    fun snellOneAndTwoRunWithoutUdp() {
        assertFalse(MihomoNodes.sanitize(snell(null, udp = true), "s")!!.optBoolean("udp"))
        assertFalse(MihomoNodes.sanitize(snell(2, udp = true), "s")!!.optBoolean("udp"))
        assertTrue(MihomoNodes.sanitize(snell(3, udp = true), "s")!!.optBoolean("udp"))
        // Surge lines set udp on: a line without a version still runs.
        val line = MihomoNodes.fromConfLine("S = snell, s.example.com, 443, psk=abc")!!
        assertFalse(MihomoNodes.sanitize(line, "S")!!.optBoolean("udp"))
    }

    @Test
    fun aSavedNodeTheSidecarRefusesIsHiddenWithAReason() {
        // As saved by an older version, which accepted version 99.
        val stored = MihomoImportTestNodes.snellNode("s").copy(mihomoProxyJson = JSONObject(snell(99, udp = false)).toString())
        assertNull(NodeCompatibility.mihomoProxy(stored))
        assertTrue(NodeCompatibility.coreIncompatibility(stored)!!.contains("99"))
        assertFalse(ManualNodeFactory.isRunnable(stored))
        // An old save of Snell without a version and udp on: fixed, not refused.
        val old = stored.copy(mihomoProxyJson = JSONObject(snell(null, udp = true)).toString())
        assertFalse(NodeCompatibility.mihomoProxy(old)!!.config.optBoolean("udp"))
    }

    private fun chain(front: String) = """
        proxies:
          - {name: Main, type: trojan, server: main.example.com, port: 443, password: pw, sni: main.example.com, dialer-proxy: Front}
          - $front
    """.trimIndent()

    @Test
    fun aFrontTheSidecarRefusesListsTheNodeAsUnsupported() {
        val preview = ImportParser.buildFilePreview("c.yaml", chain("{name: Front, type: snell, server: f.example.com, port: 443, psk: abc, version: 99}"))
        val main = preview.nodes.single { it.name == "Main" }
        // Not silently run without its front: hidden, with the front's reason.
        assertTrue(main.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(main)!!.contains("前置节点"))
        assertFalse(ManualNodeFactory.isRunnable(main))
    }

    @Test
    fun aSnellOneOrTwoFrontRunsWithoutUdp() {
        val preview = ImportParser.buildFilePreview("c.yaml", chain("{name: Front, type: snell, server: f.example.com, port: 443, psk: abc, udp: true}"))
        val main = preview.nodes.single { it.name == "Main" }
        assertFalse(main.hiddenUnsupported)
        val front = NodeCompatibility.mihomoProxy(main)!!.config.getJSONObject(MihomoProxy.DIALER_KEY)
        assertEquals("snell", front.getString("type"))
        assertFalse(front.optBoolean("udp"))
    }

    @Test
    fun savedChainsAreCheckedFrontToo() {
        val main = ImportParser.buildFilePreview("c.yaml", chain("{name: Front, type: snell, server: f.example.com, port: 443, psk: abc, version: 3}"))
            .nodes.single { it.name == "Main" }
        val json = JSONObject(main.mihomoProxyJson)
        // Saved by an older version: a v2 front with udp on …
        json.getJSONObject(MihomoProxy.DIALER_KEY).put("version", 2).put("udp", true)
        val fixed = NodeCompatibility.mihomoProxy(main.copy(mihomoProxyJson = json.toString()))!!
        assertFalse(fixed.config.getJSONObject(MihomoProxy.DIALER_KEY).optBoolean("udp"))
        assertTrue(fixed.yamlLines.size == 2)
        // … or a version the sidecar refuses.
        json.getJSONObject(MihomoProxy.DIALER_KEY).put("version", 99)
        val refused = main.copy(mihomoProxyJson = json.toString())
        assertNull(NodeCompatibility.mihomoProxy(refused))
        assertTrue(NodeCompatibility.coreIncompatibility(refused)!!.contains("前置节点"))
    }

    @Test
    fun onlyRoutedNodesThatAddToTheSidecarAreDroppedOnRetry() {
        val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"
        fun xray(id: String) = ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$id.example.com:443?security=tls&sni=$id.example.com#$id")
            .copy(id = id, groupId = "g", name = id)
        val main = xray("main")
        val other = xray("other")
        val snellNode = MihomoImportTestNodes.snellNode("sn")
        val servers = listOf(main, other, snellNode)
        val extras = listOf(XrayNamedOutbound("app_0", other), XrayNamedOutbound("stream_netflix", snellNode))
        val dropped = RoutedOutboundFallback.sidecarOutbounds(main, extras, servers)
        assertEquals(listOf("stream_netflix"), dropped.map { it.tag })
        val rules = listOf(
            XrayRoutingRule(RuleTargetPolicy.Proxy, listOf("netflix.com"), emptyList(), emptyList(), ipCidrs = emptyList(), ipCidrs6 = emptyList(), outboundTag = "stream_netflix"),
            XrayRoutingRule(RuleTargetPolicy.Proxy, listOf("a.com"), emptyList(), emptyList(), ipCidrs = emptyList(), ipCidrs6 = emptyList(), outboundTag = "app_0"),
        )
        val retargeted = RoutedOutboundFallback.withoutOutbounds(rules, dropped)
        assertNull(retargeted[0].outboundTag)
        assertEquals("app_0", retargeted[1].outboundTag)
    }
}

/** A Snell node as the importer builds it (runs in the sidecar). */
object MihomoImportTestNodes {
    fun snellNode(id: String): ServerNode =
        ImportParser.buildFilePreview("x.yaml", "proxies:\n  - {name: $id, type: snell, server: $id.example.com, port: 443, psk: abc, version: 3}\n")
            .nodes.single().copy(id = id)
}
