package com.mallocgfw.app.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedAppsTest {
    private val apps = FakeRepository.protectedApps

    @Test
    fun packageIdsAreUnique() {
        assertEquals(apps.size, apps.map { it.id }.toSet().size)
    }

    @Test
    fun everyAppHasAKnownCategory() {
        apps.forEach { assertTrue("${it.id}: ${it.category}", it.category in FakeRepository.protectedAppCategories) }
        FakeRepository.protectedAppCategories.forEach { category ->
            assertTrue("$category is empty", apps.any { it.category == category })
        }
    }

    @Test
    fun everyPresetIsDeclaredInManifestQueries() {
        // Without <queries>, Android 11+ hides these packages from the app.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        apps.forEach { app ->
            assertTrue("${app.id} missing from <queries>", manifest.contains("<package android:name=\"${app.id}\" />"))
        }
    }

    @Test
    fun threadsUsesItsRealPackage() {
        val threads = apps.single { it.name == "Threads" }
        assertEquals("com.instagram.barcelona", threads.id)
    }
}
