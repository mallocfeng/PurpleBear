package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shared node must import back as the node it was. */
class NodeShareTest {
    private val uuid = "0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11"

    private fun outboundOf(node: ServerNode) = ManualNodeFactory.inspectOutbound(node)!!.apply { remove("tag") }.toString()

    /** Shares [link]'s node as if it had no link of its own, and imports the result again. */
    private fun roundTrip(link: String) {
        val original = ManualNodeFactory.buildNodeFromShareLink(link)
        val shared = NodeShare.of(original.copy(rawUri = ""))
        assertNotNull("nothing to share for $link", shared)
        assertEquals("no share link written for $link", NodeShare.Kind.Link, shared!!.kind)
        val again = ManualNodeFactory.buildNodeFromShareLink(shared.text)
        assertEquals(outboundOf(original), outboundOf(again))
        assertEquals(original.name, again.name)
    }

    @Test
    fun aNodeImportedFromALinkIsSharedAsThatLink() {
        val link = "vless://$uuid@a.example.com:443?security=tls&sni=a.example.com#HK%2001"
        // Imports keep the link the node came from (ServerNode.rawUri).
        val shared = NodeShare.of(ManualNodeFactory.buildNodeFromShareLink(link).copy(rawUri = link))!!
        assertEquals(NodeShare.Kind.Link, shared.kind)
        assertEquals(link, shared.text)
    }

    @Test
    fun writtenLinksImportBackAsTheSameNode() {
        roundTrip("vless://$uuid@a.example.com:443?security=tls&sni=a.example.com&type=ws&host=cdn.example.com&path=%2Fws%3Fed%3D2048#%E9%A6%99%E6%B8%AF%2001")
        roundTrip("vless://$uuid@b.example.com:8443?security=reality&sni=www.example.org&fp=chrome&pbk=7xhH4b_VkliBxGulljcyPOH-bYUA2dl-XAdZAsfhk04&sid=6ba85179e30d4fc2&flow=xtls-rprx-vision&type=tcp#JP")
        roundTrip("vless://$uuid@c.example.com:443?security=tls&sni=c.example.com&type=grpc&serviceName=grpc-svc#US")
        roundTrip("trojan://p%40ss%3Aword@d.example.com:443?sni=d.example.com&type=tcp#SG")
        roundTrip("hysteria2://secret@e.example.com:8443?sni=e.example.com&obfs=salamander&obfs-password=mask#HY2")
        roundTrip("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@f.example.com:8388#SS")
    }

    @Test
    fun aVmessNodeIsSharedInTheV2rayNForm() {
        val json = """{"v":"2","ps":"VM","add":"g.example.com","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"g.example.com","path":"/vm","tls":"tls","sni":"g.example.com"}"""
        roundTrip("vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray()))
    }

    @Test
    fun aNodeTheCompatibilityCoreRunsIsSharedAsAClashEntry() {
        val node = ManualNodeFactory.buildNodeFromShareLink("vless://$uuid@a.example.com:443?security=tls&sni=a.example.com#x").copy(
            rawUri = "",
            outboundJson = "",
            mihomoProxyJson = """{"name":"T","type":"tuic","server":"t.example.com","port":443,"uuid":"$uuid","password":"pw"}""",
        )
        val shared = NodeShare.of(node)!!
        assertEquals(NodeShare.Kind.ClashYaml, shared.kind)
        assertTrue(shared.text.startsWith("proxies:\n  - "))
        assertTrue("t.example.com" in shared.text)
    }
}
