package com.mallocgfw.app.stats

import org.json.JSONArray
import org.json.JSONObject

/** Bytes up and down. */
data class TrafficCount(val up: Long = 0, val down: Long = 0) {
    val total: Long get() = up + down
    operator fun plus(other: TrafficCount) = TrafficCount(up + other.up, down + other.down)
}

/** One day's traffic ("2026-11-27", local time) per app and per node. */
data class DayTraffic(
    val day: String,
    val apps: Map<String, TrafficCount> = emptyMap(),
    val nodes: Map<String, TrafficCount> = emptyMap(),
    /**
     * Per app, the nodes its traffic went through: what tells proxied from
     * direct traffic. Missing for days counted before it was kept.
     */
    val appNodes: Map<String, Map<String, TrafficCount>> = emptyMap(),
) {
    val total: TrafficCount get() = apps.values.fold(TrafficCount()) { sum, count -> sum + count }
}

/** A range of days added up, biggest first. */
data class TrafficSummary(
    val total: TrafficCount,
    val apps: List<Pair<String, TrafficCount>>,
    val nodes: List<Pair<String, TrafficCount>>,
    /** Every day of the range, oldest first, including days with nothing. */
    val daily: List<Pair<String, TrafficCount>>,
    /** Per app, its nodes biggest first (see [DayTraffic.appNodes]). */
    val appNodes: Map<String, List<Pair<String, TrafficCount>>> = emptyMap(),
)

/**
 * Daily traffic per app and per node, kept for [KEEP_DAYS] days. App keys
 * are package names (or [UNKNOWN_APP] / "uid:N" when no package is known);
 * node keys are node names or the [Route] keys for direct, blocked, DNS and
 * Tailscale traffic.
 */
data class TrafficHistory(val days: Map<String, DayTraffic> = emptyMap()) {
    fun add(day: String, app: String, node: String, count: TrafficCount): TrafficHistory {
        if (count.total <= 0) return this
        val current = days[day] ?: DayTraffic(day)
        val appNodes = current.appNodes[app].orEmpty()
        val next = current.copy(
            apps = current.apps + (app to ((current.apps[app] ?: TrafficCount()) + count)),
            nodes = current.nodes + (node to ((current.nodes[node] ?: TrafficCount()) + count)),
            appNodes = current.appNodes + (app to (appNodes + (node to ((appNodes[node] ?: TrafficCount()) + count)))),
        )
        return copy(days = days + (day to next))
    }

    /** Drops days before the [KEEP_DAYS] ending with [today] (ISO dates compare as strings). */
    fun prune(today: String, keepDays: Int = KEEP_DAYS): TrafficHistory {
        val oldest = shiftDay(today, -(keepDays - 1))
        return copy(days = days.filterKeys { it >= oldest })
    }

    /** The [count] days ending with [today]. */
    fun summarize(today: String, count: Int): TrafficSummary {
        val range = (count - 1 downTo 0).map { shiftDay(today, -it) }
        val inRange = range.mapNotNull { days[it] }
        fun merge(maps: List<Map<String, TrafficCount>>): List<Pair<String, TrafficCount>> =
            maps.flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, counts) -> counts.fold(TrafficCount()) { sum, c -> sum + c } }
                .toList()
                .sortedByDescending { it.second.total }
        val apps = merge(inRange.map { it.apps })
        return TrafficSummary(
            total = inRange.fold(TrafficCount()) { sum, day -> sum + day.total },
            apps = apps,
            nodes = merge(inRange.map { it.nodes }),
            daily = range.map { it to (days[it]?.total ?: TrafficCount()) },
            appNodes = apps.associate { (app, _) -> app to merge(inRange.mapNotNull { it.appNodes[app] }) }
                .filterValues { it.isNotEmpty() },
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put(
            "days",
            JSONArray().apply {
                days.values.sortedBy { it.day }.forEach { day ->
                    put(
                        JSONObject().apply {
                            put("day", day.day)
                            put("apps", day.apps.toJson())
                            put("nodes", day.nodes.toJson())
                            put(
                                "appNodes",
                                JSONObject().apply { day.appNodes.forEach { (app, nodes) -> put(app, nodes.toJson()) } },
                            )
                        },
                    )
                }
            },
        )
    }

    /** Node keys for traffic that went through no node. */
    object Route {
        const val DIRECT = "@direct"
        const val BLOCK = "@block"

        /** QUIC dropped so apps retry over TCP: not blocked by a rule. */
        const val QUIC_FALLBACK = "@quic"
        const val DNS = "@dns"
        const val TAILSCALE = "@tailscale"
    }

    companion object {
        const val KEEP_DAYS = 90
        const val UNKNOWN_APP = "?"

        fun fromJson(json: JSONObject): TrafficHistory {
            val list = json.optJSONArray("days") ?: return TrafficHistory()
            val days = (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val day = item.optString("day").takeIf { DAY.matches(it) } ?: return@mapNotNull null
                val appNodes = item.optJSONObject("appNodes")?.let { byApp ->
                    byApp.keys().asSequence().associateWith { app -> byApp.optJSONObject(app).counts() }.filterValues { it.isNotEmpty() }
                }.orEmpty()
                DayTraffic(day, item.optJSONObject("apps").counts(), item.optJSONObject("nodes").counts(), appNodes)
            }
            return TrafficHistory(days.associateBy { it.day })
        }

        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        fun shiftDay(day: String, by: Int): String =
            java.time.LocalDate.parse(day).plusDays(by.toLong()).toString()

        private fun Map<String, TrafficCount>.toJson() = JSONObject().apply {
            forEach { (key, count) -> put(key, JSONArray().put(count.up).put(count.down)) }
        }

        private fun JSONObject?.counts(): Map<String, TrafficCount> {
            if (this == null) return emptyMap()
            return keys().asSequence().mapNotNull { key ->
                val pair = optJSONArray(key) ?: return@mapNotNull null
                key to TrafficCount(pair.optLong(0).coerceAtLeast(0), pair.optLong(1).coerceAtLeast(0))
            }.toMap()
        }
    }
}
