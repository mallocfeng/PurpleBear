package com.mallocgfw.app.mcp

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64

enum class McpScope { Lan, Tailnet }

data class McpConfig(
    val token: String,
    val port: Int,
    val scope: McpScope = McpScope.Lan,
    val ipAllowlist: List<String> = emptyList(),
    val tailnetAllowlist: List<String> = emptyList(),
    val approveControl: Boolean = false,
    val approveModify: Boolean = true,
    /** 0: never stop on idle. */
    val idleTimeoutMinutes: Int = 60,
    /** Node addresses and ports in tool output. Credentials are never sent. */
    val exposeAddresses: Boolean = true,
) {
    fun policy() = McpPolicy(
        approveControl = approveControl,
        approveModify = approveModify,
        ipAllowlist = ipAllowlist,
        tailnetAllowlist = tailnetAllowlist,
    )
}

val McpIdleTimeoutOptions = listOf(15, 60, 0)

/** MCP settings, kept apart from the main app state (the token never leaves this file). */
object McpSettings {
    private const val PREFS = "mcp_settings"
    private const val KEY = "config_v1"

    @Synchronized
    fun load(context: Context): McpConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        val parsed = raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
        if (parsed != null && parsed.token.isNotBlank()) return parsed
        val fresh = McpConfig(token = newToken(), port = randomPort())
        save(context, fresh)
        return fresh
    }

    @Synchronized
    fun save(context: Context, config: McpConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, serialize(config).toString())
        }
    }

    fun update(context: Context, change: (McpConfig) -> McpConfig): McpConfig {
        val next = change(load(context))
        save(context, next)
        return next
    }

    /** 32 random bytes, base64url without padding. */
    fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun randomPort(): Int = 40000 + SecureRandom().nextInt(10000)

    private fun parse(json: JSONObject) = McpConfig(
        token = json.optString("token"),
        port = json.optInt("port", randomPort()).takeIf { it in 1024..65535 } ?: randomPort(),
        scope = runCatching { McpScope.valueOf(json.optString("scope")) }.getOrDefault(McpScope.Lan),
        ipAllowlist = json.optJSONArray("ipAllowlist").strings(),
        tailnetAllowlist = json.optJSONArray("tailnetAllowlist").strings(),
        approveControl = json.optBoolean("approveControl", false),
        approveModify = json.optBoolean("approveModify", true),
        idleTimeoutMinutes = json.optInt("idleTimeoutMinutes", 60).takeIf { it in McpIdleTimeoutOptions } ?: 60,
        exposeAddresses = json.optBoolean("exposeAddresses", true),
    )

    private fun serialize(config: McpConfig) = JSONObject()
        .put("token", config.token)
        .put("port", config.port)
        .put("scope", config.scope.name)
        .put("ipAllowlist", JSONArray(config.ipAllowlist))
        .put("tailnetAllowlist", JSONArray(config.tailnetAllowlist))
        .put("approveControl", config.approveControl)
        .put("approveModify", config.approveModify)
        .put("idleTimeoutMinutes", config.idleTimeoutMinutes)
        .put("exposeAddresses", config.exposeAddresses)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotBlank) }
}

/** The last 200 tool calls: time, caller, tool, summary (already redacted), result, approval. */
object McpAuditLog {
    private const val FILE = "mcp-audit.jsonl"
    private const val MAX_ENTRIES = 200

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun record(context: Context, entry: AuditEntry) {
        val line = JSONObject()
            .put("t", entry.timeMs)
            .put("caller", entry.caller)
            .put("tool", entry.tool)
            .put("summary", Redactor.text(entry.summary).take(300))
            .put("result", entry.result)
            .put("approvedBy", entry.approvedBy ?: JSONObject.NULL)
            .toString()
        val f = file(context)
        val kept = (if (f.exists()) f.readLines() else emptyList()).takeLast(MAX_ENTRIES - 1)
        f.writeText((kept + line).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun read(context: Context): List<AuditEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                AuditEntry(
                    timeMs = json.getLong("t"),
                    caller = json.optString("caller"),
                    tool = json.optString("tool"),
                    summary = json.optString("summary"),
                    result = json.optString("result"),
                    approvedBy = json.optString("approvedBy").takeIf { !json.isNull("approvedBy") && it.isNotBlank() },
                )
            }.getOrNull()
        }.reversed()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
