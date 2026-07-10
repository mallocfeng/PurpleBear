package com.mallocgfw.app.model

import org.junit.Assert.assertTrue
import org.junit.Test

class AppDefaultsTest {
    @Test
    fun protectedAppsIncludeGooglePlayDownloadComponents() {
        val enabledPackageIds = FakeRepository.protectedApps
            .filter { it.enabled }
            .map { it.id }
            .toSet()

        assertTrue(enabledPackageIds.contains("com.android.vending"))
        assertTrue(enabledPackageIds.contains("com.google.android.gms"))
        assertTrue(enabledPackageIds.contains("com.google.android.gsf"))
        assertTrue(enabledPackageIds.contains("com.android.providers.downloads"))
    }
}
