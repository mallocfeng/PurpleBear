package com.mallocgfw.app.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subscription fetching and the Clash / Surge / Xray features beyond plain node lists. */
class SubscriptionCompatibilityTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"
    private val trojanA = "trojan://pw@a.example.com:443?security=tls&sni=a.example.com#A"
    private val clashTwo = """
        proxies:
          - {name: A, type: trojan, server: a.example.com, port: 443, password: pw}
          - {name: T, type: tuic, server: t.example.com, port: 443, uuid: $uuid, password: pw}
    """.trimIndent()

    /** Serves [default] to ordinary clients and [clash] to Clash Meta clients. */
    private fun <T> panel(default: Pair<Int, String>, clash: Pair<Int, String>, block: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            val ua = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val (code, body) = if ("clash.meta" in ua) clash else default
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}/sub")
        } finally {
            server.stop(0)
        }
    }

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun errorForUnknownClientsFallsBackToClashMeta() {
        val preview = panel(403 to "forbidden", 200 to clashTwo) { url -> runBlocking { ImportParser.buildPreview(url) } }
        assertEquals(listOf("A", "T"), preview.nodes.map { it.name })
    }

    @Test
    fun fullerClashMetaListWinsOverReducedBase64() {
        val preview = panel(200 to base64(trojanA), 200 to clashTwo) { url -> runBlocking { ImportParser.buildPreview(url) } }
        assertEquals(2, preview.nodes.size)
    }

    @Test
    fun plainListIsKeptWhenClashMetaGivesNothingBetter() {
        val preview = panel(200 to base64(trojanA), 403 to "no") { url -> runBlocking { ImportParser.buildPreview(url) } }
        assertEquals(listOf("A"), preview.nodes.map { it.name })
    }

    @Test
    fun webPageEverywhereGivesAClearError() {
        val page = "<!doctype html><html><title>Subscription</title></html>"
        val error = panel(200 to page, 200 to page) { url -> runCatching { runBlocking { ImportParser.buildPreview(url) } }.exceptionOrNull() }
        assertTrue(error?.message, "网页" in error?.message.orEmpty())
    }

    @Test
    fun hysteria2PortHoppingRunsInMihomo() {
        val links = listOf(
            "hysteria2://pw@a.example.com:20000-30000?sni=a.example.com#range",
            "hy2://pw@a.example.com:443?mport=443,20000-30000&sni=a.example.com#mport",
        )
        val nodes = ImportParser.buildFilePreview("links.txt", links.joinToString("\n")).nodes
        assertEquals(listOf("range", "mport"), nodes.map { it.name })
        val ports = nodes.map { NodeCompatibility.mihomoProxy(it)!!.config }
        assertEquals("20000-30000", ports[0].getString("ports"))
        assertEquals(20000, ports[0].getInt("port"))
        assertEquals("443,20000-30000", ports[1].getString("ports"))

        val clash = ImportParser.buildFilePreview(
            "c.yaml",
            "proxies:\n  - {name: c, type: hysteria2, server: a.example.com, port: 443, ports: 20000-30000, password: pw}",
        ).nodes.single()
        assertEquals("20000-30000", NodeCompatibility.mihomoProxy(clash)!!.config.getString("ports"))

        val singBox = ImportParser.buildFilePreview(
            "s.json",
            """{"outbounds":[{"type":"hysteria2","tag":"s","server":"a.example.com","server_ports":["20000:30000"],"password":"pw","tls":{"enabled":true,"server_name":"a.example.com"}}]}""",
        ).nodes.single()
        assertEquals("20000-30000", NodeCompatibility.mihomoProxy(singBox)!!.config.getString("ports"))
    }

    @Test
    fun providerOverrideRenamesAndForcesFields() {
        val node = ImportParser.buildFilePreview(
            "c.yaml",
            """
            proxy-providers:
              p:
                type: inline
                override:
                  additional-prefix: "[P] "
                  skip-cert-verify: true
                  proxy-name:
                    - {pattern: "HK", target: "Hong Kong"}
                payload:
                  - {name: HK 01, type: anytls, server: a.example.com, port: 443, password: pw}
            """.trimIndent(),
        ).nodes.single()
        assertEquals("[P] Hong Kong 01", node.name)
        assertTrue(NodeCompatibility.mihomoProxy(node)!!.config.getBoolean("skip-cert-verify"))
    }

    @Test
    fun clashDialerProxyBecomesAMihomoChain() {
        val nodes = ImportParser.buildFilePreview(
            "c.yaml",
            """
            proxies:
              - {name: exit, type: trojan, server: exit.example.com, port: 443, password: pw, dialer-proxy: Relay}
              - {name: front, type: tuic, server: front.example.com, port: 443, uuid: $uuid, password: pw}
            proxy-groups:
              - {name: Relay, type: select, proxies: [front]}
            """.trimIndent(),
        ).nodes.associateBy { it.name }
        val proxy = NodeCompatibility.mihomoProxy(nodes.getValue("exit"))!!
        val entries = proxy.yamlLines.map { JSONObject(it.removePrefix("  - ")) }
        assertEquals(2, entries.size)
        assertEquals(entries[1].getString("name"), entries[0].getString("dialer-proxy"))
        assertEquals("exit.example.com", entries[0].getString("server"))
        assertEquals("front.example.com", entries[1].getString("server"))
        assertFalse(entries[1].has("dialer-proxy"))
    }

    @Test
    fun xrayDialerProxyBecomesPreProxy() {
        val nodes = ImportParser.buildFilePreview(
            "x.json",
            """{"remarks":"chain","outbounds":[
              {"tag":"exit","protocol":"vless","settings":{"vnext":[{"address":"exit.example.com","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]},
               "streamSettings":{"security":"tls","tlsSettings":{"serverName":"exit.example.com"},"sockopt":{"dialerProxy":"relay"}}},
              {"tag":"relay","protocol":"trojan","settings":{"servers":[{"address":"relay.example.com","port":443,"password":"pw"}]},"streamSettings":{"security":"tls"}}]}""",
        ).nodes.associateBy { it.name }
        assertEquals(nodes.getValue("relay").id, nodes.getValue("exit").preProxyNodeId)
    }

    @Test
    fun surgeWireGuardSectionAndShadowTls() {
        val nodes = ImportParser.buildFilePreview(
            "s.conf",
            """
            [Proxy]
            WG = wireguard, section-name = Home
            SS = ss, a.example.com, 443, encrypt-method=2022-blake3-aes-128-gcm, password=pw, shadow-tls-password=st, shadow-tls-sni=www.apple.com, shadow-tls-version=3
            SN = snell, b.example.com, 443, psk=pw, version=4, shadow-tls-password=st, shadow-tls-sni=www.apple.com
            [WireGuard Home]
            private-key = OPz6V1bHKn76OwInVErwGmrq02dNVXMdoqiGI+xMznE=
            self-ip = 10.0.0.2
            mtu = 1280
            peer = (public-key = dISu35KfHMytnTSzpdc9Wm86l+oJQS+zh6QsiAJ8hHc=, allowed-ips = "0.0.0.0/0, ::/0", endpoint = wg.example.com:51820, client-id = 1/2/3)
            """.trimIndent(),
        ).nodes.associateBy { it.name }
        val wg = ManualNodeFactory.buildOutboundConfig(nodes.getValue("WG"))!!.getJSONObject("settings")
        assertEquals("wg.example.com:51820", wg.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        val ss = NodeCompatibility.mihomoProxy(nodes.getValue("SS"))!!.config
        assertEquals("shadow-tls", ss.getString("plugin"))
        assertEquals("www.apple.com", ss.getJSONObject("plugin-opts").getString("host"))
        val snell = NodeCompatibility.mihomoProxy(nodes.getValue("SN"))!!.config
        assertEquals("shadow-tls", snell.getJSONObject("obfs-opts").getString("mode"))
    }

    @Test
    fun localFileProvidersAreReported() {
        val preview = ImportParser.buildFilePreview(
            "c.yaml",
            """
            proxies:
              - {name: A, type: trojan, server: a.example.com, port: 443, password: pw}
            proxy-providers:
              local1:
                type: file
                path: ./providers/local1.yaml
            """.trimIndent(),
        )
        assertEquals(1, preview.nodes.size)
        assertTrue(preview.summary, "local1" in preview.summary)
    }
}
