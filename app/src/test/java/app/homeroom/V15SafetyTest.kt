package app.homeroom

import org.junit.Test
import org.junit.Assert.*

class V15SafetyTest {
    @Test fun crashCauseMessagesAreNeverRecorded() {
        val inner=IllegalArgumentException("private-token-and-message")
        val outer=RuntimeException("private-server-and-text",inner)
        val result=CrashLog.safeDetails(outer)
        assertTrue(result.contains("RuntimeException"))
        assertTrue(result.contains("IllegalArgumentException"))
        assertTrue(result.contains("Caused by:"))
        assertFalse(result.contains("private-token"))
        assertFalse(result.contains("private-server"))
        assertTrue(result.length<=12000)
    }
}