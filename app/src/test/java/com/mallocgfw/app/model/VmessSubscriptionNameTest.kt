package com.mallocgfw.app.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class VmessSubscriptionNameTest {
    private fun vmess(json: String) = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())

    @Test
    fun subscriptionVmessNodesKeepTheirJsonName() {
        val plain = vmess("""{"v":"2","ps":"日本 大阪 02","add":"jp.example.com","port":"443","id":"0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11","net":"ws","path":"/ws"}""")
        val escaped = vmess("""{"v":"2","ps":"英国 伦敦","add":"uk.example.com","port":"443","id":"0b4f8e8c-4c8a-4c34-9d36-0d2a5c1d7a11","net":"tcp"}""")
        val content = Base64.getEncoder().encodeToString("$plain\n$escaped".toByteArray())

        val names = ImportParser.buildFilePreview(fileName = "sub.txt", content = content)
            .nodes.map { it.name }

        assertEquals(listOf("日本 大阪 02", "英国 伦敦"), names)
    }
}
