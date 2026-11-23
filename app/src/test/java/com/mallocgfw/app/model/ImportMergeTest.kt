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
    fun reorderedSubscriptionKeepsNodeIdsAndSettings() {
        val swapped = """
            proxies:
              - {name: '🇯🇵 日本 03', type: vmess, server: n3.example.com, port: 443, uuid: 0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11, alterId: 0, cipher: auto}
              - {name: '🇭🇰 香港 02', type: ss, server: n2.example.com, port: 8388, cipher: aes-128-gcm, password: pw2}
              - {name: '🇺🇸 美国 04', type: trojan, server: n4.example.com, port: 443, password: pw4, sni: n4.example.com}
        """.trimIndent()
        val before = ImportParser.buildFilePreview("Sky.yaml", clash).nodes
        val after = ImportParser.buildFilePreview("Sky.yaml", swapped).nodes
        val groupId = before.first().groupId
        assertEquals(groupId, after.first().groupId)
        val japanBefore = before.single { "03" in it.name }
        // Reproduces the report: the parsed id alone changes with the line.
        assertFalse(japanBefore.id == after.single { "03" in it.name }.id)

        val hk2 = before.single { "02" in it.name }
        val saved = before.map {
            if (it.id == japanBefore.id) it.copy(favorite = true, fallbackNodeId = hk2.id, latencyMs = 88) else it
        }
        val refreshed = com.mallocgfw.app.core.StateOps.refreshSubscriptionNodes(saved, groupId, after)
        val japan = refreshed.single { "03" in it.name }
        assertEquals(japanBefore.id, japan.id)
        assertTrue(japan.favorite)
        assertEquals(hk2.id, japan.fallbackNodeId)
        assertEquals(88, japan.latencyMs)
        assertEquals(hk2.id, refreshed.single { "02" in it.name }.id)
        // The removed node's id is not handed to the new one.
        val removed = before.single { "01" in it.name }
        assertFalse(refreshed.any { it.id == removed.id })
        assertEquals(3, refreshed.size)
        assertEquals(refreshed.size, refreshed.map { it.id }.toSet().size)
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
