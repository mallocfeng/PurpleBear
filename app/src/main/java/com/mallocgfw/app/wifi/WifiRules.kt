package com.mallocgfw.app.wifi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Where the phone is, as the Wi-Fi rule sees it. */
enum class WifiZone {
    /** On one of the home Wi-Fi networks: the proxy goes off. */
    Home,

    /** On another Wi-Fi or mobile data: the proxy goes on. */
    Away,

    /** On a Wi-Fi whose name can't be read (no location permission, location off). */
    Unknown,

    /** No network at all. */
    Offline,
}

enum class WifiAction { None, Connect, Disconnect }

data class WifiRulesConfig(
    val enabled: Boolean = false,
    /** Wi-Fi names (SSIDs) where the proxy goes off: home networks behind a router that already proxies. */
    val homeNetworks: List<String> = emptyList(),
)

/**
 * "Auto by Wi-Fi": the proxy turns off on the home Wi-Fi networks and back on
 * elsewhere. Kept apart from the main app state, like the MCP and panel
 * settings, so the background watcher can read it cheaply.
 */
object WifiRules {
    private const val PREFS = "wifi_rules"
    private const val KEY = "config_v1"
    private const val KEY_LAST_ZONE = "last_zone"
    const val MAX_NETWORKS = 32

    @Synchronized
    fun load(context: Context): WifiRulesConfig {
        val raw = prefs(context).getString(KEY, null) ?: return WifiRulesConfig()
        return runCatching {
            val json = JSONObject(raw)
            val list = json.optJSONArray("homeNetworks") ?: JSONArray()
            WifiRulesConfig(
                enabled = json.optBoolean("enabled"),
                homeNetworks = List(list.length()) { list.optString(it) }.mapNotNull(::cleanSsid).distinct().take(MAX_NETWORKS),
            )
        }.getOrDefault(WifiRulesConfig())
    }

    @Synchronized
    fun save(context: Context, config: WifiRulesConfig) {
        prefs(context).edit {
            putString(
                KEY,
                JSONObject()
                    .put("enabled", config.enabled)
                    .put("homeNetworks", JSONArray(config.homeNetworks))
                    .toString(),
            )
        }
    }

    @Synchronized
    fun update(context: Context, change: (WifiRulesConfig) -> WifiRulesConfig): WifiRulesConfig {
        val next = change(load(context))
        save(context, next)
        return next
    }

    /** The last zone the rule acted on (Home or Away); null: act on the next one seen. */
    fun lastZone(context: Context): WifiZone? =
        prefs(context).getString(KEY_LAST_ZONE, null)?.let { name -> WifiZone.entries.firstOrNull { it.name == name } }

    fun setLastZone(context: Context, zone: WifiZone?) {
        prefs(context).edit { if (zone == null) remove(KEY_LAST_ZONE) else putString(KEY_LAST_ZONE, zone.name) }
    }

    /**
     * A Wi-Fi name as the user means it: Android wraps readable names in
     * quotes and reports "<unknown ssid>" when it won't say. Null when there
     * is no usable name.
     */
    fun cleanSsid(raw: String?): String? {
        var name = raw?.trim() ?: return null
        if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name = name.substring(1, name.length - 1)
        if (name.isEmpty() || name.equals("<unknown ssid>", ignoreCase = true) || name == "0x") return null
        return name.take(64)
    }

    /**
     * Which zone the phone is in. [wifiNames]: one entry per connected Wi-Fi
     * network, null when its name can't be read. [online]: any network at all
     * (Wi-Fi, mobile data, Ethernet), not counting VPNs.
     */
    fun zone(wifiNames: List<String?>, online: Boolean, homeNetworks: List<String>): WifiZone = when {
        wifiNames.any { it != null && it in homeNetworks } -> WifiZone.Home
        wifiNames.any { it == null } -> WifiZone.Unknown
        online || wifiNames.isNotEmpty() -> WifiZone.Away
        else -> WifiZone.Offline
    }

    /**
     * What to do on reaching [now]. Only a move between home and away acts, so
     * turning the proxy on at home by hand, or off outside, sticks until the
     * phone moves again. Unknown and offline wait for a clearer answer.
     */
    fun decide(previous: WifiZone?, now: WifiZone, vpnOn: Boolean): WifiAction = when {
        now != WifiZone.Home && now != WifiZone.Away -> WifiAction.None
        now == previous -> WifiAction.None
        now == WifiZone.Home && vpnOn -> WifiAction.Disconnect
        now == WifiZone.Away && !vpnOn -> WifiAction.Connect
        else -> WifiAction.None
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
