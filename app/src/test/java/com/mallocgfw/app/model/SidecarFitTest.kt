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

    private val mainWithFront = """
        proxies:
          - {name: Front, type: trojan, server: front.example.com, port: 443, password: pw, sni: front.example.com}
    """.trimIndent()

    private fun frontOf(node: ServerNode): JSONObject? =
        NodeCompatibility.mihomoProxy(node)?.config?.optJSONObject(MihomoProxy.DIALER_KEY)

    @Test
    fun inlineProviderNodesKeepAFrontFromTheMainConfig() {
        val config = mainWithFront + """

            proxy-providers:
              sub:
                type: inline
                override: {dialer-proxy: Front}
                payload:
                  - {name: Sub, type: vless, server: sub.example.com, port: 443, uuid: 0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11, tls: true, servername: sub.example.com}
        """.trimIndent()
        val sub = ImportParser.buildFilePreview("c.yaml", config).nodes.single { it.name == "Sub" }
        assertFalse(sub.hiddenUnsupported)
        assertEquals("front.example.com", frontOf(sub)!!.getString("server"))
    }

    @Test
    fun downloadedProviderNodesKeepAFrontFromTheMainConfig() {
        val provider = ImportParser.ProviderContent(
            content = "proxies:\n  - {name: Sub, type: ss, server: sub.example.com, port: 8388, cipher: aes-128-gcm, password: pw}\n",
            override = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "Front")),
        )
        val sub = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(provider)).nodes.single { it.name == "Sub" }
        assertFalse(sub.hiddenUnsupported)
        assertEquals("front.example.com", frontOf(sub)!!.getString("server"))
    }

    @Test
    fun aFrontThatIsNowhereMarksTheNodeUnsupported() {
        val provider = ImportParser.ProviderContent(
            content = "proxies:\n  - {name: Sub, type: ss, server: sub.example.com, port: 8388, cipher: aes-128-gcm, password: pw}\n",
            override = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "Nowhere")),
        )
        val sub = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(provider)).nodes.single { it.name == "Sub" }
        // Not run without the front it asks for.
        assertTrue(sub.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(sub)!!.contains("Nowhere"))
        // DIRECT is no front at all.
        val direct = provider.copy(override = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "DIRECT")))
        val plain = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(direct)).nodes.single { it.name == "Sub" }
        assertFalse(plain.hiddenUnsupported)
    }

    private val ssUri = "ss://YWVzLTEyOC1nY206cHc@sub.example.com:8388#Sub\n"

    @Test
    fun uriAndBase64ProvidersGetTheOverrideFrontToo() {
        val override = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "Front"))
        val b64 = java.util.Base64.getEncoder().encodeToString(ssUri.toByteArray())
        listOf("URI" to ssUri, "Base64" to b64).forEach { (label, content) ->
            val sub = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(content, override = override)))
                .nodes.single { it.name == "Sub" }
            assertFalse(label, sub.hiddenUnsupported)
            assertEquals(label, "front.example.com", frontOf(sub)?.getString("server"))
        }
        // Other override fields keep the node (rebuilt from its Clash map).
        val other = ImportParser.ProviderOverride(fields = mapOf("udp" to true))
        val kept = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(ssUri, override = other)))
            .nodes.single { it.name == "Sub" }
        assertFalse(kept.hiddenUnsupported)
        assertEquals("sub.example.com", kept.address)
    }

    private fun useGroupConfig(provider: String) = """
        proxies:
          - {name: MyVPS, type: ss, server: vps.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: Relay}
        proxy-groups:
          - {name: Relay, type: select, use: [sub]}
        proxy-providers:
          sub:
$provider
    """.trimIndent()

    @Test
    fun aFrontGroupThatUsesAProviderTakesItsFirstNode() {
        val config = useGroupConfig(
            "            type: inline\n            payload:\n              - {name: SubNode, type: trojan, server: subnode.example.com, port: 443, password: pw, sni: subnode.example.com}",
        )
        val nodes = ImportParser.buildFilePreview("u.yaml", config).nodes
        val vps = nodes.single { it.name == "MyVPS" }
        assertFalse(vps.hiddenUnsupported)
        assertEquals("subnode.example.com", frontOf(vps)!!.getString("server"))
        assertTrue(nodes.any { it.name == "SubNode" })
    }

    @Test
    fun aFrontGroupWithoutReadableNodesKeepsTheMainNodeWithAReason() {
        // The provider is downloaded separately; its nodes aren't known while
        // the main config is parsed.
        val config = useGroupConfig("            type: http\n            url: https://sub.example.com/list.yaml")
        val vps = ImportParser.buildFilePreview("u.yaml", config).nodes.single { it.name == "MyVPS" }
        assertTrue(vps.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(vps)!!.contains("订阅提供者"))
        // A group that lists nothing (include-all and the like): kept too.
        val includeAll = """
            proxies:
              - {name: MyVPS, type: ss, server: vps.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: Relay}
            proxy-groups:
              - {name: Relay, type: select, include-all: true}
        """.trimIndent()
        val kept = ImportParser.buildFilePreview("i.yaml", includeAll).nodes.single { it.name == "MyVPS" }
        assertTrue(kept.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(kept)!!.contains("Relay"))
    }

    private val ssWsUri =
        "ss://YWVzLTEyOC1nY206cHc@sub.example.com:443?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Btls%3Bhost%3Dsub.example.com%3Bpath%3D%2Fws#Sub\n"

    @Test
    fun anOverrideKeepsAShadowsocksPluginTransport() {
        val udp = ImportParser.ProviderOverride(prefix = "P-", fields = mapOf("udp" to true))
        val sub = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(ssWsUri, override = udp)))
            .nodes.single { it.name == "P-Sub" }
        assertFalse(sub.hiddenUnsupported)
        assertEquals("WebSocket", sub.transport)
        assertTrue(sub.security.contains("TLS"))
        // With a front it runs in the sidecar: still WebSocket over TLS there.
        val front = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "Front"))
        val chained = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(ssWsUri, override = front)))
            .nodes.single { it.name == "Sub" }
        assertFalse(chained.hiddenUnsupported)
        val config = NodeCompatibility.mihomoProxy(chained)!!.config
        assertEquals("v2ray-plugin", config.getString("plugin"))
        val opts = config.getJSONObject("plugin-opts")
        assertEquals("websocket", opts.getString("mode"))
        assertTrue(opts.getBoolean("tls"))
        assertEquals("/ws", opts.getString("path"))
        assertEquals("sub.example.com", opts.getString("host"))
        assertEquals("front.example.com", frontOf(chained)!!.getString("server"))
    }

    @Test
    fun aFrontGroupUsingADownloadedProviderTakesItsFirstNode() {
        val config = useGroupConfig("            type: http\n            url: https://sub.example.com/list.yaml")
        val yaml = "proxies:\n  - {name: SubNode, type: trojan, server: subnode.example.com, port: 443, password: pw, sni: subnode.example.com}\n"
        val uri = "trojan://pw@subnode.example.com:443?sni=subnode.example.com#SubNode\n"
        listOf("YAML" to yaml, "URI" to uri).forEach { (label, content) ->
            val nodes = ImportParser.buildFilePreview("u.yaml", config, listOf(ImportParser.ProviderContent(content, name = "sub"))).nodes
            val vps = nodes.single { it.name == "MyVPS" }
            assertFalse(label, vps.hiddenUnsupported)
            assertEquals(label, "subnode.example.com", frontOf(vps)!!.getString("server"))
        }
    }

    @Test
    fun aFrontGroupSkipsProviderNodesTheFiltersDrop() {
        val payload = """
            |            payload:
            |              - {name: Bad, type: trojan, server: bad.example.com, port: 443, password: pw, sni: bad.example.com}
            |              - {name: Old, type: ss, server: old.example.com, port: 8388, cipher: aes-128-gcm, password: pw}
            |              - {name: Good, type: trojan, server: good.example.com, port: 443, password: pw, sni: good.example.com}
        """.trimMargin()
        val inline = useGroupConfig("            type: inline\n            exclude-filter: \"Bad\"\n            exclude-type: \"ss\"\n$payload")
        val nodes = ImportParser.buildFilePreview("e.yaml", inline).nodes
        assertEquals("good.example.com", frontOf(nodes.single { it.name == "MyVPS" })!!.getString("server"))
        assertFalse(nodes.any { it.name == "Bad" || it.name == "Old" })
        // filter matches the renamed node, as in the list.
        val renamed = useGroupConfig(
            "            type: inline\n            filter: \"^HK-Good\"\n            override: {additional-prefix: HK-}\n$payload",
        )
        assertEquals(
            "good.example.com",
            frontOf(ImportParser.buildFilePreview("r.yaml", renamed).nodes.single { it.name == "MyVPS" })!!.getString("server"),
        )
        // Downloaded providers are filtered the same way.
        val http = useGroupConfig("            type: http\n            url: https://sub.example.com/list.yaml")
        val content = "proxies:\n" + payload.lines().drop(1).joinToString("\n") { it.removePrefix("            ") } + "\n"
        val filter = ImportParser.ProviderFilter(exclude = listOf(Regex("Bad")))
        val downloaded = ImportParser.buildFilePreview("h.yaml", http, listOf(ImportParser.ProviderContent(content, filter, name = "sub"))).nodes
        assertEquals("old.example.com", frontOf(downloaded.single { it.name == "MyVPS" })!!.getString("server"))
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
