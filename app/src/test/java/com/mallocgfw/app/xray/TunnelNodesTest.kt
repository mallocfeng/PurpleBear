package com.mallocgfw.app.xray

import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelNodesTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun node(id: String, preProxy: String = ""): ServerNode =
        ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@$id.example.com:443?security=tls&sni=$id.example.com#$id")
            .copy(id = id, groupId = "local", name = id, preProxyNodeId = preProxy)

    @Test
    fun recordsEachOutboundAndItsPreProxyChain() {
        val servers = listOf(
            node("a", preProxy = "p1"), node("p1", preProxy = "p2"), node("p2"),
            node("m", preProxy = "q"), node("q"), node("unused"),
            node("h").copy(hiddenUnsupported = true), node("x", preProxy = "h"),
        )
        val byId = servers.associateBy { it.id }
        val ids = TunnelNodes.collect(
            byId.getValue("a"),
            listOf(XrayNamedOutbound("stream_netflix", byId.getValue("m")), XrayNamedOutbound("app_0", byId.getValue("x"))),
            servers,
        )
        // A hidden front isn't built into the config (resolvePreProxyNode), so it isn't recorded.
        assertEquals(setOf("a", "p1", "p2", "m", "q", "x"), ids)
    }
}
