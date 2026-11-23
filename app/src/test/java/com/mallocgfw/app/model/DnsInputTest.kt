package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsInputTest {
    @Test
    fun acceptsIpsWithOptionalPort() {
        listOf("8.8.8.8", " 223.5.5.5 ", "1.1.1.1:53", "2001:4860:4860::8888", "[2001:4860:4860::8888]:53", "::1", "[::1]")
            .forEach { assertTrue(it, DnsInput.isValid(it)) }
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "", "dns.google", "https://dns.google/dns-query", "8.8.8", "256.1.1.1", "8.8.8.8:", "8.8.8.8:70000", "08.8.8.8", "[2001::1]:x", "1:2:3",
            // "::" with eight groups, lone colons at the ends, other ports.
            "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8", ":1::2", "1::2:", "1:2:3:4:5:6:7:8:9", "12345::1",
            "8.8.8.8:5353", "[2001:4860:4860::8888]:5353", "[2001::1",
        )
            .forEach { assertFalse(it, DnsInput.isValid(it)) }
    }

    @Test
    fun hostIsWhatAndroidNeeds() {
        assertEquals("8.8.8.8", DnsInput.hostOf("8.8.8.8:53"))
        assertEquals("223.5.5.5", DnsInput.hostOf(" 223.5.5.5 "))
        assertEquals("2001:4860:4860::8888", DnsInput.hostOf("2001:4860:4860::8888"))
        assertEquals("2001:4860:4860::8888", DnsInput.hostOf("[2001:4860:4860::8888]:53"))
        assertNull(DnsInput.hostOf("https://dns.google/dns-query"))
        assertNull(DnsInput.hostOf(""))
        assertNull(DnsInput.hostOf("1:2:3:4:5:6:7:8::"))
        // Values saved by older versions with another port keep their IP.
        assertEquals("8.8.8.8", DnsInput.hostOf("8.8.8.8:5353"))
        // Every accepted address parses as a real IP literal.
        listOf("2001:4860:4860::8888", "::1", "1:2:3:4:5:6:7::", "::", "1:2:3:4:5:6:7:8").forEach {
            assertTrue(it, DnsInput.isValid(it))
            java.net.InetAddress.getByName(it)
        }
    }

    @Test
    fun presetsAreValid() {
        DnsInput.presets.forEach { assertTrue(it.address, DnsInput.isValid(it.address)) }
    }
}
