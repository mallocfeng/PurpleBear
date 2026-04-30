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
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.model.AppDnsMode
import com.mallocgfw.app.model.AppStateStore
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
    private var lastFallbackTargetServerId: String? = null
    private var lastUnsupportedFallbackNoticeKey: String? = null
    private var failbackPrimaryServerId: String? = null
    private var failbackPrimarySuccessCount = 0
    private var publishDisconnectedOnDestroy = false
    private var cleanupCompleted = false
    private var destroyMessage = "VPN 已断开"
    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        cleanupCompleted = false
        runningInstance = this
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                publishDisconnectedOnDestroy = false
                destroyMessage = "VPN 已断开"
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                AppLogManager.append(applicationContext, TAG, "收到连接请求，serverId=$serverId")
                VpnRuntimeStore.reset(
                    applicationContext,
                    status = ConnectionStatus.Connecting,
                    activeServerId = serverId,
                    message = "正在建立系统 VPN 隧道…",
                )
                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                startServiceForeground(buildNotification("正在建立 VPN 隧道…"))
                scope.launch {
                    operationMutex.withLock {
                        cleanupCompleted = false
                        runCatching { connect(serverId) }
                            .onSuccess {
                                val connectedAtMs = System.currentTimeMillis()
                                AppLogManager.append(applicationContext, TAG, "VPN 已连接，serverId=$serverId")
                                VpnRuntimeStore.updateConnection(
                                    applicationContext,
                                    status = ConnectionStatus.Connected,
                                    activeServerId = serverId,
                                    message = "系统 VPN 已建立，Xray 正在运行",
                                    connectedAtMs = connectedAtMs,
                                    interfaceName = null,
                                )
                                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                                startSpeedMeasurement()
                                startLogMaintenance()
                                startHeartbeatMonitor()
                                val notification = buildNotification("VPN 已连接")
                                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                manager.notify(NOTIFICATION_ID, notification)
                            }
                            .onFailure { error ->
                                if (error is CancellationException) return@onFailure
                                publishDisconnectedOnDestroy = false
                                val failureMessage = XrayCoreManager.snapshot.value.message
                                    ?: error.message
                                    ?: "VPN 建立失败。"
                                AppLogManager.append(applicationContext, TAG, "VPN 连接失败：$failureMessage", error)
                                Log.e(TAG, "VPN connect failed: $failureMessage", error)
                                VpnRuntimeStore.reset(
                                    applicationContext,
                                    status = ConnectionStatus.Disconnected,
                                    activeServerId = null,
                                    message = failureMessage,
                                )
                                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                                disconnect(updateRuntime = false)
                                stopSelf()
                            }
                    }
                }
            }

            ACTION_SWITCH -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                AppLogManager.append(applicationContext, TAG, "收到热切换请求，serverId=$serverId")
                scope.launch {
                    operationMutex.withLock {
                        runCatching { switchActiveServer(serverId) }
                            .onSuccess {
                                val server = AppStateStore.load(applicationContext).servers.firstOrNull { it.id == serverId }
                                val message = server?.let { "已切换到 ${it.name}" } ?: "已切换线路"
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
                            }
                            .onFailure { error ->
                                if (error is CancellationException) return@onFailure
                                val activeServerId = VpnRuntimeStore.snapshot.value.activeServerId
                                val failureMessage = error.message ?: "切换线路失败。"
                                AppLogManager.append(applicationContext, TAG, failureMessage, error)
                                VpnRuntimeStore.updateConnection(
                                    applicationContext,
                                    status = ConnectionStatus.Connected,
                                    activeServerId = activeServerId,
                                    message = failureMessage,
                                )
                                VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
                            }
                    }
                }
            }

            ACTION_DISCONNECT -> {
                publishDisconnectedOnDestroy = true
                destroyMessage = "VPN 已断开"
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
                        disconnect(updateRuntime = false, message = destroyMessage)
                        stopSelf()
                    }
                }
            }

            ACTION_REFRESH_SPEED -> {
                AppLogManager.append(applicationContext, TAG, "收到速度刷新请求")
                if (VpnRuntimeStore.snapshot.value.status == ConnectionStatus.Connected) {
                    startSpeedMeasurement()
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
            message = destroyMessage,
        )
        // Replace the libXray dialer/listener registration with a no-op stub so
        // any future calls (e.g. NodeLatencyTester spinning up a temporary
        // Xray) don't end up invoking protect() on this destroyed service.
        runCatching { LibXray.registerDialerController(NOOP_DIALER_CONTROLLER) }
        runCatching { LibXray.registerListenerController(NOOP_DIALER_CONTROLLER) }
        VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
        super.onDestroy()
    }

    override fun onRevoke() {
        publishDisconnectedOnDestroy = true
        destroyMessage = "VPN 权限已撤销，连接已断开"
        AppLogManager.append(applicationContext, TAG, "VPN 权限被系统撤销")
        scope.launch {
            operationMutex.withLock {
                disconnect(updateRuntime = false, message = destroyMessage)
                stopSelf()
            }
        }
        super.onRevoke()
    }

    private suspend fun connect(serverId: String) {
        LibXray.registerDialerController(dialerController)
        LibXray.registerListenerController(dialerController)
        statsJob?.cancel()
        statsJob = null
        logMaintenanceJob?.cancel()
        logMaintenanceJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupCompleted = false
        resetFailoverState()
        AppLogManager.trimAllLogs(applicationContext)
        val state = AppStateStore.load(applicationContext)
        AppLogManager.append(applicationContext, TAG, "连接启动，DNS 模式：${state.settings.dnsMode.name}")
        val dnsServer = resolveDnsAddress(applicationContext, state.settings)
        val dnsEndpoint = resolveDnsEndpoint(applicationContext, state.settings)
        val server = state.servers.firstOrNull { it.id == serverId }
            ?: error("未找到要连接的节点。")
        val vpnMtu = normalizedAppVpnMtu(state.settings.vpnMtu)
        val globalProxyEnabled = state.settings.globalProxyEnabled
        val ruleRouting = if (globalProxyEnabled) {
            RuleRoutingSetup()
        } else {
            RuleSourceManager.buildRoutingSetup(
                context = applicationContext,
                sources = state.ruleSources,
                servers = state.servers,
            )
        }
        val streamingRouting = if (globalProxyEnabled) {
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
            .applyLocalNetworkExclusions()
        dnsServer?.let(builder::addDnsServer)

        if (state.proxyMode == ProxyMode.PerApp && !globalProxyEnabled) {
            val allowedPackages = buildSet {
                add(packageName)
                state.protectedApps.filter { it.enabled }.forEach { add(it.id) }
            }
            allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed package not installed: $pkg")
                }
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
        vpnInterface = builder.establish() ?: error("无法建立系统 VPN 接口。")
        val tunFd = vpnInterface?.fd ?: error("无法获取 TUN fd。")
        dnsEndpoint?.let { LibXray.initDns(dialerController, it) }

        XrayCoreManager.startVpn(
            context = applicationContext,
            server = server,
            tunFd = tunFd,
            routingRules = streamingRouting.routingRules + ruleRouting.routingRules,
            additionalOutbounds = streamingRouting.outbounds + ruleRouting.outbounds,
            vpnMtu = vpnMtu,
        )
            .getOrElse { throw it }
    }

    private suspend fun switchActiveServer(serverId: String) {
        val state = AppStateStore.load(applicationContext)
        val server = state.servers.firstOrNull { it.id == serverId }
            ?: error("未找到要切换的节点。")
        val tunFd = vpnInterface?.fd ?: error("系统 VPN 还没有建立，无法热切换线路。")
        val vpnMtu = normalizedAppVpnMtu(state.settings.vpnMtu)
        val dnsEndpoint = resolveDnsEndpoint(applicationContext, state.settings)
        val globalProxyEnabled = state.settings.globalProxyEnabled
        val ruleRouting = if (globalProxyEnabled) {
            RuleRoutingSetup()
        } else {
            RuleSourceManager.buildRoutingSetup(
                context = applicationContext,
                sources = state.ruleSources,
                servers = state.servers,
            )
        }
        val streamingRouting = if (globalProxyEnabled) {
            StreamingRoutingSetup()
        } else {
            StreamingMediaManager.buildRoutingSetup(
                context = applicationContext,
                settings = state.settings,
                selectedServerId = server.id,
                servers = state.servers,
            )
        }
        dnsEndpoint?.let { LibXray.initDns(dialerController, it) }
        XrayCoreManager.startVpn(
            context = applicationContext,
            server = server,
            tunFd = tunFd,
            routingRules = streamingRouting.routingRules + ruleRouting.routingRules,
            additionalOutbounds = streamingRouting.outbounds + ruleRouting.outbounds,
            vpnMtu = vpnMtu,
        ).getOrElse { throw it }
    }

    private suspend fun disconnect(
        updateRuntime: Boolean = true,
        message: String = "VPN 已断开",
    ) {
        withContext(Dispatchers.IO) {
            disconnectNow(updateRuntime = updateRuntime, message = message)
        }
    }

    private fun disconnectNow(
        updateRuntime: Boolean = true,
        message: String = "VPN 已断开",
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
        runCatching { LibXray.resetDns() }
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
                val state = AppStateStore.load(applicationContext)
                val intervalMinutes = state.settings.heartbeatIntervalMinutes
                    .takeIf { it in HEARTBEAT_INTERVAL_OPTIONS_MINUTES }
                    ?: DEFAULT_HEARTBEAT_INTERVAL_MINUTES
                delay(intervalMinutes * 60_000L)

                val runtime = VpnRuntimeStore.snapshot.value
                if (runtime.status != ConnectionStatus.Connected) return@launch
                val activeId = runtime.activeServerId?.takeIf { it.isNotBlank() } ?: continue
                val latestState = AppStateStore.load(applicationContext)
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
                    switchToFallback(
                        source = activeNode,
                        fallback = fallbackNode,
                        reason = "心跳连续 3 次检测失败",
                    )
                    return@launch
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
                    switchBackToPrimary(
                        primary = primaryNode,
                        fallback = activeNode,
                    )
                    return true
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
                ManualNodeFactory.supportsPreProxy(candidate)
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
                    "节点心跳失败(${attempt + 1}/3)：${server.name}，${error.message ?: "未知错误"}",
                    error,
                )
            }
            delay(1_000L)
        }
        lastError ?: return null
        return false
    }

    private suspend fun switchToFallback(
        source: ServerNode,
        fallback: ServerNode,
        reason: String,
    ) {
        operationMutex.withLock {
            val runtime = VpnRuntimeStore.snapshot.value
            if (runtime.status != ConnectionStatus.Connected || runtime.activeServerId != source.id) return
            val latestState = AppStateStore.load(applicationContext)
            val latestSource = latestState.servers.firstOrNull { it.id == source.id } ?: return
            val latestFallback = fallbackNodeFor(latestSource, latestState.servers) ?: return
            if (latestFallback.id != fallback.id) return

            val switchingMessage = "$reason，正在切换到备用节点 ${latestFallback.name}…"
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
                    val message = "已切换到备用节点 ${latestFallback.name}"
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
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    resetFailoverState()
                    val failureMessage = error.message ?: "备用节点切换失败。"
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
    }

    private suspend fun switchBackToPrimary(
        primary: ServerNode,
        fallback: ServerNode,
    ) {
        operationMutex.withLock {
            val runtime = VpnRuntimeStore.snapshot.value
            if (runtime.status != ConnectionStatus.Connected || runtime.activeServerId != fallback.id) return
            val latestState = AppStateStore.load(applicationContext)
            val latestPrimary = latestState.servers.firstOrNull { it.id == primary.id } ?: run {
                resetFailoverState()
                return
            }
            val switchingMessage = "主节点 ${latestPrimary.name} 已恢复，正在切回主节点…"
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
                    val message = "已切回主节点 ${latestPrimary.name}"
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
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    failbackPrimarySuccessCount = 0
                    val failureMessage = error.message ?: "主节点切回失败。"
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

    private fun Builder.applyLocalNetworkExclusions(): Builder = apply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@apply

        // Xray still keeps private IPs on direct outbound, but LAN file transfer
        // and service discovery break more easily if the packets enter the VPN
        // tunnel first. Exclude them at the system VPN layer when supported.
        LOCAL_EXCLUDED_ROUTES.forEach { (address, prefixLength) ->
            runCatching {
                excludeRoute(IpPrefix(InetAddress.getByName(address), prefixLength))
            }.onFailure { error ->
                Log.w(TAG, "Unable to exclude local route $address/$prefixLength", error)
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
        return Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", XrayConfigFactory.HTTP_PORT),
        )
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
        return when (settings.dnsMode) {
            AppDnsMode.System -> resolveSystemDns(context) ?: DEFAULT_REMOTE_DNS
            AppDnsMode.Remote -> DEFAULT_REMOTE_DNS
            AppDnsMode.Custom -> settings.customDnsValue.substringBefore(":").trim().takeIf { it.isNotBlank() }
        }
    }

    private suspend fun resolveDnsEndpoint(
        context: Context,
        settings: com.mallocgfw.app.model.AppSettings,
    ): String? {
        return when (settings.dnsMode) {
            AppDnsMode.System -> resolveSystemDns(context)?.let { "$it:53" } ?: "$DEFAULT_REMOTE_DNS:53"
            AppDnsMode.Remote -> "$DEFAULT_REMOTE_DNS:53"
            AppDnsMode.Custom -> settings.customDnsValue.trim().takeIf { it.isNotBlank() }?.let { value ->
                if (":" in value) value else "$value:53"
            }
        }
    }

    private suspend fun resolveSystemDns(context: Context): String? {
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
        private const val NOTIFICATION_CHANNEL_ID = "mallocgfw_vpn"
        private const val NOTIFICATION_ID = 2001
        private const val SPEED_TEST_SETTLE_MS = 1_500L
        private const val SPEED_TEST_TIMEOUT_MS = 10_000L
        private const val SYSTEM_DNS_NETWORK_WAIT_MS = 1_000L
        private const val DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 5
        private const val FAILBACK_SUCCESS_THRESHOLD = 3
        private const val SPEED_TEST_CONNECT_TIMEOUT_MS = 8_000
        private const val SPEED_TEST_READ_TIMEOUT_MS = 12_000
        private const val LOG_MAINTENANCE_INTERVAL_MS = 5 * 60 * 1_000L
        private const val UPLOAD_TEST_BYTES = 256 * 1024
        private const val DOWNLOAD_TEST_URL = "https://speed.cloudflare.com/__down?bytes=1048576"
        private const val UPLOAD_TEST_URL = "https://speed.cloudflare.com/__up"
        private val HEARTBEAT_INTERVAL_OPTIONS_MINUTES = setOf(2, 5, 10)
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

        private val NOOP_DIALER_CONTROLLER = object : DialerController {
            override fun protectFd(fd: Long): Boolean = false
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

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }

        fun switchIntent(context: Context, serverId: String): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_SWITCH
                putExtra(EXTRA_SERVER_ID, serverId)
            }
        }

        fun refreshSpeedIntent(context: Context): Intent {
            return Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_REFRESH_SPEED
            }
        }
    }
}
