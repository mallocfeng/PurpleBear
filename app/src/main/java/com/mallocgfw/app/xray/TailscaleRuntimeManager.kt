package com.mallocgfw.app.xray

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.tailscale.TailscaleBridgeClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class TailscaleRuntimeStatus {
    Stopped,
    Connecting,
    Connected,
    Failed,
}

data class TailscalePeer(
    val id: String,
    val name: String,
    val dnsName: String,
    val ipv4: String?,
    val ipv6: String?,
    val online: Boolean,
    val active: Boolean,
    val relay: String?,
    val exitNode: Boolean,
    val canExit: Boolean,
    val routes: List<String>,
)

data class TailscaleRuntimeSnapshot(
    val status: TailscaleRuntimeStatus = TailscaleRuntimeStatus.Stopped,
    val message: String? = null,
    val hostname: String? = null,
    val dnsName: String? = null,
    val tailnet: String? = null,
    val magicDnsSuffix: String? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val exitNodeId: String? = null,
    val socksHost: String? = null,
    val socksPort: Int? = null,
    val socksUsername: String? = null,
    val socksPassword: String? = null,
    val peers: List<TailscalePeer> = emptyList(),
) {
    fun routing(settings: AppSettings): TailscaleRouting? {
        val host = socksHost ?: return null
        val port = socksPort ?: return null
        val username = socksUsername ?: return null
        val password = socksPassword ?: return null
        if (status != TailscaleRuntimeStatus.Connected || port !in 1..65535) return null
        val advertisedSubnets = peers
            .flatMap { it.routes }
            .mapNotNull(::normalizePrivateTailscaleSubnet)
            .toSet()
        val selectedSubnets = settings.tailscaleSubnetRoutes
            .mapNotNull(::normalizePrivateTailscaleSubnet)
        return TailscaleRouting(
            socksHost = host,
            socksPort = port,
            socksUsername = username,
            socksPassword = password,
            // Exit-node advertisements include default routes. They must not
            // become ordinary Tailnet split routes, otherwise enabling the
            // module silently hijacks all proxy traffic even when no exit node
            // was selected by the user.
            subnetRoutes = selectedSubnets
                .filter { it in advertisedSubnets }
                .distinct(),
            routeAllTraffic = settings.tailscaleExitNodeId.isNotBlank(),
            magicDnsSuffix = magicDnsSuffix.orEmpty().trim().trim('.').lowercase()
                .ifBlank { commonMagicDnsSuffix() },
            magicDnsHosts = peers.mapNotNull { peer ->
                val name = peer.dnsName.trim().trimEnd('.').lowercase()
                val addresses = listOfNotNull(peer.ipv4, peer.ipv6)
                if (name.isBlank() || addresses.isEmpty()) null else name to addresses
            }.toMap(),
        )
    }

    /** Older control servers omit the suffix; every MagicDNS name of a tailnet shares it. */
    private fun commonMagicDnsSuffix(): String {
        val self = dnsName.orEmpty().trim().trimEnd('.').lowercase()
        return self.substringAfter('.', "").takeIf { '.' in it }.orEmpty()
    }
}

/** A tailnet port forwarded to an app-local loopback port; see bridge.go forwardConfig. */
data class TailscaleForward(val tailnetPort: Int, val localPort: Int, val secret: String)

object TailscaleRuntimeManager {
    private const val TAG = "TailscaleRuntime"
    private val _snapshot = MutableStateFlow(TailscaleRuntimeSnapshot())
    val snapshot: StateFlow<TailscaleRuntimeSnapshot> = _snapshot.asStateFlow()

    private val _bridgeLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a running Tailscale node was lost with its process; see [handleBridgeLost]. */
    val bridgeLost: SharedFlow<Unit> = _bridgeLost

    init {
        TailscaleBridgeClient.onBridgeLost = ::handleBridgeLost
    }

    /** Outlives callers: a start is a blocking call into the bridge and can't be cancelled. */
    private val startScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingStart: Deferred<Result<TailscaleRuntimeSnapshot>>? = null

    /**
     * Starts the node in the background, or joins a start already running.
     * Logging in can take the bridge's full 45 s timeout when the control
     * server is unreachable, so the VPN waits only briefly for this (see
     * XrayVpnService.awaitTailscaleForConnect) instead of calling [ensureStarted].
     */
    fun startAsync(context: Context, settings: AppSettings): Deferred<Result<TailscaleRuntimeSnapshot>> =
        synchronized(this) {
            pendingStart?.takeIf { it.isActive }
                ?: startScope.async { ensureStarted(context.applicationContext, settings) }
                    .also { pendingStart = it }
        }

    /**
     * The :tailscale process died. Its SOCKS endpoint is gone, so the snapshot
     * must stop offering routing (otherwise the UI keeps saying "connected"
     * while Tailnet traffic fails), and a running VPN has to restart the node
     * and reload its config to pick up the new endpoint.
     */
    private fun handleBridgeLost() {
        val previous = _snapshot.value
        if (previous.status == TailscaleRuntimeStatus.Stopped) return
        Log.w(TAG, "Tailscale bridge process was lost")
        _snapshot.value = previous.copy(
            status = TailscaleRuntimeStatus.Failed,
            message = "Tailscale 进程已退出，正在重新连接…",
            socksHost = null,
            socksPort = null,
            socksUsername = null,
            socksPassword = null,
        )
        _bridgeLost.tryEmit(Unit)
    }

    suspend fun ensureStarted(
        context: Context,
        settings: AppSettings,
        authKey: String = "",
    ): Result<TailscaleRuntimeSnapshot> = withContext(Dispatchers.IO) {
        if (!settings.tailscaleEnabled) {
            stopNow(context)
            return@withContext Result.success(_snapshot.value)
        }
        _snapshot.value = _snapshot.value.copy(
            status = TailscaleRuntimeStatus.Connecting,
            message = "正在连接 Tailscale…",
        )
        runCatching {
            val config = JSONObject().apply {
                put("stateDir", File(context.filesDir, "tailscale").absolutePath)
                put("hostname", "mallocgfw-android")
                put("authKey", authKey.trim())
                put("controlUrl", settings.tailscaleControlUrl.trim())
                put("alwaysUseDerp", settings.tailscaleAlwaysUseDerp)
                put("interfaces", androidInterfaces(context))
            }
            val raw = nativeValue(TailscaleBridgeClient.start(context, config.toString()))
            val next = parseSnapshot(raw)
            if (settings.tailscaleExitNodeId.isNotBlank()) {
                nativeValue(TailscaleBridgeClient.setExitNode(context, settings.tailscaleExitNodeId))
            }
            next.copy(status = TailscaleRuntimeStatus.Connected, message = "Tailscale 已连接")
        }.onSuccess { next ->
            _snapshot.value = next
        }.onFailure { error ->
            Log.e(TAG, "Unable to start Tailscale", error)
            _snapshot.value = _snapshot.value.copy(
                status = TailscaleRuntimeStatus.Failed,
                message = error.message ?: "Tailscale 连接失败。",
            )
        }.map { _snapshot.value }
    }

    suspend fun refresh(context: Context): Result<TailscaleRuntimeSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            parseSnapshot(nativeValue(TailscaleBridgeClient.status(context))).copy(status = TailscaleRuntimeStatus.Connected)
        }.onSuccess { _snapshot.value = it }
            .onFailure { error ->
                _snapshot.value = _snapshot.value.copy(
                    status = TailscaleRuntimeStatus.Failed,
                    message = error.message ?: "无法刷新 Tailscale 状态。",
                )
            }.map { _snapshot.value }
    }

    suspend fun restart(context: Context, settings: AppSettings, authKey: String = ""): Result<TailscaleRuntimeSnapshot> {
        return withContext(Dispatchers.IO) {
            // Preserve the last device snapshot while the native bridge is
            // restarting so the Compose list does not collapse and jump.
            stopBridge(context)
            ensureStarted(context, settings, authKey)
        }
    }

    suspend fun setExitNode(context: Context, nodeId: String): Result<TailscaleRuntimeSnapshot> =
        withContext(Dispatchers.IO) {
            _snapshot.value = _snapshot.value.copy(
                status = TailscaleRuntimeStatus.Connecting,
                message = "正在切换出口节点…",
            )
            runCatching {
                nativeValue(TailscaleBridgeClient.setExitNode(context, nodeId))
                parseSnapshot(nativeValue(TailscaleBridgeClient.status(context))).copy(
                    status = TailscaleRuntimeStatus.Connected,
                    message = if (nodeId.isBlank()) "已取消出口节点" else "出口节点已切换",
                )
            }.onSuccess { next ->
                _snapshot.value = next
            }.onFailure { error ->
                Log.e(TAG, "Unable to switch Tailscale exit node", error)
                _snapshot.value = _snapshot.value.copy(
                    status = TailscaleRuntimeStatus.Failed,
                    message = error.message ?: "无法切换 Tailscale 出口节点。",
                )
            }.map { _snapshot.value }
        }

    suspend fun logout(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stateDir = File(context.filesDir, "tailscale").absolutePath
            nativeValue(TailscaleBridgeClient.logout(context, stateDir))
            _snapshot.value = TailscaleRuntimeSnapshot(message = "已从 Tailscale 退出")
        }
    }

    /** Accepts tailnet connections on [TailscaleForward.tailnetPort] for the MCP server or the remote panel. */
    suspend fun serveForward(context: Context, forward: TailscaleForward): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = JSONObject()
                .put("tailnetPort", forward.tailnetPort)
                .put("localPort", forward.localPort)
                .put("secret", forward.secret)
            nativeValue(TailscaleBridgeClient.serveForward(context, config.toString()))
            Unit
        }
    }

    /** Closes the tailnet listener on [tailnetPort]; the others keep running. */
    suspend fun stopForward(context: Context, tailnetPort: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val config = JSONObject().put("tailnetPort", tailnetPort).put("stop", true)
            nativeValue(TailscaleBridgeClient.serveForward(context, config.toString()))
        }.onFailure { Log.w(TAG, "Unable to stop the tailnet forward on $tailnetPort", it) }
    }

    /** Closes every tailnet listener. */
    suspend fun stopForward(context: Context) = withContext(Dispatchers.IO) {
        runCatching { nativeValue(TailscaleBridgeClient.stopForward(context)) }
            .onFailure { Log.w(TAG, "Unable to stop the tailnet forward", it) }
    }

    suspend fun stopNow(context: Context) = withContext(Dispatchers.IO) {
        stopBridge(context)
        _snapshot.value = TailscaleRuntimeSnapshot()
    }

    private suspend fun stopBridge(context: Context) {
        runCatching {
            nativeValue(TailscaleBridgeClient.stop(context))
        }.onFailure { Log.w(TAG, "Unable to stop Tailscale", it) }
    }

    private fun parseSnapshot(raw: String): TailscaleRuntimeSnapshot {
        val json = JSONObject(raw)
        val peers = json.optJSONArray("peers").toPeers()
        return TailscaleRuntimeSnapshot(
            status = when (json.optString("state")) {
                "Running" -> TailscaleRuntimeStatus.Connected
                "Starting", "NeedsLogin" -> TailscaleRuntimeStatus.Connecting
                else -> TailscaleRuntimeStatus.Stopped
            },
            message = json.optString("message").takeIf(String::isNotBlank),
            hostname = json.optString("hostname").takeIf(String::isNotBlank),
            dnsName = json.optString("dnsName").takeIf(String::isNotBlank),
            tailnet = json.optString("tailnet").takeIf(String::isNotBlank),
            magicDnsSuffix = json.optString("magicDnsSuffix").takeIf(String::isNotBlank),
            ipv4 = json.optString("ipv4").takeIf(String::isNotBlank),
            ipv6 = json.optString("ipv6").takeIf(String::isNotBlank),
            exitNodeId = json.optString("exitNodeId").takeIf(String::isNotBlank),
            socksHost = json.optString("socksHost").takeIf(String::isNotBlank),
            socksPort = json.optInt("socksPort").takeIf { it in 1..65535 },
            socksUsername = json.optString("socksUsername").takeIf(String::isNotBlank),
            socksPassword = json.optString("socksPassword").takeIf(String::isNotBlank),
            peers = peers,
        )
    }

    private fun nativeValue(raw: String): String {
        val response = JSONObject(raw)
        check(response.optBoolean("ok")) {
            response.optString("error").ifBlank { "Tailscale native bridge failed." }
        }
        return response.opt("value")?.toString() ?: "{}"
    }

    private fun androidInterfaces(context: Context): JSONArray {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return JSONArray()
        val seenNames = mutableSetOf<String>()
        return JSONArray().apply {
            connectivityManager.allNetworks.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
                val properties = connectivityManager.getLinkProperties(network) ?: return@forEach
                val name = properties.interfaceName?.takeIf(String::isNotBlank) ?: return@forEach
                if (!seenNames.add(name)) return@forEach
                put(JSONObject().apply {
                    put("name", name)
                    // LinkProperties#getMtu is API 29+; older systems report the default.
                    val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) properties.mtu else 0
                    put("mtu", mtu.takeIf { it > 0 } ?: 1500)
                    put("addresses", JSONArray().apply {
                        properties.linkAddresses.forEach addressLoop@ { linkAddress ->
                            val host = linkAddress.address.hostAddress
                                ?.substringBefore('%')
                                ?.takeIf(String::isNotBlank)
                                ?: return@addressLoop
                            put("$host/${linkAddress.prefixLength}")
                        }
                    })
                })
            }
        }
    }

    private fun JSONArray?.toPeers(): List<TailscalePeer> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            getJSONObject(index).let { item ->
                TailscalePeer(
                    id = item.optString("id"),
                    name = item.optString("name", "未知设备"),
                    dnsName = item.optString("dnsName"),
                    ipv4 = item.optString("ipv4").takeIf(String::isNotBlank),
                    ipv6 = item.optString("ipv6").takeIf(String::isNotBlank),
                    online = item.optBoolean("online"),
                    active = item.optBoolean("active"),
                    relay = item.optString("relay").takeIf(String::isNotBlank),
                    exitNode = item.optBoolean("exitNode"),
                    canExit = item.optBoolean("canExit"),
                    routes = item.optJSONArray("routes")?.let { routes ->
                        List(routes.length()) { routes.optString(it) }.filter(String::isNotBlank)
                    }.orEmpty(),
                )
            }
        }
    }
}
