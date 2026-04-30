package com.mallocgfw.app.xray

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.mallocgfw.app.model.AppStateStore
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
    private val coreLock = Any()
    private val _snapshot = MutableStateFlow(XrayCoreSnapshot())
    val snapshot: StateFlow<XrayCoreSnapshot> = _snapshot.asStateFlow()

    suspend fun initialize(context: Context) {
        withContext(Dispatchers.IO) {
            XrayNativeLoader.ensureLoaded()
            val prepared = prepareRuntime(context)
            val version = decodeStringResponse(LibXray.xrayVersion()) ?: "Xray"
            _snapshot.value = XrayCoreSnapshot(
                status = XrayCoreStatus.Ready,
                abi = prepared.assetAbi,
                version = version,
                message = "官方 Xray 内核已就绪",
                localSocksPort = XrayConfigFactory.SOCKS_PORT,
                localHttpPort = XrayConfigFactory.HTTP_PORT,
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
        ) {
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
        ) {
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
            )
        }
    }

    private suspend fun startWithConfig(
        context: Context,
        server: ServerNode,
        message: String,
        tunFd: Int?,
        buildConfigJson: () -> String,
    ): Result<XrayCoreSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                XrayNativeLoader.ensureLoaded()
                val prepared = prepareRuntime(context)
                val datDir = prepared.runtimeDir.absolutePath
                val configJson = buildConfigJson()
                _snapshot.value = _snapshot.value.copy(
                    status = XrayCoreStatus.Starting,
                    message = message,
                    activeServerName = server.name,
                )
                val response = synchronized(coreLock) {
                    stopInternal()
                    if (tunFd != null) {
                        LibXray.setTunFd(tunFd)
                    }
                    val request = LibXray.newXrayRunFromJSONRequest(datDir, "", configJson)
                    decodeResponse(LibXray.runXrayFromJSON(request))
                }
                if (!response.success) {
                    val failureMessage = response.error ?: "Xray 启动失败。"
                    Log.e(TAG, "Unable to start Xray: $failureMessage")
                    _snapshot.value = _snapshot.value.copy(
                        status = XrayCoreStatus.Failed,
                        abi = prepared.assetAbi,
                        version = decodeStringResponse(LibXray.xrayVersion()) ?: "Xray",
                        message = failureMessage,
                        activeServerName = null,
                    )
                    return@withContext Result.failure(IllegalStateException(failureMessage))
                }

                // libXray.runXrayFromJSON returns once the boot routine has been kicked
                // off; give it a brief settle window outside of coreLock and then verify
                // the core is actually serving traffic.
                val isRunning = waitForRunningState()

                val snapshot = XrayCoreSnapshot(
                    status = if (isRunning) XrayCoreStatus.Running else XrayCoreStatus.Ready,
                    abi = prepared.assetAbi,
                    version = decodeStringResponse(LibXray.xrayVersion()) ?: "Xray",
                    message = if (tunFd == null) {
                        "Xray 已启动，本地 SOCKS ${XrayConfigFactory.SOCKS_PORT} / HTTP ${XrayConfigFactory.HTTP_PORT}"
                    } else {
                        "系统 VPN 已建立，Xray 正在运行"
                    },
                    localSocksPort = XrayConfigFactory.SOCKS_PORT,
                    localHttpPort = XrayConfigFactory.HTTP_PORT,
                    activeServerName = server.name,
                )
                _snapshot.value = snapshot
                Result.success(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start Xray", error)
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
            val running = synchronized(coreLock) { LibXray.getXrayState() }
            if (running) return true
            delay(intervalMs)
        }
        return synchronized(coreLock) { LibXray.getXrayState() }
    }

    private fun stopInternal() {
        val startedAt = System.currentTimeMillis()
        val response = decodeResponse(LibXray.stopXray())
        Log.d(TAG, "LibXray.stopXray() finished in ${System.currentTimeMillis() - startedAt} ms")
        if (!response.success && !response.error.isNullOrBlank()) {
            Log.w(TAG, "StopXray returned error: ${response.error}")
        }
    }

    private fun decodeStringResponse(encoded: String): String? {
        return decodeResponse(encoded).data?.takeIf { it.isNotBlank() }
    }

    private fun decodeResponse(encoded: String): LibXrayCallResponse {
        return runCatching {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
            val json = JSONObject(decoded)
            LibXrayCallResponse(
                success = json.optBoolean("success", false),
                data = json.optString("data").takeIf { it.isNotBlank() },
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
        val data: String? = null,
        val error: String? = null,
    )
}
