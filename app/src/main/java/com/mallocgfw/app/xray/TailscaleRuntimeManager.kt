package com.mallocgfw.app.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mallocgfw.app.model.AppSettings
import com.mallocgfw.app.tailscale.TailscaleBridgeClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
        )
    }
}

object TailscaleRuntimeManager {
    private const val TAG = "TailscaleRuntime"
    private val _snapshot = MutableStateFlow(TailscaleRuntimeSnapshot())
    val snapshot: StateFlow<TailscaleRuntimeSnapshot> = _snapshot.asStateFlow()

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
                    put("mtu", properties.mtu.takeIf { it > 0 } ?: 1500)
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
