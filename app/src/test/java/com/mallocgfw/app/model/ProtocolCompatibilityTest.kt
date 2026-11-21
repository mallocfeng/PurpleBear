package com.mallocgfw.app.model

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions found by the end-to-end protocol matrix (every protocol and
 * import format run with real traffic against Xray 26.11.9).
 */
class ProtocolCompatibilityTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun proxy(node: ServerNode): JSONObject = ManualNodeFactory.buildOutboundConfig(node)!!

    private fun importOne(fileName: String, content: String): List<ServerNode> =
        ImportParser.buildFilePreview(fileName, content).nodes

    @Test
    fun shadowsocks2022PlainUserinfoIsPercentDecoded() {
        val key = "dGVzdC1rZXktMTIzNDU2Nw+/abc="
        val link = "ss://2022-blake3-aes-128-gcm:${java.net.URLEncoder.encode(key, "UTF-8")}@example.com:443#s"
        val settings = proxy(ManualNodeFactory.buildNodeFromShareLink(link)).getJSONObject("settings")
        assertEquals(key, settings.getString("password"))
    }

    @Test
    fun socksLinkWithBase64UserinfoKeepsCredentials() {
        val info = Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        val settings = proxy(ManualNodeFactory.buildNodeFromShareLink("socks://$info@example.com:1080#k")).getJSONObject("settings")
        assertEquals("alice", settings.getString("user"))
        assertEquals("s3cret", settings.getString("pass"))
    }

    @Test
    fun clashWireGuardWithBareIpIsImported() {
        val nodes = importOne(
            "clash.yaml",
            """
            proxies:
              - {name: wg, type: wireguard, server: wg.example.com, port: 51820, ip: 10.66.0.2, private-key: "OPz6V1bHKn76OwInVErwGmrq02dNVXMdoqiGI+xMznE=", public-key: "dISu35KfHMytnTSzpdc9Wm86l+oJQS+zh6QsiAJ8hHc=", udp: true}
            """.trimIndent(),
        )
        assertEquals(1, nodes.size)
        val address = proxy(nodes.single()).getJSONObject("settings").getJSONArray("address")
        assertEquals("10.66.0.2/32", address.getString(0))
    }

    @Test
    fun singBoxWireGuardLegacyAndEndpointLayoutsAreImported() {
        val config = """
            {"outbounds":[{"type":"wireguard","tag":"legacy","server":"wg.example.com","server_port":51820,
               "local_address":["10.66.0.2/32"],"private_key":"OPz6V1bHKn76OwInVErwGmrq02dNVXMdoqiGI+xMznE=",
               "peer_public_key":"dISu35KfHMytnTSzpdc9Wm86l+oJQS+zh6QsiAJ8hHc="}],
             "endpoints":[{"type":"wireguard","tag":"endpoint","address":["10.66.0.3/32"],
               "private_key":"OPz6V1bHKn76OwInVErwGmrq02dNVXMdoqiGI+xMznE=",
               "peers":[{"address":"wg2.example.com","port":51821,"public_key":"dISu35KfHMytnTSzpdc9Wm86l+oJQS+zh6QsiAJ8hHc=","allowed_ips":["0.0.0.0/0"]}]}]}
        """.trimIndent()
        val nodes = importOne("singbox.json", config).associateBy { it.name }
        val legacy = proxy(nodes.getValue("legacy")).getJSONObject("settings")
        assertEquals("wg.example.com:51820", legacy.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        val endpoint = proxy(nodes.getValue("endpoint")).getJSONObject("settings")
        assertEquals("wg2.example.com:51821", endpoint.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals("10.66.0.3/32", endpoint.getJSONArray("address").getString(0))
    }

    @Test
    fun hysteria2SalamanderBecomesFinalmask() {
        val node = ManualNodeFactory.buildNodeFromShareLink(
            "hysteria2://pw@example.com:443?sni=example.com&obfs=salamander&obfs-password=ob123&pinSHA256=${"0f".repeat(32)}#h",
        )
        val mask = proxy(node).getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0)
        assertEquals("salamander", mask.getString("type"))
        assertEquals("ob123", mask.getJSONObject("settings").getString("password"))
        assertNull("pinned Hysteria2 stays in Xray", NodeCompatibility.mihomoProxy(node))
    }

    @Test
    fun hysteria2WithoutVerificationRunsInTheSidecar() {
        val node = ManualNodeFactory.buildNodeFromShareLink(
            "hy2://pw@example.com:443?sni=example.com&insecure=1&obfs=salamander&obfs-password=ob123#h",
        )
        val proxy = assertNotNullAnd(NodeCompatibility.mihomoProxy(node))
        val config = proxy.config
        assertEquals("hysteria2", config.getString("type"))
        assertTrue(config.getBoolean("skip-cert-verify"))
        assertEquals("salamander", config.getString("obfs"))
        assertNull(NodeCompatibility.coreIncompatibility(node))
    }

    @Test
    fun clashAndSingBoxHysteria2ObfsAreImported() {
        val clash = importOne(
            "c.yaml",
            "proxies:\n  - {name: c, type: hysteria2, server: example.com, port: 443, password: pw, sni: example.com, fingerprint: ${"ab".repeat(32)}, obfs: salamander, obfs-password: ob1}",
        ).single()
        assertTrue(proxy(clash).getJSONObject("streamSettings").has("finalmask"))
        val sing = importOne(
            "s.json",
            """{"outbounds":[{"type":"hysteria2","tag":"s","server":"example.com","server_port":443,"password":"pw","obfs":{"type":"salamander","password":"ob2"},"tls":{"enabled":true,"server_name":"example.com","insecure":true}}]}""",
        ).single()
        assertEquals("ob2", NodeCompatibility.mihomoProxy(sing)!!.config.getString("obfs-password"))
    }

    @Test
    fun nodesXray26RefusesRunInMihomoInstead() {
        fun node(link: String) = ManualNodeFactory.buildNodeFromShareLink(link)
        val refused = listOf(
            "vless://$uuid@example.com:443?security=tls&sni=example.com&type=http&path=%2F#h2",
            "vless://$uuid@example.com:443?security=tls&sni=example.com&flow=xtls-rprx-direct#x",
            "vless://$uuid@example.com:80?security=none&type=tcp#plain",
            "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("none:pw".toByteArray()) + "@example.com:8388#n",
        )
        refused.forEach { link ->
            val n = node(link)
            assertNull(link, NodeCompatibility.coreIncompatibility(n))
            assertNotNull(link, NodeCompatibility.mihomoProxy(n))
        }
        val h2 = NodeCompatibility.mihomoProxy(node(refused[0]))!!.config
        assertEquals("h2", h2.getString("network"))
        val legacy = NodeCompatibility.mihomoProxy(node(refused[1]))!!.config
        assertTrue("legacy XTLS flow is dropped", !legacy.has("flow"))
        // Private hosts and REALITY + gRPC stay in Xray.
        assertNull(NodeCompatibility.mihomoProxy(node("vless://$uuid@192.168.1.10:80?security=none&type=tcp#lan")))
        assertNull(NodeCompatibility.mihomoProxy(node("vless://$uuid@example.com:443?security=reality&sni=www.apple.com&pbk=ikI19Th3L8St5Pdf4OX8QMP9V4gEGx_Zgg-Q8LLi6zI&sid=6ba8&type=grpc&serviceName=g#rg")))
    }

    @Test
    fun quicTransportIsReportedNotImportedAsTcp() {
        val preview = ImportParser.buildFilePreview(
            "links.txt",
            listOf(
                "vmess://" + Base64.getEncoder().encodeToString("""{"v":"2","ps":"q","add":"example.com","port":"443","id":"$uuid","aid":"0","net":"quic","type":"none","tls":"tls"}""".toByteArray()),
                "vless://$uuid@example.com:443?security=tls&sni=example.com&type=http#h2",
            ).joinToString("\n"),
        )
        // QUIC transport can't run anywhere: reported, not silently imported as TCP.
        assertTrue(preview.summary, "quic" in preview.summary)
        assertEquals(listOf("h2"), preview.nodes.filter { !it.hiddenUnsupported }.map { it.name })
    }

    @Test
    fun severalPastedLinksAreImportedTogether() {
        val pasted = listOf(
            "trojan://pw@example.com:443?security=tls&sni=example.com#one",
            "socks://example.com:1080#two",
            "http://u:p@example.com:8080#three",
        ).joinToString("\n")
        val preview = kotlinx.coroutines.runBlocking { ImportParser.buildPreview(pasted) }
        assertEquals(ServerGroupType.Local, preview.group.type)
        assertEquals(listOf("one", "two", "three"), preview.nodes.map { it.name })
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
