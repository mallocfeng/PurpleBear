package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ImportParser
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions found while testing pre-proxy chains on a device. */
class PreProxyChainTest {
    private val ssKey = "YWVzLTEyOC1nY206cGI" // aes-128-gcm:pb

    private fun local(link: String): ServerNode =
        runBlocking { ImportParser.buildPreview(link) }.nodes.single()

    @Test
    fun separateLocalImportsOfTheSameProviderGetDistinctIds() {
        // Same scheme and credentials, imported one at a time: both come out as
        // local_group_1_ss___…, which made one node open, chain to or delete the other.
        val relay = local("ss://$ssKey@relay.example.com:8388#Relay")
        val exit = local("ss://$ssKey@exit.example.com:8388#Exit")
        assertEquals(relay.id, exit.id)

        val repaired = ImportParser.withUniqueIds(listOf(relay, exit))
        assertEquals(relay.id, repaired[0].id)
        assertNotEquals(repaired[0].id, repaired[1].id)
        assertEquals("Exit", repaired[1].name)
        // Stable: loading the same saved list twice gives the same ids.
        assertEquals(repaired.map { it.id }, ImportParser.withUniqueIds(listOf(relay, exit)).map { it.id })
    }

    @Test
    fun uniqueNodeIdAvoidsEveryTakenId() {
        val taken = mutableSetOf("a")
        val first = ImportParser.uniqueNodeId("a", taken).also { taken += it }
        val second = ImportParser.uniqueNodeId("a", taken)
        assertNotEquals("a", first)
        assertNotEquals(first, second)
        assertEquals("b", ImportParser.uniqueNodeId("b", taken))
    }

    @Test
    fun pinFetchConfigRunsOnlyTheFrontChainWithoutSniffing() {
        val front = local("ss://$ssKey@relay.example.com:8388#Relay")
        val config = JSONObject(
            XrayConfigFactory.buildPinFetchConfig(front, listOf(front), LocalProxyAllocation.ephemeral()),
        )
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        // Sniffing would swap the exit's address for its TLS SNI.
        assertFalse(inbound.has("sniffing"))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("proxy", outbound.getString("tag"))
        assertEquals("shadowsocks", outbound.getString("protocol"))
    }

    @Test
    fun threeHopChainDialsThroughEachFront() {
        val near = local("ss://$ssKey@near.example.com:8388#Near").copy(id = "near")
        val middle = local("ss://$ssKey@middle.example.com:8388#Middle").copy(id = "middle", preProxyNodeId = "near")
        val exit = ManualNodeFactory.buildNodeFromShareLink(
            "trojan://pw@exit.example.com:443?security=tls&sni=exit.example.com#Exit",
        ).copy(id = "exit", preProxyNodeId = "middle")
        val servers = listOf(near, middle, exit)
        val outbounds = JSONObject(XrayConfigFactory.build(exit, availableServers = servers)).getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.associateBy { it.getString("tag") }
        fun dialer(tag: String) = byTag.getValue(tag).optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
        val middleTag = dialer("proxy")!!
        val nearTag = dialer(middleTag)!!
        assertTrue(middleTag, middleTag.startsWith("proxy_pre_"))
        assertTrue(byTag.getValue(nearTag).getJSONObject("settings").toString().contains("near.example.com"))
        assertTrue(byTag.getValue(middleTag).getJSONObject("settings").toString().contains("middle.example.com"))
        assertTrue(dialer(nearTag).isNullOrEmpty())
    }
}
