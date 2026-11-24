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

    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    @Test
    fun anOverrideKeepsXhttpAndHttpUpgradeSettings() {
        val xhttp = "vless://$uuid@x.example.com:443?encryption=none&security=tls&sni=cdn.example.com&type=xhttp&host=h.example.com&path=%2Fxp#X\n"
        val udp = ImportParser.ProviderOverride(fields = mapOf("udp" to true))
        val x = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(xhttp, override = udp)))
            .nodes.single { it.name == "X" }
        val stream = ManualNodeFactory.inspectOutbound(x)!!.getJSONObject("streamSettings")
        assertEquals("/xp", stream.getJSONObject("xhttpSettings").getString("path"))
        assertEquals("h.example.com", stream.getJSONObject("xhttpSettings").getString("host"))
        assertEquals("cdn.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        // Through a front (sidecar): the same path, host and server name.
        val front = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "Front"))
        val chained = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(xhttp, override = front)))
            .nodes.single { it.name == "X" }
        val config = NodeCompatibility.mihomoProxy(chained)!!.config
        assertEquals("/xp", config.getJSONObject("xhttp-opts").getString("path"))
        assertEquals("h.example.com", config.getJSONObject("xhttp-opts").getString("host"))
        assertEquals("cdn.example.com", config.getString("servername"))
        // HTTPUpgrade becomes mihomo's ws + v2ray-http-upgrade and back.
        val hu = "vless://$uuid@d.example.com:443?encryption=none&security=tls&sni=d.example.com&type=httpupgrade&host=d.example.com&path=%2Fhu#HU\n"
        val huNode = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(hu, override = front)))
            .nodes.single { it.name == "HU" }
        assertEquals("HTTPUpgrade", huNode.transport)
        assertTrue(NodeCompatibility.mihomoProxy(huNode)!!.config.getJSONObject("ws-opts").getBoolean("v2ray-http-upgrade"))
        // Clash XHTTP proxies keep theirs too.
        val yaml = "proxies:\n  - {name: Y, type: vless, server: y.example.com, port: 443, uuid: $uuid, tls: true, servername: cdn.example.com, " +
            "network: xhttp, xhttp-opts: {path: /yp, host: hy.example.com}}\n"
        val y = ManualNodeFactory.inspectOutbound(ImportParser.buildFilePreview("y.yaml", yaml).nodes.single())!!
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        assertEquals("/yp", y.getString("path"))
        assertEquals("hy.example.com", y.getString("host"))
    }

    @Test
    fun aDirectDialerOverrideKeepsTheNode() {
        val hy2 = "hysteria2://pw@h2.example.com:443?sni=h2.example.com#H2\n"
        val direct = ImportParser.ProviderOverride(fields = mapOf("dialer-proxy" to "DIRECT"))
        val preview = ImportParser.buildFilePreview("c.yaml", mainWithFront, listOf(ImportParser.ProviderContent(hy2, override = direct)))
        val node = preview.nodes.single { it.name == "H2" }
        assertFalse(node.hiddenUnsupported)
        assertNull(frontOf(node))
    }

    private fun groupFront(group: String): ServerNode = ImportParser.buildFilePreview(
        "g.yaml",
        """
            proxies:
              - {name: MyVPS, type: ss, server: vps.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: Relay}
              - {name: Own, type: trojan, server: own.example.com, port: 443, password: pw, sni: own.example.com}
            proxy-groups:
              - $group
            proxy-providers:
              sub:
                type: inline
                payload:
                  - {name: HK, type: trojan, server: hk.example.com, port: 443, password: pw, sni: hk.example.com}
                  - {name: JP, type: trojan, server: jp.example.com, port: 443, password: pw, sni: jp.example.com}
        """.trimIndent(),
    ).nodes.single { it.name == "MyVPS" }

    @Test
    fun aFrontGroupAppliesItsOwnFilters() {
        assertEquals("jp.example.com", frontOf(groupFront("{name: Relay, type: select, use: [sub], filter: \"^JP$\"}"))!!.getString("server"))
        assertEquals("jp.example.com", frontOf(groupFront("{name: Relay, type: select, use: [sub], exclude-filter: HK}"))!!.getString("server"))
        // filter picks among provider proxies only; exclude-filter drops listed ones too.
        assertEquals("own.example.com", frontOf(groupFront("{name: Relay, type: select, proxies: [Own], use: [sub], filter: JP}"))!!.getString("server"))
        assertEquals("jp.example.com", frontOf(groupFront("{name: Relay, type: select, proxies: [Own], use: [sub], exclude-filter: \"Own|HK\"}"))!!.getString("server"))
        // Nothing left: listed as unsupported with the reason.
        val none = groupFront("{name: Relay, type: select, use: [sub], filter: US}")
        assertTrue(none.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(none)!!.contains("筛选"))
    }

    @Test
    fun aProviderFrontKeepsItsCertificateName() {
        val config = useGroupConfig("            type: http\n            url: https://sub.example.com/list.txt")
        val vcn = "vless://$uuid@x.example.com:443?encryption=none&security=tls&sni=cdn.example.com&vcn=cert.example.com" +
            "&type=xhttp&host=h.example.com&path=%2Fxp#SubNode\n"
        val nodes = ImportParser.buildFilePreview("u.yaml", config, listOf(ImportParser.ProviderContent(vcn, name = "sub"))).nodes
        // Listed on its own, the node checks the certificate against that name.
        val own = ManualNodeFactory.inspectOutbound(nodes.single { it.name == "SubNode" })!!
        assertEquals("cert.example.com", own.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("verifyPeerCertByName"))
        // As a front in the sidecar too.
        val front = frontOf(nodes.single { it.name == "MyVPS" })!!
        assertEquals("cdn.example.com", front.getString("servername"))
        assertEquals("cert.example.com", front.getString("name-cert-verify"))
        assertEquals("/xp", front.getJSONObject("xhttp-opts").getString("path"))
        // Clash proxies with name-cert-verify keep it in Xray.
        val yaml = "proxies:\n  - {name: Y, type: trojan, server: y.example.com, port: 443, password: pw, sni: y.example.com, name-cert-verify: cert.example.com}\n"
        val y = ManualNodeFactory.inspectOutbound(ImportParser.buildFilePreview("y.yaml", yaml).nodes.single())!!
        assertEquals("cert.example.com", y.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("verifyPeerCertByName"))
    }

    @Test
    fun aFrontGroupStartsOnItsDefaultSelection() {
        fun front(group: String) = frontOf(groupFront(group))?.optString("server")
        assertEquals("jp.example.com", front("{name: Relay, type: select, proxies: [DIRECT], use: [sub], default-selected: JP}"))
        assertEquals("own.example.com", front("{name: Relay, type: select, proxies: [DIRECT, Own], default-selected: Own}"))
        // Filtered out or not in the group: the first member, as in mihomo.
        assertEquals("hk.example.com", front("{name: Relay, type: select, use: [sub], default-selected: JP, exclude-filter: JP}"))
        assertEquals("hk.example.com", front("{name: Relay, type: select, use: [sub], default-selected: Nowhere}"))
        // DIRECT selected: no front.
        val direct = groupFront("{name: Relay, type: select, proxies: [Own, DIRECT], default-selected: DIRECT}")
        assertFalse(direct.hiddenUnsupported)
        assertNull(frontOf(direct))
    }

    @Test
    fun clashSocksAndHttpKeepTheirTls() {
        fun tlsOf(yaml: String) = ManualNodeFactory.inspectOutbound(ImportParser.buildFilePreview("t.yaml", "proxies:\n  - $yaml\n").nodes.single())!!
            .optJSONObject("streamSettings")
        val socks = tlsOf("{name: S, type: socks5, server: s.example.com, port: 443, username: u, password: p, tls: true, name-cert-verify: cert.example.com}")!!
        assertEquals("tls", socks.getString("security"))
        assertEquals("cert.example.com", socks.getJSONObject("tlsSettings").getString("verifyPeerCertByName"))
        assertNull(tlsOf("{name: S, type: socks5, server: s.example.com, port: 1080}"))
        val http = tlsOf("{name: H, type: http, server: h.example.com, port: 443, tls: true, sni: custom.example.com}")!!
        assertEquals("custom.example.com", http.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun excludedProtocolsNeverBecomeTheFront() {
        // The Hysteria2 node can't be a sidecar front; excluded, it must not
        // stand in for the Trojan node after it either.
        val list = "hysteria2://pw@hy.example.com:443?sni=hy.example.com#HY\ntrojan://pw@tr.example.com:443?sni=tr.example.com#TR\n"
        fun front(provider: ImportParser.ProviderFilter, group: String): ServerNode {
            val config = """
                proxies:
                  - {name: MyVPS, type: ss, server: vps.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: Relay}
                proxy-groups:
                  - $group
                proxy-providers:
                  sub: {type: http, url: https://sub.example.com/list.txt}
            """.trimIndent()
            return ImportParser.buildFilePreview("x.yaml", config, listOf(ImportParser.ProviderContent(list, provider, name = "sub")))
                .nodes.single { it.name == "MyVPS" }
        }
        val byProvider = front(ImportParser.ProviderFilter(excludeTypes = setOf("hysteria2")), "{name: Relay, type: select, use: [sub]}")
        assertFalse(byProvider.hiddenUnsupported)
        assertEquals("tr.example.com", frontOf(byProvider)!!.getString("server"))
        val byGroup = front(ImportParser.ProviderFilter(), "{name: Relay, type: select, use: [sub], exclude-type: hysteria2}")
        assertEquals("tr.example.com", frontOf(byGroup)!!.getString("server"))
        // Not excluded: the Hysteria2 node is the front and can't be one.
        val kept = front(ImportParser.ProviderFilter(), "{name: Relay, type: select, use: [sub]}")
        assertTrue(kept.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(kept)!!.contains("HY"))
    }

    @Test
    fun socksAndHttpLinksCanBeFronts() {
        val config = useGroupConfig("            type: http\n            url: https://sub.example.com/list.txt")
        listOf(
            "socks://dTpw@sk.example.com:1080#SubNode" to "socks5",
            "http://u:p@hp.example.com:8080#SubNode" to "http",
        ).forEach { (link, type) ->
            val nodes = ImportParser.buildFilePreview("u.yaml", config, listOf(ImportParser.ProviderContent(link + "\n", name = "sub"))).nodes
            assertFalse(type, nodes.single { it.name == "SubNode" }.hiddenUnsupported)
            val vps = nodes.single { it.name == "MyVPS" }
            assertFalse(type, vps.hiddenUnsupported)
            val front = frontOf(vps)!!
            assertEquals(type, type, front.getString("type"))
            assertEquals(type, "u", front.getString("username"))
            assertEquals(type, "p", front.getString("password"))
        }
    }

    @Test
    fun aFrontChainIsNeverCutShort() {
        fun chain(fronts: Int): ServerNode {
            val lines = (1..fronts).joinToString("\n") { i ->
                val next = if (i < fronts) ", dialer-proxy: F${i + 1}" else ""
                "  - {name: F$i, type: trojan, server: f$i.example.com, port: 443, password: pw, sni: f$i.example.com$next}"
            }
            val yaml = "proxies:\n  - {name: Main, type: ss, server: m.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: F1}\n$lines\n"
            return ImportParser.buildFilePreview("c.yaml", yaml).nodes.single { it.name == "Main" }
        }
        fun hops(node: ServerNode): Int {
            var hop = NodeCompatibility.mihomoProxy(node)!!.config.optJSONObject(MihomoProxy.DIALER_KEY)
            var count = 0
            while (hop != null) { count++; hop = hop.optJSONObject(MihomoProxy.DIALER_KEY) }
            return count
        }
        listOf(5, 8).forEach { n ->
            val node = chain(n)
            assertFalse("$n", node.hiddenUnsupported)
            assertEquals(n, hops(node))
        }
        val tooLong = chain(9)
        assertTrue(tooLong.hiddenUnsupported)
        assertTrue(NodeCompatibility.coreIncompatibility(tooLong)!!.contains("前置链"))
        // A loop never runs either.
        val loop = """
            proxies:
              - {name: Main, type: ss, server: m.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: A}
              - {name: A, type: trojan, server: a.example.com, port: 443, password: pw, sni: a.example.com, dialer-proxy: B}
              - {name: B, type: trojan, server: b.example.com, port: 443, password: pw, sni: b.example.com, dialer-proxy: A}
        """.trimIndent()
        assertTrue(ImportParser.buildFilePreview("l.yaml", loop).nodes.single { it.name == "Main" }.hiddenUnsupported)
    }

    @Test
    fun providersSharingALinkAreAllKept() {
        val list = "proxies:\n" +
            "  - {name: HK1, type: trojan, server: hk.example.com, port: 443, password: pw, sni: hk.example.com}\n" +
            "  - {name: JP1, type: trojan, server: jp.example.com, port: 443, password: pw, sni: jp.example.com}\n"
        var hits = 0
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/list.yaml") { exchange ->
                hits++
                val body = list.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/list.yaml"
            val config = """
                proxies:
                  - {name: MyVPS, type: ss, server: vps.example.com, port: 8388, cipher: aes-128-gcm, password: pw, dialer-proxy: Relay}
                proxy-groups:
                  - {name: Relay, type: select, use: [jp]}
                proxy-providers:
                  hk: {type: http, url: "$url", filter: HK}
                  jp: {type: http, url: "$url", filter: JP}
            """.trimIndent()
            val nodes = kotlinx.coroutines.runBlocking { ImportParser.buildFilePreviewWithProviders("s.yaml", config) }.nodes
            assertEquals(1, hits)
            assertTrue(nodes.any { it.name == "HK1" })
            assertTrue(nodes.any { it.name == "JP1" })
            val vps = nodes.single { it.name == "MyVPS" }
            assertFalse(vps.hiddenUnsupported)
            assertEquals("jp.example.com", frontOf(vps)!!.getString("server"))
        } finally {
            server.stop(0)
        }
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
