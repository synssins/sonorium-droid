package app.sonorium.server

import app.sonorium.net.EditorTrack
import app.sonorium.net.TrackGroup
import app.sonorium.server.ThemeEditorRules.Section
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEditorRulesTest {
    private fun group(name: String, mode: String? = null, vararg more: Pair<String, Any>) = TrackGroup(
        name,
        JsonObject((listOfNotNull(mode?.let { "mode" to JsonPrimitive(it) }) + more.map { (k, v) ->
            k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
        }).toMap()),
    )

    @Test fun `section follows the group, then the mode, then the auto rule`() {
        val groups = listOf(group("Birds", "ebb_flow"), group("Old", "intrusion"), group("Plain"))
        assertEquals(Section.EbbFlow, ThemeEditorRules.sectionOf(EditorTrack("Birds/robin", playbackMode = "continuous"), groups))
        // an intrusion group's tracks show by their own mode
        assertEquals(Section.Background, ThemeEditorRules.sectionOf(EditorTrack("Old/x", playbackMode = "continuous"), groups))
        // missing mode → intermittent
        assertEquals(Section.Intermittent, ThemeEditorRules.sectionOf(EditorTrack("Plain/x", playbackMode = "continuous"), groups))
        assertEquals(Section.Unassigned, ThemeEditorRules.sectionOf(EditorTrack("a", playbackMode = "unassigned"), groups))
        assertEquals(Section.Background, ThemeEditorRules.sectionOf(EditorTrack("a", playbackMode = "auto", presence = 1.0), groups))
        assertEquals(Section.Intermittent, ThemeEditorRules.sectionOf(EditorTrack("a", presence = 0.5, isShortFile = true), groups))
        assertEquals(Section.EbbFlow, ThemeEditorRules.sectionOf(EditorTrack("a", presence = 0.5), groups))
    }

    @Test fun `folders without a group entry become groups`() {
        val all = ThemeEditorRules.allGroups(listOf(group("birds")), listOf(EditorTrack("Rain/a"), EditorTrack("x")))
        assertEquals(listOf("birds", "Rain"), all.map { it.name })
    }

    @Test fun `gapless and the advanced dot`() {
        assertTrue(ThemeEditorRules.gaplessAllowed(EditorTrack("a")))
        assertFalse(ThemeEditorRules.gaplessAllowed(EditorTrack("a", isShortFile = true)))
        assertFalse(ThemeEditorRules.gaplessAllowed(EditorTrack("a", playbackMode = "sparse")))
        assertTrue(ThemeEditorRules.advancedDot(EditorTrack("a", fadeIn = 2.0), Section.Background, false))
        assertTrue(ThemeEditorRules.advancedDot(EditorTrack("a", rise = 0.3), Section.EbbFlow, false))
        assertFalse(ThemeEditorRules.advancedDot(EditorTrack("a", rise = 0.3), Section.Intermittent, false))
        assertFalse(ThemeEditorRules.advancedDot(EditorTrack("a", seamlessLoop = true), Section.Background, true))
    }

    @Test fun `new group names, lengths, minutes`() {
        assertEquals("New group", ThemeEditorRules.newGroupName(listOf("Birds")))
        assertEquals("New group 3", ThemeEditorRules.newGroupName(listOf("new group", "New Group 2")))
        assertEquals("1:03", ThemeEditorRules.formatLength(62.5))
        assertEquals("—", ThemeEditorRules.formatLength(0.0))
        assertEquals("2", ThemeEditorRules.minutes(120.0))
        assertEquals("2.5", ThemeEditorRules.minutes(150.0))
        assertEquals(30.0, ThemeEditorRules.gapSeconds("0.5"))
        assertEquals(null, ThemeEditorRules.gapSeconds("x"))
        assertEquals(60.0 to 120.0, ThemeEditorRules.gapPair(120.0, 60.0))
        assertEquals(30.0, ThemeEditorRules.fadeSeconds("45"), 0.0)
        assertEquals(10.0 to 20.0, ThemeEditorRules.pausePair("20", "10"))
        assertEquals(64, ThemeEditorRules.playsAt(0.8, 0.8))
    }

    @Test fun `category options`() {
        val known = listOf("Weather", "Fantasy", "Night", "Wet nights")
        val opts = ThemeEditorRules.categoryOptions(known, listOf("Fantasy"), "n")
        assertEquals(listOf("Night", "Wet nights", "n"), opts.map { it.name })
        assertTrue(opts.last().create)
        assertEquals(listOf("Night", "Wet nights"), ThemeEditorRules.categoryOptions(known, emptyList(), "night").map { it.name })
        assertEquals(listOf("Weather"), ThemeEditorRules.addCategory(emptyList(), known, " weather "))
        assertEquals(listOf("Weather"), ThemeEditorRules.addCategory(listOf("Weather"), known, "WEATHER"))
    }

    @Test fun `cancel restores only what changed`() {
        val t = EditorTrack("a", volume = 0.5, fadeIn = 2.0, rise = 0.1)
        val g = group("Birds", "intermittent", "volume" to 0.8)
        val snap = ThemeEditorRules.snapshot(listOf(t), listOf(g))
        val now = listOf(t.copy(volume = 0.7, fadeIn = 0.0, rise = 0.4, muted = true), EditorTrack("new"))
        val nowGroups = listOf(TrackGroup("Birds", JsonObject(mapOf("mode" to JsonPrimitive("intermittent"), "muted" to JsonPrimitive(true)))))
        val calls = ThemeEditorRules.restoreCalls(snap, now, nowGroups)
        assertEquals(
            listOf(
                ThemeEditorRules.Restore("a", "volume", JsonObject(mapOf("volume" to JsonPrimitive(0.5)))),
                ThemeEditorRules.Restore("a", "muted", JsonObject(mapOf("muted" to JsonPrimitive(false)))),
                ThemeEditorRules.Restore("a", "fades", JsonObject(mapOf("fade_in" to JsonPrimitive(2.0)))),
                ThemeEditorRules.Restore("a", "ebb", JsonObject(mapOf("rise" to JsonPrimitive(0.1)))),
                ThemeEditorRules.Restore("Birds", null, JsonObject(mapOf("volume" to JsonPrimitive(0.8), "muted" to JsonNull))),
            ),
            calls,
        )
        assertTrue(ThemeEditorRules.restoreCalls(snap, listOf(t), listOf(g)).isEmpty())
    }

    @Test fun `reset track sends only what differs`() {
        assertEquals(listOf("volume", "presence", "muted", "seamless_loop"), ThemeEditorRules.resetTrackCalls(EditorTrack("a")).map { it.first })
        assertEquals(
            listOf("volume", "presence", "muted", "seamless_loop", "exclusive", "fades", "ebb"),
            ThemeEditorRules.resetTrackCalls(EditorTrack("a", exclusive = true, fadeOut = 1.0, pauseMax = 200.0)).map { it.first },
        )
    }
}
