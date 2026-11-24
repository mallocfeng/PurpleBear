package com.mallocgfw.app.xray

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ServerNode
import java.net.Socket

object VpnServiceController {
    fun isRunning(): Boolean {
        if (XrayVpnService.isRunning()) return true
        return VpnRuntimeStore.snapshot.value.status != ConnectionStatus.Disconnected
    }

    fun protectSocket(socket: Socket): Boolean {
        return if (XrayVpnService.isRunning()) XrayVpnService.protectSocket(socket) else false
    }

    fun isActuallyRunning(context: Context): Boolean {
        if (XrayVpnService.isRunning()) return true
        val systemVpnActive = runCatching {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrDefault(false)
        return systemVpnActive
    }

    fun start(context: Context, server: ServerNode) {
        ContextCompat.startForegroundService(
            context,
            connectIntent(context, server),
        )
    }

    fun connectIntent(context: Context, server: ServerNode): Intent {
        return XrayVpnService.connectIntent(context, server.id)
    }

    /** The user turning the VPN off; refused in lockdown mode (see [manualDisconnectBlocked]). */
    fun disconnect(context: Context) {
        context.startService(XrayVpnService.disconnectIntent(context))
    }

    /** The first half of the app's own reconnect (disconnect, then connect). */
    fun disconnectForReconnect(context: Context) {
        context.startService(XrayVpnService.disconnectIntent(context, internal = true))
    }

    fun manualDisconnectBlocked(): Boolean = XrayVpnService.manualDisconnectBlocked()

    fun lockdownDisconnectMessage(): String = XrayVpnService.lockdownDisconnectMessage()

    fun refreshSpeedMeasurement(context: Context) {
        context.startService(XrayVpnService.refreshSpeedIntent(context))
    }
}
