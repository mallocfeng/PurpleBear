package com.mallocgfw.app.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mallocgfw.app.BuildConfig
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.R
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.mcp.McpAuth
import com.mallocgfw.app.mcp.McpHttpServer
import com.mallocgfw.app.mcp.McpSettings
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.xray.AppLogManager
import com.mallocgfw.app.xray.TailscaleForward
import com.mallocgfw.app.xray.TailscaleRuntimeManager
import com.mallocgfw.app.xray.TailscaleRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

data class PanelStatus(
    val running: Boolean = false,
    val scope: PanelScope = PanelScope.Lan,
    /** Address a browser uses: the Wi-Fi IP or the tailnet IP. Null while not listening. */
    val host: String? = null,
    val port: Int? = null,
    val message: String? = null,
) {
    val url: String? get() = if (host != null && port != null) "http://$host:$port/" else null
}

/** Starts / stops the remote panel and publishes its state to the statistics screen. */
object PanelRuntime {
    private val _status = MutableStateFlow(PanelStatus())
    val status: StateFlow<PanelStatus> = _status.asStateFlow()

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, PanelService::class.java).setAction(PanelService.ACTION_START))
    }

    fun stop(context: Context) {
        if (!_status.value.running) return
        context.startService(Intent(context, PanelService::class.java).setAction(PanelService.ACTION_STOP))
    }

    /** Re-reads the settings (scope, port, token) when the panel is running. */
    fun restartIfRunning(context: Context) {
        if (!_status.value.running) return
        context.startService(Intent(context, PanelService::class.java).setAction(PanelService.ACTION_RELOAD))
    }

    internal fun publish(change: (PanelStatus) -> PanelStatus) = _status.update(change)
}

/**
 * The remote panel: a read-only web page with the connection list and the
 * traffic statistics, for a browser on the same Wi-Fi or on the tailnet.
 * Runs only while switched on, with a notification that can stop it.
 */
class PanelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: McpHttpServer? = null
    private var config: PanelConfig? = null
    private var api: PanelApi? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiAddress: InetAddress? = null
    private var tailnetJob: Job? = null
    private var forwardedPort: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppController.init(this)
        AppTextLanguage.apply(AppController.current().settings.language)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                startInForeground()
                scope.launch { setUp() }
            }
            else -> {
                startInForeground()
                if (api == null) scope.launch { setUp() }
            }
        }
        // Killed by the system: stay off until the user turns it on again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDown()
        scope.cancel()
        PanelRuntime.publish { PanelStatus() }
        AppLogManager.append(this, TAG, "远程面板已停止")
        super.onDestroy()
    }

    private fun tearDown() {
        server?.stop()
        server = null
        wifiCallback?.let { callback -> runCatching { connectivity().unregisterNetworkCallback(callback) } }
        wifiCallback = null
        wifiAddress = null
        tailnetJob?.cancel()
        tailnetJob = null
        forwardedPort?.let { port ->
            val context = applicationContext
            CoroutineScope(Dispatchers.IO).launch { TailscaleRuntimeManager.stopForward(context, port) }
        }
        forwardedPort = null
    }

    @Synchronized
    private fun setUp() {
        tearDown()
        val cfg = PanelSettings.load(this)
        config = cfg
        api = PanelApi(applicationContext, McpAuth(token = { config?.token.orEmpty() }))
        PanelRuntime.publish { PanelStatus(running = true, scope = cfg.scope, port = cfg.port) }
        AppLogManager.append(this, TAG, "远程面板启动，范围：${cfg.scope.name}，端口：${cfg.port}")
        when (cfg.scope) {
            PanelScope.Lan -> watchWifi(cfg)
            PanelScope.Tailnet -> startTailnet(cfg)
        }
    }

    private fun bind(bindings: List<McpHttpServer.Binding>): List<Int>? {
        server?.stop()
        val handler = api ?: return null
        val next = McpHttpServer(handler = { handler.handle(it) }, maxBodyBytes = 0, maxConcurrent = 8)
        return try {
            val ports = next.start(bindings)
            server = next
            ports
        } catch (error: Exception) {
            next.stop()
            server = null
            AppLogManager.append(this, TAG, "远程面板监听失败", error)
            PanelRuntime.publish {
                it.copy(host = null, message = tr("无法监听端口：${error.message}", "Couldn't listen: ${error.message}", "Не удалось открыть порт: ${error.message}"))
            }
            null
        }
    }

    private fun watchWifi(cfg: PanelConfig) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val address = linkProperties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                if (address != wifiAddress) rebindLan(cfg, address)
            }

            override fun onLost(network: Network) {
                rebindLan(cfg, null)
            }
        }
        wifiCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build() // keeps NOT_VPN: the tunnel's own address must never match
        connectivity().registerNetworkCallback(request, callback)
        if (wifiAddress == null) rebindLan(cfg, null)
    }

    @Synchronized
    private fun rebindLan(cfg: PanelConfig, address: InetAddress?) {
        if (config != cfg) return
        wifiAddress = address
        // Only the Wi-Fi interface: never 0.0.0.0, so mobile data and the VPN tunnel stay closed.
        val bindings = buildList {
            address?.let { add(McpHttpServer.Binding(InetSocketAddress(it, cfg.port))) }
            if (BuildConfig.DEBUG) add(McpHttpServer.Binding(InetSocketAddress(LOOPBACK_V4, cfg.port)))
        }
        if (bindings.isEmpty()) {
            server?.stop()
            server = null
            PanelRuntime.publish {
                it.copy(host = null, message = tr("等待 Wi-Fi：未连接 Wi-Fi 时不监听。", "Waiting for Wi-Fi: not listening without Wi-Fi.", "Ожидание Wi-Fi: без Wi-Fi панель не слушает."))
            }
            updateNotification()
            return
        }
        if (bind(bindings) == null) return
        PanelRuntime.publish {
            it.copy(
                host = address?.hostAddress ?: "127.0.0.1",
                port = cfg.port,
                message = if (address == null) {
                    tr("等待 Wi-Fi（调试版仅监听本机回环）。", "Waiting for Wi-Fi (debug build: loopback only).", "Ожидание Wi-Fi (отладочная сборка: только loopback).")
                } else {
                    null
                },
            )
        }
        updateNotification()
    }

    private fun startTailnet(cfg: PanelConfig) {
        val secret = McpSettings.newToken()
        // The bridge forwards tailnet connections here; loopback only.
        val ports = bind(listOf(McpHttpServer.Binding(InetSocketAddress(LOOPBACK_V4, 0), forwardSecret = secret))) ?: return
        PanelRuntime.publish {
            it.copy(host = null, message = tr("等待 Tailscale 连接…", "Waiting for Tailscale…", "Ожидание Tailscale…"))
        }
        tailnetJob = scope.launch {
            TailscaleRuntimeManager.snapshot
                .distinctUntilChangedBy { it.status to it.ipv4 }
                .collect { snapshot ->
                    if (snapshot.status != TailscaleRuntimeStatus.Connected) {
                        PanelRuntime.publish {
                            it.copy(host = null, message = tr("等待 Tailscale 连接…", "Waiting for Tailscale…", "Ожидание Tailscale…"))
                        }
                        return@collect
                    }
                    val forward = TailscaleForward(tailnetPort = cfg.port, localPort = ports.first(), secret = secret)
                    TailscaleRuntimeManager.serveForward(applicationContext, forward)
                        .onSuccess {
                            forwardedPort = cfg.port
                            PanelRuntime.publish { status ->
                                status.copy(host = snapshot.ipv4 ?: snapshot.dnsName, port = cfg.port, message = null)
                            }
                            updateNotification()
                        }
                        .onFailure { error ->
                            AppLogManager.append(applicationContext, TAG, "远程面板 Tailnet 监听失败", error)
                            PanelRuntime.publish {
                                it.copy(host = null, message = tr("Tailnet 监听失败：${error.message}", "Tailnet listener failed: ${error.message}", "Ошибка слушателя tailnet: ${error.message}"))
                            }
                        }
                }
        }
    }

    // ---- notification -----------------------------------------------------------

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
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("远程面板", "Remote panel", "Удалённая панель"), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val status = PanelRuntime.status.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PanelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(tr("远程面板运行中", "Remote panel running", "Удалённая панель работает"))
            .setContentText(status.url ?: status.message.orEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr("停止", "Stop", "Остановить"), stop).build())
            .build()
    }

    private fun connectivity() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "PANEL"

        /** IPv4 on purpose: the Tailscale bridge dials 127.0.0.1. */
        private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val CHANNEL_ID = "mallocgfw_remote_panel"
        private const val NOTIFICATION_ID = 7202
        const val ACTION_START = "com.mallocgfw.app.action.PANEL_START"
        const val ACTION_STOP = "com.mallocgfw.app.action.PANEL_STOP"
        const val ACTION_RELOAD = "com.mallocgfw.app.action.PANEL_RELOAD"
    }
}
