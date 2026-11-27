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
import com.mallocgfw.app.mcp.ApprovalBroker
import com.mallocgfw.app.model.ImportLinks
import com.mallocgfw.app.ui.MallocGfwApp

class MainActivity : ComponentActivity() {
    // Set in onCreate: while the activity is being constructed it has no intent yet.
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (rotation, theme, process restored): the intent was handled already.
        if (savedInstanceState == null) launchRequest = parseLaunchRequest(intent)
        ApprovalBroker.init(this)
        com.mallocgfw.app.xray.AppLogManager.trimAllLogs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MallocGfwApp(
                launchRequest = launchRequest,
                onLaunchRequestConsumed = { launchRequest = null },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // MCP approvals show as a dialog while the app is in front, as notifications otherwise.
        ApprovalBroker.setUiInForeground(true)
        // Auto by Wi-Fi: restart the watcher if the system stopped it.
        com.mallocgfw.app.wifi.WifiRuleRuntime.sync(this)
    }

    override fun onPause() {
        ApprovalBroker.setUiInForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = parseLaunchRequest(intent)
    }

    private fun parseLaunchRequest(intent: Intent?): LaunchRequest? {
        if (intent?.action == Intent.ACTION_VIEW) {
            // A link the app was opened with stays in the intent: don't import it again
            // when the activity is recreated from history.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val link = ImportLinks.parse(intent.data) ?: return null
            return LaunchRequest(LaunchAction.ImportLink, serverId = null, nonce = System.currentTimeMillis(), importLink = link)
        }
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
        val importLink: ImportLinks.Request? = null,
    )

    enum class LaunchAction {
        TileConnect,
        TilePreferences,
        ImportLink,
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
