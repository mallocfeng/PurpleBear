package com.mallocgfw.app.stats

import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.TextLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsModelTest {
    private fun snapshot(epoch: Long, vararg totals: ConnectionTotal) =
        ConnectionSnapshot(epoch, 0, true, emptyList(), emptyList(), totals.toList())

    @Test
    fun parsesTheCoreSnapshot() {
        val json = """
            {"epoch":7,"now":1000,"enabled":true,
             "active":[{"id":2,"start":900,"net":"tcp","src":"172.19.0.1:4000","host":"www.example.com","ip":"93.184.216.34","port":443,"in":"tun-in","out":"proxy","rule":"rule|My list","uid":10123,"up":10,"down":20}],
             "closed":[{"id":1,"start":100,"end":800,"net":"udp","src":"172.19.0.1:5000","host":"8.8.8.8","port":53,"in":"tun-in","out":"direct","uid":-1,"up":1,"down":2}],
             "totals":[{"uid":10123,"out":"proxy","up":10,"down":20}]}
        """.trimIndent()
        val snap = ConnectionSnapshot.parse(json)
        assertEquals(7, snap.epoch)
        val active = snap.active.single()
        assertEquals("www.example.com", active.host)
        assertEquals("93.184.216.34", active.ip)
        assertEquals("rule|My list", active.rule)
        assertTrue(active.open)
        val closed = snap.closed.single()
        assertNull(closed.ip)
        assertNull(closed.rule)
        assertEquals(800L, closed.endMs)
        assertEquals(-1, closed.uid)
        assertEquals(ConnectionTotal(10123, "proxy", 10, 20), snap.totals.single())
    }

    @Test
    fun totalsTurnIntoWhatMovedSinceLastLook() {
        val delta = TotalsDelta()
        assertEquals(listOf(ConnectionTotal(1, "proxy", 100, 200)), delta.next(snapshot(1, ConnectionTotal(1, "proxy", 100, 200))))
        assertEquals(emptyList<ConnectionTotal>(), delta.next(snapshot(1, ConnectionTotal(1, "proxy", 100, 200))))
        assertEquals(
            listOf(ConnectionTotal(1, "proxy", 50, 0), ConnectionTotal(2, "direct", 5, 5)),
            delta.next(snapshot(1, ConnectionTotal(1, "proxy", 150, 200), ConnectionTotal(2, "direct", 5, 5))),
        )
        // A new process starts counting from zero again.
        assertEquals(listOf(ConnectionTotal(1, "proxy", 30, 40)), delta.next(snapshot(2, ConnectionTotal(1, "proxy", 30, 40))))
    }

    @Test
    fun historyAddsUpDaysAppsAndNodes() {
        val history = TrafficHistory()
            .add("2026-11-26", "com.a", "Tokyo", TrafficCount(10, 100))
            .add("2026-11-27", "com.a", "Tokyo", TrafficCount(1, 10))
            .add("2026-11-27", "com.b", TrafficHistory.Route.DIRECT, TrafficCount(2, 20))
            .add("2026-11-27", "com.b", "Tokyo", TrafficCount(0, 0))

        val today = history.summarize("2026-11-27", 1)
        assertEquals(TrafficCount(3, 30), today.total)
        assertEquals(listOf("com.b", "com.a"), today.apps.map { it.first })
        assertEquals(listOf(TrafficHistory.Route.DIRECT, "Tokyo"), today.nodes.map { it.first })

        val week = history.summarize("2026-11-27", 7)
        assertEquals(TrafficCount(13, 130), week.total)
        assertEquals(7, week.daily.size)
        assertEquals("2026-11-21", week.daily.first().first)
        assertEquals(TrafficCount(11, 110), week.apps.first { it.first == "com.a" }.second)

        val restored = TrafficHistory.fromJson(JSONObject(history.toJson().toString()))
        assertEquals(history, restored)

        val pruned = history.prune("2026-11-27", keepDays = 1)
        assertEquals(setOf("2026-11-27"), pruned.days.keys)
    }

    @Test
    fun historyKnowsWhichNodesEachAppUsed() {
        val history = TrafficHistory()
            .add("2026-11-26", "com.a", "Tokyo", TrafficCount(1, 9))
            .add("2026-11-27", "com.a", TrafficHistory.Route.DIRECT, TrafficCount(0, 5))
            .add("2026-11-27", "com.a", "Tokyo", TrafficCount(1, 1))
            .add("2026-11-27", "com.b", TrafficHistory.Route.BLOCK, TrafficCount(3, 0))

        val week = history.summarize("2026-11-27", 7)
        assertEquals(
            listOf("Tokyo" to TrafficCount(2, 10), TrafficHistory.Route.DIRECT to TrafficCount(0, 5)),
            week.appNodes["com.a"],
        )
        assertEquals(listOf(TrafficHistory.Route.BLOCK to TrafficCount(3, 0)), week.appNodes["com.b"])
        assertEquals(history, TrafficHistory.fromJson(JSONObject(history.toJson().toString())))

        // Days saved before the breakdown existed still load; their apps have none.
        val old = JSONObject(history.toJson().toString())
        old.getJSONArray("days").getJSONObject(0).remove("appNodes")
        val restored = TrafficHistory.fromJson(old)
        assertEquals(listOf("Tokyo" to TrafficCount(1, 1), TrafficHistory.Route.DIRECT to TrafficCount(0, 5)).toSet(), restored.summarize("2026-11-27", 7).appNodes["com.a"]!!.toSet())
    }

    @Test
    fun ruleTagsNameTheRuleAndStayUnique() {
        AppTextLanguage.current = TextLanguage.English
        val tag = RuleTags.tag(RuleTags.Kind.Media, "Netflix|HK~")
        assertEquals("media|Netflix/HK-", tag)
        assertEquals(RuleTags.Kind.Media to "Netflix/HK-", RuleTags.parse(tag))
        assertEquals("Media: Netflix/HK-", RuleTags.describe(tag))
        assertEquals("Fallback", RuleTags.describe(null))
        // The Play rule covers all of google.com and googleapis.com: named for what it matches.
        assertEquals("Google services", RuleTags.describe(RuleTags.tag(RuleTags.Kind.Play)))

        val rules = JSONArray()
            .put(JSONObject().put("ruleTag", "rule|A"))
            .put(JSONObject().put("ruleTag", "rule|A"))
            .put(JSONObject())
            .put(JSONObject().put("ruleTag", "rule|A"))
        RuleTags.makeUnique(rules)
        assertEquals(listOf("rule|A", "rule|A~2", "", "rule|A~3"), List(rules.length()) { rules.getJSONObject(it).optString("ruleTag") })
        assertEquals(RuleTags.Kind.Rule to "A", RuleTags.parse("rule|A~3"))
    }
}
