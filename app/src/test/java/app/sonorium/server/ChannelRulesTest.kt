package app.sonorium.server

import app.sonorium.net.AdhocSelection
import app.sonorium.net.Area
import app.sonorium.net.Floor
import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerHierarchy
import app.sonorium.net.ThemeSummary
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelRulesTest {
    @Test
    fun themeIcons() {
        assertEquals("🍺", ChannelRules.themeIcon("x", ThemeSummary("x", icon = "🍺")))
        assertEquals("🌧️", ChannelRules.themeIcon("x", ThemeSummary("x", icon = "mdi:weather-rainy")))
        assertEquals("🍺", ChannelRules.themeIcon("cozy-tavern", ThemeSummary("cozy-tavern")))
        assertEquals("🌧️", ChannelRules.themeIcon("rainy-tavern", null)) // first keyword in list order
        assertEquals("🎵", ChannelRules.themeIcon("df297db5", ThemeSummary("df297db5", icon = "mdi:unknown")))
    }

    @Test
    fun clockAndTimes() {
        assertEquals("0:05", ChannelRules.clock(5))
        assertEquals("13:01", ChannelRules.clock(781))
        assertEquals("1:00:00", ChannelRules.clock(3600))
        assertEquals("0:00", ChannelRules.clock(-3))
        val now = Instant.parse("2026-10-12T15:00:00Z")
        assertEquals("03:08", ChannelRules.recentTime("2026-10-12T03:08:57.039075+00:00", now, ZoneOffset.UTC))
        assertEquals("Yesterday 23:10", ChannelRules.recentTime("2026-10-11T23:10:00+00:00", now, ZoneOffset.UTC))
        // naive timestamps are UTC (native-api.md 1.1)
        assertEquals("03:08", ChannelRules.recentTime("2026-10-12T03:08:40.953353", now, ZoneOffset.UTC))
    }

    private val hierarchy = SpeakerHierarchy(
        floors = listOf(
            Floor("ground", "Ground", areas = listOf(
                Area("kitchen", "Kitchen", speakers = listOf(Speaker("k1"), Speaker("k2"))),
                Area("living", "Living", speakers = listOf(Speaker("l1"), Speaker("off", online = false))),
            )),
        ),
        unassignedAreas = listOf(Area("garage", "Garage", speakers = listOf(Speaker("g1")))),
        unassignedSpeakers = listOf(Speaker("u1")),
    )
    private val picker = ChannelRules.Picker(hierarchy, setOf("k1", "k2", "l1", "off", "g1", "u1"))

    @Test
    fun compactsFullFloorsAndAreas() {
        assertEquals(AdhocSelection(includeFloors = listOf("ground")), picker.compact(setOf("k1", "k2", "l1")))
        assertEquals(
            AdhocSelection(includeAreas = listOf("kitchen", "garage"), includeSpeakers = listOf("u1")),
            picker.compact(setOf("k1", "k2", "g1", "u1")),
        )
        assertEquals(AdhocSelection(includeSpeakers = listOf("k1")), picker.compact(setOf("k1")))
    }

    @Test
    fun effectiveSelection() {
        assertEquals(setOf("k1", "k2", "l1"), picker.effective(AdhocSelection(includeFloors = listOf("ground"))))
        assertEquals(setOf("k2", "l1"), picker.effective(AdhocSelection(includeFloors = listOf("ground"), excludeSpeakers = listOf("k1"))))
        // speakers not enabled are dropped
        assertEquals(setOf("g1"), ChannelRules.Picker(hierarchy, setOf("g1")).effective(AdhocSelection(includeSpeakers = listOf("k1", "g1"))))
    }
}
