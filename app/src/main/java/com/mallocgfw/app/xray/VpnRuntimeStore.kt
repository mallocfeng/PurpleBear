package com.mallocgfw.app.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mallocgfw.app.model.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class VpnRuntimeSnapshot(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val activeServerId: String? = null,
    val message: String? = null,
    val connectedAtMs: Long? = null,
    val speedTestInFlight: Boolean = false,
    val speedTestTimedOut: Boolean = false,
    val sessionDurationMs: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxRateBytesPerSec: Long = 0L,
    val txRateBytesPerSec: Long = 0L,
    val interfaceName: String? = null,
)

object VpnRuntimeStore {
    private const val PREFS_NAME = "mallocgfw_runtime"
    private const val STATE_KEY = "vpn_runtime_state_v1"

    private val _snapshot = MutableStateFlow(VpnRuntimeSnapshot())
    val snapshot: StateFlow<VpnRuntimeSnapshot> = _snapshot.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun initialize(context: Context) {
        refresh(context)
    }

    fun refresh(context: Context) {
        val prefs = prefs(context)
        val raw = prefs.getString(STATE_KEY, null)
        val persisted = raw?.let(::parseSnapshot) ?: VpnRuntimeSnapshot()
        val reconciled = reconcileWithSystemState(context, persisted)
        _snapshot.value = reconciled
        if (reconciled != persisted) {
            prefs.edit().putString(STATE_KEY, serializeSnapshot(reconciled).toString()).apply()
        }
    }

    fun updateConnection(
        context: Context,
        status: ConnectionStatus,
        activeServerId: String? = null,
        message: String? = null,
        connectedAtMs: Long? = _snapshot.value.connectedAtMs,
        interfaceName: String? = _snapshot.value.interfaceName,
        persist: Boolean = true,
    ) {
        write(
            context = context,
            next = _snapshot.value.copy(
                status = status,
                activeServerId = activeServerId,
                message = message,
                connectedAtMs = connectedAtMs,
                speedTestInFlight = false,
                speedTestTimedOut = false,
                interfaceName = interfaceName,
            ),
            persist = persist,
        )
    }

    fun updateTraffic(
        context: Context,
        rxBytes: Long,
        txBytes: Long,
        rxRateBytesPerSec: Long,
        txRateBytesPerSec: Long,
        interfaceName: String? = _snapshot.value.interfaceName,
        persist: Boolean = false,
    ) {
        write(
            context = context,
            next = _snapshot.value.copy(
                speedTestTimedOut = false,
                rxBytes = rxBytes,
                txBytes = txBytes,
                rxRateBytesPerSec = rxRateBytesPerSec,
                txRateBytesPerSec = txRateBytesPerSec,
                interfaceName = interfaceName,
            ),
            persist = persist,
        )
    }

    fun setSpeedTestInFlight(
        context: Context,
        inFlight: Boolean,
        persist: Boolean = false,
    ) {
        write(
            context = context,
            next = _snapshot.value.copy(
                speedTestInFlight = inFlight,
                speedTestTimedOut = if (inFlight) false else _snapshot.value.speedTestTimedOut,
            ),
            persist = persist,
        )
    }

    fun setSpeedTestTimedOut(
        context: Context,
        timedOut: Boolean,
        persist: Boolean = false,
    ) {
        write(
            context = context,
            next = _snapshot.value.copy(
                speedTestInFlight = false,
                speedTestTimedOut = timedOut,
            ),
            persist = persist,
        )
    }

    fun reset(
        context: Context,
        status: ConnectionStatus = ConnectionStatus.Disconnected,
        activeServerId: String? = null,
        message: String? = null,
        persist: Boolean = true,
    ) {
        write(
            context = context,
            next = VpnRuntimeSnapshot(
                status = status,
                activeServerId = activeServerId,
                message = message,
                speedTestInFlight = false,
                speedTestTimedOut = false,
            ),
            persist = persist,
        )
    }

    private fun write(
        context: Context,
        next: VpnRuntimeSnapshot,
        persist: Boolean,
    ) {
        _snapshot.value = next
        if (!persist) return
        prefs(context)
            .edit()
            .putString(STATE_KEY, serializeSnapshot(next).toString())
            .apply()
    }

    private fun parseSnapshot(raw: String): VpnRuntimeSnapshot {
        return runCatching {
            val json = JSONObject(raw)
            VpnRuntimeSnapshot(
                status = runCatching {
                    enumValueOf<ConnectionStatus>(json.optString("status"))
                }.getOrDefault(ConnectionStatus.Disconnected),
                activeServerId = json.optString("activeServerId").takeIf { it.isNotBlank() },
                message = json.optString("message").takeIf { it.isNotBlank() },
                connectedAtMs = json.optLong("connectedAtMs").takeIf { it > 0L },
                speedTestInFlight = json.optBoolean("speedTestInFlight", false),
                speedTestTimedOut = json.optBoolean("speedTestTimedOut", false),
                sessionDurationMs = json.optLong("sessionDurationMs", 0L),
                rxBytes = json.optLong("rxBytes", 0L),
                txBytes = json.optLong("txBytes", 0L),
                rxRateBytesPerSec = json.optLong("rxRateBytesPerSec", 0L),
                txRateBytesPerSec = json.optLong("txRateBytesPerSec", 0L),
                interfaceName = json.optString("interfaceName").takeIf { it.isNotBlank() },
            )
        }.getOrDefault(VpnRuntimeSnapshot())
    }

    private fun reconcileWithSystemState(
        context: Context,
        snapshot: VpnRuntimeSnapshot,
    ): VpnRuntimeSnapshot {
        if (snapshot.status == ConnectionStatus.Disconnected) return snapshot
        // Don't trust activeNetwork on its own: when WiFi/cellular and VPN are
        // both up the system can return the underlying transport as active and
        // we'd wrongly tear our state down. Walk every network and look for a
        // VPN transport instead.
        val systemVpnActive = runCatching {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
        if (systemVpnActive) return snapshot
        return VpnRuntimeSnapshot(
            status = ConnectionStatus.Disconnected,
            activeServerId = null,
            message = "检测到系统 VPN 当前未连接，已同步为未连接状态。",
            speedTestInFlight = false,
            speedTestTimedOut = false,
        )
    }

    private fun serializeSnapshot(snapshot: VpnRuntimeSnapshot): JSONObject {
        return JSONObject().apply {
            put("status", snapshot.status.name)
            putOpt("activeServerId", snapshot.activeServerId)
            putOpt("message", snapshot.message)
            putOpt("connectedAtMs", snapshot.connectedAtMs)
            put("speedTestInFlight", snapshot.speedTestInFlight)
            put("speedTestTimedOut", snapshot.speedTestTimedOut)
            put("sessionDurationMs", snapshot.sessionDurationMs)
            put("rxBytes", snapshot.rxBytes)
            put("txBytes", snapshot.txBytes)
            put("rxRateBytesPerSec", snapshot.rxRateBytesPerSec)
            put("txRateBytesPerSec", snapshot.txRateBytesPerSec)
            putOpt("interfaceName", snapshot.interfaceName)
        }
    }
}
