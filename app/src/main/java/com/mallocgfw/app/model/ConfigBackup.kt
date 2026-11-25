package com.mallocgfw.app.model

import org.json.JSONObject

/**
 * The app's saved state as one file the user can keep and restore: node
 * groups and nodes, subscriptions, rule sources, per-app and media routing,
 * and settings.
 *
 * Not in it, because they belong to one phone: the MCP token, pinned
 * certificates, the Tailscale node identity, logs and downloaded rule lists
 * (those are downloaded again after a restore).
 *
 * The file is plain JSON and holds node passwords, subscription links and
 * the Tailscale auth key: the export screen says so.
 */
object ConfigBackup {
    const val FORMAT = 1
    private const val APP = "PurpleBear"

    fun export(state: PersistedAppState, appVersion: String, nowMs: Long): String = JSONObject()
        .put("app", APP)
        .put("format", FORMAT)
        .put("appVersion", appVersion)
        .put("exportedAtMs", nowMs)
        .put("state", AppStateStore.serializeState(AppStateStore.normalize(state)))
        .toString(2)

    /** The state in a backup file. Throws with a message for the user when [text] isn't one. */
    fun parse(text: String): PersistedAppState {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: error(tr("这不是 PurpleBear 的备份文件。", "This isn't a PurpleBear backup file.", "Это не файл резервной копии PurpleBear."))
        val state = root.optJSONObject("state")
        if (root.optString("app") != APP || state == null) {
            error(tr("这不是 PurpleBear 的备份文件。", "This isn't a PurpleBear backup file.", "Это не файл резервной копии PurpleBear."))
        }
        val format = root.optInt("format", 0)
        if (format > FORMAT) {
            error(
                tr(
                    "这份备份来自更新版本的 PurpleBear，请先升级应用再恢复。",
                    "This backup is from a newer PurpleBear. Update the app, then restore.",
                    "Эта копия создана более новой версией PurpleBear. Обновите приложение и повторите.",
                ),
            )
        }
        val restored = runCatching { AppStateStore.normalize(AppStateStore.parseState(state)) }.getOrElse {
            error(tr("备份文件已损坏，无法读取。", "The backup file is damaged and can't be read.", "Файл резервной копии повреждён."))
        }
        // Tied to the phone it ran on: start disconnected, nothing to resume.
        return restored.copy(settings = restored.settings.copy(resumeConnectionOnLaunch = false))
    }

    /** A file name such as purplebear-backup-20261124.json. */
    fun fileName(nowMs: Long): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(nowMs))
        return "purplebear-backup-$date.json"
    }
}
