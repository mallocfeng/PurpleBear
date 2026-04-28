package com.mallocgfw.app.quicksettings

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mallocgfw.app.R
import com.mallocgfw.app.model.ConnectionStatus
import com.mallocgfw.app.model.ServerNode
import com.mallocgfw.app.xray.VpnRuntimeSnapshot

internal object VpnQuickSettingsTileHelper {
    fun requestRefresh(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, VpnQuickSettingsTileService::class.java),
            )
        }
    }

    fun updateTile(
        context: Context,
        tile: Tile,
        runtime: VpnRuntimeSnapshot,
        activeServer: ServerNode?,
    ) {
        tile.label = context.getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(context, R.drawable.ic_vpn_tile)
        tile.state = when (runtime.status) {
            ConnectionStatus.Connected -> Tile.STATE_ACTIVE
            ConnectionStatus.Connecting,
            ConnectionStatus.Disconnecting -> Tile.STATE_UNAVAILABLE
            ConnectionStatus.Disconnected -> if (activeServer != null) {
                Tile.STATE_INACTIVE
            } else {
                Tile.STATE_UNAVAILABLE
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (runtime.status) {
                ConnectionStatus.Connected -> activeServer?.name ?: context.getString(R.string.qs_tile_status_connected)
                ConnectionStatus.Connecting -> context.getString(R.string.qs_tile_status_connecting)
                ConnectionStatus.Disconnecting -> context.getString(R.string.qs_tile_status_disconnecting)
                ConnectionStatus.Disconnected -> activeServer?.name ?: context.getString(R.string.qs_tile_status_no_node)
            }
        }
        tile.contentDescription = buildString {
            append(context.getString(R.string.qs_tile_label))
            append(' ')
            append(
                when (runtime.status) {
                    ConnectionStatus.Connected -> activeServer?.name ?: context.getString(R.string.qs_tile_status_connected)
                    ConnectionStatus.Connecting -> context.getString(R.string.qs_tile_status_connecting)
                    ConnectionStatus.Disconnecting -> context.getString(R.string.qs_tile_status_disconnecting)
                    ConnectionStatus.Disconnected -> activeServer?.name ?: context.getString(R.string.qs_tile_status_no_node)
                },
            )
        }
        tile.updateTile()
    }
}
