package com.mallocgfw.app.model

import com.mallocgfw.app.xray.LocalProxyAllocation
import com.mallocgfw.app.xray.XrayConfigFactory
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Formats and protocols Xray can't run are converted to mihomo proxies
 * instead of being dropped (the matrix runs these with real traffic).
 */
class MihomoImportTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun import(file: String, content: String) = ImportParser.buildFilePreview(file, content)

    /** name → "xray" or the mihomo proxy type. */
    private fun cores(file: String, content: String): Map<String, String> =
        import(file, content).nodes.filter { !it.hiddenUnsupported }.associate { node ->
            node.name to (NodeCompatibility.mihomoProxy(node)?.config?.optString("type") ?: "xray")
        }

    @Test
    fun clashProtocolsXrayLacksRunInMihomo() {
        val result = cores(
            "c.yaml",
            """
            proxies:
              - {name: tuic, type: tuic, server: a.example.com, port: 443, uuid: $uuid, password: pw}
              - {name: anytls, type: anytls, server: a.example.com, port: 443, password: pw}
              - {name: hy1, type: hysteria, server: a.example.com, port: 443, auth-str: pw, up: 10, down: 50}
              - {name: snell, type: snell, server: a.example.com, port: 443, psk: pw, version: 3}
              - {name: mieru, type: mieru, server: a.example.com, port: 2999, transport: TCP, username: u, password: p}
              - {name: ss-obfs, type: ss, server: a.example.com, port: 8388, cipher: aes-256-gcm, password: pw, plugin: obfs, plugin-opts: {mode: http, host: bing.com}}
              - {name: trojan, type: trojan, server: a.example.com, port: 443, password: pw}
              - {name: group, type: select, proxies: [trojan]}
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "tuic" to "tuic", "anytls" to "anytls", "hy1" to "hysteria", "snell" to "snell",
                "mieru" to "mieru", "ss-obfs" to "ss", "trojan" to "xray",
            ),
            result,
        )
    }

    @Test
    fun unknownTypesAreReportedNotSilentlyDropped() {
        val preview = import(
            "c.yaml",
            "proxies:\n  - {name: x, type: naive, server: a.example.com, port: 443}\n  - {name: t, type: trojan, server: a.example.com, port: 443, password: pw}",
        )
        assertEquals(1, preview.nodes.size)
        assertTrue(preview.summary, "naive" in preview.summary)
    }

    @Test
    fun importWithOnlyUnsupportedNodesSaysWhy() {
        val quic = "vmess://" + Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"q","add":"a.example.com","port":"443","id":"$uuid","aid":"0","net":"quic","tls":"tls"}""".toByteArray(),
        )
        val error = runCatching { import("links.txt", quic) }.exceptionOrNull() ?: return fail("expected an error")
        assertTrue(error.message, "quic" in error.message.orEmpty())
    }

    @Test
    fun shareLinksForMihomoProtocols() {
        val result = cores(
            "links.txt",
            listOf(
                "tuic://$uuid:pw@a.example.com:443?sni=a.example.com&congestion_control=bbr#tuic",
                "anytls://pw@a.example.com:443?sni=a.example.com#anytls",
                "hysteria://a.example.com:443?auth=pw&peer=a.example.com&upmbps=10&downmbps=50#hy1",
                "wireguard://OPz6V1bHKn76OwInVErwGmrq02dNVXMdoqiGI%2BxMznE%3D@a.example.com:51820?publickey=dISu35KfHMytnTSzpdc9Wm86l%2BoJQS%2Bzh6QsiAJ8hHc%3D&address=10.0.0.2%2F32#wg",
                "ss://YWVzLTI1Ni1nY206cHc@a.example.com:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#ss-obfs",
            ).joinToString("\n"),
        )
        assertEquals(
            mapOf("tuic" to "tuic", "anytls" to "anytls", "hy1" to "hysteria", "wg" to "xray", "ss-obfs" to "ss"),
            result,
        )
    }

    @Test
    fun singBoxShadowTlsDetourBecomesPlugin() {
        val node = import(
            "sb.json",
            """{"outbounds":[
              {"type":"shadowsocks","tag":"ss","method":"2022-blake3-aes-128-gcm","password":"pw","detour":"stls","server":"127.0.0.1","server_port":1},
              {"type":"shadowtls","tag":"stls","server":"a.example.com","server_port":443,"version":3,"password":"st","tls":{"enabled":true,"server_name":"www.apple.com","insecure":true}}]}""",
        ).nodes.single()
        val config = NodeCompatibility.mihomoProxy(node)!!.config
        assertEquals("a.example.com", config.getString("server"))
        assertEquals("shadow-tls", config.getString("plugin"))
        val opts = config.getJSONObject("plugin-opts")
        assertEquals("www.apple.com", opts.getString("host"))
        assertTrue(opts.getBoolean("skip-cert-verify"))
    }

    @Test
    fun surgeLoonAndQuantumultXConfigs() {
        assertEquals(
            mapOf("A" to "xray", "B" to "ss", "C" to "snell", "D" to "tuic"),
            cores(
                "s.conf",
                """
                [General]
                loglevel = notify
                [Proxy]
                A = trojan, a.example.com, 443, password=pw, sni=a.example.com
                B = ss, b.example.com, 8388, encrypt-method=aes-256-gcm, password=pw, obfs=http, obfs-host=bing.com
                C = snell, c.example.com, 443, psk=pw, version=4
                D = tuic-v5, d.example.com, 443, uuid=$uuid, password=pw, sni=d.example.com
                [Proxy Group]
                P = select, A, B
                """.trimIndent(),
            ),
        )
        assertEquals(
            mapOf("L1" to "ss", "L2" to "xray"),
            cores("l.conf", "[Proxy]\nL1 = Shadowsocks,a.example.com,443,aes-128-gcm,\"pw\",obfs-name=http,obfs-host=bing.com\nL2 = Trojan,b.example.com,443,\"pw\",tls-name=b.example.com\n"),
        )
        assertEquals(
            mapOf("Q1" to "ss", "Q2" to "xray"),
            cores("q.conf", "shadowsocks=a.example.com:443, method=aes-128-gcm, password=pw, obfs=http, obfs-host=bing.com, tag=Q1\ntrojan=b.example.com:443, password=pw, over-tls=true, tls-host=b.example.com, tag=Q2\n"),
        )
    }

    @Test
    fun xrayJsonConfigsKeepXrayUnlessRefused() {
        assertEquals(
            mapOf("A" to "xray", "B" to "vmess"),
            cores(
                "x.json",
                """[{"remarks":"A","outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"a.example.com","port":443,"password":"pw"}]},"streamSettings":{"security":"tls"}}]},
                    {"remarks":"B","outbounds":[{"protocol":"vmess","settings":{"vnext":[{"address":"b.example.com","port":443,"users":[{"id":"$uuid"}]}]},"streamSettings":{"network":"h2","security":"tls"}}]}]""",
            ),
        )
    }

    @Test
    fun inlineProxyProvidersAreImported() {
        assertEquals(
            mapOf("p" to "anytls"),
            cores("c.yaml", "proxy-providers:\n  inline1:\n    type: inline\n    payload:\n      - {name: p, type: anytls, server: a.example.com, port: 443, password: pw}\n"),
        )
    }

    @Test
    fun providerFiltersAreHonoured() {
        val names = import(
            "c.yaml",
            """
            proxy-providers:
              p1:
                type: inline
                filter: "(?i)hk|jp"
                exclude-filter: "过期"
                exclude-type: "ss|http"
                payload:
                  - {name: HK 01, type: trojan, server: a.example.com, port: 443, password: pw}
                  - {name: JP 01, type: ss, server: a.example.com, port: 8388, cipher: aes-128-gcm, password: pw}
                  - {name: jp 02, type: anytls, server: a.example.com, port: 443, password: pw}
                  - {name: US 01, type: trojan, server: a.example.com, port: 443, password: pw}
                  - {name: HK 过期, type: trojan, server: a.example.com, port: 443, password: pw}
            """.trimIndent(),
        ).nodes.map { it.name }
        assertEquals(listOf("HK 01", "jp 02"), names)
    }

    @Test
    fun downloadedProvidersUseTheirOwnFilters() {
        val provider = ImportParser.ProviderContent(
            "proxies:\n  - {name: keep, type: trojan, server: a.example.com, port: 443, password: pw}\n  - {name: drop, type: trojan, server: a.example.com, port: 443, password: pw}",
            ImportParser.ProviderFilter.from(mapOf("exclude-filter" to "drop")),
        )
        val names = ImportParser.buildFilePreview(
            "c.yaml",
            "proxies:\n  - {name: main, type: trojan, server: a.example.com, port: 443, password: pw}",
            listOf(provider),
        ).nodes.map { it.name }
        assertEquals(listOf("main", "keep"), names)
    }

    @Test
    fun mkcpLinksBecomeXrayKcpSettings() {
        val vmess = "vmess://" + Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"k","add":"a.example.com","port":"443","id":"$uuid","aid":"0","net":"kcp","type":"wechat-video","path":"seed1"}""".toByteArray(),
        )
        val vless = "vless://$uuid@a.example.com:443?security=tls&sni=a.example.com&type=kcp&headerType=dns&seed=seed2#k2"
        listOf(vmess to ("wechat-video" to "seed1"), vless to ("dns" to "seed2")).forEach { (link, expected) ->
            val node = import("links.txt", link).nodes.single()
            assertTrue(NodeCompatibility.mihomoProxy(node) == null)
            val stream = ManualNodeFactory.buildOutboundConfig(node)!!.getJSONObject("streamSettings")
            assertEquals("kcp", stream.getString("network"))
            val kcp = stream.getJSONObject("kcpSettings")
            assertEquals(expected.first, kcp.getJSONObject("header").getString("type"))
            assertEquals(expected.second, kcp.getString("seed"))
        }
    }

    @Test
    fun severalSidecarNodesGetTheirOwnListeners() {
        val nodes = import(
            "c.yaml",
            "proxies:\n  - {name: a, type: tuic, server: a.example.com, port: 443, uuid: $uuid, password: pw}\n  - {name: b, type: anytls, server: b.example.com, port: 443, password: pw}",
        ).nodes
        val (main, extra) = nodes
        val allocation = LocalProxyAllocation.ephemeral()
        val sidecar = SsrMihomoConfigFactory.sidecarNodes(main, listOf(extra))
        assertEquals(2, sidecar.size)
        val first = SsrMihomoConfigFactory.endpointFor(main, sidecar, allocation)
        val second = SsrMihomoConfigFactory.endpointFor(extra, sidecar, allocation)
        assertNotEquals(first.port, second.port)

        val yaml = SsrMihomoConfigFactory.buildConfig(sidecar.map { NodeCompatibility.mihomoProxy(it)!! to SsrMihomoConfigFactory.endpointFor(it, sidecar, allocation) })
        assertTrue(yaml.contains("\"port\":${first.port}") && yaml.contains("\"port\":${second.port}"))

        val config = JSONObject(
            XrayConfigFactory.build(
                node = main,
                additionalOutbounds = listOf(XrayNamedOutbound(tag = "extra", node = extra)),
                localProxy = allocation,
            ),
        )
        val outbounds = config.getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.associateBy { it.optString("tag") }
        assertEquals(first.port, byTag.getValue("proxy").getJSONObject("settings").getInt("port"))
        assertEquals(second.port, byTag.getValue("extra").getJSONObject("settings").getInt("port"))
    }

    @Test
    fun sidecarNodesAreNotEditableOrChainable() {
        val node = import("c.yaml", "proxies:\n  - {name: t, type: tuic, server: a.example.com, port: 443, uuid: $uuid, password: pw}").nodes.single()
        assertTrue(node.mihomoProxyJson.isNotBlank())
        assertFalse(ManualNodeFactory.supportsPreProxy(node))
    }
}
