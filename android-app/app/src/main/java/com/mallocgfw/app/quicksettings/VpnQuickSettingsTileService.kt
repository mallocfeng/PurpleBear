package com.mallocgfw.app.quicksettings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.model.AppStateStore
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.xray.VpnRuntimeSnapshot
import com.mallocgfw.app.xray.VpnRuntimeStore
import com.mallocgfw.app.xray.VpnServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnQuickSettingsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tileJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTileAsync()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            handleToggleAsync()
        }
    }

    override fun onStopListening() {
        tileJob?.cancel()
        tileJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleToggleAsync() {
        tileJob?.cancel()
        tileJob = scope.launch {
            val tileState = loadTileState()
            when (tileState.runtime.status) {
                ConnectionStatus.Connected,
                ConnectionStatus.Connecting,
                ConnectionStatus.Disconnecting -> {
                    VpnServiceController.disconnect(applicationContext)
                }

                ConnectionStatus.Disconnected -> {
                    val prepareIntent = VpnService.prepare(applicationContext)
                    if (tileState.server != null && prepareIntent == null) {
                        VpnServiceController.start(applicationContext, tileState.server)
                    } else {
                        launchMainActivity(tileState.server?.id)
                    }
                }
            }
            VpnQuickSettingsTileHelper.requestRefresh(applicationContext)
            qsTile?.let {
                VpnQuickSettingsTileHelper.updateTile(applicationContext, it, tileState.runtime, tileState.server)
            }
        }
    }

    private fun refreshTileAsync() {
        tileJob?.cancel()
        tileJob = scope.launch {
            val tileState = loadTileState()
            qsTile?.let {
                VpnQuickSettingsTileHelper.updateTile(applicationContext, it, tileState.runtime, tileState.server)
            }
        }
    }

    private suspend fun loadTileState(): TileState = withContext(Dispatchers.IO) {
        VpnRuntimeStore.initialize(applicationContext)
        val runtime = VpnRuntimeStore.snapshot.value
        val state = AppStateStore.load(applicationContext)
        val preferredId = listOf(
            runtime.activeServerId,
            state.settings.lastConnectedServerId,
            state.selectedServerId,
        ).firstOrNull { !it.isNullOrBlank() }
        val server = state.servers.firstOrNull { it.id == preferredId } ?: state.servers.firstOrNull()
        TileState(runtime, server)
    }

    private data class TileState(
        val runtime: VpnRuntimeSnapshot,
        val server: ServerNode?,
    )

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchMainActivity(serverId: String?) {
        val intent = MainActivity.tileConnectIntent(applicationContext, serverId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
