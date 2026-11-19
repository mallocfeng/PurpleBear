package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.SsrEndpoint
import com.mallocgfw.app.model.SsrMihomoConfigFactory
import java.net.Authenticator
import java.net.InetAddress
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyRuntimeTest {
    @After
    fun tearDown() = LocalProxyRuntime.clear()

    @Test
    fun everyAllocationGetsFreshDistinctPortsAndSecrets() {
        val first = LocalProxyRuntime.allocate()
        val second = LocalProxyRuntime.allocate()

        val ports = listOf(first.socks.port, first.http.port, first.ssr.port)
        assertEquals(3, ports.distinct().size)
        ports.forEach { assertFalse(it in listOf(10808, 10809, 17891)) }
        assertNotEquals(first.http.password, second.http.password)
        assertNotEquals(first.http.password, first.socks.password)
        assertTrue(first.http.password.length >= 32)
        assertEquals(second, LocalProxyRuntime.current())
        assertFalse("toString must not leak the password", first.http.toString().contains(first.http.password))
    }

    @Test
    fun portPickerAvoidsReservedPorts() {
        val allocation = LocalProxyRuntime.newAllocation { count, excluded ->
            assertTrue(10809 in excluded)
            (40_001..40_000 + count).toList()
        }
        assertEquals(40_002, allocation.http.port)
    }

    @Test
    fun authenticatorOnlyAnswersOurLoopbackListeners() {
        val allocation = LocalProxyRuntime.allocate()
        val loopback = InetAddress.getByName("127.0.0.1")

        val http = LocalProxyRuntime.credentialsFor(
            "127.0.0.1", loopback, allocation.http.port, Authenticator.RequestorType.PROXY, "https",
        )
        assertNotNull(http)
        assertEquals(allocation.http.username, http?.userName)
        assertEquals(allocation.http.password, String(http!!.password))

        val socks = LocalProxyRuntime.credentialsFor(
            "localhost", loopback, allocation.socks.port, Authenticator.RequestorType.SERVER, "SOCKS5",
        )
        assertEquals(allocation.socks.username, socks?.userName)

        // Same port on another host, an origin-server challenge, or an unknown port: no answer.
        assertNull(
            LocalProxyRuntime.credentialsFor(
                "example.com", InetAddress.getByName("93.184.216.34"), allocation.http.port,
                Authenticator.RequestorType.PROXY, "https",
            ),
        )
        assertNull(
            LocalProxyRuntime.credentialsFor(
                "127.0.0.1", loopback, allocation.http.port, Authenticator.RequestorType.SERVER, "https",
            ),
        )
        assertNull(
            LocalProxyRuntime.credentialsFor(
                "127.0.0.1", loopback, 1, Authenticator.RequestorType.PROXY, "https",
            ),
        )
        LocalProxyRuntime.clear()
        assertNull(
            LocalProxyRuntime.credentialsFor(
                "127.0.0.1", loopback, allocation.http.port, Authenticator.RequestorType.PROXY, "https",
            ),
        )
    }

    @Test
    fun xrayInboundsRequireTheAllocatedCredentials() {
        val allocation = LocalProxyAllocation.ephemeral()
        val proxyConfig = JSONObject(XrayConfigFactory.build(node = node(), localProxy = allocation))
        val vpnConfig = JSONObject(XrayConfigFactory.buildVpn(node = node(), localProxy = allocation))

        val socks = proxyConfig.inbound("socks-in")
        assertEquals(allocation.socks.port, socks.getInt("port"))
        assertEquals("127.0.0.1", socks.getString("listen"))
        assertEquals("password", socks.getJSONObject("settings").getString("auth"))
        assertAccount(allocation.socks, socks.getJSONObject("settings").getJSONArray("accounts"))

        val http = proxyConfig.inbound("http-in")
        assertEquals(allocation.http.port, http.getInt("port"))
        assertAccount(allocation.http, http.getJSONObject("settings").getJSONArray("accounts"))

        val speedTest = vpnConfig.inbound("speedtest-http-in")
        assertEquals(allocation.http.port, speedTest.getInt("port"))
        assertAccount(allocation.http, speedTest.getJSONObject("settings").getJSONArray("accounts"))

        // In-app HttpURLConnection traffic uses this SOCKS inbound in VPN mode.
        val appSocks = vpnConfig.inbound("app-socks-in")
        assertEquals(allocation.socks.port, appSocks.getInt("port"))
        assertEquals("password", appSocks.getJSONObject("settings").getString("auth"))
        assertAccount(allocation.socks, appSocks.getJSONObject("settings").getJSONArray("accounts"))
        val forcedToProxy = vpnConfig.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("proxy", forcedToProxy.getString("outboundTag"))
        val forcedTags = forcedToProxy.getJSONArray("inboundTag")
        assertEquals(listOf("speedtest-http-in", "app-socks-in"), List(forcedTags.length()) { forcedTags.getString(it) })
    }

    @Test
    fun ssrSidecarRequiresAuthAndRuntimeOutboundCarriesIt() {
        val allocation = LocalProxyAllocation.ephemeral()
        val endpoint = SsrEndpoint(
            name = "ssr",
            server = "ssr.example.com",
            port = 443,
            method = "aes-256-cfb",
            password = "secret",
            protocol = "origin",
            obfs = "plain",
        )

        val yaml = SsrMihomoConfigFactory.buildConfig(endpoint, allocation.ssr)
        assertTrue(yaml.contains("mixed-port: ${allocation.ssr.port}"))
        assertTrue(yaml.contains("\"${allocation.ssr.username}:${allocation.ssr.password}\""))
        assertTrue(yaml.contains("skip-auth-prefixes: []"))

        val ssrNode = ManualNodeFactory.buildNodeFromShareLink(ssrLink())
        assertFalse("stored node must not hold runtime credentials", ssrNode.outboundJson.contains("\"pass\""))

        val outbound = JSONObject(XrayConfigFactory.build(node = ssrNode, localProxy = allocation))
            .getJSONArray("outbounds").getJSONObject(0)
        val settings = outbound.getJSONObject("settings")
        assertEquals("proxy", outbound.getString("tag"))
        assertEquals(allocation.ssr.port, settings.getInt("port"))
        assertEquals(allocation.ssr.username, settings.getString("user"))
        assertEquals(allocation.ssr.password, settings.getString("pass"))
    }

    private fun assertAccount(expected: LocalProxyEndpoint, accounts: JSONArray) {
        assertEquals(1, accounts.length())
        assertEquals(expected.username, accounts.getJSONObject(0).getString("user"))
        assertEquals(expected.password, accounts.getJSONObject(0).getString("pass"))
    }

    private fun JSONObject.inbound(tag: String): JSONObject {
        val inbounds = getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            if (inbound.optString("tag") == tag) return inbound
        }
        throw AssertionError("inbound $tag not found")
    }

    private fun ssrLink(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val password = encoder.encodeToString("secret".toByteArray())
        val body = "ssr.example.com:443:origin:aes-256-cfb:plain:$password/?remarks=" +
            encoder.encodeToString("SSR".toByteArray())
        return "ssr://" + encoder.encodeToString(body.toByteArray())
    }

    private fun node() = ServerNode(
        id = "node_test",
        groupId = "local",
        name = "Test",
        code = "TEST",
        subscription = "Local",
        region = "Test",
        latencyMs = 0,
        protocol = "SOCKS",
        security = "none",
        transport = "tcp",
        description = "Test node",
        address = "127.0.0.1",
        port = "1080",
        flow = "none",
        stable = true,
        favorite = false,
        rawUri = "",
        outboundJson = """{"protocol":"freedom"}""",
    )
}
