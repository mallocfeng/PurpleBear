package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUsageTest {
    @Test
    fun readsTheProvidersHeader() {
        val usage = SubscriptionUsage.parse("upload=1073741824; download=2147483648; total=107374182400; expire=1798675200")!!
        assertEquals(3_221_225_472L, usage.usedBytes)
        assertEquals(107_374_182_400L, usage.limitBytes)
        assertEquals(1_798_675_200L, usage.expiresAtSec)
        assertFalse(usage.isUsedUp())
        assertFalse(usage.isExpired(nowMs = 1_790_000_000_000L))
        assertTrue(usage.isExpired(nowMs = 1_800_000_000_000L))
    }

    @Test
    fun missingAndZeroFieldsMeanNotReportedOrUnlimited() {
        // No limit, no expiry: panels send 0.
        val unlimited = SubscriptionUsage.parse("upload=10;download=20;total=0;expire=0")!!
        assertEquals(30L, unlimited.usedBytes)
        assertNull(unlimited.limitBytes)
        assertNull(unlimited.expiresAtSec)
        assertFalse(unlimited.isUsedUp())
        assertFalse(unlimited.isExpired(nowMs = Long.MAX_VALUE))
        // Only an expiry.
        val expiryOnly = SubscriptionUsage.parse("expire=1798675200")!!
        assertNull(expiryOnly.usedBytes)
        // Floats and odd spacing.
        assertEquals(12_000_000_000L, SubscriptionUsage.parse(" Upload = 1.2E10 ; total=5e10")!!.uploadBytes)
        // Nothing usable.
        assertNull(SubscriptionUsage.parse(null))
        assertNull(SubscriptionUsage.parse(""))
        assertNull(SubscriptionUsage.parse("hello"))
        assertNull(SubscriptionUsage.parse("upload=abc"))
    }

    @Test
    fun usedUpWhenAtOrOverTheAllowance() {
        assertTrue(SubscriptionUsage.parse("upload=60;download=40;total=100")!!.isUsedUp())
    }
}
