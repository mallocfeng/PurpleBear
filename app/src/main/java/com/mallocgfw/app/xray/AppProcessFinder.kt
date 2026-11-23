package com.mallocgfw.app.xray

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetSocketAddress
import libXray.LibXray
import libXray.ProcessFinder

/**
 * Tells Xray which app owns a connection, for per-app nodes. Android only
 * answers this for the active VPN app (API 29+); libXray reads /proc itself on
 * older versions.
 */
object AppProcessFinder {
    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val finder = object : ProcessFinder {
            override fun findProcessByConnection(
                network: String,
                srcIP: String,
                srcPort: Long,
                destIP: String,
                destPort: Long,
            ): Long {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || connectivity == null) return -1
                val protocol = if (network.startsWith("udp")) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                return runCatching {
                    connectivity.getConnectionOwnerUid(
                        protocol,
                        InetSocketAddress(srcIP, srcPort.toInt()),
                        InetSocketAddress(destIP, destPort.toInt()),
                    ).toLong()
                }.getOrDefault(-1L)
            }
        }
        runCatching { LibXray.registerProcessFinder(finder, Build.VERSION.SDK_INT.toLong()) }
            .onSuccess { registered = true }
    }
}
