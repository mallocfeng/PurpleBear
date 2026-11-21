package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Applying an import must keep every node of a config-file subscription. */
class ImportMergeTest {
    private val clash = """
        proxies:
          - {name: '🇭🇰 香港 01', type: trojan, server: n1.example.com, port: 443, password: pw1, sni: n1.example.com}
          - {name: '🇭🇰 香港 02', type: ss, server: n2.example.com, port: 8388, cipher: aes-128-gcm, password: pw2}
          - {name: '🇯🇵 日本 03', type: vmess, server: n3.example.com, port: 443, uuid: 0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11, alterId: 0, cipher: auto}
    """.trimIndent()

    @Test
    fun clashNodesWithoutShareLinksAreNotMergedIntoOne() {
        val nodes = ImportParser.buildFilePreview("Sky.yaml", clash).nodes
        assertEquals(3, nodes.size)
        assertTrue("config-file nodes carry no share link", nodes.all { it.rawUri.isBlank() })
        val groupId = nodes.first().groupId
        // What applyImport does for a subscription group.
        val merged = nodes.mergedSubscriptionGroupNodes(groupId)
        assertEquals(nodes.map { it.name }, merged.map { it.name })
        assertFalse(nodes[0].isSameImportedNode(nodes[1]))
    }

    @Test
    fun theSameNodeImportedTwiceIsStillMerged() {
        val nodes = ImportParser.buildFilePreview("Sky.yaml", clash).nodes
        val again = ImportParser.buildFilePreview("Sky.yaml", clash).nodes
        val merged = (nodes + again).mergedSubscriptionGroupNodes(nodes.first().groupId)
        assertEquals(3, merged.size)
        assertTrue(nodes[0].isSameImportedNode(again[0]))
    }

    @Test
    fun shareLinkNodesStillMatchByLink() {
        val link = "trojan://pw@a.example.com:443?security=tls&sni=a.example.com#A"
        val a = ManualNodeFactory.buildNodeFromShareLink(link).copy(groupId = "g", rawUri = link)
        val renamed = a.copy(name = "A (renamed)")
        assertTrue(a.isSameImportedNode(renamed))
    }
}
