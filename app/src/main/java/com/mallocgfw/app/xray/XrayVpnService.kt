package com.mallocgfw.app.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.PerAppRouting
import com.mallocgfw.app.model.DnsInput
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ManualNodeFactory
import com.mallocgfw.app.model.ProxyMode
import com.mallocgfw.app.model.RuleRoutingSetup
import com.mallocgfw.app.model.RuleSourceManager
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.StreamingMediaManager
import com.mallocgfw.app.model.StreamingRoutingSetup
import com.mallocgfw.app.model.normalizedAppVpnMtu
import com.mallocgfw.app.quicksettings.VpnQuickSettingsTileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libXray.DialerController
import libXray.LibXray
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.Socket
import javax.net.ssl.HttpsURLConnection

class XrayVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var logMaintenanceJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile
    private var streamingRetryJob: Job? = null

    /** The newest start request handed to onStartCommand (main thread); see [stopSelfUnlessSuperseded]. */
    @Volatile
    private var lastStartId = 0

    /**
     * A tunnel has been up since the user last connected: a connect that
     * fails after that is a dropped connection, retried here ([scheduleAutoReconnect])
     * whether or not the app's screens are in memory.
     */
    @Volatile
    private var sessionEstablished = false

    @Volatile
    private var autoReconnectJob: Job? = null

    @Volatile
    private var autoReconnectAttempts = 0

    /** Set by [startCore] when routed nodes had to be left out; shown once connected. */
    @Volatile
    private var routedFallbackNotice: String? = null

    /**
     * Bumped (on the main thread) by every connect, switch and disconnect
     * request, so a delayed task can tell that the user picked another route
     * since it was scheduled.
     */
    @Volatile
    private var routeGeneration = 0L
    private var lastFallbackTargetServerId: String? = null
    private var lastUnsupportedFallbackNoticeKey: String? = null
    private var failbackPrimaryServerId: String? = null
    private var failbackPrimarySuccessCount = 0
    private var publishDisconnectedOnDestroy = false

    // System DNS follows the network (home router -> carrier DNS). The tunnel
    // hands Android one DNS address at connect time, so when the network's
    // DNS changes the tunnel is rebuilt; otherwise every lookup goes to an
    // address the new network can't reach.
    private var systemDnsCallback: ConnectivityManager.NetworkCallback? = null
    private var systemDnsChangeJob: Job? = null
    @Volatile
    private var tunnelSystemDns: String? = null
    private var cleanupCompleted = false
    /** Set when the reason for stopping is known; otherwise the plain "disconnected" text. */
    private var destroyMessage: String? = null
    private val tailscaleRecoveryTimes = ArrayDeque<Long>()

    /** Whether the current tunnel was built with Tailnet routing. */
    @Volatile
    private var tunnelHasTailscale = false

    /**
     * Gives Tailscale a few seconds to come up, then lets the VPN connect without
     * it. A login against an unreachable control server only fails after the
     * bridge's 45 s timeout, and the tunnel used to wait for all of it. If the
     * node comes up later, [reloadWhenTailscaleReady] adds Tailnet routing.
     */
    private suspend fun awaitTailscaleForConnect(settings: com.mallocgfw.app.model.AppSettings) {
        val start = TailscaleRuntimeManager.startAsync(applicationContext, settings)
        val result = withTimeoutOrNull(TAILSCALE_CONNECT_WAIT_MS) { start.await() }
        when {
            result == null -> {
                AppLogManager.append(applicationContext, TAG, "Tailscale 仍在连接，先建立 VPN；Tailscale 就绪后会自动加载 Tailnet 分流")
                scope.launch { reloadWhenTailscaleReady(start) }
            }
            result.isFailure -> AppLogManager.append(
                applicationContext,
                TAG,
                "Tailscale 未就绪，本次连接将不加载 Tailscale 分流：${result.exceptionOrNull()?.message}",
            )
        }
    }

    private suspend fun reloadWhenTailscaleReady(start: kotlinx.coroutines.Deferred<Result<*>>) {
        val result = start.await()
        result.exceptionOrNull()?.let { error ->
            AppLogManager.append(applicationContext, TAG, "Tailscale 连接失败，VPN 继续运行但不加载 Tailscale 分流：${error.message}")
            return
        }
        // After any connect still in progress, so its tunnel state is final.
        val serverId = operationMutex.withLock {
            val runtime = VpnRuntimeStore.snapshot.value
            val settings = AppController.load(applicationContext).settings
            runtime.activeServerId?.takeIf {
                it.isNotBlank() &&
                    runtime.status == ConnectionStatus.Connected &&
                    !tunnelHasTailscale &&
                    settings.tailscaleEnabled &&
                    TailscaleRuntimeManager.snapshot.value.routing(settings) != null
            }
        } ?: return
        AppLogManager.append(applicationContext, TAG, "Tailscale 已就绪，正在重新连接以加载 Tailnet 分流")
        startService(connectIntent(applicationContext, serverId))
    }

    /** Real resolver behind the MagicDNS address of the current tunnel, or null. */
    @Volatile
    private var activeMagicDnsUpstream: String? = null

    override fun onCreate() {
        super.onCreate()
        cleanupCompleted = false
        runningInstance = this
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        scope.launch {
            TailscaleRuntimeManager.bridgeLost.collect { recoverTailscaleAfterBridgeLoss() }
        }
    }

    /**
     * The Tailscale node died with its process while the tunnel was up. Its
     * SOCKS endpoint changes on restart, so reconnect: [connect] starts the node
     * again and rebuilds the Xray config with the new endpoint.
     */
    private suspend fun recoverTailscaleAfterBridgeLoss() {
        val now = System.currentTimeMillis()
        while (tailscaleRecoveryTimes.isNotEmpty() && now - tailscaleRecoveryTimes.first() > TAILSCALE_RECOVERY_WINDOW_MS) {
            tailscaleRecoveryTimes.removeFirst()
        }
        if (tailscaleRecoveryTimes.size >= TAILSCALE_RECOVERY_LIMIT) {
            AppLogManager.append(applicationContext, TAG, "Tailscale 进程反复退出，已停止自动恢复；请在 Tailscale 页面重新连接。")
            return
        }
        // Give Android a moment to bring the bound :tailscale service back.
        delay(TAILSCALE_RECOVERY_DELAY_MS)
        val runtime = VpnRuntimeStore.snapshot.value
        val serverId = runtime.activeServerId?.takeIf { it.isNotBlank() } ?: return
        if (runtime.status != ConnectionStatus.Connected) return
        if (!AppController.load(applicationContext).settings.tailscaleEnabled) return
        tailscaleRecoveryTimes.addLast(now)
        AppLogManager.append(applicationContext, TAG, "Tailscale 进程已退出，正在重新连接以恢复 Tailnet 分流")
        startService(connectIntent(applicationContext, serverId))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> beginConnect(
                serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty(),
                keepFailover = intent.getBooleanExtra(EXTRA_KEEP_FAILOVER, false),
                autoRetry = intent.getBooleanExtra(EXTRA_AUTO_RETRY, false),
                // Redelivered after the system killed the process: nobody is
                // watching, so a failed start is retried like a dropped tunnel.
                systemStarted = flags and START_FLAG_REDELIVERY != 0,
            )

            // The system starts the VPN itself for Always-on (at boot, after an
            // update, when the user turns it on), with this action and none of
            // ours. Without handling it, "Block connections without VPN" leaves
            // the phone offline.
            VpnService.SERVICE_INTERFACE -> {
                val serverId = alwaysOnServerId()
                if (serverId == null) {
                    val message = tr(
                        "系统要求启动 Always-on VPN，但没有可用节点，请先在 PurpleBear 中导入并选择节点。",
                        "The system started the Always-on VPN, but there's no usable node. Import and select one in PurpleBear.",
                        "Система запустила постоянный VPN, но подходящего узла нет. Импортируйте и выберите узел в PurpleBear.",
                    )
                    AppLogManager.append(applicationContext, TAG, message)
                    VpnRuntimeStore.reset(applicationContext, status = ConnectionStatus.Disconnected, activeServerId = null, message = message)
                    stopSelf(startId)
                } else {
                    AppLogManager.append(applicationContext, TAG, "系统启动 Always-on VPN，serverId=$serverId")
                    beginConnect(serverId, keepFailover = false, systemStarted = true)
                }
            }

            ACTION_SWITCH -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                val generation = newRouteGeneration()
                AppLogManager.append(applicationContext, TAG, "收到热切换请求，serverId=$serverId")
                scope.launch {
                    operationMutex.withLock {
                        val previousServerId = VpnRuntimeStore.snapshot.value.activeServerId
                        runCatching { switchActiveServer(serverId) }
                            .onSuccess {
                                if (recheckInstalledNodes()) return@onSuccess
                                val server = AppController.load(applicationContext).servers.firstOrNull { it.id == serverId }
                                val message = routedFallbackNotice
                                    ?: server?.let { tr("已切换到 ${it.name}", "Switched to ${it.name}", "Переключено на ${it.name}") }
                                    ?: tr("已切换线路", "Node switched", "Узел переключён")
                                AppLogManager.append(applicationContext, TAG, message)
                                resetFailoverState()
                                VpnRuntimeStore.updateConnection(
                                    applicationContext,
                                    status = ConnectionStatus.Connected,
                                    activeServerId = serverId,
                                    message = message,
                                )
                                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                                server?.let { XrayCoreManager.updateActiveServer(it.name, message) }
                                startHeartbeatMonitor()
                                // This request cancelled a pending media-rule retry
                                // (newRouteGeneration): schedule it for the new node.
                                retryUnappliedStreamingRules(serverId, generation)
                            }
                            .onFailure { error ->
                                if (error is CancellationException) return@onFailure
                                if (!recoverFromFailedSwitch(previousServerId, error, generation)) return@onFailure
                                val activeServerId = VpnRuntimeStore.snapshot.value.activeServerId
                                val failureMessage = error.message ?: tr("切换线路失败。", "Couldn't switch nodes.", "Не удалось переключить узел.")
                                AppLogManager.append(applicationContext, TAG, failureMessage, error)
                                VpnRuntimeStore.updateConnection(
                                    applicationContext,
                                    status = ConnectionStatus.Connected,
                                    activeServerId = activeServerId,
                                    message = failureMessage,
                                )
                                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                                activeServerId?.takeIf { it.isNotBlank() }?.let { retryUnappliedStreamingRules(it, generation) }
                            }
                    }
                }
            }

            ACTION_DISCONNECT -> {
                if (!intent.getBooleanExtra(EXTRA_INTERNAL_DISCONNECT, false) && manualDisconnectBlocked()) {
                    // Last line of defence; the UI, the tile and MCP check first.
                    AppLogManager.append(applicationContext, TAG, "锁定模式下拒绝手动断开")
                    val runtime = VpnRuntimeStore.snapshot.value
                    VpnRuntimeStore.updateConnection(
                        applicationContext,
                        status = runtime.status,
                        activeServerId = runtime.activeServerId,
                        message = lockdownDisconnectMessage(),
                    )
                    // Not sticky for this request: with REDELIVER the system would
                    // keep this refused disconnect and replay it after a process
                    // restart, when lockdown may be off, taking the VPN down then.
                    // (stopSelf(startId) can't drop it: as the newest start it
                    // would stop the service.)
                    return START_NOT_STICKY
                }
                publishDisconnectedOnDestroy = true
                destroyMessage = null
                val generation = newRouteGeneration()
                // The session ends here: nothing after this is a dropped tunnel.
                sessionEstablished = false
                autoReconnectAttempts = 0
                AppLogManager.append(applicationContext, TAG, "收到断开请求")
                VpnRuntimeStore.reset(
                    applicationContext,
                    status = ConnectionStatus.Disconnecting,
                    activeServerId = VpnRuntimeStore.snapshot.value.activeServerId,
                    message = "正在断开 VPN…",
                    persist = false,
                )
                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                scope.launch {
                    operationMutex.withLock {
                        disconnect(updateRuntime = false, message = destroyMessage ?: tr("VPN 已断开", "VPN disconnected", "VPN отключён"))
                        // A connect that arrived after this disconnect is queued
                        // behind it: stopping would cancel it (onDestroy cancels scope).
                        stopSelfUnlessSuperseded(generation)
                    }
                }
            }

            // Sent by older versions only (see refreshSpeedMeasurement): measure
            // if connected, and don't keep a service alive that has no tunnel.
            ACTION_REFRESH_SPEED -> {
                if (VpnRuntimeStore.snapshot.value.status == ConnectionStatus.Connected) {
                    startSpeedMeasurement()
                } else if (vpnInterface == null) {
                    stopSelfResult(startId)
                }
            }
        }
        // If the OS kills us under memory pressure while we're in the
        // Connected/Connecting state, ask it to redeliver the original
        // ACTION_CONNECT intent so we can re-establish the tunnel. Manual
        // disconnects call stopSelf() which clears the redelivered intent,
        // so REDELIVER never replays an unwanted disconnect.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        if (runningInstance === this) {
            runningInstance = null
        }
        AppLogManager.append(applicationContext, TAG, "VPN Service 销毁")
        scope.cancel()
        disconnectNow(
            updateRuntime = publishDisconnectedOnDestroy,
            message = destroyMessage ?: tr("VPN 已断开", "VPN disconnected", "VPN отключён"),
        )
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        super.onDestroy()
    }

    override fun onRevoke() {
        publishDisconnectedOnDestroy = true
        destroyMessage = tr("VPN 权限已撤销，连接已断开", "VPN permission was revoked; disconnected", "Разрешение VPN отозвано, соединение разорвано")
        AppLogManager.append(applicationContext, TAG, "VPN 权限被系统撤销")
        // Another VPN took over, or the user revoked it: not a drop to retry.
        sessionEstablished = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        scope.launch {
            operationMutex.withLock {
                disconnect(updateRuntime = false, message = destroyMessage ?: tr("VPN 已断开", "VPN disconnected", "VPN отключён"))
                stopSelf()
            }
        }
        super.onRevoke()
    }

    /**
     * [keepFailover]: an internal reconnect (the network's DNS changed) while on
     * a fallback node keeps the link to the primary, so the heartbeat still
     * switches back once the primary recovers.
     */
    private suspend fun connect(serverId: String, keepFailover: Boolean = false) {
        ensureSocketProtectorRegistered()
        statsJob?.cancel()
        statsJob = null
        logMaintenanceJob?.cancel()
        logMaintenanceJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupCompleted = false
        if (!keepFailover || serverId != lastFallbackTargetServerId) resetFailoverState()
        AppLogManager.trimAllLogs(applicationContext)
        val state = AppController.load(applicationContext)
        // The service may run with no UI open (Quick Settings, auto reconnect).
        AppTextLanguage.apply(state.settings.language)
        AppLogManager.append(applicationContext, TAG, "连接启动，DNS 模式：${state.settings.dnsMode.name}")
        if (state.settings.tailscaleEnabled) {
            awaitTailscaleForConnect(state.settings)
        }
        val tailscaleRouting = TailscaleRuntimeManager.snapshot.value.routing(state.settings)
        tunnelHasTailscale = tailscaleRouting != null
        val dnsServer = resolveDnsAddress(applicationContext, state.settings)
        val dnsEndpoint = resolveDnsEndpoint(applicationContext, state.settings)
        tunnelSystemDns = dnsServer.takeIf { usesSystemDns(state.settings) }
        encryptedDnsOf(state.settings)?.let { dns ->
            AppLogManager.append(applicationContext, TAG, "使用加密 DNS（${dns.label}）：${state.settings.customDnsValue.trim()}")
        }
        val server = state.servers.firstOrNull { it.id == serverId }
            ?: error("未找到要连接的节点。")
        val vpnMtu = normalizedAppVpnMtu(state.settings.vpnMtu)
        val globalProxyEnabled = state.settings.globalProxyEnabled
        val ruleRouting = RuleSourceManager.buildRoutingSetup(
            context = applicationContext,
            sources = state.ruleSources,
            servers = state.servers,
            region = state.settings.geoRoutingRegion,
            globalMode = globalProxyEnabled,
        )
        val streamingRouting = if (globalProxyEnabled) {
            StreamingMediaManager.clearPending()
            StreamingRoutingSetup()
        } else {
            StreamingMediaManager.buildRoutingSetup(
                context = applicationContext,
                settings = state.settings,
                selectedServerId = server.id,
                servers = state.servers,
            )
        }
        val builder = Builder()
            .setSession("PurpleBear")
            .setMtu(vpnMtu)
            .addAddress("172.19.0.1", 30)
            .addAddress("fd00:1:fd00:1::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .applyLocalNetworkExclusions(tailscaleRouting?.subnetRoutes.orEmpty())
        // Android 10+ treats a VPN as metered unless told otherwise, even on
        // Wi-Fi: Play auto-updates, cloud backups and "Wi-Fi only" downloads
        // of every app would stop while connected. Not metered here means
        // "as metered as the network underneath".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        // MagicDNS: Android gets Tailscale's resolver address, which always enters
        // the tunnel (a LAN resolver is excluded from it). Xray answers tailnet
        // names and forwards every other query to the real resolver, routed as
        // before; see XrayConfigFactory.magicDnsOutbounds.
        // With encrypted DNS, non-tailnet names go on to the DNS module's address.
        val magicDnsUpstream = (if (encryptedDnsOf(state.settings) != null) "${XrayConfigFactory.ENCRYPTED_DNS_ADDRESS}:53" else dnsEndpoint)
            ?.takeIf { tailscaleRouting?.resolvesMagicDns == true }
        activeMagicDnsUpstream = magicDnsUpstream
        if (magicDnsUpstream != null) {
            builder.addDnsServer(XrayConfigFactory.TAILSCALE_RESOLVER)
            // Lets short names like "nas" expand to nas.<tailnet>.ts.net.
            tailscaleRouting?.magicDnsSuffix?.let { suffix ->
                runCatching { builder.addSearchDomain(suffix) }
                    .onFailure { Log.w(TAG, "Unable to add MagicDNS search domain", it) }
            }
            AppLogManager.append(applicationContext, TAG, "已启用 MagicDNS，其他域名仍使用 $magicDnsUpstream")
        } else {
            dnsServer?.let(builder::addDnsServer)
        }

        if (state.proxyMode == ProxyMode.PerApp && !globalProxyEnabled) {
            val allowedPackages = state.protectedApps.filter { it.enabled }.map { it.id }.distinct()
            require(allowedPackages.isNotEmpty()) { tr("请先选择需要代理的应用。", "Choose the apps to proxy first.", "Сначала выберите приложения для прокси.") }
            var installedAllowedPackageCount = 0
            allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    installedAllowedPackageCount += 1
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed package not installed: $pkg")
                }
            }
            require(installedAllowedPackageCount > 0) {
                tr("所选代理应用均未安装，请重新选择后再连接。", "None of the selected apps are installed. Choose again before connecting.", "Ни одно из выбранных приложений не установлено. Выберите заново перед подключением.")
            }
            if (manualDisconnectBlocked()) {
                // The per-app screen warns about this too.
                AppLogManager.append(applicationContext, TAG, "系统已开启“屏蔽未使用 VPN 的连接”：分应用模式下，未选中的应用不经过 VPN，会被系统断网")
            }
        } else {
            // Smart mode and the global-proxy rule mode keep the app's own outbound traffic
            // (subscription refresh, geo updates, speedtest, log uploads, …)
            // off the tunnel. Xray protects its own dialed sockets via the
            // DialerController, but plain Java HttpURLConnection traffic does
            // not, so without this exclusion every app-level network call
            // would loop through the TUN.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Unable to disallow self ($packageName) from VPN")
            }
        }

        // If a previous Xray instance is still alive (re-entry of ACTION_CONNECT,
        // or a stale core), shut it down BEFORE recycling the TUN file descriptor.
        // Otherwise the old core keeps reading/writing on a fd we just closed and
        // can crash inside libgojni before XrayCoreManager.startVpn stops it.
        runCatching { XrayCoreManager.stopNow() }
            .onFailure { Log.w(TAG, "Unable to stop previous Xray before reconnect", it) }

        vpnInterface?.close()
        vpnInterface = builder.establish() ?: error(tr("无法建立系统 VPN 接口。", "Couldn't create the system VPN interface.", "Не удалось создать системный VPN-интерфейс."))
        val tunFd = vpnInterface?.fd ?: error(tr("无法获取 TUN fd。", "Couldn't get the TUN fd.", "Не удалось получить TUN fd."))
        dnsEndpoint?.let(::installProcessDns)

        AppProcessFinder.register(applicationContext)
        val perAppRouting = PerAppRouting.buildSetup(applicationContext, state, server.id)
        startCore(server, tunFd, state.settings, vpnMtu, dnsServer, perAppRouting, streamingRouting, ruleRouting)
    }

    private suspend fun switchActiveServer(serverId: String) {
        val state = AppController.load(applicationContext)
        val server = state.servers.firstOrNull { it.id == serverId }
            ?: error(tr("未找到要切换的节点。", "Couldn't find the node to switch to.", "Узел для переключения не найден."))
        val tunFd = vpnInterface?.fd ?: error(tr("系统 VPN 还没有建立，无法热切换线路。", "The system VPN isn't up yet, so nodes can't be hot-switched.", "Системный VPN ещё не установлен, горячее переключение узла невозможно."))
        val vpnMtu = normalizedAppVpnMtu(state.settings.vpnMtu)
        val dnsServer = resolveDnsAddress(applicationContext, state.settings)
        val dnsEndpoint = resolveDnsEndpoint(applicationContext, state.settings)
        val globalProxyEnabled = state.settings.globalProxyEnabled
        val ruleRouting = RuleSourceManager.buildRoutingSetup(
            context = applicationContext,
            sources = state.ruleSources,
            servers = state.servers,
            region = state.settings.geoRoutingRegion,
            globalMode = globalProxyEnabled,
        )
        val streamingRouting = if (globalProxyEnabled) {
            StreamingMediaManager.clearPending()
            StreamingRoutingSetup()
        } else {
            StreamingMediaManager.buildRoutingSetup(
                context = applicationContext,
                settings = state.settings,
                selectedServerId = server.id,
                servers = state.servers,
            )
        }
        dnsEndpoint?.let(::installProcessDns)
        AppProcessFinder.register(applicationContext)
        val perAppRouting = PerAppRouting.buildSetup(applicationContext, state, server.id)
        startCore(server, tunFd, state.settings, vpnMtu, dnsServer, perAppRouting, streamingRouting, ruleRouting)
    }

    /**
     * Starts the core for [server] with the routed outbounds (per-app, media,
     * NODE: rules). When that fails and some routed nodes run in the mihomo
     * sidecar beside the main node, a refused one (say a Snell version mihomo
     * rejects) would keep the whole VPN off: start again without them, their
     * traffic going by the rule's policy (the main node), and say so
     * ([routedFallbackNotice]). Records what was installed.
     */
    private suspend fun startCore(
        server: ServerNode,
        tunFd: Int,
        settings: com.mallocgfw.app.model.AppSettings,
        vpnMtu: Int,
        dnsServer: String?,
        perAppRouting: StreamingRoutingSetup,
        streamingRouting: StreamingRoutingSetup,
        ruleRouting: RuleRoutingSetup,
    ) {
        // One reading of the nodes for this start: the routed-outbound checks,
        // the core (chain check, pin fetch, sidecar listeners, config) and the
        // tunnel record all use it, with the nodes as it has them.
        val servers = AppController.load(applicationContext).servers
        val byId = servers.associateBy { it.id }
        val runServer = byId[server.id] ?: server
        val extras = (perAppRouting.outbounds + streamingRouting.outbounds + ruleRouting.outbounds)
            .map { it.copy(node = byId[it.node.id] ?: it.node) }
        val rules = perAppRouting.routingRules + streamingRouting.routingRules + ruleRouting.routingRules
        suspend fun start(routingRules: List<com.mallocgfw.app.model.XrayRoutingRule>, outbounds: List<com.mallocgfw.app.model.XrayNamedOutbound>) =
            XrayCoreManager.startVpn(
                context = applicationContext,
                server = runServer,
                tunFd = tunFd,
                routingRules = routingRules,
                additionalOutbounds = outbounds,
                servers = servers,
                vpnMtu = vpnMtu,
                magicDnsUpstream = activeMagicDnsUpstream,
                dnsServer = dnsServer,
                dnsOverProxy = settings.dnsMode == AppDnsMode.Remote,
                encryptedDns = encryptedDnsOf(settings),
            )
        routedFallbackNotice = null
        // Routed nodes whose pre-proxy chain can't run (a loop or an over-long
        // chain saved by an older version) are left out up front: one of them
        // would fail the whole config, the main node with it.
        val broken = RoutedOutboundFallback.brokenChains(extras, servers)
        val brokenOutbounds = broken.map { it.first }
        val brokenTags = brokenOutbounds.map { it.tag }.toSet()
        val notices = mutableListOf<String>()
        if (broken.isNotEmpty()) {
            val kinds = buildList {
                if (perAppRouting.outbounds.any { it.tag in brokenTags }) add(tr("分应用", "per-app", "по приложениям"))
                if (streamingRouting.outbounds.any { it.tag in brokenTags }) add(tr("流媒体", "media", "медиа"))
                if (ruleRouting.outbounds.any { it.tag in brokenTags }) add(tr("规则", "rule", "правила"))
            }.joinToString("、")
            val reasons = broken.map { (outbound, problem) -> "${outbound.node.name}：$problem" }.distinct().joinToString("；")
            notices += tr(
                "分流节点的前置代理链无法使用（$reasons），使用它的${kinds}分流暂时改走当前节点，请在节点设置中修改前置代理。",
                "A routed node's pre-proxy chain can't run ($reasons); its $kinds routes go through the current node for now. Change the pre-proxy in the node's settings.",
                "Цепочка предварительных прокси узла маршрутизации не работает ($reasons); маршруты ($kinds) пока идут через текущий узел. Измените предварительный прокси в настройках узла.",
            )
            AppLogManager.append(applicationContext, TAG, notices.last())
        }
        val usable = extras - brokenOutbounds.toSet()
        val usableRules = RoutedOutboundFallback.withoutOutbounds(rules, brokenOutbounds)
        var installed = usable
        val first = start(usableRules, usable)
        if (first.isFailure) {
            val error = first.exceptionOrNull() ?: IllegalStateException()
            val dropped = RoutedOutboundFallback.sidecarOutbounds(runServer, usable, servers)
            if (dropped.isEmpty()) throw error
            val names = dropped.map { it.node.name }.distinct().joinToString("、")
            AppLogManager.append(applicationContext, TAG, "分流节点启动失败（${error.message.orEmpty()}），去掉 $names 后重试", error)
            installed = usable - dropped.toSet()
            // Still failing: the main node itself is the problem; report the first error.
            start(RoutedOutboundFallback.withoutOutbounds(usableRules, dropped), installed).getOrElse { throw error }
            notices += tr(
                "VPN 已连接，但分流节点 $names 无法启动，相关应用、流媒体和规则暂时改走当前节点。",
                "VPN connected, but the routed node $names couldn't start; the apps, media and rules using it go through the current node for now.",
                "VPN подключён, но узел маршрутизации $names не запустился; связанные приложения, медиа и правила пока идут через текущий узел.",
            )
            AppLogManager.append(applicationContext, TAG, notices.last())
        }
        routedFallbackNotice = notices.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val droppedIds = (extras - installed.toSet()).map { it.node.id }.toSet()
        StreamingMediaManager.markInstalled(
            streamingRouting.copy(appliedRoutes = streamingRouting.appliedRoutes.filterValues { it !in droppedIds }),
        )
        TunnelNodes.record(runServer, installed, servers)
    }

    private suspend fun disconnect(
        updateRuntime: Boolean = true,
        message: String = tr("VPN 已断开", "VPN disconnected", "VPN отключён"),
    ) {
        withContext(Dispatchers.IO) {
            disconnectNow(updateRuntime = updateRuntime, message = message)
        }
    }

    private fun disconnectNow(
        updateRuntime: Boolean = true,
        message: String = tr("VPN 已断开", "VPN disconnected", "VPN отключён"),
    ) {
        val disconnectStartedAt = System.currentTimeMillis()
        if (cleanupCompleted) {
            if (updateRuntime) {
                AppLogManager.append(applicationContext, TAG, message)
                VpnRuntimeStore.reset(
                    applicationContext,
                    status = ConnectionStatus.Disconnected,
                    activeServerId = null,
                    message = message,
                )
                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
            }
            return
        }
        unwatchSystemDns()
        StreamingMediaManager.clearUnapplied()
        TunnelNodes.clear()
        statsJob?.cancel()
        statsJob = null
        logMaintenanceJob?.cancel()
        logMaintenanceJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        val stopXrayStartedAt = System.currentTimeMillis()
        runCatching { XrayCoreManager.stopNow() }
            .onFailure { Log.w(TAG, "Unable to stop Xray cleanly", it) }
        Log.d(TAG, "disconnectNow stopXray phase took ${System.currentTimeMillis() - stopXrayStartedAt} ms")
        val resetDnsStartedAt = System.currentTimeMillis()
        runCatching { LibXray.resetDNS() }
            .onFailure { Log.w(TAG, "Unable to reset libXray DNS state", it) }
        Log.d(TAG, "disconnectNow resetDns phase took ${System.currentTimeMillis() - resetDnsStartedAt} ms")
        val closeTunStartedAt = System.currentTimeMillis()
        runCatching { vpnInterface?.close() }
            .onFailure { Log.w(TAG, "Unable to close VPN interface", it) }
        Log.d(TAG, "disconnectNow closeTun phase took ${System.currentTimeMillis() - closeTunStartedAt} ms")
        vpnInterface = null
        val stopForegroundStartedAt = System.currentTimeMillis()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "disconnectNow stopForeground phase took ${System.currentTimeMillis() - stopForegroundStartedAt} ms")
        Log.d(TAG, "disconnectNow total cleanup took ${System.currentTimeMillis() - disconnectStartedAt} ms")
        cleanupCompleted = true
        if (updateRuntime) {
            AppLogManager.append(applicationContext, TAG, message)
            VpnRuntimeStore.reset(
                applicationContext,
                status = ConnectionStatus.Disconnected,
                activeServerId = null,
                message = message,
            )
            VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        }
    }

    /**
     * Media routes whose rules couldn't be downloaded before the tunnel was up
     * (from mainland China GitHub often fails direct) are retried through the
     * proxy once connected; if any now has rules, reconnect once to apply it.
     *
     * The download can take a while; if the user switched node, reconnected or
     * disconnected meanwhile (or a failover moved the tunnel), the reconnect is
     * skipped so it doesn't override that choice. The downloaded rules stay
     * cached and apply on the next connect.
     */
    private fun retryUnappliedStreamingRules(serverId: String, generation: Long) {
        streamingRetryJob?.cancel()
        streamingRetryJob = null
        val pending = StreamingMediaManager.unappliedServices.value
        if (pending.isEmpty()) return
        val names = pending.mapNotNull { StreamingMediaManager.serviceById(it)?.name }.joinToString("、")
        AppLogManager.append(applicationContext, TAG, "流媒体分流未生效（规则下载失败）：$names，连接后重试下载")
        streamingRetryJob = scope.launch {
            delay(STREAMING_RETRY_DELAY_MS)
            if (!isCurrentRoute(serverId, generation)) return@launch
            if (!StreamingMediaManager.retryUnapplied(applicationContext)) {
                AppLogManager.append(applicationContext, TAG, "流媒体规则重试仍失败：$names")
                return@launch
            }
            // Checked and sent under the operation lock (failover switches hold
            // it) and on the main thread, where onStartCommand bumps the
            // generation: a request handled before this check fails it, and one
            // handled after it is queued behind this reconnect.
            operationMutex.withLock {
                withContext(Dispatchers.Main) {
                    if (isCurrentRoute(serverId, generation)) {
                        AppLogManager.append(applicationContext, TAG, "流媒体规则已下载，重连以应用")
                        startService(connectIntent(applicationContext, serverId).putExtra(EXTRA_KEEP_FAILOVER, true))
                    } else {
                        AppLogManager.append(applicationContext, TAG, "流媒体规则已下载，但线路已变更，不再重连（下次连接时生效）")
                    }
                }
            }
        }
    }

    /**
     * The core is up; before announcing it, check that every node it was built
     * with still exists. A node removed while the tunnel was being set up (the
     * service had already read the old settings) would otherwise keep carrying
     * traffic. Every path that installs a core config runs this before it
     * publishes Connected: connect, hot switch, switch to the fallback, switch
     * back to the primary and the recovery after a failed switch.
     * True when a reconnect or a disconnect was started instead; the new
     * request is queued behind this one (operationMutex).
     */
    private fun recheckInstalledNodes(): Boolean {
        val serverId = TunnelNodes.serverId() ?: return false
        val installed = TunnelNodes.current() ?: return false
        val state = AppController.load(applicationContext)
        return when (val effect = com.mallocgfw.app.core.StateOps.staleTunnelEffect(state, serverId, installed)) {
            com.mallocgfw.app.core.StateOps.RemovalEffect.None -> false
            is com.mallocgfw.app.core.StateOps.RemovalEffect.Reconnect -> {
                AppLogManager.append(applicationContext, TAG, "连接期间节点已删除：${effect.message}")
                startService(connectIntent(applicationContext, effect.server.id).putExtra(EXTRA_KEEP_FAILOVER, true))
                true
            }
            is com.mallocgfw.app.core.StateOps.RemovalEffect.Disconnect -> {
                AppLogManager.append(applicationContext, TAG, "连接期间节点已删除：${effect.message}")
                startService(disconnectIntent(applicationContext, internal = true))
                true
            }
        }
    }

    /**
     * Starts a connection to [serverId]: an ACTION_CONNECT from the app, or the
     * system starting the VPN (Always-on, see [alwaysOnServerId]).
     */
    private fun beginConnect(
        serverId: String,
        keepFailover: Boolean,
        /** One of [scheduleAutoReconnect]'s attempts: keeps counting them. */
        autoRetry: Boolean = false,
        /** Started by the system (Always-on, redelivery), not from the app's screens. */
        systemStarted: Boolean = false,
    ) {
        publishDisconnectedOnDestroy = false
        destroyMessage = null
        val generation = newRouteGeneration()
        if (!autoRetry) autoReconnectAttempts = 0
        // A failure of this connect is retried when it interrupts a session
        // that was up, or when the system started it with no screen to show
        // the error on. A first connect the user just tapped reports its error.
        val retryOnFailure = sessionEstablished || systemStarted || autoRetry
        AppLogManager.append(applicationContext, TAG, "收到连接请求，serverId=$serverId")
        VpnRuntimeStore.reset(
            applicationContext,
            status = ConnectionStatus.Connecting,
            activeServerId = serverId,
            message = tr("正在建立系统 VPN 隧道…", "Setting up the system VPN tunnel…", "Создание системного VPN-туннеля…"),
        )
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        startServiceForeground(buildNotification(tr("正在建立 VPN 隧道…", "Setting up the VPN tunnel…", "Создание VPN-туннеля…")))
        scope.launch {
            operationMutex.withLock {
                // An older request's cleanup, run while this one waited for the
                // lock, may have called stopForeground: take the foreground again.
                startServiceForeground(buildNotification(tr("正在建立 VPN 隧道…", "Setting up the VPN tunnel…", "Создание VPN-туннеля…")))
                cleanupCompleted = false
                runCatching { connect(serverId, keepFailover) }
                    .onSuccess {
                        if (recheckInstalledNodes()) return@onSuccess
                        sessionEstablished = true
                        autoReconnectAttempts = 0
                        val connectedAtMs = System.currentTimeMillis()
                        AppLogManager.append(applicationContext, TAG, "VPN 已连接，serverId=$serverId")
                        VpnRuntimeStore.updateConnection(
                            applicationContext,
                            status = ConnectionStatus.Connected,
                            activeServerId = serverId,
                            message = routedFallbackNotice
                                ?: tr("系统 VPN 已建立，Xray 正在运行", "System VPN is up and Xray is running", "Системный VPN установлен, Xray работает"),
                            connectedAtMs = connectedAtMs,
                            interfaceName = null,
                        )
                        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                        startSpeedMeasurement()
                        startLogMaintenance()
                        startHeartbeatMonitor()
                        watchSystemDns()
                        retryUnappliedStreamingRules(serverId, generation)
                        val notification = buildNotification(tr("VPN 已连接", "VPN connected", "VPN подключён"))
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, notification)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        if (generation != routeGeneration) {
                            // A newer connect / switch / disconnect already arrived and
                            // is waiting for operationMutex: it takes over the tunnel.
                            // Stopping the service here would cancel it (onDestroy
                            // cancels scope).
                            AppLogManager.append(applicationContext, TAG, "旧的连接请求失败，已有新的请求，交由新请求处理：${error.message.orEmpty()}")
                            return@onFailure
                        }
                        publishDisconnectedOnDestroy = false
                        val failureMessage = XrayCoreManager.snapshot.value.message
                            ?: error.message
                            ?: tr("VPN 建立失败。", "Couldn't set up the VPN.", "Не удалось установить VPN.")
                        AppLogManager.append(applicationContext, TAG, "VPN 连接失败：$failureMessage", error)
                        Log.e(TAG, "VPN connect failed: $failureMessage", error)
                        if (retryOnFailure && scheduleAutoReconnect(serverId, error, failureMessage, generation)) return@onFailure
                        sessionEstablished = false
                        VpnRuntimeStore.reset(
                            applicationContext,
                            status = ConnectionStatus.Disconnected,
                            activeServerId = null,
                            message = failureMessage,
                        )
                        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                        disconnect(updateRuntime = false)
                        // A request can arrive during the cleanup too; it is queued
                        // behind this one, and stopping would cancel it.
                        stopSelfUnlessSuperseded(generation)
                    }
            }
        }
    }

    /**
     * The tunnel went down on its own (a reconnect after a network change
     * failed, a node switch left nothing running): try again here, up to
     * [AUTO_RECONNECT_DELAYS_MS].size times with growing pauses, when "断线自动重连"
     * is on. In the service, so it works with the app's screens gone.
     * True when a retry was scheduled; the caller then leaves the service
     * running. Not retried: a setting the user has to fix (require() failures
     * such as no app selected in per-app mode), and a node that is gone.
     * A connect, switch or disconnect that arrives meanwhile cancels the retry
     * ([newRouteGeneration]).
     */
    private suspend fun scheduleAutoReconnect(
        serverId: String,
        error: Throwable,
        failureMessage: String,
        generation: Long,
    ): Boolean {
        if (error is IllegalArgumentException) return false
        val state = AppController.load(applicationContext)
        if (!state.settings.autoReconnect) return false
        if (state.servers.none { it.id == serverId }) return false
        if (autoReconnectAttempts >= AUTO_RECONNECT_DELAYS_MS.size) {
            AppLogManager.append(applicationContext, TAG, "自动重连 ${AUTO_RECONNECT_DELAYS_MS.size} 次仍失败，停止重试：$failureMessage")
            return false
        }
        val attempt = ++autoReconnectAttempts
        val delayMs = AUTO_RECONNECT_DELAYS_MS[attempt - 1]
        val seconds = delayMs / 1000
        val message = tr(
            "连接中断（$failureMessage），$seconds 秒后第 $attempt 次自动重连…",
            "Connection lost ($failureMessage). Reconnecting in $seconds s (attempt $attempt)…",
            "Соединение потеряно ($failureMessage). Переподключение через $seconds с (попытка $attempt)…",
        )
        AppLogManager.append(applicationContext, TAG, message)
        disconnect(updateRuntime = false)
        VpnRuntimeStore.reset(applicationContext, status = ConnectionStatus.Connecting, activeServerId = serverId, message = message)
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        // disconnect() left the foreground; stay there while waiting, or the
        // system may stop the service (and refuse the start below).
        startServiceForeground(buildNotification(tr("连接中断，等待自动重连…", "Connection lost; waiting to reconnect…", "Соединение потеряно, ожидание переподключения…")))
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            delay(delayMs)
            withContext(Dispatchers.Main) {
                if (generation != routeGeneration) return@withContext
                startService(
                    connectIntent(applicationContext, serverId)
                        .putExtra(EXTRA_KEEP_FAILOVER, true)
                        .putExtra(EXTRA_AUTO_RETRY, true),
                )
            }
        }
        return true
    }

    /** The node an Always-on start connects to (StateOps.alwaysOnServerId). */
    private fun alwaysOnServerId(): String? =
        com.mallocgfw.app.core.StateOps.alwaysOnServerId(AppController.load(applicationContext))

    /**
     * Stops the service after a cleanup, unless a connect / switch / disconnect
     * arrived meanwhile:
     * - one already handed to onStartCommand bumped routeGeneration (both are
     *   read on the main thread, where onStartCommand runs);
     * - one the system accepted but hasn't handed over yet has a newer start
     *   id: stopSelfResult([lastStartId]) then refuses to stop, where a plain
     *   stopSelf() would drop it with the service.
     */
    private suspend fun stopSelfUnlessSuperseded(generation: Long) {
        withContext(Dispatchers.Main) {
            val stopped = generation == routeGeneration && stopSelfResult(lastStartId)
            if (!stopped) {
                AppLogManager.append(applicationContext, TAG, "清理期间收到新的请求，保留服务交由新请求处理")
            }
        }
    }

    private fun newRouteGeneration(): Long {
        streamingRetryJob?.cancel()
        streamingRetryJob = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        return ++routeGeneration
    }

    private fun isCurrentRoute(serverId: String, generation: Long): Boolean {
        val runtime = VpnRuntimeStore.snapshot.value
        return generation == routeGeneration &&
            runtime.status == ConnectionStatus.Connected &&
            runtime.activeServerId == serverId
    }

    /** System mode, or a custom value that isn't usable (which falls back to the system DNS). */
    private fun usesSystemDns(settings: com.mallocgfw.app.model.AppSettings): Boolean =
        settings.dnsMode == AppDnsMode.System ||
            (
                settings.dnsMode == AppDnsMode.Custom &&
                    DnsInput.hostOf(settings.customDnsValue) == null &&
                    encryptedDnsOf(settings) == null
                )

    private fun encryptedDnsOf(settings: com.mallocgfw.app.model.AppSettings): DnsInput.EncryptedDns? =
        DnsInput.encryptedOf(settings.customDnsValue).takeIf { settings.dnsMode == AppDnsMode.Custom }

    private fun watchSystemDns() {
        unwatchSystemDns()
        if (tunnelSystemDns == null) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        // The VPN owner's default network is the VPN itself, so watch the
        // physical networks directly and re-read the system DNS the same way
        // connect() does.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleSystemDnsCheck()
            override fun onLost(network: Network) = scheduleSystemDnsCheck()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleSystemDnsCheck()
        }
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onSuccess { systemDnsCallback = callback }
            .onFailure { Log.w(TAG, "Unable to watch the system DNS", it) }
    }

    private fun scheduleSystemDnsCheck() {
        systemDnsChangeJob?.cancel()
        systemDnsChangeJob = scope.launch {
            // Let the switch settle (a Wi-Fi handover reports several changes).
            delay(SYSTEM_DNS_SETTLE_MS)
            val previous = tunnelSystemDns ?: return@launch
            val current = resolveSystemDns(applicationContext, log = false) ?: return@launch
            if (current == previous) return@launch
            val runtime = VpnRuntimeStore.snapshot.value
            val serverId = runtime.activeServerId?.takeIf { it.isNotBlank() } ?: return@launch
            if (runtime.status != ConnectionStatus.Connected) return@launch
            AppLogManager.append(
                applicationContext,
                TAG,
                "网络已切换，系统 DNS 由 $previous 变为 $current，正在重连以更新 DNS",
            )
            tunnelSystemDns = current
            startService(connectIntent(applicationContext, serverId).putExtra(EXTRA_KEEP_FAILOVER, true))
        }
    }

    private fun unwatchSystemDns() {
        systemDnsChangeJob?.cancel()
        systemDnsChangeJob = null
        val callback = systemDnsCallback ?: return
        systemDnsCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun startLogMaintenance() {
        logMaintenanceJob?.cancel()
        logMaintenanceJob = scope.launch {
            while (true) {
                delay(LOG_MAINTENANCE_INTERVAL_MS)
                AppLogManager.trimAllLogs(applicationContext)
            }
        }
    }

    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                val state = AppController.load(applicationContext)
                val intervalMinutes = state.settings.heartbeatIntervalMinutes
                    .takeIf { it in HEARTBEAT_INTERVAL_OPTIONS_MINUTES }
                    ?: DEFAULT_HEARTBEAT_INTERVAL_MINUTES
                delay(intervalMinutes * 60_000L)

                val runtime = VpnRuntimeStore.snapshot.value
                if (runtime.status != ConnectionStatus.Connected) return@launch
                val activeId = runtime.activeServerId?.takeIf { it.isNotBlank() } ?: continue
                val latestState = AppController.load(applicationContext)
                val activeNode = latestState.servers.firstOrNull { it.id == activeId } ?: continue
                if (probeAndMaybeFailback(activeNode, latestState.servers)) {
                    return@launch
                }
                val fallbackNode = fallbackNodeFor(
                    source = activeNode,
                    servers = latestState.servers,
                ) ?: continue

                val heartbeatOk = runHeartbeatProbeBatch(activeNode)
                if (heartbeatOk == false) {
                    // The phone being offline (a lift, the underground) fails the
                    // probes just the same: only switch when the fallback can be
                    // reached, or the VPN would sit on the fallback for a quarter
                    // of an hour over a few seconds without signal.
                    if (!hasUnderlyingNetwork()) {
                        AppLogManager.append(applicationContext, "FALLBACK", "心跳失败，但当前没有可用网络，不切换备用节点")
                        continue
                    }
                    if (runHeartbeatProbeBatch(fallbackNode) == false) {
                        AppLogManager.append(applicationContext, "FALLBACK", "心跳失败，但备用节点 ${fallbackNode.name} 同样无法连通，不切换")
                        continue
                    }
                    val switching = switchToFallback(
                        source = activeNode,
                        fallback = fallbackNode,
                        reason = tr("心跳连续 3 次检测失败", "3 fallback checks failed in a row", "3 проверки подряд не прошли"),
                    )
                    // A switch that went ahead restarts the monitor itself; one
                    // that was called off (settings changed meanwhile) must not
                    // end the checks for the rest of the session.
                    if (switching) return@launch
                }
            }
        }
    }

    private suspend fun probeAndMaybeFailback(
        activeNode: ServerNode,
        servers: List<ServerNode>,
    ): Boolean {
        val primaryId = failbackPrimaryServerId ?: return false
        if (activeNode.id != lastFallbackTargetServerId) {
            resetFailoverState()
            return false
        }
        val primaryNode = servers.firstOrNull { it.id == primaryId } ?: run {
            resetFailoverState()
            return false
        }
        val primaryOk = runHeartbeatProbeBatch(primaryNode)
        when (primaryOk) {
            true -> {
                failbackPrimarySuccessCount += 1
                AppLogManager.append(
                    applicationContext,
                    "FAILBACK",
                    "主节点 ${primaryNode.name} 恢复探测成功($failbackPrimarySuccessCount/$FAILBACK_SUCCESS_THRESHOLD)",
                )
                if (failbackPrimarySuccessCount >= FAILBACK_SUCCESS_THRESHOLD) {
                    // False: called off, the monitor keeps running.
                    return switchBackToPrimary(
                        primary = primaryNode,
                        fallback = activeNode,
                    )
                }
            }
            false -> {
                failbackPrimarySuccessCount = 0
            }
            null -> Unit
        }
        return false
    }

    private fun fallbackNodeFor(
        source: ServerNode,
        servers: List<ServerNode>,
    ): ServerNode? {
        val fallbackId = source.fallbackNodeId.trim().takeIf { it.isNotBlank() } ?: return null
        if (!NodeLatencyTester.supportsHeartbeatFallback(source)) {
            val noticeKey = "${source.id}:$fallbackId"
            if (lastUnsupportedFallbackNoticeKey != noticeKey) {
                lastUnsupportedFallbackNoticeKey = noticeKey
                AppLogManager.append(
                    applicationContext,
                    "FALLBACK",
                    "忽略 ${source.name} 的备用节点配置：${NodeLatencyTester.heartbeatFallbackUnsupportedMessage(source)}",
                )
            }
            return null
        }
        if (source.id == lastFallbackTargetServerId) return null
        return servers.firstOrNull { candidate ->
            candidate.id == fallbackId &&
                candidate.id != source.id &&
                !candidate.hiddenUnsupported &&
                ManualNodeFactory.isRunnable(candidate)
        }
    }

    private suspend fun runHeartbeatProbeBatch(server: ServerNode): Boolean? {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching {
                NodeLatencyTester.measureHeartbeat(applicationContext, server)
            }
            result.onSuccess { latency ->
                AppLogManager.append(applicationContext, "HEARTBEAT", "节点心跳成功：${server.name} -> ${latency} ms")
                return true
            }.onFailure { error ->
                if (error is UnsupportedOperationException) {
                    AppLogManager.append(applicationContext, "HEARTBEAT", "跳过心跳：${server.name}", error)
                    return null
                }
                lastError = error
                AppLogManager.append(
                    applicationContext,
                    "HEARTBEAT",
                    "节点心跳失败(${attempt + 1}/3)：${server.name}，${error.message ?: tr("未知错误", "Unknown error", "Неизвестная ошибка")}",
                    error,
                )
            }
            delay(1_000L)
        }
        lastError ?: return null
        return false
    }

    /**
     * True when the switch went ahead (whatever its outcome, it restarts the
     * heartbeat monitor or hands over to a reconnect); false when it was
     * called off because the route or the settings changed meanwhile.
     */
    private suspend fun switchToFallback(
        source: ServerNode,
        fallback: ServerNode,
        reason: String,
    ): Boolean {
        operationMutex.withLock {
            val generation = routeGeneration
            val runtime = VpnRuntimeStore.snapshot.value
            if (runtime.status != ConnectionStatus.Connected || runtime.activeServerId != source.id) return false
            val latestState = AppController.load(applicationContext)
            val latestSource = latestState.servers.firstOrNull { it.id == source.id } ?: return false
            val latestFallback = fallbackNodeFor(latestSource, latestState.servers) ?: return false
            if (latestFallback.id != fallback.id) return false

            val switchingMessage = tr("$reason，正在切换到备用节点 ${latestFallback.name}…", "$reason. Switching to fallback node ${latestFallback.name}…", "$reason. Переключение на резервный узел ${latestFallback.name}…")
            AppLogManager.append(
                applicationContext,
                "FALLBACK",
                "节点 ${latestSource.name} 触发备用切换：${latestFallback.name}，原因：$reason",
            )
            lastFallbackTargetServerId = latestFallback.id
            failbackPrimaryServerId = latestSource.id
            failbackPrimarySuccessCount = 0
            VpnRuntimeStore.updateConnection(
                context = applicationContext,
                status = ConnectionStatus.Connecting,
                activeServerId = latestSource.id,
                message = switchingMessage,
                persist = false,
            )
            VpnQuickSettingsTileHelper.requestRefresh(applicationContext)

            runCatching { switchActiveServer(latestFallback.id) }
                .onSuccess {
                    if (recheckInstalledNodes()) return@onSuccess
                    val message = tr("已切换到备用节点 ${latestFallback.name}", "Switched to fallback node ${latestFallback.name}", "Переключено на резервный узел ${latestFallback.name}")
                    AppLogManager.append(applicationContext, TAG, message)
                    VpnRuntimeStore.updateConnection(
                        applicationContext,
                        status = ConnectionStatus.Connected,
                        activeServerId = latestFallback.id,
                        message = message,
                    )
                    VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                    XrayCoreManager.updateActiveServer(latestFallback.name, message)
                    startHeartbeatMonitor()
                    // A pending media-rule retry was for the previous node: redo it for this one.
                    retryUnappliedStreamingRules(latestFallback.id, generation)
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    resetFailoverState()
                    if (!recoverFromFailedSwitch(latestSource.id, error, generation)) return@onFailure
                    val failureMessage = error.message ?: tr("备用节点切换失败。", "Couldn't switch to the fallback node.", "Не удалось переключиться на резервный узел.")
                    AppLogManager.append(applicationContext, TAG, failureMessage, error)
                    VpnRuntimeStore.updateConnection(
                        applicationContext,
                        status = ConnectionStatus.Connected,
                        activeServerId = latestSource.id,
                        message = failureMessage,
                    )
                    VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                    startHeartbeatMonitor()
                }
        }
        return true
    }

    /** True when the switch went ahead, false when it was called off (see [switchToFallback]). */
    private suspend fun switchBackToPrimary(
        primary: ServerNode,
        fallback: ServerNode,
    ): Boolean {
        operationMutex.withLock {
            val generation = routeGeneration
            val runtime = VpnRuntimeStore.snapshot.value
            if (runtime.status != ConnectionStatus.Connected || runtime.activeServerId != fallback.id) return false
            val latestState = AppController.load(applicationContext)
            val latestPrimary = latestState.servers.firstOrNull { it.id == primary.id } ?: run {
                resetFailoverState()
                return false
            }
            val switchingMessage = tr("主节点 ${latestPrimary.name} 已恢复，正在切回主节点…", "Primary node ${latestPrimary.name} is back; switching back…", "Основной узел ${latestPrimary.name} снова доступен, возврат на него…")
            AppLogManager.append(
                applicationContext,
                "FAILBACK",
                "备用节点 ${fallback.name} 将切回主节点 ${latestPrimary.name}",
            )
            VpnRuntimeStore.updateConnection(
                context = applicationContext,
                status = ConnectionStatus.Connecting,
                activeServerId = fallback.id,
                message = switchingMessage,
                persist = false,
            )
            VpnQuickSettingsTileHelper.requestRefresh(applicationContext)

            runCatching { switchActiveServer(latestPrimary.id) }
                .onSuccess {
                    if (recheckInstalledNodes()) return@onSuccess
                    val message = tr("已切回主节点 ${latestPrimary.name}", "Switched back to primary node ${latestPrimary.name}", "Возврат на основной узел ${latestPrimary.name}")
                    AppLogManager.append(applicationContext, TAG, message)
                    resetFailoverState()
                    VpnRuntimeStore.updateConnection(
                        applicationContext,
                        status = ConnectionStatus.Connected,
                        activeServerId = latestPrimary.id,
                        message = message,
                    )
                    VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                    XrayCoreManager.updateActiveServer(latestPrimary.name, message)
                    startHeartbeatMonitor()
                    // A pending media-rule retry was for the previous node: redo it for this one.
                    retryUnappliedStreamingRules(latestPrimary.id, generation)
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    failbackPrimarySuccessCount = 0
                    if (!recoverFromFailedSwitch(fallback.id, error, generation)) return@onFailure
                    val failureMessage = error.message ?: tr("主节点切回失败。", "Couldn't switch back to the primary node.", "Не удалось вернуться на основной узел.")
                    AppLogManager.append(applicationContext, TAG, failureMessage, error)
                    VpnRuntimeStore.updateConnection(
                        applicationContext,
                        status = ConnectionStatus.Connected,
                        activeServerId = fallback.id,
                        message = failureMessage,
                    )
                    VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                    startHeartbeatMonitor()
                }
        }
        return true
    }

    /**
     * Whether a physical network is up at all. The heartbeat asks before
     * blaming a node for failed probes. Not NET_CAPABILITY_VALIDATED: where
     * the system's own connectivity check is blocked, a working network
     * never gets it. A network that is up but dead is caught by probing the
     * fallback node too.
     */
    private fun hasUnderlyingNetwork(): Boolean = runCatching {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        @Suppress("DEPRECATION")
        connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrDefault(true)

    /**
     * A failed switch can leave nothing behind the tunnel: runXray stops the
     * old core before starting the new one, and fresh local proxy ports are
     * handed out before that. Put the previous node back; if that fails too,
     * disconnect rather than report a tunnel that carries nothing.
     * Returns true when the previous node is running again.
     */
    private suspend fun recoverFromFailedSwitch(previousServerId: String?, error: Throwable, generation: Long): Boolean {
        if (generation != routeGeneration) {
            // A newer connect / switch / disconnect is queued behind this one and
            // sets the tunnel up (or down) itself; disconnecting and stopping the
            // service here would cancel it.
            AppLogManager.append(applicationContext, TAG, "切换失败，已有新的请求，交由新请求处理：${error.message.orEmpty()}")
            return false
        }
        val previousId = previousServerId?.takeIf { it.isNotBlank() }
        if (previousId != null && runCatching { switchActiveServer(previousId) }.isSuccess) {
            AppLogManager.append(applicationContext, TAG, "切换失败，已恢复原节点")
            // The restored node or one it uses may have been removed meanwhile:
            // a reconnect / disconnect is under way, so don't report Connected.
            return !recheckInstalledNodes()
        }
        val message = tr(
            "切换线路失败，VPN 已断开：${error.message.orEmpty()}",
            "Couldn't switch nodes, so the VPN was disconnected: ${error.message.orEmpty()}",
            "Не удалось переключить узел, VPN отключён: ${error.message.orEmpty()}",
        )
        // Restoring the old node took a while: a request may have arrived since.
        if (generation != routeGeneration) {
            AppLogManager.append(applicationContext, TAG, "切换失败，已有新的请求，交由新请求处理：${error.message.orEmpty()}")
            return false
        }
        AppLogManager.append(applicationContext, TAG, message, error)
        // Nothing runs behind the tunnel now: a dropped connection. Reconnect
        // to the node that was running rather than stay off.
        if (previousId != null && scheduleAutoReconnect(previousId, error, message, generation)) return false
        sessionEstablished = false
        publishDisconnectedOnDestroy = true
        destroyMessage = message
        disconnect(updateRuntime = true, message = message)
        stopSelfUnlessSuperseded(generation)
        return false
    }

    private fun resetFailoverState() {
        lastFallbackTargetServerId = null
        lastUnsupportedFallbackNoticeKey = null
        failbackPrimaryServerId = null
        failbackPrimarySuccessCount = 0
    }

    private fun startSpeedMeasurement() {
        statsJob?.cancel()
        statsJob = scope.launch {
            VpnRuntimeStore.setSpeedTestInFlight(applicationContext, inFlight = true, persist = true)
            try {
                val completed = withTimeoutOrNull(SPEED_TEST_TIMEOUT_MS) {
                    delay(SPEED_TEST_SETTLE_MS)
                    val download = runCatching { runDownloadSpeedTest() }.getOrNull()
                    val upload = runCatching { runUploadSpeedTest() }.getOrNull()
                    if (download == null && upload == null) return@withTimeoutOrNull false
                    VpnRuntimeStore.updateTraffic(
                        context = applicationContext,
                        rxBytes = download?.first ?: 0L,
                        txBytes = upload?.first ?: 0L,
                        rxRateBytesPerSec = download?.second ?: 0L,
                        txRateBytesPerSec = upload?.second ?: 0L,
                        interfaceName = null,
                        persist = true,
                    )
                    true
                }
                if (completed == null) {
                    VpnRuntimeStore.setSpeedTestTimedOut(applicationContext, timedOut = true, persist = true)
                    return@launch
                }
            } finally {
                VpnRuntimeStore.setSpeedTestInFlight(applicationContext, inFlight = false, persist = true)
            }
        }
    }

    private fun Builder.applyLocalNetworkExclusions(tailscaleSubnetRoutes: List<String>): Builder = apply {
        val includedPrefixes = tailscaleSubnetRoutes.mapNotNull { route ->
            parseTailscaleSubnetPrefix(route)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Exclude ordinary LAN ranges at the system layer, except when an
            // entire range was explicitly selected as a Tailscale subnet.
            LOCAL_EXCLUDED_ROUTES.forEach { (address, prefixLength) ->
                val excludedAddress = InetAddress.getByName(address)
                if (includedPrefixes.any { it == SubnetPrefix.of(excludedAddress, prefixLength) }) return@forEach
                runCatching {
                    excludeRoute(IpPrefix(excludedAddress, prefixLength))
                }.onFailure { error ->
                    Log.w(TAG, "Unable to exclude local route $address/$prefixLength", error)
                }
            }
        }

        // A selected /24 (or other more-specific prefix) wins over the broad
        // RFC1918 exclusion by longest-prefix matching and re-enters the VPN.
        includedPrefixes.forEach { prefix ->
            runCatching {
                addRoute(prefix.address, prefix.prefixLength)
            }.onFailure { error ->
                Log.w(TAG, "Unable to include Tailscale subnet $prefix", error)
            }
        }
    }

    private fun runDownloadSpeedTest(): Pair<Long, Long> {
        val connection = (URL(DOWNLOAD_TEST_URL).openConnection(speedTestProxy()) as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = SPEED_TEST_CONNECT_TIMEOUT_MS
            readTimeout = SPEED_TEST_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
        }
        return connection.useAndDisconnect {
            val startedAt = System.currentTimeMillis()
            val payload = inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                }
                total
            }
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            payload to ((payload * 1000L) / elapsedMs)
        }
    }

    private fun runUploadSpeedTest(): Pair<Long, Long> {
        val payload = ByteArray(UPLOAD_TEST_BYTES) { (it % 251).toByte() }
        val connection = (URL(UPLOAD_TEST_URL).openConnection(speedTestProxy()) as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = SPEED_TEST_CONNECT_TIMEOUT_MS
            readTimeout = SPEED_TEST_READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(payload.size)
        }
        return connection.useAndDisconnect {
            val startedAt = System.currentTimeMillis()
            outputStream.use { it.write(payload) }
            runCatching {
                inputStream.use { it.copyTo(ByteArrayOutputStream()) }
            }
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            payload.size.toLong() to ((payload.size.toLong() * 1000L) / elapsedMs)
        }
    }

    private fun speedTestProxy(): Proxy {
        // Authenticated SOCKS inbound of this run (forced to the proxy outbound);
        // LocalProxyRuntime's scoped Authenticator supplies the credentials.
        return LocalProxyRuntime.current()?.socks?.socksProxy()
            ?: error(tr("本地测速入口尚未就绪。", "The local speed test entry isn't ready.", "Локальная точка проверки скорости не готова."))
    }

    private inline fun <T> HttpsURLConnection.useAndDisconnect(block: HttpsURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

    private suspend fun resolveDnsAddress(
        context: Context,
        settings: com.mallocgfw.app.model.AppSettings,
    ): String? {
        // Keep the user's existing VPN DNS path intact. Sending all Android
        // DNS to Tailscale's 100.100.100.100 resolver makes ordinary domains
        // depend on the userspace Tailnet path and breaks normal proxying.
        return when (settings.dnsMode) {
            AppDnsMode.System -> resolveSystemDns(context) ?: DEFAULT_REMOTE_DNS
            AppDnsMode.Remote -> DEFAULT_REMOTE_DNS
            // Encrypted DNS is answered inside the tunnel; an unusable value falls back to the system DNS.
            AppDnsMode.Custom -> DnsInput.hostOf(settings.customDnsValue)
                ?: XrayConfigFactory.ENCRYPTED_DNS_ADDRESS.takeIf { encryptedDnsOf(settings) != null }
                ?: resolveSystemDns(context)
                ?: DEFAULT_REMOTE_DNS
        }
    }

    private suspend fun resolveDnsEndpoint(
        context: Context,
        settings: com.mallocgfw.app.model.AppSettings,
    ): String? {
        return when (settings.dnsMode) {
            AppDnsMode.System -> dnsEndpointOf(resolveSystemDns(context) ?: DEFAULT_REMOTE_DNS)
            AppDnsMode.Remote -> dnsEndpointOf(DEFAULT_REMOTE_DNS)
            AppDnsMode.Custom -> dnsEndpointOf(
                // Port 53, like Android's own queries to this server. With encrypted
                // DNS, PurpleBear's own lookups (e.g. the DoQ server name) use the system DNS.
                DnsInput.hostOf(settings.customDnsValue) ?: resolveSystemDns(context) ?: DEFAULT_REMOTE_DNS,
            )
        }
    }

    /**
     * libXray's SetDNS only accepts IP endpoints such as `8.8.8.8:53` or
     * `[2001:4860:4860::8888]:53`, so bare IPv6 literals need brackets.
     */
    private fun dnsEndpointOf(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("[") -> if ("]:" in trimmed) trimmed else "$trimmed:53"
            trimmed.count { it == ':' } > 1 -> "[$trimmed]:53"
            ":" in trimmed -> trimmed
            else -> "$trimmed:53"
        }
    }

    private fun installProcessDns(endpoint: String) {
        // DNS for app-level Go lookups is best-effort: a bad custom value must
        // not abort the tunnel, Xray itself still resolves through its outbounds.
        runCatching { LibXray.setDNS(SocketProtector, endpoint) }
            .onFailure { error ->
                AppLogManager.append(applicationContext, TAG, "进程 DNS 设置失败（$endpoint）：${error.message}")
            }
    }

    private suspend fun resolveSystemDns(context: Context, log: Boolean = true): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val preferred = snapshotDirectNetworks(
            connectivityManager = connectivityManager,
            waitMs = SYSTEM_DNS_NETWORK_WAIT_MS,
        ).asSequence()
            .mapNotNull { network -> connectivityManager.getLinkProperties(network)?.firstUsableDns() }
            .firstOrNull()
            ?: connectivityManager.activeNetwork
                ?.let(connectivityManager::getLinkProperties)
                ?.firstUsableDns()
        if (!log) return preferred
        if (preferred != null) {
            AppLogManager.append(applicationContext, TAG, "使用系统 DNS：$preferred")
        } else {
            AppLogManager.append(applicationContext, TAG, "未获取到系统 DNS，回退到远端 $DEFAULT_REMOTE_DNS")
        }
        return preferred
    }

    private fun LinkProperties.firstUsableDns(): String? {
        return dnsServers
            .asSequence()
            .filter { address ->
                !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            }
            .sortedBy { address ->
                when (address) {
                    is Inet4Address -> 0
                    is Inet6Address -> 1
                    else -> 2
                }
            }
            .map { it.hostAddress ?: it.toString() }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun buildNotification(contentText: String): Notification {
        ensureChannel()
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PurpleBear")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PurpleBear VPN",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startServiceForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "XrayVpnService"
        private const val DEFAULT_REMOTE_DNS = "1.1.1.1"
        private const val ACTION_CONNECT = "com.mallocgfw.app.action.CONNECT_VPN"
        private const val ACTION_SWITCH = "com.mallocgfw.app.action.SWITCH_VPN_ROUTE"
        private const val ACTION_DISCONNECT = "com.mallocgfw.app.action.DISCONNECT_VPN"
        private const val ACTION_REFRESH_SPEED = "com.mallocgfw.app.action.REFRESH_SPEED"
        private const val EXTRA_SERVER_ID = "extra_server_id"
        private const val EXTRA_KEEP_FAILOVER = "extra_keep_failover"
        private const val EXTRA_AUTO_RETRY = "extra_auto_retry"

        /** Pauses before each automatic reconnect; their number is the number of attempts. */
        private val AUTO_RECONNECT_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)
        private const val EXTRA_INTERNAL_DISCONNECT = "extra_internal_disconnect"
        private const val NOTIFICATION_CHANNEL_ID = "mallocgfw_vpn"
        private const val NOTIFICATION_ID = 2001
        private const val SPEED_TEST_SETTLE_MS = 1_500L
        private const val SPEED_TEST_TIMEOUT_MS = 22_000L
        private const val SYSTEM_DNS_NETWORK_WAIT_MS = 1_000L
        private const val SYSTEM_DNS_SETTLE_MS = 3_000L
        private const val STREAMING_RETRY_DELAY_MS = 3_000L
        private const val DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 5
        private const val FAILBACK_SUCCESS_THRESHOLD = 3
        private const val SPEED_TEST_CONNECT_TIMEOUT_MS = 6_000
        private const val SPEED_TEST_READ_TIMEOUT_MS = 8_000
        private const val LOG_MAINTENANCE_INTERVAL_MS = 5 * 60 * 1_000L
        private const val UPLOAD_TEST_BYTES = 64 * 1024
        private const val DOWNLOAD_TEST_URL = "https://speed.cloudflare.com/__down?bytes=262144"
        private const val UPLOAD_TEST_URL = "https://speed.cloudflare.com/__up"
        private val HEARTBEAT_INTERVAL_OPTIONS_MINUTES = setOf(2, 5, 10)
        private const val TAILSCALE_RECOVERY_DELAY_MS = 1_500L
        /** A node with saved state usually logs in within 1–3 s. */
        private const val TAILSCALE_CONNECT_WAIT_MS = 6_000L
        private const val TAILSCALE_RECOVERY_WINDOW_MS = 10 * 60_000L
        private const val TAILSCALE_RECOVERY_LIMIT = 3
        private val LOCAL_EXCLUDED_ROUTES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "169.254.0.0" to 16,
            "224.0.0.0" to 4,
            "255.255.255.255" to 32,
        )
        @Volatile
        private var runningInstance: XrayVpnService? = null

        /**
         * Xray-core keeps every registered dialer/listener controller in a
         * list and fails a dial if any of them fails; registering again only
         * appends. So register one controller per process that forwards to
         * whichever service is running now. Registering per connect (and a
         * "return false" stub on destroy) made every connection after the
         * first fail with "protect Android VPN socket failed".
         */
        private object SocketProtector : DialerController {
            // No VPN (e.g. NodeLatencyTester's temporary core): nothing to
            // route around, so the socket is fine as it is.
            override fun protectFd(fd: Long): Boolean = runningInstance?.protect(fd.toInt()) ?: true
        }

        @Volatile
        private var socketProtectorRegistered = false

        private fun ensureSocketProtectorRegistered() {
            if (socketProtectorRegistered) return
            synchronized(SocketProtector) {
                if (socketProtectorRegistered) return
                LibXray.registerDialerController(SocketProtector)
                LibXray.registerListenerController(SocketProtector)
                socketProtectorRegistered = true
            }
        }

        fun isRunning(): Boolean = runningInstance != null

        fun protectSocket(socket: Socket): Boolean {
            val service = runningInstance ?: return false
            return service.protect(socket)
        }

        fun connectIntent(context: Context, serverId: String): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_ID, serverId)
            }
        }

        /**
         * [internal]: part of the app's own reconnect (disconnect, then connect),
         * not the user turning the VPN off; allowed even in lockdown mode.
         */
        fun disconnectIntent(context: Context, internal: Boolean = false): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_DISCONNECT
                putExtra(EXTRA_INTERNAL_DISCONNECT, internal)
            }
        }

        /**
         * Always-on with "Block connections without VPN" (lockdown) is on for
         * this app: the system blocks every connection outside the VPN, so
         * taking the tunnel down by hand would leave the phone offline. The
         * user turns those off in system settings to disconnect.
         */
        fun manualDisconnectBlocked(): Boolean {
            val service = runningInstance ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return service.isAlwaysOn && service.isLockdownEnabled
            // Android 8 and 9 have lockdown too, but VpnService.isAlwaysOn /
            // isLockdownEnabled only came in 10: read the settings they are
            // stored in (Settings.Secure ALWAYS_ON_VPN_APP / _LOCKDOWN), which
            // those versions let apps read.
            return runCatching {
                val resolver = service.contentResolver
                android.provider.Settings.Secure.getString(resolver, "always_on_vpn_app") == service.packageName &&
                    android.provider.Settings.Secure.getInt(resolver, "always_on_vpn_lockdown", 0) == 1
            }.getOrDefault(false)
        }

        /** Short: toasts on some phones (Xiaomi) show only two lines. */
        fun lockdownDisconnectMessage(): String = tr(
            "已开启屏蔽非 VPN 连接，断开会断网。请先在系统 VPN 设置中关闭。",
            "Connections without VPN are blocked; disconnecting would cut you off. Turn that off in system VPN settings first.",
            "Подключения без VPN заблокированы: отключение оставит без сети. Сначала выключите это в настройках VPN.",
        )

        fun switchIntent(context: Context, serverId: String): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_SWITCH
                putExtra(EXTRA_SERVER_ID, serverId)
            }
        }

        /**
         * Measures the speed again in the running service. A direct call, not a
         * start request: a start would take a new start id, and a disconnect's
         * stopSelfResult(startId) would then refuse to stop the service, leaving
         * it (and the UI, which waits for onDestroy) on "Disconnecting".
         */
        fun requestSpeedRefresh() {
            val service = runningInstance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (runningInstance === service && VpnRuntimeStore.snapshot.value.status == ConnectionStatus.Connected) {
                    AppLogManager.append(service.applicationContext, TAG, "收到速度刷新请求")
                    service.startSpeedMeasurement()
                }
            }
        }
    }
}
