package com.mallocgfw.app.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProfileTest {
    @Test
    fun theProviderNameComesFromTheTitleOrTheFileName() {
        assertEquals("机场", ProviderProfile.parse(mapOf("content-disposition" to "attachment; filename*=UTF-8''%E6%9C%BA%E5%9C%BA"))?.title)
        assertEquals("Fast Air", ProviderProfile.parse(mapOf("content-disposition" to "attachment; filename=\"Fast Air.yaml\""))?.title)
        val b64 = Base64.getEncoder().encodeToString("紫熊云".toByteArray())
        val both = mapOf("profile-title" to "base64:$b64", "content-disposition" to "attachment; filename=Other")
        assertEquals("紫熊云", ProviderProfile.parse(both)?.title)
        // Placeholders some panels send are no name.
        assertNull(ProviderProfile.parse(mapOf("content-disposition" to "attachment; filename=clash.yaml")))
    }

    @Test
    fun onlyAWebAddressIsTakenAsTheWebsite() {
        assertEquals("https://air.example.com/user", ProviderProfile.parse(mapOf("profile-web-page-url" to "https://air.example.com/user"))?.webPageUrl)
        assertNull(ProviderProfile.parse(mapOf("profile-web-page-url" to "javascript:alert(1)")))
        assertNull(ProviderProfile.parse(emptyMap()))
    }
}
