package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsInputTest {
    @Test
    fun foreignPublicResolversGoThroughTheNodeOnlyFromChina() {
        listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "2001:4860:4860::8888").forEach {
            assertTrue(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.China))
            assertFalse(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.Russia))
            assertFalse(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.English))
        }
        listOf("223.5.5.5", "119.29.29.29", "114.114.114.114", "192.168.0.1", "10.0.0.1").forEach {
            assertFalse(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.China))
        }
    }

    @Test
    fun foreignEncryptedServersAreKnownByHostName() {
        listOf("dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.adguard-dns.com", "abc123.dns.nextdns.io").forEach {
            assertTrue(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.China))
        }
        listOf("dns.alidns.com", "doh.pub", "dot.pub").forEach {
            assertFalse(it, DnsInput.needsProxy(it, AppGeoRoutingRegion.China))
        }
        assertTrue(DnsInput.isUnreachableDoq("quic://dns.adguard-dns.com", AppGeoRoutingRegion.China))
        assertFalse(DnsInput.isUnreachableDoq("quic://dns.alidns.com", AppGeoRoutingRegion.China))
        assertFalse(DnsInput.isUnreachableDoq("https://dns.google/dns-query", AppGeoRoutingRegion.China))
        assertFalse(DnsInput.isUnreachableDoq("quic://dns.adguard-dns.com", AppGeoRoutingRegion.Russia))
    }

    @Test
    fun acceptsIpsWithOptionalPort() {
        listOf("8.8.8.8", " 223.5.5.5 ", "1.1.1.1:53", "2001:4860:4860::8888", "[2001:4860:4860::8888]:53", "192.168.1.1", "[fd00::1]")
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
        listOf("2001:4860:4860::8888", "1:2:3:4:5:6:7::", "1:2:3:4:5:6:7:8").forEach {
            assertTrue(it, DnsInput.isValid(it))
            java.net.InetAddress.getByName(it)
        }
    }

    @Test
    fun addressesAndroidRefusesAreRejected() {
        // VpnService.Builder.addDnsServer throws for loopback and unspecified addresses.
        listOf("127.0.0.1", "127.1.2.3", "127.0.0.1:53", "0.0.0.0", "::1", "[::1]", "[::1]:53", "::", "[::]", "::ffff:7f00:1", "::ffff:0:0")
            .forEach {
                assertFalse(it, DnsInput.isValid(it))
                assertTrue(it, DnsInput.isRefusedByAndroid(it))
                // A value saved by an older version falls back to the system DNS.
                assertNull(it, DnsInput.hostOf(it))
            }
    }

    @Test
    fun fullWidthPunctuationFromChineseInputMethodsBecomesAscii() {
        assertEquals("127.0.0.1", DnsInput.toHalfWidth("127。0。0。1"))
        assertEquals("tls://dns.google", DnsInput.toHalfWidth("tls：／／dns．google"))
        assertEquals("8.8.8.8", DnsInput.normalize("\u3000８。８。８。８ "))
        assertEquals("abc", DnsInput.toHalfWidth("abc"))
        assertEquals("223.5.5.5", DnsInput.hostOf("223。5。5。5"))
        assertTrue(DnsInput.isValid("223。5。5。5"))
        assertTrue(DnsInput.isRefusedByAndroid("127。0。0。1"))
        assertEquals("dns.google", DnsInput.encryptedOf("tls：//dns。google")!!.host)
    }

    @Test
    fun validDohUrlsWithEscapesAndQueriesStillParse() {
        listOf("https://dns.example.com/dns%2Dquery", "https://dns.example.com/dns-query?ct=application%2Fdns-message", "https://[2001:db8::1]/dns-query")
            .forEach { assertTrue(it, DnsInput.isValid(it)) }
        // An invalid DoH URL saved by an older version: no encrypted server and
        // no IP, so the VPN uses the system DNS (XrayVpnService.usesSystemDns).
        val old = "https://example.com/dns-query%"
        assertNull(DnsInput.encryptedOf(old))
        assertNull(DnsInput.hostOf(old))
    }

    @Test
    fun onlyRealIpsCountAsRefused() {
        listOf("8.8.8.8", "dns.google", "tls://127.0.0.1", "8.8.8", "").forEach { assertFalse(it, DnsInput.isRefusedByAndroid(it)) }
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
            // Bad escapes, which Xray's URL parser refuses (the core wouldn't start),
            // and characters that aren't valid in a URI.
            "https://example.com/dns-query%", "https://example.com/dns%2", "https://example.com/dns%zzquery",
            "https://example.com/a|b", "https://example.com/a\"b",
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
