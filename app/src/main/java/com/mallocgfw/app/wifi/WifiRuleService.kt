package com.mallocgfw.app.wifi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.R
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.VpnServiceController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WifiRuleStatus(
    val running: Boolean = false,
    val zone: WifiZone? = null,
    /** The Wi-Fi the phone is on, when its name can be read. */
    val wifiName: String? = null,
    /** What the rule did last, for the settings screen. */
    val lastEvent: String? = null,
)

/** Starts / stops the Wi-Fi watcher and publishes what it sees. */
object WifiRuleRuntime {
    private val _status = MutableStateFlow(WifiRuleStatus())
    val status: StateFlow<WifiRuleStatus> = _status.asStateFlow()

    /** Runs the watcher when the rule is on: at app start, after boot, after a setting change. */
    fun sync(context: Context) {
        val config = WifiRules.load(context)
        if (config.enabled) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WifiRuleService::class.java).setAction(WifiRuleService.ACTION_START))
            }.onFailure { AppLogManager.append(context, WifiRuleService.TAG, "无法启动 Wi-Fi 自动切换：${it.message}") }
        } else if (_status.value.running) {
            context.startService(Intent(context, WifiRuleService::class.java).setAction(WifiRuleService.ACTION_STOP))
        }
    }

    internal fun publish(change: (WifiRuleStatus) -> WifiRuleStatus) {
        _status.value = change(_status.value)
    }
}

/** What reading Wi-Fi names in the background needs, for the settings screen and the watcher. */
object WifiAccess {
    fun locationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time": the watcher reads the name with no app screen open. */
    fun backgroundLocationGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean =
        runCatching { LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager) }
            .getOrDefault(false)

    fun batteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun ready(context: Context): Boolean = locationGranted(context) && backgroundLocationGranted(context) && locationEnabled(context)

    /** The Wi-Fi the phone is on now; null when off Wi-Fi or the name can't be read. */
    @Suppress("DEPRECATION")
    fun currentWifiName(context: Context): String? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        WifiRules.cleanSsid(wifi.connectionInfo?.ssid)
    }.getOrNull()
}

/**
 * Watches the networks while the Wi-Fi rule is on and turns the proxy off on
 * the home Wi-Fi, on again elsewhere. A foreground service so it keeps
 * running with no app screen open; its notification is quiet.
 */
class WifiRuleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Connected Wi-Fi networks and their names ("" while unreadable). */
    private val wifiNames = ConcurrentHashMap<Network, String>()
    private val online = ConcurrentHashMap.newKeySet<Network>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var onlineCallback: ConnectivityManager.NetworkCallback? = null
    private var pending: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppTextLanguage.apply(AppController.load(applicationContext).settings.language)
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_TURN_OFF -> {
                WifiRules.update(applicationContext) { it.copy(enabled = false) }
                AppLogManager.append(applicationContext, TAG, "从通知关闭 Wi-Fi 自动切换")
                stopEverything()
                return START_NOT_STICKY
            }
        }
        if (!WifiRules.load(applicationContext).enabled) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (wifiCallback == null) register()
        WifiRuleRuntime.publish { it.copy(running = true) }
        schedule(SETTLE_MS)
        // Restarted by the system after being killed: keep watching.
        return START_STICKY
    }

    override fun onDestroy() {
        unregister()
        scope.cancel()
        WifiRuleRuntime.publish { it.copy(running = false, zone = null, wifiName = null) }
        super.onDestroy()
    }

    private fun stopEverything() {
        unregister()
        WifiRuleRuntime.publish { it.copy(running = false, zone = null, wifiName = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- networks ------------------------------------------------------------

    private fun register() {
        val cm = connectivity()
        val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        }
        val any = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online.add(network)
                schedule(SETTLE_MS)
            }

            override fun onLost(network: Network) {
                online.remove(network)
                schedule(SETTLE_MS)
            }
        }
        // Builder requests leave VPNs out (NOT_VPN), so the proxy's own tunnel isn't counted.
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
            wifiCallback = wifi
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), any)
            onlineCallback = any
        }.onFailure { AppLogManager.append(applicationContext, TAG, "无法监听网络变化：${it.message}") }
    }

    private fun onWifi(network: Network, caps: NetworkCapabilities) {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiRules.cleanSsid((caps.transportInfo as? WifiInfo)?.ssid)
        } else {
            null
        }
        val name = fromCaps ?: WifiAccess.currentWifiName(applicationContext)
        val before = wifiNames.put(network, name.orEmpty())
        if (before != name.orEmpty()) schedule(SETTLE_MS)
    }

    private fun onWifiLost(network: Network) {
        wifiNames.remove(network)
        schedule(SETTLE_MS)
    }

    private fun unregister() {
        val cm = connectivity()
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        onlineCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifiCallback = null
        onlineCallback = null
        wifiNames.clear()
        online.clear()
        pending?.cancel()
    }

    // ---- decisions -----------------------------------------------------------

    /** Networks flap while switching (Wi-Fi drops before mobile data takes over): wait for them to settle. */
    @Synchronized
    private fun schedule(delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            evaluate()
        }
    }

    private suspend fun evaluate() {
        val context = applicationContext
        val config = WifiRules.load(context)
        if (!config.enabled) return
        // A name that couldn't be read when the network came up may be readable now (permission just granted).
        wifiNames.keys.forEach { network ->
            if (wifiNames[network].isNullOrEmpty()) WifiAccess.currentWifiName(context)?.let { wifiNames[network] = it }
        }
        val names = wifiNames.values.map { it.ifEmpty { null } }
        val zone = WifiRules.zone(names, online.isNotEmpty(), config.homeNetworks)
        val previous = WifiRules.lastZone(context)
        val vpnOn = VpnServiceController.isRunning()
        val action = WifiRules.decide(previous, zone, vpnOn)
        val wifiName = names.firstOrNull { it != null }
        WifiRuleRuntime.publish { it.copy(running = true, zone = zone, wifiName = wifiName) }
        if (zone == WifiZone.Home || zone == WifiZone.Away) WifiRules.setLastZone(context, zone)
        when (action) {
            WifiAction.None -> Unit
            WifiAction.Disconnect -> turnOff(wifiName.orEmpty())
            WifiAction.Connect -> turnOn(wifiName)
        }
        updateNotification()
    }

    private suspend fun turnOff(wifiName: String) {
        val context = applicationContext
        if (VpnServiceController.manualDisconnectBlocked()) {
            event(tr("已连上「$wifiName」，但锁定模式下不能自动断开代理", "On “$wifiName”, but lockdown mode keeps the proxy on", "Сеть «$wifiName», но режим блокировки не даёт отключить прокси"))
            return
        }
        AppController.load(context)
        if (AppController.disconnect()) {
            event(tr("已连上家里的「$wifiName」，代理已自动断开", "On home Wi-Fi “$wifiName”: proxy turned off", "Домашняя сеть «$wifiName»: прокси выключен"))
        }
    }

    private suspend fun turnOn(wifiName: String?) {
        val context = applicationContext
        val state = AppController.load(context)
        if (!AppController.vpnPermissionGranted()) {
            event(tr("离开家里的 Wi-Fi，但还没有 VPN 权限：请打开 PurpleBear 连接一次", "Left home Wi-Fi, but the VPN permission is missing: open PurpleBear and connect once", "Вне домашней сети, но нет разрешения VPN: откройте PurpleBear и подключитесь"))
            return
        }
        val serverId = listOf(state.settings.lastConnectedServerId, state.selectedServerId)
            .firstOrNull { id -> id.isNotBlank() && state.servers.any { it.id == id } }
            ?: state.servers.firstOrNull()?.id
        if (serverId == null) {
            event(tr("离开家里的 Wi-Fi，但没有可用节点", "Left home Wi-Fi, but there's no node to connect to", "Вне домашней сети, но нет узла для подключения"))
            return
        }
        AppController.connect(serverId).onSuccess { server ->
            event(
                if (wifiName != null) {
                    tr("在「$wifiName」，已自动连接 ${server.name}", "On “$wifiName”: connected to ${server.name}", "Сеть «$wifiName»: подключено к ${server.name}")
                } else {
                    tr("离开家里的 Wi-Fi，已自动连接 ${server.name}", "Left home Wi-Fi: connected to ${server.name}", "Вне домашней сети: подключено к ${server.name}")
                },
            )
        }.onFailure { event(it.message ?: tr("自动连接失败", "Couldn't connect", "Не удалось подключиться")) }
    }

    private fun event(message: String) {
        AppLogManager.append(applicationContext, TAG, message)
        WifiRuleRuntime.publish { it.copy(lastEvent = message) }
    }

    // ---- notification --------------------------------------------------------

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // Quiet: it only says the watcher is on.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Wi-Fi 自动切换", "Auto by Wi-Fi", "Авто по Wi-Fi"), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val status = WifiRuleRuntime.status.value
        val text = when (status.zone) {
            WifiZone.Home -> tr("在家里的「${status.wifiName}」：不用代理", "On home Wi-Fi “${status.wifiName}”: no proxy", "Домашняя сеть «${status.wifiName}»: без прокси")
            WifiZone.Away -> tr("不在家里的 Wi-Fi：自动使用代理", "Away from home Wi-Fi: proxy on", "Вне домашней сети: прокси включён")
            WifiZone.Unknown -> tr("读不到 Wi-Fi 名称：需要位置权限（始终允许）并打开定位", "Can't read the Wi-Fi name: needs location “Allow all the time” and location on", "Не видно имя Wi-Fi: нужен доступ к геолокации «Всегда» и включённая геолокация")
            WifiZone.Offline -> tr("没有网络", "No network", "Нет сети")
            null -> tr("正在检查网络…", "Checking the network…", "Проверка сети…")
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 1,
            Intent(this, WifiRuleService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(tr("Wi-Fi 自动切换已开启", "Auto by Wi-Fi is on", "Авто по Wi-Fi включено"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr("关闭", "Turn off", "Выключить"), off).build())
            .build()
    }

    private fun connectivity() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        internal const val TAG = "WIFI"
        private const val CHANNEL_ID = "mallocgfw_wifi_rule"
        private const val NOTIFICATION_ID = 7203
        private const val SETTLE_MS = 3_000L
        const val ACTION_START = "com.mallocgfw.app.action.WIFI_RULE_START"
        const val ACTION_STOP = "com.mallocgfw.app.action.WIFI_RULE_STOP"
        const val ACTION_TURN_OFF = "com.mallocgfw.app.action.WIFI_RULE_TURN_OFF"
    }
}

/** Starts the watcher after the phone restarts or the app updates. */
class WifiRuleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // A fresh start: apply the rule to wherever the phone is now.
                WifiRules.setLastZone(context, null)
                WifiRuleRuntime.sync(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> WifiRuleRuntime.sync(context)
        }
    }
}
