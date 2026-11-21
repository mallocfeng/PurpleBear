package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeFlagsTest {
    private val hk = "🇭🇰"
    private val jp = "🇯🇵"
    private val us = "🇺🇸"

    private fun flag(name: String) = NodeFlags.flagFor(name)

    @Test
    fun flagEmojiAnywhereInTheName() {
        assertEquals(hk, flag("🇭🇰 香港 01"))
        assertEquals(jp, flag("Aurora | 🇯🇵 东京 IPLC"))
    }

    @Test
    fun chineseCountryAndCityNames() {
        assertEquals(hk, flag("香港 01"))
        assertEquals(jp, flag("日本 | 大阪 02"))
        assertEquals(jp, flag("东京 专线"))
        assertEquals(us, flag("美西 洛杉矶 x2"))
        assertEquals("🇹🇼", flag("台灣 HiNet"))
        assertEquals("🇸🇬", flag("狮城 01"))
        assertEquals("🇬🇧", flag("英国 伦敦"))
        // Longest name wins: Indonesia, not India.
        assertEquals("🇮🇩", flag("印度尼西亚 01"))
        assertEquals("🇮🇳", flag("印度 孟买"))
    }

    @Test
    fun englishNamesAndCodes() {
        assertEquals(jp, flag("Tokyo Premium"))
        assertEquals("🇸🇬", flag("Singapore 03"))
        assertEquals(hk, flag("HK01 IPLC"))
        assertEquals(hk, flag("hk-02"))
        assertEquals(us, flag("US-SJC 1"))
        assertEquals(us, flag("USA West"))
        assertEquals("🇬🇧", flag("UK London"))
        assertEquals("🇩🇪", flag("DE Frankfurt"))
    }

    @Test
    fun exitCountryBeatsTheMainlandEntryCity() {
        assertEquals(hk, flag("上海→香港 IPLC"))
        assertEquals("🇨🇳", flag("回国 上海"))
    }

    @Test
    fun unrecognisedNamesKeepTheInitial() {
        assertNull(flag("Plus 套餐 剩余流量"))
        assertNull(flag("Netflix 专线"))
        assertNull(flag("Node NO.1"))
        assertNull(flag("E01-Reality-Vision"))
        assertNull(flag("P2-Exit-Trojan-insecure"))
        assertNull(flag("默认节点"))
    }
}
