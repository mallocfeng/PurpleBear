package com.mallocgfw.app.stats

import org.json.JSONArray
import org.json.JSONObject

/** One connection the tunnel carries (or carried), as the core's tracker reports it. */
data class TrackedConnection(
    val id: Long,
    val startMs: Long,
    /** Null while the connection is open. */
    val endMs: Long?,
    /** "tcp" or "udp". */
    val network: String,
    val source: String,
    /** The sniffed domain, or the IP address when none was found. */
    val host: String,
    /** The IP address the app dialed, when [host] is a domain. */
    val ip: String?,
    val port: Int,
    val inbound: String,
    /** Xray outbound tag: "proxy", "direct", "block", a routed node's tag … */
    val outbound: String,
    /** The routing rule's tag (see [RuleTags]); null when the fallback took it. */
    val rule: String?,
    /** Android UID of the app that opened it; -1 when unknown. */
    val uid: Int,
    val up: Long,
    val down: Long,
) {
    val open: Boolean get() = endMs == null
}

/** Bytes per app and outbound since the core's process started. */
data class ConnectionTotal(val uid: Int, val outbound: String, val up: Long, val down: Long)

/** What `LibXray.connTrackSnapshot` returns. */
data class ConnectionSnapshot(
    /** Changes when the process (and with it every counter) restarts. */
    val epoch: Long,
    val nowMs: Long,
    val enabled: Boolean,
    val active: List<TrackedConnection>,
    /** Connections closed since the id asked for, oldest first. */
    val closed: List<TrackedConnection>,
    val totals: List<ConnectionTotal>,
) {
    companion object {
        val Empty = ConnectionSnapshot(0, 0, false, emptyList(), emptyList(), emptyList())

        fun parse(json: String): ConnectionSnapshot {
            val root = JSONObject(json)
            return ConnectionSnapshot(
                epoch = root.optLong("epoch"),
                nowMs = root.optLong("now"),
                enabled = root.optBoolean("enabled"),
                active = root.optJSONArray("active").connections(),
                closed = root.optJSONArray("closed").connections(),
                totals = root.optJSONArray("totals").objects().map {
                    ConnectionTotal(
                        uid = it.optInt("uid", -1),
                        outbound = it.optString("out"),
                        up = it.optLong("up"),
                        down = it.optLong("down"),
                    )
                },
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.connections(): List<TrackedConnection> = objects().map {
            TrackedConnection(
                id = it.optLong("id"),
                startMs = it.optLong("start"),
                endMs = it.optLong("end").takeIf { end -> end > 0 },
                network = it.optString("net"),
                source = it.optString("src"),
                host = it.optString("host"),
                ip = it.optString("ip").takeIf(String::isNotBlank),
                port = it.optInt("port"),
                inbound = it.optString("in"),
                outbound = it.optString("out"),
                rule = it.optString("rule").takeIf(String::isNotBlank),
                uid = it.optInt("uid", -1),
                up = it.optLong("up"),
                down = it.optLong("down"),
            )
        }
    }
}

/**
 * Turns the core's running totals into the bytes moved since the last look.
 * The totals only grow while the process lives; a new [ConnectionSnapshot.epoch]
 * starts over from zero.
 */
class TotalsDelta {
    private var epoch: Long? = null
    private var previous: Map<Pair<Int, String>, ConnectionTotal> = emptyMap()

    /** What each (uid, outbound) moved since the previous call; only non-zero entries. */
    fun next(snapshot: ConnectionSnapshot): List<ConnectionTotal> {
        if (epoch != snapshot.epoch) {
            epoch = snapshot.epoch
            previous = emptyMap()
        }
        val current = snapshot.totals.associateBy { it.uid to it.outbound }
        val deltas = current.values.mapNotNull { total ->
            val before = previous[total.uid to total.outbound]
            val up = (total.up - (before?.up ?: 0)).coerceAtLeast(0)
            val down = (total.down - (before?.down ?: 0)).coerceAtLeast(0)
            if (up == 0L && down == 0L) null else ConnectionTotal(total.uid, total.outbound, up, down)
        }
        previous = current
        return deltas
    }
}
