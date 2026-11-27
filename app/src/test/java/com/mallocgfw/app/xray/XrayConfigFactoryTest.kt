package com.mallocgfw.app.xray

import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.RuleTargetPolicy
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayRoutingRule
import com.mallocgfw.app.stats.RuleTags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigFactoryTest {
    @Test
    fun everyRoutingRuleHasAUniqueTagNamingItsSource() {
        val listRule = { domain: String ->
            XrayRoutingRule(
                target = RuleTargetPolicy.Proxy,
                domainSuffixes = listOf(domain),
                fullDomains = emptyList(),
                domainKeywords = emptyList(),
                ipCidrs = listOf("203.0.113.0/24"),
                ipCidrs6 = emptyList(),
                ruleTag = RuleTags.tag(RuleTags.Kind.Rule, "My list"),
            )
        }
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                routingRules = listOf(listRule("example.com"), listRule("example.org")),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                dnsServer = "1.1.1.1",
            ),
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val tags = List(rules.length()) { rules.getJSONObject(it).optString("ruleTag") }
        assertTrue("every rule is tagged: $tags", tags.none { it.isEmpty() })
        // Xray refuses duplicate tags.
        assertEquals(tags.size, tags.toSet().size)
        val listTags = tags.filter { RuleTags.parse(it)?.second == "My list" }
        assertEquals(4, listTags.size) // two lists × (domains, IPs)
        assertTrue(listTags.all { RuleTags.parse(it)?.first == RuleTags.Kind.Rule })
        assertEquals(RuleTags.Kind.Final, RuleTags.parse(tags.last())?.first)
        assertTrue(tags.any { RuleTags.parse(it)?.first == RuleTags.Kind.Dns })
    }

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
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
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
    fun vpnConfigDropsQuicThroughItsOwnBlackhole() {
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                geoRoutingRegion = AppGeoRoutingRegion.China,
                globalProxyEnabled = false,
            ),
        )
        val outbounds = config.getJSONArray("outbounds")
        val protocols = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .associate { it.optString("tag") to it.optString("protocol") }
        assertEquals("blackhole", protocols["block"])
        assertEquals("blackhole", protocols[XrayConfigFactory.QUIC_FALLBACK_TAG])
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val quicToBlock = (0 until rules.length()).map { rules.getJSONObject(it) }.any {
            it.optString("outboundTag") == "block" && it.optString("port") == "443" && it.optString("network") == "udp"
        }
        assertTrue("QUIC drops should not count as rule blocks", !quicToBlock)
    }

    @Test
    fun foreignEncryptedDnsGoesThroughTheNodeFromChina() {
        val viaNode = { raw: String, region: AppGeoRoutingRegion ->
            val dns = com.mallocgfw.app.model.DnsInput.encryptedOf(raw)!!
            val config = JSONObject(
                XrayConfigFactory.buildVpn(
                    node = testNode(),
                    geoRoutingRegion = region,
                    dnsServer = XrayConfigFactory.ENCRYPTED_DNS_ADDRESS,
                    encryptedDns = dns,
                ),
            )
            val rules = config.getJSONObject("routing").getJSONArray("rules")
            (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull {
                it.optString("outboundTag") == "proxy" && it.optString("port") == dns.port.toString() &&
                    (it.domainValues().contains("full:${dns.host}") || it.optJSONArray("ip")?.optString(0) == dns.host)
            }
        }
        assertTrue(viaNode("https://dns.google/dns-query", AppGeoRoutingRegion.China) != null)
        assertTrue(viaNode("tls://one.one.one.one", AppGeoRoutingRegion.China) != null)
        assertTrue(viaNode("https://1.1.1.1/dns-query", AppGeoRoutingRegion.China) != null)
        assertTrue(viaNode("https://dns.alidns.com/dns-query", AppGeoRoutingRegion.China) == null)
        assertTrue(viaNode("https://dns.google/dns-query", AppGeoRoutingRegion.English) == null)
    }

    @Test
    fun quicWithAKnownDomainIsDroppedBeforeEveryRuleThatUsesANode() {
        val rule = { target: com.mallocgfw.app.model.RuleTargetPolicy, domain: String ->
            com.mallocgfw.app.model.XrayRoutingRule(
                target = target,
                domainSuffixes = listOf(domain), fullDomains = emptyList(), domainKeywords = emptyList(),
                ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            )
        }
        val build = { proxyQuic: Boolean, fallbackDirect: Boolean ->
            val config = JSONObject(
                XrayConfigFactory.buildVpn(
                    node = testNode(),
                    routingRules = listOf(
                        rule(com.mallocgfw.app.model.RuleTargetPolicy.Proxy, "proxied.example"),
                        rule(com.mallocgfw.app.model.RuleTargetPolicy.Direct, "direct.example"),
                    ),
                    geoRoutingRegion = AppGeoRoutingRegion.China,
                    globalProxyEnabled = false,
                    proxyQuicEnabled = proxyQuic,
                    fallbackDirect = fallbackDirect,
                ),
            )
            val rules = config.getJSONObject("routing").getJSONArray("rules")
            (0 until rules.length()).map { rules.getJSONObject(it) }
        }
        val isQuicDrop = { r: JSONObject ->
            r.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
                r.optString("network") == "udp" && r.optString("port") == "443"
        }

        val rules = build(false, false)
        val proxied = rules.indexOfFirst { it.optString("outboundTag") == "proxy" && it.domainValues().contains("domain:proxied.example") }
        val guard = rules[proxied - 1]
        assertTrue("QUIC to a proxied domain is dropped first", isQuicDrop(guard))
        assertEquals(listOf("domain:proxied.example"), guard.domainValues())
        val direct = rules.indexOfFirst { it.domainValues().contains("domain:direct.example") }
        assertTrue("direct rules keep their QUIC", !isQuicDrop(rules[direct - 1]))
        assertTrue("a proxy fallback drops QUIC first", isQuicDrop(rules[rules.size - 2]) && !rules[rules.size - 2].has("domain"))

        assertTrue("a direct fallback keeps QUIC", !isQuicDrop(build(false, true).let { it[it.size - 2] }))
        assertTrue(
            "QUIC through the proxy keeps only the Play drop",
            build(true, false).none { isQuicDrop(it) && !it.optString("ruleTag").startsWith("play") },
        )
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
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
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
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
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
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null
        }
        val playUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
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
            outboundTag = "app_0", priority = com.mallocgfw.app.model.PerAppRouting.PRIORITY, processes = listOf("10154"),
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
            val index = rules.indexOfFirstRule {
                it.optJSONArray("process")?.optString(0) == "10154" && it.optString("outboundTag") != XrayConfigFactory.QUIC_FALLBACK_TAG
            }
            assertTrue("process rule missing (global=$global)", index >= 0)
            assertEquals("app_0", rules.getJSONObject(index).optString("outboundTag"))
        }
    }

    @Test
    fun perAppNodeOutranksUserRuleSourceWhateverTheListOrder() {
        val userRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Direct,
            domainSuffixes = listOf("example.com"), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            priority = 2_000, // user rule source
        )
        val appRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Proxy,
            domainSuffixes = emptyList(), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            outboundTag = "app_0", priority = com.mallocgfw.app.model.PerAppRouting.PRIORITY, processes = listOf("10154"),
        )
        val config = JSONObject(
            XrayConfigFactory.buildVpn(
                node = testNode(),
                routingRules = listOf(userRule, appRule),
                geoRoutingRegion = AppGeoRoutingRegion.China,
            ),
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val appIndex = rules.indexOfFirstRule { it.optJSONArray("process")?.optString(0) == "10154" }
        val userIndex = rules.indexOfFirstRule { it.optJSONArray("domain")?.optString(0) == "domain:example.com" }
        assertTrue("both rules present", appIndex >= 0 && userIndex >= 0)
        assertTrue("per-app rule must come before the user rule", appIndex < userIndex)
    }

    @Test
    fun topPriorityRuleComesFirstAndSurvivesGlobalMode() {
        val topRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Direct,
            domainSuffixes = listOf("top.example"), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            priority = com.mallocgfw.app.model.RuleSourceManager.TOP_RULE_PRIORITY,
        )
        val appRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Proxy,
            domainSuffixes = emptyList(), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            outboundTag = "app_0", priority = com.mallocgfw.app.model.PerAppRouting.PRIORITY, processes = listOf("10154"),
        )
        val userRule = com.mallocgfw.app.model.XrayRoutingRule(
            target = com.mallocgfw.app.model.RuleTargetPolicy.Proxy,
            domainSuffixes = listOf("user.example"), fullDomains = emptyList(), domainKeywords = emptyList(),
            ipCidrs = emptyList(), ipCidrs6 = emptyList(),
            priority = com.mallocgfw.app.model.RuleSourceManager.USER_RULE_PRIORITY,
        )
        listOf(false, true).forEach { global ->
            val config = JSONObject(
                XrayConfigFactory.buildVpn(
                    node = testNode(),
                    routingRules = listOf(appRule, userRule, topRule),
                    globalProxyEnabled = global,
                    geoRoutingRegion = AppGeoRoutingRegion.China,
                ),
            )
            val rules = config.getJSONObject("routing").getJSONArray("rules")
            val topIndex = rules.indexOfFirstRule { it.optJSONArray("domain")?.optString(0) == "domain:top.example" }
            val appIndex = rules.indexOfFirstRule { it.optJSONArray("process")?.optString(0) == "10154" }
            val userIndex = rules.indexOfFirstRule { it.optJSONArray("domain")?.optString(0) == "domain:user.example" }
            assertTrue("top rule missing (global=$global)", topIndex >= 0)
            assertEquals("direct", rules.getJSONObject(topIndex).optString("outboundTag"))
            assertTrue("top rule must come before per-app (global=$global)", topIndex < appIndex)
            if (global) {
                assertTrue("user rules are skipped in global mode", userIndex < 0)
            } else {
                assertTrue("user rule below top rule", userIndex > topIndex)
            }
        }
    }

    @Test
    fun fallbackDirectSendsUnmatchedTrafficDirectInRuleModeOnly() {
        fun lastOutbound(global: Boolean, direct: Boolean): String {
            val config = JSONObject(
                XrayConfigFactory.buildVpn(
                    node = testNode(),
                    globalProxyEnabled = global,
                    fallbackDirect = direct,
                    geoRoutingRegion = AppGeoRoutingRegion.China,
                ),
            )
            val rules = config.getJSONObject("routing").getJSONArray("rules")
            return rules.getJSONObject(rules.length() - 1).getString("outboundTag")
        }
        assertEquals("proxy", lastOutbound(global = false, direct = false))
        assertEquals("direct", lastOutbound(global = false, direct = true))
        // Global mode means everything through the proxy.
        assertEquals("proxy", lastOutbound(global = true, direct = true))
        val standalone = JSONObject(XrayConfigFactory.build(node = testNode(), fallbackDirect = true))
        val rules = standalone.getJSONObject("routing").getJSONArray("rules")
        assertEquals("direct", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
    }

    @Test
    fun standaloneProxyDoesNotGloballyBlockUdp443() {
        val config = JSONObject(XrayConfigFactory.build(node = testNode()))
        val rules = config.getJSONObject("routing").getJSONArray("rules")

        val globalUdp443BlockIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == XrayConfigFactory.QUIC_FALLBACK_TAG &&
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

    @Test
    fun dotForwardsEveryOtherQueryTypeOverTls() {
        val dot = com.mallocgfw.app.model.DnsInput.encryptedOf("tls://dns.google")!!
        assertTrue(XrayConfigFactory.encryptedDnsForwardsAllQueryTypes(dot))
        val config = JSONObject(
            XrayConfigFactory.buildVpn(node = testNode(), dnsServer = XrayConfigFactory.ENCRYPTED_DNS_ADDRESS, encryptedDns = dot),
        )
        val outbounds = config.getJSONArray("outbounds")
        val out = List(outbounds.length()) { outbounds.getJSONObject(it) }.single { it.optString("tag") == "encrypted-dns-out" }
        val settings = out.getJSONObject("settings")
        assertEquals("tcp", settings.getString("network"))
        assertEquals("dns.google", settings.getString("address"))
        assertEquals(853, settings.getInt("port"))
        // Forwarded queries take the same TLS outbound as the DNS module's.
        assertEquals("dot-out", out.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val rules = settings.getJSONArray("rules")
        val actions = List(rules.length()) { rules.getJSONObject(it) }
        // A / AAAA first to the DNS module, everything else (HTTPS, TXT, SRV …) forwarded.
        assertEquals("hijack", actions.first().getString("action"))
        assertEquals("1,28", actions.first().getString("qType"))
        assertEquals("direct", actions.last().getString("action"))
        assertTrue(!actions.last().has("qType") && !actions.last().has("domain"))

        // DoH / DoQ can't forward raw queries: the outbound keeps Xray's defaults.
        listOf("https://dns.alidns.com/dns-query", "quic://dns.adguard-dns.com").forEach { raw ->
            val dns = com.mallocgfw.app.model.DnsInput.encryptedOf(raw)!!
            assertTrue(raw, !XrayConfigFactory.encryptedDnsForwardsAllQueryTypes(dns))
            val c = JSONObject(XrayConfigFactory.buildVpn(node = testNode(), dnsServer = XrayConfigFactory.ENCRYPTED_DNS_ADDRESS, encryptedDns = dns))
            val obs = c.getJSONArray("outbounds")
            val o = List(obs.length()) { obs.getJSONObject(it) }.single { it.optString("tag") == "encrypted-dns-out" }
            assertTrue(raw, !o.has("settings"))
        }
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
