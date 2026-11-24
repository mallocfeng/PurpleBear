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
            "", "dns.google", "8.8.8", "256.1.1.1", "8.8.8.8:", "8.8.8.8:70000", "08.8.8.8", "[2001::1]:x", "1:2:3",
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
    fun encryptedServersParse() {
        val doh = DnsInput.encryptedOf("https://dns.alidns.com/dns-query")!!
        assertEquals(DnsInput.EncryptedDns.Kind.DoH, doh.kind)
        assertEquals("https://dns.alidns.com:443/dns-query", doh.xrayServer)
        assertEquals("https://dns.google:443/dns-query", DnsInput.encryptedOf("https://dns.google")!!.xrayServer)
        assertEquals("https://1.1.1.1:8443/dns-query", DnsInput.encryptedOf("https://1.1.1.1:8443/dns-query")!!.xrayServer)
        val dot = DnsInput.encryptedOf(" tls://dns.google ")!!
        assertEquals(DnsInput.EncryptedDns.Kind.DoT, dot.kind)
        assertEquals("tcp://dns.google:853", dot.xrayServer)
        assertEquals("dns.google", dot.host)
        assertEquals("quic+local://dns.adguard-dns.com:853", DnsInput.encryptedOf("quic://dns.adguard-dns.com")!!.xrayServer)
        assertEquals("tcp://[2001:4860:4860::8888]:853", DnsInput.encryptedOf("tls://[2001:4860:4860::8888]")!!.xrayServer)
        listOf("https://dns.alidns.com/dns-query", "tls://dot.pub:853", "quic://dns.adguard-dns.com").forEach {
            assertTrue(it, DnsInput.isValid(it))
            assertNull("encrypted isn't a bare IP for Android", DnsInput.hostOf(it))
        }
    }

    @Test
    fun badEncryptedServersAreRejected() {
        listOf(
            "http://dns.google/dns-query", "tls://", "tls://dns.google/path", "quic://dns..google",
            "https://user@dns.google/dns-query", "tls://dns.google:99999", "tls://-bad.example.com",
            "https://dns google/dns-query", "tls://1.2.3", "doh://dns.google",
        ).forEach {
            assertNull(it, DnsInput.encryptedOf(it))
            assertFalse(it, DnsInput.isValid(it))
        }
    }

    @Test
    fun presetsAreValid() {
        DnsInput.presets.forEach { assertTrue(it.address, DnsInput.isValid(it.address)) }
        DnsInput.encryptedPresets.forEach { assertTrue(it.address, DnsInput.encryptedOf(it.address) != null) }
    }
}
