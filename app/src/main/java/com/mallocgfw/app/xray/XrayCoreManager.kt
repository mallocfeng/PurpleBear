package com.mallocgfw.app.xray

import com.mallocgfw.app.model.RuleSourceManager

import android.content.Context
import android.os.Build
import android.util.Log
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.XrayNamedOutbound
import com.mallocgfw.app.model.XrayRoutingRule
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import libXray.LibXray
import org.json.JSONObject
import com.mallocgfw.app.model.tr

enum class XrayCoreStatus {
    Idle,
    Preparing,
    Ready,
    Starting,
    Running,
    Failed,
}

data class XrayCoreSnapshot(
    val status: XrayCoreStatus = XrayCoreStatus.Idle,
    val abi: String? = null,
    val version: String? = null,
    val message: String? = null,
    val localSocksPort: Int? = null,
    val localHttpPort: Int? = null,
    val activeServerName: String? = null,
)

object XrayCoreManager {
    private const val TAG = "XrayCoreManager"
    private const val LIBXRAY_API_VERSION = 3
    private const val ENV_ASSET_LOCATION = "xray.location.asset"
    private const val ENV_TUN_FD = "xray.tun.fd"
    private val coreLock = Any()
    private val _snapshot = MutableStateFlow(XrayCoreSnapshot())
    val snapshot: StateFlow<XrayCoreSnapshot> = _snapshot.asStateFlow()

    suspend fun initialize(context: Context) {
        withContext(Dispatchers.IO) {
            XrayNativeLoader.ensureLoaded()
            val prepared = prepareRuntime(context)
            val version = xrayVersion()
            _snapshot.value = XrayCoreSnapshot(
                status = XrayCoreStatus.Ready,
                abi = prepared.assetAbi,
                version = version,
                message = "官方 Xray 内核已就绪",
            )
        }
    }

    suspend fun start(
        context: Context,
        server: ServerNode,
        routingRules: List<XrayRoutingRule> = emptyList(),
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
    ): Result<XrayCoreSnapshot> {
        // Hard guard: a standalone Xray instance and the VpnService share the
        // same global libXray singleton. Starting one while the other is alive
        // would `stopInternal()` the active VPN's core mid-tunnel.
        if (XrayVpnService.isRunning()) {
            return Result.failure(
                IllegalStateException(tr("当前已连接 VPN，请先断开后再启动独立 Xray 实例。", "VPN is connected. Disconnect before starting a separate Xray instance.", "VPN подключён. Отключитесь, прежде чем запускать отдельный экземпляр Xray.")),
            )
        }
        return startWithConfig(
            context = context,
            server = server,
            message = tr("正在启动 Xray 内核…", "Starting the Xray core…", "Запуск ядра Xray…"),
            tunFd = null,
            additionalOutbounds = additionalOutbounds,
        ) { localProxy ->
            val state = AppController.load(context)
            val settings = state.settings
            XrayConfigFactory.build(
                node = server,
                availableServers = state.servers,
                routingRules = routingRules,
                additionalOutbounds = additionalOutbounds,
                logLevel = settings.logLevel.wireValue,
                errorLogPath = AppLogManager.xrayErrorLogFile(context).absolutePath,
                accessLogPath = AppLogManager.xrayAccessLogFile(context).absolutePath,
                globalProxyEnabled = settings.globalProxyEnabled,
                geoRoutingRegion = settings.geoRoutingRegion,
                homeSitesDirect = RuleSourceManager.homeSitesDirect(state.ruleSources),
                tailscale = TailscaleRuntimeManager.snapshot.value.routing(settings),
                localProxy = localProxy,
            )
        }
    }

    suspend fun startVpn(
        context: Context,
        server: ServerNode,
        tunFd: Int,
        routingRules: List<XrayRoutingRule> = emptyList(),
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
        vpnMtu: Int = XrayConfigFactory.DEFAULT_VPN_MTU,
        magicDnsUpstream: String? = null,
        dnsServer: String? = null,
        dnsOverProxy: Boolean = false,
        encryptedDns: com.mallocgfw.app.model.DnsInput.EncryptedDns? = null,
    ): Result<XrayCoreSnapshot> {
        return startWithConfig(
            context = context,
            server = server,
            message = tr("正在建立系统 VPN 隧道…", "Setting up the system VPN tunnel…", "Создание системного VPN-туннеля…"),
            tunFd = tunFd,
            additionalOutbounds = additionalOutbounds,
        ) { localProxy ->
            val state = AppController.load(context)
            val settings = state.settings
            XrayConfigFactory.buildVpn(
                node = server,
                availableServers = state.servers,
                routingRules = routingRules,
                additionalOutbounds = additionalOutbounds,
                logLevel = settings.logLevel.wireValue,
                errorLogPath = AppLogManager.xrayErrorLogFile(context).absolutePath,
                accessLogPath = AppLogManager.xrayAccessLogFile(context).absolutePath,
                vpnMtu = vpnMtu,
                magicDnsUpstream = magicDnsUpstream,
                globalProxyEnabled = settings.globalProxyEnabled,
                geoRoutingRegion = settings.geoRoutingRegion,
                homeSitesDirect = RuleSourceManager.homeSitesDirect(state.ruleSources),
                tailscale = TailscaleRuntimeManager.snapshot.value.routing(settings),
                proxyQuicEnabled = settings.proxyQuicEnabled,
                dnsServer = dnsServer,
                dnsOverProxy = dnsOverProxy,
                encryptedDns = encryptedDns,
                localProxy = localProxy,
            )
        }
    }

    /** A node that may need a pinned certificate, and the node it dials through. */
    private data class PinCandidate(val node: ServerNode, val front: ServerNode?)

    /**
     * The node, any extra routed outbounds, and each one's pre-proxy chain,
     * nodes nearest the device first (a front must be pinned before the node
     * behind it can be reached through it).
     */
    private fun pinCandidates(
        context: Context,
        server: ServerNode,
        additionalOutbounds: List<XrayNamedOutbound>,
    ): List<PinCandidate> {
        val servers = runCatching { AppController.load(context).servers }.getOrDefault(emptyList())
        return (listOf(server) + additionalOutbounds.map { it.node }).flatMap { root ->
            val chain = mutableListOf<PinCandidate>()
            var current = root
            val visited = mutableSetOf(root.id)
            while (chain.size < 8) {
                val front = XrayConfigFactory.resolvePreProxyNode(current, servers, visited)
                chain += PinCandidate(current, front)
                current = front ?: break
                visited += current.id
            }
            chain.asReversed()
        }.distinctBy { it.node.id to it.front?.id }
    }

    /**
     * Fetches certificates of skip-verify nodes that sit behind a pre-proxy:
     * runs only the pre-proxy chain for a moment and handshakes through it.
     */
    private suspend fun fetchChainedPins(
        context: Context,
        chained: List<PinCandidate>,
        localProxy: LocalProxyAllocation,
        datDir: String,
        sidecarNodes: List<ServerNode>,
    ) {
        if (chained.isEmpty() || !InsecureTlsPinner.hasPending(context, chained.map { it.node })) return
        val servers = runCatching { AppController.load(context).servers }.getOrDefault(emptyList())
        for (candidate in chained) {
            val front = candidate.front ?: continue
            if (!InsecureTlsPinner.hasPending(context, listOf(candidate.node))) continue
            val config = runCatching { XrayConfigFactory.buildPinFetchConfig(front, servers, localProxy, sidecarNodes) }
                .onFailure { Log.w(TAG, "Unable to build pin fetch config", it) }
                .getOrNull() ?: continue
            val response = synchronized(coreLock) {
                stopInternal()
                invoke(
                    method = "runXray",
                    payload = JSONObject().put("xrayJson", withRuntimeEnv(config, datDir, null)),
                )
            }
            try {
                if (response.success) {
                    InsecureTlsPinner.refreshThrough(context, listOf(candidate.node), localProxy.socks.socksProxy())
                } else {
                    Log.w(TAG, "Pin fetch core did not start: ${response.error}")
                }
            } finally {
                synchronized(coreLock) { stopInternal() }
            }
        }
    }

    private suspend fun startWithConfig(
        context: Context,
        server: ServerNode,
        message: String,
        tunFd: Int?,
        additionalOutbounds: List<XrayNamedOutbound> = emptyList(),
        buildConfigJson: (LocalProxyAllocation) -> String,
    ): Result<XrayCoreSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                XrayNativeLoader.ensureLoaded()
                // Nodes that skip certificate verification need a pinned
                // certificate before the config is built (Xray rejects allowInsecure).
                val pinCandidates = pinCandidates(context, server, additionalOutbounds)
                InsecureTlsPinner.refresh(context, pinCandidates.filter { it.front == null }.map { it.node })
                val prepared = prepareRuntime(context)
                val datDir = prepared.runtimeDir.absolutePath
                // Fresh random ports and credentials for every run; see LocalProxyRuntime.
                val localProxy = LocalProxyRuntime.allocate(::freePortsFromCore)
                val servers = runCatching { AppController.load(context).servers }.getOrDefault(emptyList())
                // Includes pre-proxy fronts run by the sidecar; same list and order
                // as the config builder uses, so listener slots match.
                val sidecarNodes = XrayConfigFactory.sidecarNodesFor(server, additionalOutbounds.map { it.node }, servers)
                // Started before the pin fetch: a chain may go through a sidecar front.
                SsrRuntimeManager.startIfNeeded(context, sidecarNodes, localProxy).getOrElse { throw it }
                fetchChainedPins(context, pinCandidates.filter { it.front != null }, localProxy, datDir, sidecarNodes)
                val configJson = buildConfigJson(localProxy)
                _snapshot.value = _snapshot.value.copy(
                    status = XrayCoreStatus.Starting,
                    message = message,
                    activeServerName = server.name,
                )
                val runtimeConfigJson = withRuntimeEnv(configJson, datDir, tunFd)
                val response = synchronized(coreLock) {
                    stopInternal()
                    invoke(
                        method = "runXray",
                        payload = JSONObject().put("xrayJson", runtimeConfigJson),
                    )
                }
                if (!response.success) {
                    val failureMessage = response.error ?: tr("Xray 启动失败。", "Xray failed to start.", "Не удалось запустить Xray.")
                    Log.e(TAG, "Unable to start Xray: $failureMessage")
                    SsrRuntimeManager.stopNow()
                    LocalProxyRuntime.clear()
                    _snapshot.value = _snapshot.value.copy(
                        status = XrayCoreStatus.Failed,
                        abi = prepared.assetAbi,
                        version = xrayVersion(),
                        message = failureMessage,
                        activeServerName = null,
                    )
                    return@withContext Result.failure(IllegalStateException(failureMessage))
                }

                // libXray's runXray returns after core.Start(); still verify outside
                // of coreLock that the core reports running and its local HTTP inbound
                // is accepting connections. The HTTP inbound is also the safe egress path for app-level
                // resource refreshes while PurpleBear itself is excluded from the VPN.
                val isRunning = waitForRunningState()
                if (!isRunning) {
                    val failureMessage = tr("Xray 启动后未进入运行状态，本地 HTTP 代理不可用。", "Xray didn't reach the running state after starting; the local HTTP proxy is unavailable.", "После запуска Xray не перешёл в рабочее состояние, локальный HTTP-прокси недоступен.")
                    Log.e(TAG, failureMessage)
                    synchronized(coreLock) {
                        stopInternal()
                    }
                    SsrRuntimeManager.stopNow()
                    LocalProxyRuntime.clear()
                    _snapshot.value = _snapshot.value.copy(
                        status = XrayCoreStatus.Failed,
                        abi = prepared.assetAbi,
                        version = xrayVersion(),
                        message = failureMessage,
                        activeServerName = null,
                    )
                    return@withContext Result.failure(IllegalStateException(failureMessage))
                }

                val snapshot = XrayCoreSnapshot(
                    status = XrayCoreStatus.Running,
                    abi = prepared.assetAbi,
                    version = xrayVersion(),
                    message = if (tunFd == null) {
                        tr("Xray 已启动，本地 SOCKS ${localProxy.socks.port} / HTTP ${localProxy.http.port}（需认证）", "Xray started, local SOCKS ${localProxy.socks.port} / HTTP ${localProxy.http.port} (auth required)", "Xray запущен, локальный SOCKS ${localProxy.socks.port} / HTTP ${localProxy.http.port} (с аутентификацией)")
                    } else {
                        tr("系统 VPN 已建立，Xray 正在运行", "System VPN is up and Xray is running", "Системный VPN установлен, Xray работает")
                    },
                    localSocksPort = localProxy.socks.port,
                    localHttpPort = localProxy.http.port,
                    activeServerName = server.name,
                )
                _snapshot.value = snapshot
                Result.success(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start Xray", error)
                SsrRuntimeManager.stopNow()
                LocalProxyRuntime.clear()
                _snapshot.value = _snapshot.value.copy(
                    status = XrayCoreStatus.Failed,
                    message = error.message ?: tr("Xray 启动失败。", "Xray failed to start.", "Не удалось запустить Xray."),
                    activeServerName = null,
                )
                Result.failure(error)
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            stopNow()
        }
    }

    fun stopNow() {
        synchronized(coreLock) {
            stopInternal()
        }
        SsrRuntimeManager.stopNow()
        LocalProxyRuntime.clear()
        _snapshot.value = _snapshot.value.copy(
            status = if (_snapshot.value.version == null) XrayCoreStatus.Idle else XrayCoreStatus.Ready,
            message = tr("Xray 已停止", "Xray stopped", "Xray остановлен"),
            activeServerName = null,
        )
    }

    fun updateActiveServer(serverName: String, message: String? = null) {
        _snapshot.value = _snapshot.value.copy(
            status = XrayCoreStatus.Running,
            activeServerName = serverName,
            message = message ?: _snapshot.value.message,
        )
    }

    private suspend fun prepareRuntime(context: Context): PreparedRuntime {
        _snapshot.value = _snapshot.value.copy(status = XrayCoreStatus.Preparing, message = tr("正在准备官方 Xray 资产…", "Preparing the official Xray assets…", "Подготовка официальных ресурсов Xray…"))
        val assetAbi = resolveAssetAbi()
        val runtimeDir = File(context.filesDir, "xray-runtime/$assetAbi").apply { mkdirs() }
        GeoDataManager.installIntoRuntime(
            context = context,
            runtimeDir = runtimeDir,
            assetAbi = assetAbi,
        )

        return PreparedRuntime(
            assetAbi = assetAbi,
            runtimeDir = runtimeDir,
        )
    }

    private fun resolveAssetAbi(): String {
        // The release ABI filter and shipped assets are arm64-v8a only.
        // If/when more ABIs are packaged this list and the build.gradle
        // abiFilters need to grow together.
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                else -> null
            }
        } ?: error(tr("当前设备 ABI 暂未打包官方 Xray 资产。", "No official Xray assets are bundled for this device's ABI.", "Для ABI этого устройства официальные ресурсы Xray не включены."))
    }

    private suspend fun waitForRunningState(
        attempts: Int = 4,
        intervalMs: Long = 60L,
    ): Boolean {
        repeat(attempts) {
            val running = synchronized(coreLock) { isXrayRunning() }
            if (running && ResourceFetchClient.isLocalHttpProxyAvailable()) return true
            delay(intervalMs)
        }
        val running = synchronized(coreLock) { isXrayRunning() }
        return running && ResourceFetchClient.isLocalHttpProxyAvailable()
    }

    private fun stopInternal() {
        val startedAt = System.currentTimeMillis()
        val response = invoke("stopXray")
        Log.d(TAG, "libXray stopXray finished in ${System.currentTimeMillis() - startedAt} ms")
        if (!response.success && !response.error.isNullOrBlank()) {
            Log.w(TAG, "StopXray returned error: ${response.error}")
        }
    }

    /** libXray API v3 getFreePorts, falling back to a JVM probe if it fails. */
    private fun freePortsFromCore(count: Int, excluded: List<Int>): List<Int> {
        val response = invoke(
            method = "getFreePorts",
            payload = JSONObject().apply {
                put("count", count)
                put("excludePorts", org.json.JSONArray(excluded))
            },
        )
        val ports = response.data?.optJSONArray("ports")
            ?.let { array -> List(array.length()) { array.optInt(it) } }
            .orEmpty()
        if (response.success && ports.size == count) return ports
        Log.w(TAG, "getFreePorts failed (${response.error}); using JVM port probe")
        return LocalProxyRuntime.pickFreePorts(count, excluded)
    }

    private fun isXrayRunning(): Boolean {
        return invoke("getXrayState").data?.optBoolean("running", false) == true
    }

    private fun xrayVersion(): String {
        return invoke("xrayVersion").data
            ?.optString("version")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Xray $it" }
            ?: "Xray"
    }

    /**
     * libXray (API v3) no longer takes datDir / TUN fd as call arguments.
     * Xray-core applies the top-level `env` map before building inbounds, so
     * the geo asset directory and the Android TUN descriptor travel inside
     * the config itself.
     */
    internal fun withRuntimeEnv(configJson: String, datDir: String, tunFd: Int?): String {
        val config = JSONObject(configJson)
        val env = config.optJSONObject("env") ?: JSONObject()
        env.put(ENV_ASSET_LOCATION, datDir)
        if (tunFd != null) {
            env.put(ENV_TUN_FD, tunFd.toString())
        }
        config.put("env", env)
        return config.toString()
    }

    private fun invoke(method: String, payload: JSONObject? = null): LibXrayCallResponse {
        val request = JSONObject().apply {
            put("apiVersion", LIBXRAY_API_VERSION)
            put("method", method)
            payload?.let { put("payload", it) }
        }
        return runCatching {
            val json = JSONObject(LibXray.invoke(request.toString()))
            LibXrayCallResponse(
                success = json.optBoolean("success", false),
                data = json.optJSONObject("data"),
                error = json.optString("error").takeIf { it.isNotBlank() },
            )
        }.getOrElse { error ->
            LibXrayCallResponse(success = false, error = error.message ?: tr("libXray 响应解析失败", "Couldn't parse the libXray response", "Не удалось разобрать ответ libXray"))
        }
    }

    private data class PreparedRuntime(
        val assetAbi: String,
        val runtimeDir: File,
    )

    private data class LibXrayCallResponse(
        val success: Boolean,
        val data: JSONObject? = null,
        val error: String? = null,
    )
}
