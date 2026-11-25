package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ManualNodeFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LatencyProbeTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String) =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$id.example.com:443?security=tls&sni=$id.example.com#$id").copy(id = id)

    @Test
    fun aProbeConfigCarriesTheNodeAndItsChain() {
        val front = node("b")
        val owner = node("a").copy(preProxyNodeId = "b")
        val json = JSONObject(XrayConfigFactory.buildProbeOutbounds(owner, listOf(owner, front))!!)
        val outbounds = json.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        val frontTag = outbounds.getJSONObject(1).getString("tag")
        assertEquals(frontTag, outbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        // Alone: just the node.
        assertEquals(1, JSONObject(XrayConfigFactory.buildProbeOutbounds(front, listOf(front))!!).getJSONArray("outbounds").length())
    }

    @Test
    fun noProbeConfigWhenTheChainCantRunOrNeedsTheCompatibilityCore() {
        val hiddenFront = node("b").copy(hiddenUnsupported = true)
        val owner = node("a").copy(preProxyNodeId = "b")
        assertNull(XrayConfigFactory.buildProbeOutbounds(owner, listOf(owner, hiddenFront)))
        val sidecar = node("t").copy(
            outboundJson = "",
            rawUri = "",
            mihomoProxyJson = """{"name":"T","type":"tuic","server":"t.example.com","port":443,"uuid":"$uuid","password":"pw"}""",
        )
        assertNull(XrayConfigFactory.buildProbeOutbounds(sidecar, listOf(sidecar)))
    }

    @Test
    fun probeAnswersAreReadPerNode() {
        val raw = """{"success":true,"data":{"results":[
            {"success":true,"delay":231},
            {"success":false,"delay":11000,"error":"context deadline exceeded"},
            {"success":false,"delay":10000,"error":"failed to build outbound 'proxy': bad"}
        ]}}"""
        val results = LatencyProbeClient.parse(raw, 3)
        assertEquals(231, results[0].delayMs)
        assertNull(results[1].delayMs)
        assertTrue(results[1].timedOut)
        assertFalse(results[2].timedOut)
        assertTrue(NodeLatencyTester.isProbeSetupError(results[2].error))
        assertFalse(NodeLatencyTester.isProbeSetupError(results[1].error))
    }

    @Test
    fun anAnswerThatIsNotAResultMeansTheProbeIsUnavailable() {
        listOf(
            "garbage",
            """{"success":false,"error":"pingBatch requires an isolated process"}""",
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"results":[{"success":true,"delay":5}]}}""",
        ).forEach { raw ->
            try {
                LatencyProbeClient.parse(raw, 2)
                fail("accepted: $raw")
            } catch (error: LatencyProbeClient.ProbeUnavailable) {
                assertNotNull(error.message)
            }
        }
    }
}
