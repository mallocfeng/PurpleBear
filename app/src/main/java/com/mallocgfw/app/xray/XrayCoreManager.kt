package com.mallocgfw.app.xray

import android.content.Context
import android.os.Build
import android.util.Log
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.model.SsrMihomoConfigFactory
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
                IllegalStateException("当前已连接 VPN，请先断开后再启动独立 Xray 实例。"),
            )
        }
        return startWithConfig(
            context = context,
            server = server,
            message = "正在启动 Xray 内核…",
            tunFd = null,
            additionalOutbounds = additionalOutbounds,
        ) { localProxy ->
            val state = AppStateStore.load(context)
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
    ): Result<XrayCoreSnapshot> {
        return startWithConfig(
            context = context,
            server = server,
            message = "正在建立系统 VPN 隧道…",
            tunFd = tunFd,
            additionalOutbounds = additionalOutbounds,
        ) { localProxy ->
            val state = AppStateStore.load(context)
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
                globalProxyEnabled = settings.globalProxyEnabled,
                geoRoutingRegion = settings.geoRoutingRegion,
                tailscale = TailscaleRuntimeManager.snapshot.value.routing(settings),
                localProxy = localProxy,
            )
        }
    }

    /** The node, its pre-proxy chain and any extra routed outbounds. */
    private fun pinCandidates(
        context: Context,
        server: ServerNode,
        additionalOutbounds: List<XrayNamedOutbound>,
    ): List<ServerNode> {
        val servers = runCatching { AppStateStore.load(context).servers }.getOrDefault(emptyList())
        val chain = mutableListOf(server)
        var current = server
        while (chain.size < 8) {
            val nextId = current.preProxyNodeId.trim()
            if (nextId.isBlank()) break
            val next = servers.firstOrNull { it.id == nextId } ?: break
            if (chain.any { it.id == next.id }) break
            chain += next
            current = next
        }
        return chain + additionalOutbounds.map { it.node }
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
                InsecureTlsPinner.refresh(context, pinCandidates(context, server, additionalOutbounds))
                val prepared = prepareRuntime(context)
                val datDir = prepared.runtimeDir.absolutePath
                // Fresh random ports and credentials for every run; see LocalProxyRuntime.
                val localProxy = LocalProxyRuntime.allocate(::freePortsFromCore)
                val configJson = buildConfigJson(localProxy)
                SsrRuntimeManager.startIfNeeded(
                    context,
                    SsrMihomoConfigFactory.sidecarNodes(server, additionalOutbounds.map { it.node }),
                    localProxy,
                ).getOrElse { throw it }
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
                    val failureMessage = response.error ?: "Xray 启动失败。"
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
                    val failureMessage = "Xray 启动后未进入运行状态，本地 HTTP 代理不可用。"
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
                        "Xray 已启动，本地 SOCKS ${localProxy.socks.port} / HTTP ${localProxy.http.port}（需认证）"
                    } else {
                        "系统 VPN 已建立，Xray 正在运行"
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
                    message = error.message ?: "Xray 启动失败。",
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
            message = "Xray 已停止",
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
        _snapshot.value = _snapshot.value.copy(status = XrayCoreStatus.Preparing, message = "正在准备官方 Xray 资产…")
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
        } ?: error("当前设备 ABI 暂未打包官方 Xray 资产。")
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
            LibXrayCallResponse(success = false, error = error.message ?: "libXray 响应解析失败")
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
