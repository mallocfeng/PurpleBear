package com.mallocgfw.app.model

import com.mallocgfw.app.xray.XrayCoreManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Xray-core v26.10+ rejects `allowInsecure`, deprecates WebSocket
 * `headers.Host`, and dropped Shadowsocks UoT. Nodes stored by older app
 * versions still carry those fields and must be migrated at build time.
 */
class CoreMigrationTest {
    @Before
    fun resetPins() {
        TlsPinCache.clear()
    }

    @Test
    fun insecureTrojanLinkUsesFetchedCertificatePin() {
        val node = ManualNodeFactory.buildServerNode(
            ManualNodeFactory.prefillFromShareLink(
                "trojan://secret@1.2.3.4:8443?security=tls&sni=self.example&allowInsecure=1#t",
            ),
        )

        val target = ManualNodeFactory.insecureTlsTarget(node)
        assertEquals(TlsPinTarget("1.2.3.4", 8443, "self.example"), target)
        // Until a certificate is fetched the node simply verifies normally.
        val unpinned = ManualNodeFactory.buildOutboundConfig(node)!!
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertFalse(unpinned.has("allowInsecure"))
        assertFalse(unpinned.has("pinnedPeerCertSha256"))

        TlsPinCache.put(target!!, "AB".repeat(32))
        val pinned = ManualNodeFactory.buildOutboundConfig(node)!!
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertFalse(pinned.has("allowInsecure"))
        assertEquals("ab".repeat(32), pinned.getString("pinnedPeerCertSha256"))
    }

    @Test
    fun insecureSpellingsAreRecognised() {
        listOf("insecure=1", "skip-cert-verify=true", "allowInsecure=true").forEach { flag ->
            val node = ManualNodeFactory.buildNodeFromShareLink("trojan://p@example.com:443?sni=example.com&$flag#t")
            assertEquals(flag, TlsPinTarget("example.com", 443, "example.com"), ManualNodeFactory.insecureTlsTarget(node))
        }
    }

    @Test
    fun nodesThatVerifyOrAlreadyPinNeedNoFetch() {
        val verifying = ManualNodeFactory.buildNodeFromShareLink("trojan://p@example.com:443?sni=example.com#t")
        assertNull(ManualNodeFactory.insecureTlsTarget(verifying))
        val pinned = ManualNodeFactory.buildNodeFromShareLink(
            "trojan://p@example.com:443?sni=example.com&allowInsecure=1&pcs=${"0f".repeat(32)}#t",
        )
        assertNull(ManualNodeFactory.insecureTlsTarget(pinned))
    }

    @Test
    fun legacyStoredOutboundWithAllowInsecureGetsPinned() {
        val legacy = """
            {"protocol":"trojan","settings":{"servers":[{"address":"example.com","port":443,"password":"p"}]},
             "streamSettings":{"network":"tcp","security":"tls",
               "tlsSettings":{"serverName":"cdn.example.com","allowInsecure":true}}}
        """.trimIndent()
        val node = node(outboundJson = legacy)
        val target = ManualNodeFactory.insecureTlsTarget(node)
        assertEquals(TlsPinTarget("example.com", 443, "cdn.example.com"), target)

        TlsPinCache.put(target!!, "cd".repeat(32))
        val tls = ManualNodeFactory.buildOutboundConfig(node)!!
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("cd".repeat(32), tls.getString("pinnedPeerCertSha256"))
    }

    @Test
    fun storedOutboundDropsAllowInsecureAndMovesWebSocketHost() {
        val legacy = """
            {"protocol":"trojan","settings":{"servers":[{"address":"example.com","port":443,"password":"p"}]},
             "streamSettings":{"network":"ws","security":"tls",
               "tlsSettings":{"serverName":"example.com","allowInsecure":true},
               "wsSettings":{"path":"/ws","headers":{"Host":"cdn.example.com","X-Test":"1"}}}}
        """.trimIndent()

        val outbound = ManualNodeFactory.buildOutboundConfig(node(outboundJson = legacy))!!
        val stream = outbound.getJSONObject("streamSettings")

        assertFalse(stream.getJSONObject("tlsSettings").has("allowInsecure"))
        val ws = stream.getJSONObject("wsSettings")
        assertEquals("cdn.example.com", ws.getString("host"))
        assertFalse(ws.getJSONObject("headers").has("Host"))
        assertEquals("1", ws.getJSONObject("headers").getString("X-Test"))
    }

    @Test
    fun storedShadowsocksOutboundDropsRemovedUotFields() {
        val legacy = """
            {"protocol":"shadowsocks","settings":{"address":"1.2.3.4","port":8388,"method":"aes-256-gcm",
             "password":"p","uot":true,"uotVersion":2}}
        """.trimIndent()

        val settings = ManualNodeFactory.buildOutboundConfig(node(outboundJson = legacy))!!
            .getJSONObject("settings")

        assertFalse(settings.has("uot"))
        assertFalse(settings.has("uotVersion"))
    }

    @Test
    fun shareLinkCertificatePinBecomesPinnedPeerCertSha256() {
        val pin = "AB:".repeat(31) + "AB"
        val node = ManualNodeFactory.buildNodeFromShareLink(
            "trojan://secret@example.com:443?security=tls&sni=example.com&allowInsecure=1&pcs=$pin&vcn=cdn.example.com#t",
        )
        val tls = ManualNodeFactory.buildOutboundConfig(node)!!
            .getJSONObject("streamSettings")
            .getJSONObject("tlsSettings")

        assertFalse(tls.has("allowInsecure"))
        assertEquals("ab".repeat(32), tls.getString("pinnedPeerCertSha256"))
        assertEquals("cdn.example.com", tls.getString("verifyPeerCertByName"))
    }

    @Test
    fun hysteria2PinSha256MapsToPinnedPeerCert() {
        val pin = "0f".repeat(32)
        val node = ManualNodeFactory.buildNodeFromShareLink(
            "hysteria2://pw@example.com:443?sni=example.com&insecure=1&pinSHA256=$pin#h",
        )
        val tls = ManualNodeFactory.buildOutboundConfig(node)!!
            .getJSONObject("streamSettings")
            .getJSONObject("tlsSettings")

        assertFalse(tls.has("allowInsecure"))
        assertEquals(pin, tls.getString("pinnedPeerCertSha256"))
    }

    @Test
    fun runtimeEnvCarriesAssetDirAndTunFd() {
        val config = JSONObject(
            XrayCoreManager.withRuntimeEnv("""{"log":{},"env":{"keep":"me"}}""", "/data/geo", 42),
        )
        val env = config.getJSONObject("env")

        assertEquals("/data/geo", env.getString("xray.location.asset"))
        assertEquals("42", env.getString("xray.tun.fd"))
        assertEquals("me", env.getString("keep"))
        assertTrue(config.has("log"))
    }

    private fun node(outboundJson: String) = ServerNode(
        id = "n",
        groupId = "local",
        name = "n",
        code = "N",
        subscription = "Local",
        region = "",
        latencyMs = 0,
        protocol = "",
        security = "",
        transport = "",
        description = "",
        address = "example.com",
        port = "443",
        flow = "none",
        stable = true,
        favorite = false,
        rawUri = "",
        outboundJson = outboundJson,
    )
}
