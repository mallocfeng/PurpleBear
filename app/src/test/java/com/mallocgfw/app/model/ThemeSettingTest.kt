package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSettingTest {
    private fun state(settings: AppSettings) = PersistedAppState(
        serverGroups = emptyList(),
        servers = emptyList(),
        subscriptions = emptyList(),
        ruleSources = emptyList(),
        recentImports = emptyList(),
        protectedApps = emptyList(),
        selectedServerId = "",
        proxyMode = ProxyMode.Smart,
        settings = settings,
    )

    private fun roundTrip(settings: AppSettings) =
        AppStateStore.parseState(AppStateStore.serializeState(state(settings))).settings

    @Test
    fun aNewInstallFollowsTheSystemUntilAThemeIsPicked() {
        assertEquals(true, AppStateStore.defaultSettings().followSystemTheme)
        assertEquals(true, roundTrip(AppStateStore.defaultSettings()).followSystemTheme)
        // Picked with the toggle: kept as picked.
        val picked = roundTrip(AppStateStore.defaultSettings().copy(lightThemeEnabled = true, followSystemTheme = false))
        assertEquals(false, picked.followSystemTheme)
        assertEquals(true, picked.lightThemeEnabled)
    }

    @Test
    fun settingsSavedBeforeFollowingTheSystemKeepTheirTheme() {
        val json = AppStateStore.serializeState(state(AppStateStore.defaultSettings()))
        json.getJSONObject("settings").remove("followSystemTheme")
        val loaded = AppStateStore.parseState(json).settings
        assertEquals(false, loaded.followSystemTheme)
        assertEquals(false, loaded.lightThemeEnabled)
    }

    @Test
    fun bypassAppsSurviveSavingAndOlderSettingsHaveNone() {
        val saved = roundTrip(AppStateStore.defaultSettings().copy(bypassAppsEnabled = true, bypassApps = listOf("com.bank", "com.game")))
        assertEquals(true, saved.bypassAppsEnabled)
        assertEquals(listOf("com.bank", "com.game"), saved.bypassApps)
        val json = AppStateStore.serializeState(state(AppStateStore.defaultSettings()))
        json.getJSONObject("settings").apply { remove("bypassAppsEnabled"); remove("bypassApps"); remove("subscriptionReminders") }
        val old = AppStateStore.parseState(json).settings
        assertEquals(false, old.bypassAppsEnabled)
        assertEquals(emptyList<String>(), old.bypassApps)
        assertEquals(true, old.subscriptionReminders)
    }
}
