package com.mallocgfw.app.xray

import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ManualNodeFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicDnsConfigTest {
    private val node = ManualNodeFactory.buildNodeFromShareLink(
        "trojan://pw@exit.example.com:443?security=tls&sni=exit.example.com#Exit",
    )
    private val tailscale = TailscaleRouting(
        socksHost = "127.0.0.1",
        socksPort = 41000,
        socksUsername = "tsnet",
        socksPassword = "secret",
        magicDnsSuffix = "tail1234.ts.net",
        magicDnsHosts = mapOf("nas.tail1234.ts.net" to listOf("100.64.0.5", "fd7a:115c:a1e0::5")),
    )

    private fun vpnConfig(upstream: String?, routing: TailscaleRouting? = tailscale) = JSONObject(
        XrayConfigFactory.buildVpn(node, tailscale = routing, magicDnsUpstream = upstream),
    )

    private fun JSONObject.outbound(tag: String): JSONObject? {
        val outbounds = getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull { it.getString("tag") == tag }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun systemDnsIsInterceptedFirstAndTailnetNamesComeFromHosts() {
        val config = vpnConfig("192.168.1.1:53")
        val first = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("magic-dns-out", first.getString("outboundTag"))
        assertEquals("100.100.100.100", first.getJSONArray("ip").getString(0))
        assertEquals("tun-in", first.getJSONArray("inboundTag").getString(0))

        val dns = config.outbound("magic-dns-out")!!
        val settings = dns.getJSONObject("settings")
        // Passed-on queries go to the real resolver, through the loopback.
        assertEquals("192.168.1.1", settings.getString("address"))
        assertEquals(53, settings.getInt("port"))
        assertEquals("magic-dns-pass", dns.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals("loopback", config.outbound("magic-dns-pass")!!.getString("protocol"))

        val rules = settings.getJSONArray("rules").objects()
        assertEquals(listOf("hijack", "return", "direct"), rules.map { it.getString("action") })
        assertEquals("full:nas.tail1234.ts.net", rules[0].getJSONArray("domain").getString(0))
        assertEquals(3, rules[1].getInt("rCode"))
        assertEquals("domain:tail1234.ts.net", rules[1].getJSONArray("domain").getString(0))

        val hosts = config.getJSONObject("dns").getJSONObject("hosts")
        assertEquals("100.64.0.5", hosts.getJSONArray("full:nas.tail1234.ts.net").getString(0))
        // No upstream: hosts answer every record type, nothing waits on a resolver.
        assertFalse(config.getJSONObject("dns").has("servers"))
    }

    @Test
    fun ipv6UpstreamIsSplitCorrectly() {
        val settings = vpnConfig("[2001:4860:4860::8888]:53").outbound("magic-dns-out")!!.getJSONObject("settings")
        assertEquals("2001:4860:4860::8888", settings.getString("address"))
        assertEquals(53, settings.getInt("port"))
    }

    @Test
    fun withoutMagicDnsNothingChanges() {
        val config = vpnConfig(upstream = null)
        assertNull(config.outbound("magic-dns-out"))
        assertFalse(config.has("dns"))
        val rules = config.getJSONObject("routing").getJSONArray("rules").objects()
        assertTrue(rules.none { it.optString("outboundTag") == "magic-dns-out" })
    }

    @Test
    fun tunnelBuiltForMagicDnsStillForwardsWhenTailscaleIsGone() {
        // The VPN advertised 100.100.100.100; if Tailscale drops before the config
        // is built, queries must still reach the real resolver.
        val config = vpnConfig("10.0.2.3:53", routing = null)
        val rules = config.outbound("magic-dns-out")!!.getJSONObject("settings").getJSONArray("rules").objects()
        assertEquals(listOf("direct"), rules.map { it.getString("action") })
        assertFalse(config.has("dns"))
    }

    @Test
    fun snapshotProvidesPeerNamesAndSuffix() {
        val snapshot = TailscaleRuntimeSnapshot(
            status = TailscaleRuntimeStatus.Connected,
            dnsName = "phone.tail1234.ts.net.",
            socksHost = "127.0.0.1",
            socksPort = 41000,
            socksUsername = "tsnet",
            socksPassword = "secret",
            peers = listOf(
                TailscalePeer("1", "nas", "NAS.tail1234.ts.net.", "100.64.0.5", null, true, true, null, false, false, emptyList()),
                TailscalePeer("2", "old", "", "100.64.0.6", null, false, false, null, false, false, emptyList()),
            ),
        )
        val routing = snapshot.routing(AppStateStore.defaultSettings())!!
        // Suffix taken from the device's own name when the control server omits it.
        assertEquals("tail1234.ts.net", routing.magicDnsSuffix)
        assertEquals(mapOf("nas.tail1234.ts.net" to listOf("100.64.0.5")), routing.magicDnsHosts)
    }
}
