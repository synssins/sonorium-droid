package app.sonorium.server

import app.sonorium.server.LogsRules.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsRulesTest {
    private val info = Entry("21:03:18.042", "INFO", "sonorium", "Playback: Kitchen: step 2/3", 1)
    private val warn = Entry("21:03:19.310", "WARNING", "aiohttp.client", "Speaker did not answer", 2)
    private val debug = Entry("21:03:20.000", "DEBUG", "sonorium", "tick", 3)

    @Test fun `filters and search`() {
        assertFalse(LogsRules.visible(debug, "Info", ""))
        assertTrue(LogsRules.visible(debug, "All", ""))
        assertTrue(LogsRules.visible(warn, "Warnings", ""))
        assertFalse(LogsRules.visible(info, "Warnings", ""))
        assertTrue(LogsRules.visible(info, "Playback", ""))
        assertFalse(LogsRules.visible(warn, "Playback", ""))
        assertTrue(LogsRules.visible(warn, "All", "AIOHTTP"))
        assertFalse(LogsRules.visible(warn, "All", "kitchen"))
        assertEquals(1, LogsRules.rank("weird"))
    }

    @Test fun `copy line, keep and meta`() {
        assertEquals("21:03:19.310 WARNING  aiohttp.client: Speaker did not answer", LogsRules.line(warn))
        assertEquals(LogsRules.KEEP, LogsRules.append(List(LogsRules.KEEP) { info }, listOf(warn)).size)
        assertEquals("1.6.2 · Docker · log level: info", LogsRules.meta("1.6.2", "Docker", "info"))
        assertEquals("1.6.2 · log level: info", LogsRules.meta("1.6.2", "", "info"))
    }
}
