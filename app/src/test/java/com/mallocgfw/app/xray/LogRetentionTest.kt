package com.mallocgfw.app.xray

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogRetentionTest {
    private val cutoff = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-05-26 00:00:00")!!.time

    @Test
    fun readsBothTimeFormats() {
        assertEquals(cutoff, LogRetention.lineTime("2026-05-26 00:00:00 [SYNC] done"))
        assertEquals(cutoff, LogRetention.lineTime("2026/05/26 00:00:00.123456 [Warning] core: started"))
        assertNull(LogRetention.lineTime("    at com.example.Foo"))
        assertNull(LogRetention.lineTime(""))
    }

    @Test
    fun dropsOldEntriesWithTheirFollowingLines() {
        val text = listOf(
            "2026-01-02 10:00:00 [A] old",
            "  detail of old",
            "2026/03/01 10:00:00.1 [Warning] old core",
            "2026-06-01 10:00:00 [B] new",
            "  detail of new",
            "",
        ).joinToString("\n")
        assertEquals(
            "2026-06-01 10:00:00 [B] new\n  detail of new\n",
            LogRetention.dropOldLines(text, cutoff),
        )
    }

    @Test
    fun dropsEverythingWhenAllIsOld() {
        val text = "partial line from trimming\n2026-01-02 10:00:00 [A] old\n  detail\n"
        assertEquals("", LogRetention.dropOldLines(text, cutoff))
        assertEquals("no dates here\n", LogRetention.dropOldLines("no dates here\n", cutoff))
        val mixed = "partial line\n2026-01-02 10:00:00 [A] old\n2026-06-01 10:00:00 [B] new\n"
        assertEquals("2026-06-01 10:00:00 [B] new\n", LogRetention.dropOldLines(mixed, cutoff))
    }

    @Test
    fun keepsEverythingWhenNothingIsOld() {
        val text = "2026-06-01 10:00:00 [B] new\n2026-07-01 10:00:00 [C] newer\n"
        assertEquals(text, LogRetention.dropOldLines(text, cutoff))
    }
}
