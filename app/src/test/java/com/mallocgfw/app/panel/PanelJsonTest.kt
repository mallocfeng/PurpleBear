package com.mallocgfw.app.panel

import com.mallocgfw.app.stats.ConnectionView
import com.mallocgfw.app.stats.LabeledConnection
import com.mallocgfw.app.stats.SpeedSample
import com.mallocgfw.app.stats.TrafficCount
import com.mallocgfw.app.stats.TrafficHistory
import com.mallocgfw.app.stats.TrackedConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PanelJsonTest {
    private fun conn(id: Long, end: Long? = null) = LabeledConnection(
        connection = TrackedConnection(id, 1000, end, "tcp", "172.19.0.1:1", "<script>x.example</script>", null, 443, "tun-in", "proxy", "rule|A", 10123, 5, 6),
        appKey = "com.example",
        appLabel = "Example",
        nodeLabel = "Tokyo",
        ruleLabel = "Rule: A",
        nodeKey = if (id % 2 == 0L) "Tokyo" else TrafficHistory.Route.DIRECT,
        ruleKind = "rule",
    )

    @Test
    fun snapshotCarriesConnectionsAndTraffic() {
        val history = TrafficHistory().add("2026-11-27", "com.example", "Tokyo", TrafficCount(1, 2))
        val json = PanelJson.snapshot(
            view = ConnectionView(running = true, active = listOf(conn(2)), closed = (1L..150L).map { conn(it, end = 2000) }, upSpeed = 3, downSpeed = 4, speedHistory = listOf(SpeedSample(4500, 7, 8))),
            connected = true,
            node = "Tokyo",
            summaries = listOf("today" to history.summarize("2026-11-27", 1)),
            language = "en",
            version = "test",
            appLabel = { "Label of $it" },
            nowMs = 5000,
            device = "Pixel 9",
            connectedAtMs = 4000,
        )
        assertEquals("Pixel 9", json.getString("device"))
        assertEquals("[4500,7,8]", json.getJSONArray("speedHistory").getJSONArray(0).toString())
        assertEquals(4000L, json.getJSONObject("vpn").getLong("since"))
        assertEquals("Tokyo", json.getJSONObject("vpn").getString("node"))
        val active = json.getJSONArray("active").getJSONObject(0)
        // Raw text: the page inserts it with textContent.
        assertEquals("<script>x.example</script>", active.getString("host"))
        assertEquals("Rule: A", active.getString("rule"))
        assertEquals("proxy", active.getString("route"))
        assertEquals("rule", active.getString("ruleKind"))
        assertEquals("direct", json.getJSONArray("closed").getJSONObject(0).getString("route"))
        assertFalse(active.has("end"))
        assertEquals(PanelJson.MAX_CLOSED, json.getJSONArray("closed").length())
        val today = json.getJSONObject("traffic").getJSONObject("today")
        assertEquals(3L, today.getLong("up") + today.getLong("down"))
        assertEquals("Label of com.example", today.getJSONArray("apps").getJSONObject(0).getString("name"))
        assertEquals("Tokyo", today.getJSONArray("nodes").getJSONObject(0).getString("name"))
        assertEquals("proxy", today.getJSONArray("nodes").getJSONObject(0).getString("route"))
        val via = today.getJSONArray("apps").getJSONObject(0).getJSONArray("via").getJSONObject(0)
        assertEquals("Tokyo", via.getString("name"))
        assertEquals("proxy", via.getString("route"))
        assertEquals(3L, via.getLong("up") + via.getLong("down"))
    }
}
