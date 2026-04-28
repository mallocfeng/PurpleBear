package com.mallocgfw.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.mallocgfw.app.ui.MallocGfwApp

class MainActivity : ComponentActivity() {
    private var launchRequest by mutableStateOf(parseLaunchRequest(intent))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MallocGfwApp(
                launchRequest = launchRequest,
                onLaunchRequestConsumed = { launchRequest = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = parseLaunchRequest(intent)
    }

    private fun parseLaunchRequest(intent: Intent?): LaunchRequest? {
        val action = when (intent?.action) {
            ACTION_TILE_CONNECT -> LaunchAction.TileConnect
            TileService.ACTION_QS_TILE_PREFERENCES -> LaunchAction.TilePreferences
            else -> return null
        }
        return LaunchRequest(
            action = action,
            serverId = intent.getStringExtra(EXTRA_SERVER_ID)?.takeIf { it.isNotBlank() },
            nonce = System.currentTimeMillis(),
        )
    }

    data class LaunchRequest(
        val action: LaunchAction,
        val serverId: String?,
        val nonce: Long,
    )

    enum class LaunchAction {
        TileConnect,
        TilePreferences,
    }

    companion object {
        private const val ACTION_TILE_CONNECT = "com.mallocgfw.app.action.TILE_CONNECT"
        private const val EXTRA_SERVER_ID = "com.mallocgfw.app.extra.SERVER_ID"

        fun tileConnectIntent(context: Context, serverId: String?): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ACTION_TILE_CONNECT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_SERVER_ID, serverId)
            }
        }
    }
}
