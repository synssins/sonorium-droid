package app.sonorium.server

import app.sonorium.net.EditorSequence
import app.sonorium.net.SequenceDefaults
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SequenceRulesTest {
    private val d = SequenceDefaults(300.0, 900.0, 30.0)

    @Test fun `minutes and summary`() {
        assertEquals("5", SequenceRules.minutes(300.0))
        assertEquals("2.5", SequenceRules.minutes(150.0))
        assertEquals("", SequenceRules.minutes(null))
        assertEquals("30", SequenceRules.plain(30.0))
        val t = SequenceRules.timing("", "20", "x", d)
        assertEquals("3 presets · 5–20 min each · ? s ramp", SequenceRules.summary(3, t))
        assertEquals("1 preset · 5–15 min each · 30 s ramp", SequenceRules.summary(1, SequenceRules.timing("", "", "", d)))
        assertEquals("Add at least 2 presets", SequenceRules.summary(0, t))
    }

    @Test fun `save checks in order`() {
        val others = listOf(EditorSequence("a", "Dusk"))
        val ok = SequenceRules.timing("", "", "", d)
        assertEquals("Enter a name", SequenceRules.check(" ", null, others, ok, 2)?.first)
        assertEquals("A sequence with that name exists", SequenceRules.check("dusk", null, others, ok, 2)?.first)
        assertNull(SequenceRules.check("dusk", "a", others, ok, 2))
        assertEquals("Minimum is more than maximum", SequenceRules.check("x", null, others, SequenceRules.timing("20", "10", "", d), 2)?.first)
        assertEquals("Enter the time on each preset", SequenceRules.check("x", null, others, SequenceRules.timing("0", "10", "", d), 2)?.first)
        assertEquals("Enter the ramp in seconds", SequenceRules.check("x", null, others, SequenceRules.timing("", "", "-1", d), 2)?.first)
        assertEquals("Add at least 2 presets", SequenceRules.check("x", null, others, ok, 1)?.first)
    }

    @Test fun `body keeps empty fields null and only live transitions`() {
        val tr = JsonObject(mapOf("a" to JsonObject(emptyMap()), "gone" to JsonObject(emptyMap())))
        val b = SequenceRules.body(" Night ", listOf("a", "b"), "stop", SequenceRules.timing("2.5", "", "10", d), tr)
        assertEquals(JsonPrimitive("Night"), b["name"])
        assertEquals(JsonPrimitive(150), b["hold_min"])
        assertEquals(JsonNull, b["hold_max"])
        assertEquals(JsonPrimitive(10), b["ramp"])
        assertEquals(setOf("a"), (b["transitions"] as JsonObject).keys)
    }

    @Test fun `neighbours wrap by at_end`() {
        val s = listOf("a", "b", "c")
        assertEquals("b" to "b", SequenceRules.neighbours(0, s, "reverse").let { it.first to it.second })
        assertEquals("c" to "b", SequenceRules.neighbours(0, s, "restart"))
        assertEquals(null to "b", SequenceRules.neighbours(0, s, "stop"))
        assertEquals("b" to "a", SequenceRules.neighbours(2, s, "restart"))
        assertEquals("b" to "b", SequenceRules.neighbours(2, s, "reverse"))
        assertEquals(listOf("b", "a", "c"), SequenceRules.move(s, 0, 1))
    }
}
