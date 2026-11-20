package com.mallocgfw.app.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.net.InetAddress
import org.junit.Test

class TailscaleSubnetRoutesTest {
    @Test
    fun privateIpv4SubnetsAreCanonicalised() {
        assertEquals("192.168.1.0/24", normalizePrivateTailscaleSubnet(" 192.168.1.77/24 "))
        assertEquals("10.0.0.0/8", normalizePrivateTailscaleSubnet("10.1.2.3/8"))
        assertEquals("172.20.0.0/14", normalizePrivateTailscaleSubnet("172.22.9.9/14"))
    }

    @Test
    fun publicOrTooBroadRangesAreRejected() {
        assertNull(normalizePrivateTailscaleSubnet("8.8.8.0/24"))
        assertNull(normalizePrivateTailscaleSubnet("172.0.0.0/8"))
        assertNull(normalizePrivateTailscaleSubnet("192.168.0.0/33"))
        assertNull(normalizePrivateTailscaleSubnet("not-a-cidr"))
    }

    @Test
    fun ipv6UniqueLocalIsAllowed() {
        val prefix = parseTailscaleSubnetPrefix("fd12:3456::1/64")!!
        assertEquals(64, prefix.prefixLength)
        assertTrue(prefix.contains(InetAddress.getByName("fd12:3456::abcd")))
        assertTrue(normalizePrivateTailscaleSubnet("fd12:3456::1/64") != null)
    }

    @Test
    fun prefixesCompareByMaskedAddress() {
        assertEquals(
            parseTailscaleSubnetPrefix("192.168.0.0/16"),
            SubnetPrefix.of(InetAddress.getByName("192.168.44.1"), 16),
        )
    }
}
