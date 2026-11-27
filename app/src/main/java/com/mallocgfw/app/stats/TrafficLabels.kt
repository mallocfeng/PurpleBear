package com.mallocgfw.app.stats

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.xray.TunnelNodes
import com.mallocgfw.app.xray.XrayConfigFactory
import java.util.concurrent.ConcurrentHashMap

/** Names for the apps and routes in connection and traffic lists. */
object TrafficLabels {
    private val appKeys = ConcurrentHashMap<Int, String>()
    private val appLabels = ConcurrentHashMap<String, String>()

    /** Outbound tags of the DNS helpers in the Xray config (XrayConfigFactory). */
    private val DNS_OUTBOUNDS = setOf("remote-dns-out", "encrypted-dns-out", "dot-out", "magic-dns-out", "magic-dns-pass", "dns-out")

    /**
     * The key traffic of [uid] is filed under: the package name (the first
     * one for a shared UID), "uid:N" for system UIDs without one, or
     * [TrafficHistory.UNKNOWN_APP].
     */
    fun appKey(context: Context, uid: Int): String {
        if (uid < 0) return TrafficHistory.UNKNOWN_APP
        return appKeys.getOrPut(uid) {
            val packages = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull().orEmpty()
            when {
                uid == Process.myUid() -> context.packageName
                uid < Process.FIRST_APPLICATION_UID -> "uid:$uid"
                packages.isNotEmpty() -> packages.sorted().first()
                else -> "uid:$uid"
            }
        }
    }

    fun appLabel(context: Context, key: String): String {
        if (key == TrafficHistory.UNKNOWN_APP) return tr("未知应用", "Unknown app", "Неизвестное приложение")
        if (key.startsWith("uid:")) {
            val uid = key.removePrefix("uid:").toIntOrNull()
            return when (uid) {
                0 -> tr("系统（root）", "System (root)", "Система (root)")
                1000 -> tr("Android 系统", "Android system", "Система Android")
                1051 -> tr("系统 DNS 解析", "System DNS resolver", "Системный DNS")
                else -> tr("系统（UID $uid）", "System (UID $uid)", "Система (UID $uid)")
            }
        }
        return appLabels.getOrPut(key) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(key, PackageManager.GET_META_DATA)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: key
        }
    }

    /** True when [key] names an installed package (one with an icon to show). */
    fun isPackage(key: String): Boolean = key != TrafficHistory.UNKNOWN_APP && !key.startsWith("uid:")

    /** The node key traffic through Xray outbound [tag] is filed under. */
    fun nodeKey(tag: String): String = when {
        tag == "direct" || tag.isBlank() -> TrafficHistory.Route.DIRECT
        tag == "block" -> TrafficHistory.Route.BLOCK
        tag == XrayConfigFactory.QUIC_FALLBACK_TAG -> TrafficHistory.Route.QUIC_FALLBACK
        tag == "tailscale" -> TrafficHistory.Route.TAILSCALE
        tag in DNS_OUTBOUNDS -> TrafficHistory.Route.DNS
        else -> outboundNames()[tag] ?: tag
    }

    /**
     * The running tunnel's node names; the last ones seen once it stopped, so
     * the bytes counted while it shuts down still go to the right node.
     */
    @Volatile
    private var lastOutboundNames: Map<String, String> = emptyMap()

    private fun outboundNames(): Map<String, String> {
        val current = TunnelNodes.outboundNames()
        if (current.isEmpty()) return lastOutboundNames
        lastOutboundNames = current
        return current
    }

    /** What kind of route a node key is: proxy, direct, block, quic, dns or tailscale. */
    fun routeKind(key: String): String = when (key) {
        TrafficHistory.Route.DIRECT -> "direct"
        TrafficHistory.Route.BLOCK -> "block"
        TrafficHistory.Route.QUIC_FALLBACK -> "quic"
        TrafficHistory.Route.TAILSCALE -> "tailscale"
        TrafficHistory.Route.DNS -> "dns"
        else -> "proxy"
    }

    fun nodeLabel(key: String): String = when (key) {
        TrafficHistory.Route.DIRECT -> tr("直连", "Direct", "Напрямую")
        TrafficHistory.Route.BLOCK -> tr("拦截", "Blocked", "Заблокировано")
        TrafficHistory.Route.QUIC_FALLBACK -> tr("QUIC 转 TCP", "QUIC → TCP", "QUIC → TCP")
        TrafficHistory.Route.TAILSCALE -> "Tailscale"
        TrafficHistory.Route.DNS -> "DNS"
        else -> key
    }
}
