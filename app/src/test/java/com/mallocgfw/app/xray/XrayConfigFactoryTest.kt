package com.mallocgfw.app.xray

import com.mallocgfw.app.model.AppGeoRoutingRegion
import com.mallocgfw.app.model.ServerNode
import org.json.JSONArray
import org.json.JSONObject
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
        val udp443FallbackIndex = rules.indexOfFirstRule { rule ->
            rule.optString("outboundTag") == "block" &&
                rule.optString("network") == "udp" &&
                rule.optString("port") == "443" &&
                rule.optJSONArray("domain") == null
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

        assertTrue("VPN UDP/443 fallback rule should exist", udp443FallbackIndex >= 0)
        assertTrue("Google Play proxy rule should exist", googlePlayProxyIndex >= 0)
        assertTrue("Geo direct rule should exist in China smart routing", geoDirectIndex >= 0)
        assertTrue("UDP/443 fallback should run before Google Play proxy", udp443FallbackIndex < googlePlayProxyIndex)
        assertTrue("Google Play proxy should run before Geo direct", googlePlayProxyIndex < geoDirectIndex)
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

    private fun JSONArray.indexOfFirstRule(predicate: (JSONObject) -> Boolean): Int {
        for (index in 0 until length()) {
            if (predicate(getJSONObject(index))) return index
        }
        return -1
    }

    private fun JSONObject.domainValues(): List<String> {
        val domains = optJSONArray("domain") ?: return emptyList()
        return List(domains.length()) { index -> domains.getString(index) }
    }
}
