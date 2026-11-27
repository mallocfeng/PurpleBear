package com.mallocgfw.app.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiRulesTest {
    private val home = listOf("HomeNet", "HomeNet-5G")

    @Test
    fun cleansAndroidsSsidForms() {
        assertEquals("HomeNet", WifiRules.cleanSsid("\"HomeNet\""))
        assertEquals("Café 5G", WifiRules.cleanSsid(" \"Café 5G\" "))
        assertNull(WifiRules.cleanSsid("<unknown ssid>"))
        assertNull(WifiRules.cleanSsid("\"\""))
        assertNull(WifiRules.cleanSsid(null))
    }

    @Test
    fun zoneFollowsTheWifiName() {
        assertEquals(WifiZone.Home, WifiRules.zone(listOf("HomeNet"), online = true, homeNetworks = home))
        assertEquals(WifiZone.Away, WifiRules.zone(listOf("Starbucks"), online = true, homeNetworks = home))
        // Mobile data only.
        assertEquals(WifiZone.Away, WifiRules.zone(emptyList(), online = true, homeNetworks = home))
        assertEquals(WifiZone.Offline, WifiRules.zone(emptyList(), online = false, homeNetworks = home))
        // A Wi-Fi whose name can't be read: don't guess.
        assertEquals(WifiZone.Unknown, WifiRules.zone(listOf(null), online = true, homeNetworks = home))
        // Names are matched exactly.
        assertEquals(WifiZone.Away, WifiRules.zone(listOf("homenet"), online = true, homeNetworks = home))
    }

    @Test
    fun actsOnlyWhenArrivingOrLeaving() {
        // Arriving home with the proxy on turns it off; leaving with it off turns it on.
        assertEquals(WifiAction.Disconnect, WifiRules.decide(WifiZone.Away, WifiZone.Home, vpnOn = true))
        assertEquals(WifiAction.Connect, WifiRules.decide(WifiZone.Home, WifiZone.Away, vpnOn = false))
        // First look after turning the rule on.
        assertEquals(WifiAction.Connect, WifiRules.decide(null, WifiZone.Away, vpnOn = false))
        assertEquals(WifiAction.Disconnect, WifiRules.decide(null, WifiZone.Home, vpnOn = true))
        // Turned on by hand at home / off by hand outside: left alone.
        assertEquals(WifiAction.None, WifiRules.decide(WifiZone.Home, WifiZone.Home, vpnOn = true))
        assertEquals(WifiAction.None, WifiRules.decide(WifiZone.Away, WifiZone.Away, vpnOn = false))
        // Already in the wanted state.
        assertEquals(WifiAction.None, WifiRules.decide(WifiZone.Away, WifiZone.Home, vpnOn = false))
        // Unclear answers wait.
        assertEquals(WifiAction.None, WifiRules.decide(WifiZone.Home, WifiZone.Unknown, vpnOn = false))
        assertEquals(WifiAction.None, WifiRules.decide(WifiZone.Home, WifiZone.Offline, vpnOn = false))
    }
}
