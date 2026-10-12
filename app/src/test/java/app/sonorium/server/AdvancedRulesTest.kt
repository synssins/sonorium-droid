package app.sonorium.server

import app.sonorium.net.EditorTrack
import app.sonorium.net.TrackGroup
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedRulesTest {
    private fun json(s: String) = app.sonorium.net.SonoriumApi.json.parseToJsonElement(s) as JsonObject

    private val tracks = listOf(
        EditorTrack("fire", playbackMode = "continuous", volume = 0.5),
        EditorTrack("Rain/drizzle", playbackMode = "presence", presence = 0.4, rise = 0.3),
        EditorTrack("new", playbackMode = "unassigned"),
    )
    private val groups = listOf(TrackGroup("Rain", JsonObject(mapOf("mode" to JsonPrimitive("ebb_flow"), "volume" to JsonPrimitive(0.8)))))

    @Test fun `base values follow twStart`() {
        val preset = json("""{"tracks": {"fire": {"muted": true}}, "groups": {"Rain": {"muted": true}}}""")
        val layer = json("""{"tracks": {"Rain/drizzle": {"volume": 0.9, "rise": null}, "gone": {"volume": 0.1}}, "groups": {}}""")
        val w = AdvancedRules.start(tracks, groups, preset, layer)
        assertEquals(setOf("fire", "Rain/drizzle"), w.tracks.keys) // Unassigned skipped
        // listed by the preset: unset volume plays at 100 %, Ebb fields keep the theme's
        assertEquals(JsonPrimitive(1), w.baseTracks["fire"]!!["volume"])
        assertEquals(JsonPrimitive(true), w.baseTracks["fire"]!!["muted"])
        // not listed: the theme's values
        assertEquals(JsonPrimitive(0.4), w.baseTracks["Rain/drizzle"]!!["presence"])
        assertEquals(JsonPrimitive(0.9), w.tracks["Rain/drizzle"]!!["volume"])
        assertEquals(JsonPrimitive(0.8), w.baseGroups["Rain"]!!["volume"])
        assertEquals(JsonPrimitive(true), w.baseGroups["Rain"]!!["muted"])
        assertEquals(JsonNull, w.baseGroups["Rain"]!!["gap_min"])
        assertEquals(setOf("gone"), w.keepTracks.keys)
        assertEquals(1, AdvancedRules.count(w))
        assertFalse(AdvancedRules.unsaved(w))
        val changed = AdvancedRules.set(w, true, "Rain", mapOf("gap_min" to JsonPrimitive(120)))
        assertTrue(AdvancedRules.unsaved(changed))
        val layerOut = AdvancedRules.layer(changed)
        val t = layerOut["tracks"] as JsonObject
        assertEquals(setOf("gone", "Rain/drizzle"), t.keys)
        assertEquals(JsonNull, (t["Rain/drizzle"] as JsonObject)["presence"])
        assertEquals(JsonPrimitive(120), ((layerOut["groups"] as JsonObject)["Rain"] as JsonObject)["gap_min"])
        assertFalse(AdvancedRules.unsaved(AdvancedRules.revert(changed, null, null)))
    }

    @Test fun `same compares as shown`() {
        assertTrue(AdvancedRules.same("volume", JsonPrimitive(0.801), JsonPrimitive(0.8)))
        assertTrue(AdvancedRules.same("gap_min", JsonPrimitive(61), JsonPrimitive(60)))
        assertFalse(AdvancedRules.same("pause_min", JsonPrimitive(31), JsonPrimitive(30)))
        assertTrue(AdvancedRules.same("muted", JsonNull, null))
        assertFalse(AdvancedRules.same("muted", JsonPrimitive(false), JsonNull))
    }

    @Test fun `transition rows round-trip`() {
        val track = json("""{"key": "thunder", "name": "thunder", "mode": "Intermittent", "from": 0.0, "to": 0.7, "presence_from": 1.0, "presence_to": 0.4}""")
        val saved = json("""{"thunder": {"styles": ["gusts", "dist"], "values": {"dist": 70}, "pre_fade": 20, "max_volume": 0.5}}""")
        val r = AdvancedRules.rowFrom(track, "in", saved)
        assertEquals(70, r.cap)
        assertEquals(50, r.maxVol)
        assertEquals(70, r.v["dist"])
        assertEquals(50, r.v["gusts"])
        val cfg = AdvancedRules.rowConfig(r)!!
        assertEquals(json("""{"styles": ["gusts", "dist"], "values": {"gusts": 50, "dist": 70}, "pre_fade": 20, "max_volume": 0.5}"""), cfg)
        assertNull(AdvancedRules.rowConfig(r.copy(on = emptySet(), pre = 0)))
        // build only for Intermittent rows
        val bg = r.copy(mode = "Background", on = setOf("build"))
        assertEquals(json("""{"styles": [], "values": {}, "pre_fade": 20}"""), AdvancedRules.rowConfig(bg))
        assertEquals("20 s of 50 s", AdvancedRules.slider(r, "swell", 30).text)
        assertEquals("Interval 16% → 40%" to "Volume 41% → 70%", AdvancedRules.hints(r, out = false))
        assertEquals("off", AdvancedRules.pct(0.0))
        val step = AdvancedRules.stepTransitions(listOf(r), emptyList(), JsonObject(emptyMap()), json("""{"x": {}}"""))!!
        assertEquals(setOf("in", "out"), step.keys)
        assertNull(AdvancedRules.stepTransitions(listOf(r.copy(on = emptySet(), pre = 0)), emptyList(), JsonObject(emptyMap()), JsonObject(emptyMap())))
    }
}
