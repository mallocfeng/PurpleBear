package com.mallocgfw.app.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLinksTest {
    private val sub = "https://panel.example.com/api/v1/client/subscribe?token=abc+1"
    private val encoded = java.net.URLEncoder.encode(sub, "UTF-8")

    @Test
    fun theCommonImportButtonsAreUnderstood() {
        assertEquals(ImportLinks.Request(sub, "Fast Air"), ImportLinks.parse("clash://install-config?url=$encoded&name=Fast%20Air"))
        assertEquals(ImportLinks.Request(sub, null), ImportLinks.parse("clashmeta://install-config?url=$encoded"))
        assertEquals(ImportLinks.Request(sub, "机场"), ImportLinks.parse("v2rayng://install-sub?url=$encoded&name=%E6%9C%BA%E5%9C%BA"))
        assertEquals(ImportLinks.Request(sub, "Air"), ImportLinks.parse("sing-box://import-remote-profile?url=$encoded#Air"))
        // Hiddify puts the URL, its own query included, after import/.
        assertEquals(ImportLinks.Request(sub, "Air"), ImportLinks.parse("hiddify://import/$sub#Air"))
        val b64 = Base64.getEncoder().encodeToString(sub.toByteArray())
        assertEquals(ImportLinks.Request(sub, "Air"), ImportLinks.parse("shadowrocket://add/sub://$b64?remarks=Air"))
        // A node link works too.
        val node = "vless://id@a.example.com:443?security=tls#A"
        assertEquals(node, ImportLinks.parse("v2rayng://install-config?url=${java.net.URLEncoder.encode(node, "UTF-8")}")?.url)
    }

    @Test
    fun linksToAnythingButASubscriptionOrNodeAreRefused() {
        assertNull(ImportLinks.parse("clash://install-config?url=file%3A%2F%2F%2Fsdcard%2Fx.yaml"))
        assertNull(ImportLinks.parse("clash://install-config?url=intent%3A%2F%2Fx"))
        assertNull(ImportLinks.parse("clash://install-config"))
        assertNull(ImportLinks.parse("myapp://install-config?url=$encoded"))
        assertNull(ImportLinks.parse("https://panel.example.com/"))
    }
}
