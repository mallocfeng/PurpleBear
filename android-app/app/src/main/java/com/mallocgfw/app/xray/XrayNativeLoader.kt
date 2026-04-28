package com.mallocgfw.app.xray

object XrayNativeLoader {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("gojni")
            loaded = true
        }
    }
}
