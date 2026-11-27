package com.mallocgfw.app.stats

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * The daily traffic history on disk (`traffic_stats.json`): what the
 * statistics screen and the remote panel add up. Written at most once a
 * minute while traffic flows, and when the VPN stops.
 */
object TrafficStatsStore {
    private const val TAG = "TrafficStatsStore"
    private const val FILE = "traffic_stats.json"
    private const val WRITE_INTERVAL_MS = 60_000L

    private var cache: TrafficHistory? = null
    private var dirty = false
    private var lastWriteMs = 0L

    private val _version = MutableStateFlow(0L)

    /** Bumped whenever the history changes, so screens can add it up again. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun today(): String = LocalDate.now().toString()

    @Synchronized
    fun history(context: Context): TrafficHistory {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val loaded = runCatching {
            if (file.exists()) TrafficHistory.fromJson(JSONObject(file.readText())) else TrafficHistory()
        }.onFailure { Log.w(TAG, "Unreadable traffic history, starting over", it) }
            .getOrDefault(TrafficHistory())
            .prune(today())
        cache = loaded
        return loaded
    }

    /** Adds what moved since the last call; [entries] are (app key, node key, bytes). */
    @Synchronized
    fun add(context: Context, entries: List<Triple<String, String, TrafficCount>>) {
        if (entries.isEmpty()) return
        val day = today()
        var next = history(context)
        entries.forEach { (app, node, count) -> next = next.add(day, app, node, count) }
        cache = next
        dirty = true
        _version.value += 1
        flush(context, force = false)
    }

    @Synchronized
    fun flush(context: Context, force: Boolean = true) {
        val current = cache ?: return
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (!force && now - lastWriteMs < WRITE_INTERVAL_MS) return
        val pruned = current.prune(today())
        cache = pruned
        runCatching {
            val file = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(pruned.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(pruned.toJson().toString())
                tmp.delete()
            }
        }.onSuccess {
            dirty = false
            lastWriteMs = now
        }.onFailure { Log.w(TAG, "Couldn't save the traffic history", it) }
    }

    @Synchronized
    fun clear(context: Context) {
        cache = TrafficHistory()
        dirty = false
        File(context.filesDir, FILE).delete()
        _version.value += 1
    }
}
