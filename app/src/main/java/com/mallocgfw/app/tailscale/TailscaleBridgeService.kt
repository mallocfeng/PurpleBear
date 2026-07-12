package com.mallocgfw.app.tailscale

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Runs in the dedicated :tailscale process. This is essential because libXray
 * also embeds Go and two c-shared Go runtimes cannot safely share one process.
 */
class TailscaleBridgeService : Service() {
    private val bridge = object : ITailscaleBridge.Stub() {
        override fun start(config: String): String = TailscaleNative.start(config)

        override fun status(): String = TailscaleNative.status()

        override fun stop(): String = TailscaleNative.stop()

        override fun setExitNode(nodeId: String): String = TailscaleNative.setExitNode(nodeId)

        override fun logout(stateDir: String): String = TailscaleNative.logout(stateDir)
    }

    override fun onBind(intent: Intent?): IBinder = bridge
}
