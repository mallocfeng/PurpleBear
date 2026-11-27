package com.mallocgfw.app.sync

import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.model.SubscriptionUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRemindersTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val gb = 1L shl 30

    private fun sub(id: String, usage: SubscriptionUsage?) =
        SubscriptionItem(id, id, "订阅", 1, "刚刚", nextSync = "手动", status = "已同步", autoUpdate = true, usage = usage)

    private fun keys(vararg subs: SubscriptionItem) = SubscriptionReminders.due(subs.toList(), now).associate { it.subscriptionId to it.keys }

    @Test
    fun expiryWarnsInTheLastThreeDaysAndOnceExpired() {
        val due = keys(
            sub("far", SubscriptionUsage(expireAtSec = (now + 10 * day) / 1000)),
            sub("soon", SubscriptionUsage(expireAtSec = (now + 2 * day) / 1000)),
            sub("over", SubscriptionUsage(expireAtSec = (now - day) / 1000)),
            sub("none", null),
        )
        assertEquals(setOf("soon", "over"), due.keys)
        assertTrue(due.getValue("soon").single().startsWith("expiring|"))
        assertTrue(due.getValue("over").single().startsWith("expired|"))
    }

    @Test
    fun trafficWarnsFromNinetyPercentAndWhenUsedUp() {
        val due = keys(
            sub("ok", SubscriptionUsage(uploadBytes = 0, downloadBytes = 50 * gb, totalBytes = 100 * gb)),
            sub("high", SubscriptionUsage(uploadBytes = 1 * gb, downloadBytes = 90 * gb, totalBytes = 100 * gb)),
            sub("out", SubscriptionUsage(uploadBytes = 0, downloadBytes = 100 * gb, totalBytes = 100 * gb)),
            sub("unlimited", SubscriptionUsage(uploadBytes = 0, downloadBytes = 500 * gb, totalBytes = 0)),
        )
        assertEquals(setOf("high", "out"), due.keys)
        assertTrue(due.getValue("high").single().startsWith("traffic|"))
        assertTrue(due.getValue("out").single().startsWith("usedup|"))
        // A renewed plan (new allowance) is a new warning.
        val renewed = keys(sub("high", SubscriptionUsage(uploadBytes = 1 * gb, downloadBytes = 190 * gb, totalBytes = 200 * gb)))
        assertTrue(renewed.getValue("high").single() != due.getValue("high").single())
    }
}
