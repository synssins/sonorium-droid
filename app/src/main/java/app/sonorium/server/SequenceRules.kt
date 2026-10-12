package app.sonorium.server

import app.sonorium.net.EditorSequence
import app.sonorium.net.SequenceDefaults
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The sequence editor's rules (native-api.md Phase 3; app.js `sq*`, `seqMinutes`, `txNeighbours`). */
object SequenceRules {
    val END_LABELS = mapOf("reverse" to "reverse", "restart" to "start again", "stop" to "stop")

    /** `seqMinutes`: seconds as minutes, whole or one decimal; empty for null. */
    fun minutes(seconds: Double?): String {
        seconds ?: return ""
        val m = seconds / 60
        return if (m == Math.floor(m)) "${m.toLong()}" else "${Math.round(m * 10) / 10.0}"
    }

    /** A number as JS `String(n)` would show it (30.0 → "30"). */
    fun plain(v: Double?): String = when {
        v == null -> ""
        v == Math.floor(v) && !v.isInfinite() -> "${v.toLong()}"
        else -> "$v"
    }

    /** `sqTimingValues`: empty fields take the defaults; NaN when not a number. */
    data class Timing(val min: Double, val max: Double, val ramp: Double, val raw: Triple<String, String, String>)

    fun timing(min: String, max: String, ramp: String, d: SequenceDefaults): Timing {
        fun parse(s: String, default: Double) = if (s.isBlank()) default else s.trim().toDoubleOrNull() ?: Double.NaN
        return Timing(parse(min, d.holdMin / 60), parse(max, d.holdMax / 60), parse(ramp, d.ramp), Triple(min.trim(), max.trim(), ramp.trim()))
    }

    fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    private fun num(v: Double): String = if (v.isNaN() || v.isInfinite()) "?" else {
        val r = Math.round(v * 10) / 10.0
        if (r == Math.floor(r)) "${r.toLong()}" else "$r"
    }

    /** `sqRenderSum`. */
    fun summary(steps: Int, t: Timing): String =
        if (steps == 0) "Add at least 2 presets" else "${plural(steps, "preset")} · ${num(t.min)}–${num(t.max)} min each · ${num(t.ramp)} s ramp"

    /** The first failing check of Save, in order: its toast and the fields to mark red. */
    fun check(name: String, id: String?, others: List<EditorSequence>, t: Timing, steps: Int): Pair<String, Set<String>>? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name" to setOf("name")
        if (others.any { it.id != id && it.name.equals(n, ignoreCase = true) }) return "A sequence with that name exists" to setOf("name")
        if (!(t.min > 0) || !(t.max > 0) || t.min > t.max) {
            return (if (t.min > t.max) "Minimum is more than maximum" else "Enter the time on each preset") to setOf("min", "max")
        }
        if (!(t.ramp >= 0)) return "Enter the ramp in seconds" to setOf("ramp")
        if (steps < 2) return "Add at least 2 presets" to emptySet()
        return null
    }

    /** The body of POST / PUT: transitions only for the steps still in it. */
    fun body(name: String, steps: List<String>, atEnd: String, t: Timing, transitions: JsonObject): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("presets", JsonArray(steps.map { JsonPrimitive(it) }))
        put("at_end", atEnd)
        put("hold_min", if (t.raw.first.isEmpty()) JsonNull else JsonPrimitive((t.min * 60).roundToLong()))
        put("hold_max", if (t.raw.second.isEmpty()) JsonNull else JsonPrimitive((t.max * 60).roundToLong()))
        put("ramp", if (t.raw.third.isEmpty()) JsonNull else JsonPrimitive(t.ramp.roundToLong()))
        put("transitions", JsonObject(transitions.filterKeys { it in steps }))
    }

    /** `txNeighbours`: the presets before and after step [index]. */
    fun neighbours(index: Int, steps: List<String>, atEnd: String): Pair<String?, String?> {
        val n = steps.size
        val prev = when {
            index > 0 -> steps[index - 1]
            atEnd == "restart" && n > 1 -> steps[n - 1]
            atEnd == "reverse" && n > 1 -> steps[1]
            else -> null
        }
        val next = when {
            index < n - 1 -> steps[index + 1]
            atEnd == "restart" && n > 1 -> steps[0]
            atEnd == "reverse" && n > 1 -> steps[n - 2]
            else -> null
        }
        return prev to next
    }

    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}
