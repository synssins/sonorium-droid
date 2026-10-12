package app.sonorium.server

import app.sonorium.net.Area
import app.sonorium.net.Floor
import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerHierarchy
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRulesTest {
    private val kitchen = Speaker("media_player.kitchen", "Kitchen speaker", source = listOf("ha"), originalName = "Kitchen", address = "192.0.2.21", defaultAreaId = "kitchen")
    private val office = Speaker("net:cast:1", "Office display", online = false, source = listOf("discovered"))
    private val h = SpeakerHierarchy(
        floors = listOf(Floor("ground", "Ground floor", areas = listOf(Area("kitchen", "Kitchen", "ground", listOf(kitchen)), Area("den", "Den", "ground")))),
        unassignedSpeakers = listOf(office),
    )

    @Test
    fun sectionsAndFilters() {
        assertEquals(listOf("Ground floor · Kitchen", "No area"), SpeakerRules.sections(h) { true }.map { it.title })
        assertEquals(listOf("Ground floor · Kitchen"), SpeakerRules.sections(h) { SpeakerRules.matches(it, "", null, hideOffline = true) }.map { it.title })
        assertTrue(SpeakerRules.matches(kitchen, " 192.0.2 ", null, false))
        assertTrue(SpeakerRules.matches(kitchen, "KITCHEN", "ha", false))
        assertFalse(SpeakerRules.matches(kitchen, "", "discovered", false))
    }

    @Test
    fun edits() {
        assertTrue(SpeakerRules.renamed(kitchen))
        assertEquals(SpeakerRules.Rename.None, SpeakerRules.rename(kitchen, "Kitchen speaker"))
        assertEquals(SpeakerRules.Rename.Reset("Kitchen"), SpeakerRules.rename(kitchen, ""))
        assertEquals(SpeakerRules.Rename.Reset("Kitchen"), SpeakerRules.rename(kitchen, "Kitchen"))
        assertEquals(SpeakerRules.Rename.To("Cook"), SpeakerRules.rename(kitchen, "Cook"))
        assertNull(SpeakerRules.roomValue(kitchen, "kitchen"))
        assertEquals("", SpeakerRules.roomValue(kitchen, ""))
        assertNull(SpeakerRules.roomValue(office, ""))
        assertEquals(listOf(-20, -10, -5, 0, 10, 20), SpeakerRules.offsetOptions(-5))
        assertEquals("+10%", SpeakerRules.offsetText(10))
        assertEquals("0%", SpeakerRules.offsetText(0))
    }

    @Test
    fun timeAndChecks() {
        val now = Instant.parse("2026-10-11T20:02:11Z")
        assertEquals("1 hour ago", SpeakerRules.timeAgo("2026-10-11T19:02:11+00:00", now))
        assertEquals("just now", SpeakerRules.timeAgo("2026-10-11T20:01:40Z", now))
        assertEquals("2 days ago", SpeakerRules.timeAgo("2026-10-09T19:00:00Z", now))
        assertEquals("Enter the speaker's IP address or network name", SpeakerRules.checkAddress(" ", ""))
        assertEquals("Enter just the address, e.g. 192.168.1.50 or speaker.local", SpeakerRules.checkAddress("http://x", ""))
        assertEquals("Port must be a number from 1 to 65535", SpeakerRules.checkAddress("x", "70000"))
        assertNull(SpeakerRules.checkAddress("speaker.local", ""))
    }
}
