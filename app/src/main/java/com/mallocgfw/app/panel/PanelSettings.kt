package com.mallocgfw.app.panel

import android.content.Context
import androidx.core.content.edit
import com.mallocgfw.app.mcp.McpSettings
import org.json.JSONObject

enum class PanelScope { Lan, Tailnet }

data class PanelConfig(
    val token: String,
    val port: Int,
    val scope: PanelScope = PanelScope.Lan,
)

/** Remote panel settings, kept apart from the main app state like the MCP ones. */
object PanelSettings {
    private const val PREFS = "panel_settings"
    private const val KEY = "config_v1"

    @Synchronized
    fun load(context: Context): PanelConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
        if (parsed != null && parsed.token.isNotBlank()) return parsed
        val fresh = PanelConfig(token = McpSettings.newToken(), port = McpSettings.randomPort())
        save(context, fresh)
        return fresh
    }

    @Synchronized
    fun save(context: Context, config: PanelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(
                KEY,
                JSONObject()
                    .put("token", config.token)
                    .put("port", config.port)
                    .put("scope", config.scope.name)
                    .toString(),
            )
        }
    }

    fun update(context: Context, change: (PanelConfig) -> PanelConfig): PanelConfig {
        val next = change(load(context))
        save(context, next)
        return next
    }

    private fun parse(json: JSONObject) = PanelConfig(
        token = json.optString("token"),
        port = json.optInt("port").takeIf { it in 1024..65535 } ?: McpSettings.randomPort(),
        scope = runCatching { PanelScope.valueOf(json.optString("scope")) }.getOrDefault(PanelScope.Lan),
    )
}
