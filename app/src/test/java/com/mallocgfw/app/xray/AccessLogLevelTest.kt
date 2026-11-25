package com.mallocgfw.app.xray

import com.mallocgfw.app.model.AppLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The access log is a browsing history: only kept while the user is looking into a problem. */
class AccessLogLevelTest {
    @Test
    fun onlyInfoAndDebugKeepTheAccessLog() {
        assertFalse(AppLogManager.recordsAccessLog(AppLogLevel.Error))
        assertFalse(AppLogManager.recordsAccessLog(AppLogLevel.Warning))
        assertTrue(AppLogManager.recordsAccessLog(AppLogLevel.Info))
        assertTrue(AppLogManager.recordsAccessLog(AppLogLevel.Debug))
        // Xray's own value for "no access log"; a missing key would log to stdout.
        assertEquals("none", AppLogManager.ACCESS_LOG_OFF)
    }
}
