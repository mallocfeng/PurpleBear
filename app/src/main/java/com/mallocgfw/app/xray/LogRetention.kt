package com.mallocgfw.app.xray

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Logs keep at most about six months of entries; older ones are deleted. */
object LogRetention {
    const val MAX_AGE_DAYS = 180L

    fun cutoffMillis(now: Long = System.currentTimeMillis()): Long = now - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)

    // App log: "2026-11-22 23:11:03 [...]"; Xray logs: "2026/11/22 23:11:03.123 [...]".
    private val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss")

    private fun formats() = patterns.map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }

    /** When the entry on [line] was written, or null for a line that continues the one before. */
    fun lineTime(line: String): Long? = lineTime(line, formats())

    private fun lineTime(line: String, formats: List<SimpleDateFormat>): Long? {
        if (line.length < 19 || !line[0].isDigit()) return null
        val head = line.substring(0, 19)
        return formats.firstNotNullOfOrNull { format ->
            val position = ParsePosition(0)
            format.parse(head, position)?.takeIf { position.index == 19 }?.time
        }
    }

    /**
     * Drops entries written before [cutoff]. Logs only grow at the end, so
     * everything from the first recent entry on is kept. A line without a
     * time belongs to the entry above it (lines before the first dated one
     * are a cut-off entry: they go with the old ones).
     */
    fun dropOldLines(text: String, cutoff: Long): String {
        val formats = formats()
        val lines = text.split('\n')
        var firstOld = -1
        for ((index, line) in lines.withIndex()) {
            val time = lineTime(line, formats) ?: continue
            if (time >= cutoff) {
                return if (firstOld < 0) text else lines.subList(index, lines.size).joinToString("\n")
            }
            if (firstOld < 0) firstOld = index
        }
        // Nothing recent (or no dates at all, which keeps the text as it is).
        return if (firstOld < 0) text else ""
    }
}
