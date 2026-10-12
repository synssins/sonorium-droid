package app.sonorium.server

import app.sonorium.net.SonoriumApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedPageRulesTest {
    private fun json(s: String) = SonoriumApi.json.parseToJsonElement(s) as JsonObject
    private val defaults = json("""{"sparse_min_interval": 180.0, "sparse_max_interval": 1800.0, "sparse_interval_variance": 0.3, "loop_crossfade": 1.5}""")
    private val wait = AdvancedPageRules.ROWS[0]
    private val variance = AdvancedPageRules.ROWS[1]
    private val loop = AdvancedPageRules.ROWS[3]

    @Test fun `shown values and defaults`() {
        assertEquals("30", AdvancedPageRules.shown(variance, 0.3))
        assertEquals("0.1235", AdvancedPageRules.num(0.12345))
        assertEquals("180–1800 s", AdvancedPageRules.defaultText(wait, defaults))
        assertEquals("30 %", AdvancedPageRules.defaultText(variance, defaults))
    }

    @Test fun `warnings only outside the usual range`() {
        assertEquals("Far below the usual 0.5 to 5 s: a loop may click where it starts over", AdvancedPageRules.warning(loop, json("""{"loop_crossfade": 0.2}""")))
        assertEquals("Far above the usual 0 to 60%", AdvancedPageRules.warning(variance, json("""{"sparse_interval_variance": 0.7}""")))
        assertNull(AdvancedPageRules.warning(loop, json("""{"loop_crossfade": 2}""")))
        assertTrue(AdvancedPageRules.changed(loop, json("""{"loop_crossfade": 2}"""), defaults))
        assertFalse(AdvancedPageRules.changed(loop, json("""{"loop_crossfade": 1.5}"""), defaults))
    }

    @Test fun `bodies and refusals`() {
        val (b, bad) = AdvancedPageRules.body(variance, listOf("40"), defaults)
        assertEquals(JsonPrimitive(0.4), b!!["sparse_interval_variance"])
        assertTrue(bad.isEmpty())
        assertEquals(setOf("sparse_interval_variance"), AdvancedPageRules.body(variance, listOf("140"), defaults).second)
        assertEquals(setOf("sparse_min_interval"), AdvancedPageRules.body(wait, listOf("-1", ""), defaults).second)
        // empty high = the default 1800; low 2000 > 1800 → both red
        assertEquals(wait.keys.toSet(), AdvancedPageRules.body(wait, listOf("2000", ""), defaults).second)
        assertEquals(JsonNull, AdvancedPageRules.body(wait, listOf("120", ""), defaults).first!!["sparse_max_interval"])
        assertTrue(AdvancedPageRules.streamUrlOk("HTTP://10.0.0.2:8008"))
        assertFalse(AdvancedPageRules.streamUrlOk("10.0.0.2"))
    }
}
