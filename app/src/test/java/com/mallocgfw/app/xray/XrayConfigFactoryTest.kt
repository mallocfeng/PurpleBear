package com.mallocgfw.app.xray

import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.ServerNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigFactoryTest {
    @Test
    fun vpnConfigForcesGooglePlayDownloadsThroughProxyBeforeGeoDirectRules() {
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                globalProxyEnabled = false,
            ),
        )

        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val playUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("network") == "udp" &&
                rule.optString("port") == "443" &&
                rule.domainValues().contains("domain:play.googleapis.com")
        }
        val googlePlayProxyIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "proxy" &&
                rule.domainValues().containsAll(
                    listOf(
                        "domain:android.clients.google.com",
                        "domain:google.com",
                        "domain:play.googleapis.com",
                        "domain:gvt1.com",
                        "domain:gvt2.com",
                    ),
                )
        }
        val geoDirectIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "direct" &&
                rule.domainValues().contains("geosite:cn")
        }

        assertTrue("Play UDP/443 block rule should exist", playUdp443BlockIndex >= 0)
        assertTrue("Google Play proxy rule should exist", googlePlayProxyIndex >= 0)
        assertTrue("Geo direct rule should exist in China smart routing", geoDirectIndex >= 0)
        assertTrue("Play UDP/443 block should run before Google Play proxy", playUdp443BlockIndex < googlePlayProxyIndex)
        assertTrue("Google Play proxy should run before Geo direct", googlePlayProxyIndex < geoDirectIndex)
    }

    @Test
    fun vpnConfigBlocksOnlyForeignQuicBeforeUserRules() {
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                globalProxyEnabled = false,
            ),
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")

        val udp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("network") == "udp" &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null
        }
        val geoDirectIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "direct" &&
                rule.domainValues().contains("geosite:cn")
        }

        assertTrue("VPN should block foreign UDP/443", udp443BlockIndex >= 0)
        assertEquals(
            listOf("geoip:!cn"),
            rules.getJSONObject(udp443BlockIndex).ipValues(),
        )
        assertTrue("Foreign UDP/443 block should run before geo direct", udp443BlockIndex < geoDirectIndex)
    }

    @Test
    fun globalProxyVpnBlocksAllUdp443() {
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                globalProxyEnabled = true,
            ),
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val udp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("network") == "udp" &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null &&
                rule.optJSONArray("ip") == null
        }
        assertTrue("Global proxy should block every UDP/443", udp443BlockIndex >= 0)
    }

    @Test
    fun proxyQuicSettingKeepsOnlyThePlayQuicBlock() {
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                globalProxyEnabled = false,
                proxyQuicEnabled = true,
            ),
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val generalUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null
        }
        val playUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("port") == "443" &&
                rule.domainValues().contains("domain:play.googleapis.com")
        }
        assertTrue("Proxy QUIC should not block foreign UDP/443", generalUdp443BlockIndex < 0)
        assertTrue("Play QUIC should still fall back to TCP", playUdp443BlockIndex >= 0)
    }

    @Test
    fun customDnsServerAlwaysGoesDirect() {
        val config = JSONObject(XrayConfigFactory.buildVpn(node = testNode(), dnsServer = "223.5.5.5"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val first = rules.getJSONObject(0)
        assertEquals(listOf("223.5.5.5"), first.ipValues())
        assertEquals("53", first.optString("port"))
        assertEquals("direct", first.optString("outboundTag"))
        val outbounds = config.getJSONArray("outbounds")
        assertTrue((0 until outbounds.length()).none { outbounds.getJSONObject(it).optString("tag") == "remote-dns-out" })
    }

    @Test
    fun remoteDnsGoesOverTcpThroughTheProxy() {
        val config = JSONObject(XrayConfigFactory.buildVpn(node = testNode(), dnsServer = "1.1.1.1", dnsOverProxy = true))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val dnsRule = rules.getJSONObject(rules.indexOfFirstRule { it.ipValues() == listOf("1.1.1.1") })
        assertEquals("remote-dns-out", dnsRule.optString("outboundTag"))
        val outbounds = config.getJSONArray("outbounds")
        val dnsOut = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.single { it.optString("tag") == "remote-dns-out" }
        assertEquals("dns", dnsOut.optString("protocol"))
        assertEquals("tcp", dnsOut.getJSONObject("settings").optString("network"))
        assertEquals("proxy", dnsOut.getJSONObject("streamSettings").getJSONObject("sockopt").optString("dialerProxy"))
    }

    @Test
    fun perAppNodeBecomesProcessRuleEvenInGlobalMode() {
        val appRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Proxy,
            domainSuffixes = emptyList(), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            outboundTag = "app_0", priority = 2_000, processes = listOf("10154"),
        )
        listOf(false, true).forEach { global ->
            val config = JSONObject(
                XrayConfigFactory.buildVpn(
                    node = testNode(),
                    routingRules = listOf(appRule),
                    globalProxyEnabled = global,
                    geoRoutingRegion = AppGeoRoutingRegion.China,
                ),
            )
            val rules = config.getJSONObject("routing").getJSONArray("rules")
            val index = rules.indexOfFirstRule { it.optJSONArray("process")?.optString(0) == "10154" }
            assertTrue("process rule missing (global=$global)", index >= 0)
            assertEquals("app_0", rules.getJSONObject(index).optString("outboundTag"))
        }
    }

    @Test
    fun standaloneProxyDoesNotGloballyBlockUdp443() {
        val config = JSONObject(XrayConfigFactory.build(node = testNode()))
        val rules = config.getJSONObject("routing").getJSONArray("rules")

        val globalUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("network") == "udp" &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null
        }

        assertTrue("Standalone proxy should preserve UDP/443", globalUdp443BlockIndex < 0)
    }

    private fun testNode(): ServerNode {
        return ServerNode(
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

    @Test
    fun homeSitesSwitchControlsTheRegionSiteList() {
        fun domains(region: AppGeoRoutingRegion, on: Boolean): List<String> {
            val rules = JSONObject(
                XrayConfigFactory.buildVpn(node = testNode(), geoRoutingRegion = region, homeSitesDirect = on),
            ).getJSONObject("routing").getJSONArray("rules")
            return List(rules.length()) { rules.getJSONObject(it) }.flatMap { it.domainValues() }
        }
        assertTrue(domains(AppGeoRoutingRegion.Russia, true).contains("geosite:category-ru"))
        assertTrue(domains(AppGeoRoutingRegion.Russia, false).none { it == "geosite:category-ru" })
        assertTrue(domains(AppGeoRoutingRegion.China, false).none { it == "geosite:cn" })
    }

    @Test
    fun remoteDnsForwardsEveryQueryThroughTheProxy() {
        val outbounds = JSONObject(
            XrayConfigFactory.buildVpn(node = testNode(), dnsServer = "1.1.1.1", dnsOverProxy = true),
        ).getJSONArray("outbounds")
        val dns = List(outbounds.length()) { outbounds.getJSONObject(it) }.single { it.optString("tag") == "remote-dns-out" }
        val settings = dns.getJSONObject("settings")
        assertEquals("1.1.1.1", settings.getString("address"))
        assertEquals("tcp", settings.getString("network"))
        val rules = settings.getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("direct", rules.getJSONObject(0).getString("action"))
        assertEquals("proxy", dns.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
    }

    @Test
    fun encryptedDnsGoesToTheDnsModule() {
        val dot = com.mallocgfw.app.model.DnsInput.encryptedOf("tls://dns.google")!!
        val config = JSONObject(
            XrayConfigFactory.buildVpn(node = testNode(), dnsServer = XrayConfigFactory.ENCRYPTED_DNS_ADDRESS, encryptedDns = dot),
        )
        val dns = config.getJSONObject("dns")
        assertEquals("tcp://dns.google:853", dns.getJSONArray("servers").getString(0))
        val tag = dns.getString("tag")
        val outbounds = config.getJSONArray("outbounds")
        val byTag = List(outbounds.length()) { outbounds.getJSONObject(it) }.associateBy { it.optString("tag") }
        assertEquals("dns", byTag.getValue("encrypted-dns-out").getString("protocol"))
        val dotOut = byTag.getValue("dot-out")
        assertEquals("tls", dotOut.getJSONObject("streamSettings").getString("security"))
        assertEquals("dns.google", dotOut.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("dot-loop", dotOut.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals("loopback", byTag.getValue("dot-loop").getString("protocol"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val list = List(rules.length()) { rules.getJSONObject(it) }
        assertTrue(list.any { it.ipValues() == listOf(XrayConfigFactory.ENCRYPTED_DNS_ADDRESS) && it.optString("outboundTag") == "encrypted-dns-out" })
        assertTrue(list.any { it.optJSONArray("inboundTag")?.getString(0) == tag && it.optString("port") == "853" && it.optString("outboundTag") == "dot-out" })

        val doh = com.mallocgfw.app.model.DnsInput.encryptedOf("https://dns.alidns.com/dns-query")!!
        val dohConfig = JSONObject(
            XrayConfigFactory.buildVpn(node = testNode(), dnsServer = XrayConfigFactory.ENCRYPTED_DNS_ADDRESS, encryptedDns = doh),
        )
        val dohOutbounds = dohConfig.getJSONArray("outbounds")
        assertTrue(List(dohOutbounds.length()) { dohOutbounds.getJSONObject(it).optString("tag") }.none { it == "dot-out" })
    }

    private fun JSONArray.indexOfFirstRule(predicate: (JSONObject) -> Boolean): Int {
        for (index in 0 until length()) {
            if (predicate(getJSONObject(index))) return index
        }
        return -1
    }

    private fun JSONObject.ipValues(): List<String> {
        val ips = optJSONArray("ip") ?: return emptyList()
        return List(ips.length()) { index -> ips.getString(index) }
    }

    private fun JSONObject.domainValues(): List<String> {
        val domains = optJSONArray("domain") ?: return emptyList()
        return List(domains.length()) { index -> domains.getString(index) }
    }
}
