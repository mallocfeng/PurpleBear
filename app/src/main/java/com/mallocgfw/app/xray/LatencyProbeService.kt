package com.mallocgfw.app.xray

import android.app.Service
import android.content.Intent
import android.os.IBinder
import libXray.LibXray

/**
 * Runs in the :probe process. libXray's batch ping needs a process with no
 * managed Xray instance, and the main process has the VPN's. Being the same
 * app, this process is outside the VPN too (the app excludes itself), so a
 * probe dials the node over the real network whether or not the VPN is on.
 *
 * Nothing else lives here: no state, no logs. The process goes away when the
 * client unbinds.
 */
class LatencyProbeService : Service() {
    private val binder = object : ILatencyProbe.Stub() {
        override fun invoke(requestJson: String): String {
            XrayNativeLoader.ensureLoaded()
            return LibXray.invoke(requestJson)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
