package app.sonorium.server

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Settings › Advanced (native-api.md Phase 3; app.js `renderAdvancedSettings`, `pv*`). */
object AdvancedPageRules {
    data class Row(
        val section: String,
        val name: String,
        val keys: List<String>,
        val unit: String,
        val lo: Double,
        val hi: Double,
        val description: String,
        val below: String? = null,
    ) {
        val percent: Boolean get() = unit == "%"
    }

    val ROWS = listOf(
        Row("Intermittent", "Wait between plays", listOf("sparse_min_interval", "sparse_max_interval"), "s", 30.0, 7200.0,
            "How long Intermittent tracks wait between plays. A track's Interval slider sets where in this range it waits: 100% near the shortest, 0% near the longest."),
        Row("Intermittent", "Wait randomness", listOf("sparse_interval_variance"), "%", 0.0, 60.0,
            "How far each wait strays from the Interval's point in the range, either way."),
        Row("Intermittent", "Gap after a group's track", listOf("group_gap"), "s", 10.0, 1800.0,
            "Every Intermittent group uses this, unless its own Gap is set."),
        Row("Background", "Loop crossfade", listOf("loop_crossfade"), "s", 0.5, 5.0,
            "How long a looping file crossfades into itself when it starts over.", below = "a loop may click where it starts over"),
        Row("Background", "Merry-go-round crossfade range", listOf("group_crossfade_min", "group_crossfade_max"), "s", 0.5, 120.0,
            "The shortest and longest crossfade a Merry-go-round group can be given."),
        Row("Fades", "Track fade", listOf("track_fade"), "s", 1.0, 20.0, "How long a track fades in and out when it joins or leaves the mix."),
        Row("Fades", "Switch fade", listOf("switch_fade"), "s", 0.5, 10.0, "How long a track fades when it's switched on or off, or muted."),
        Row("Ebb & Flow", "Long pause chance", listOf("ebb_long_rest"), "%", 0.0, 50.0, "How often a pause lasts 2 to 4 times longer, so it never sounds regular."),
        Row("Ebb & Flow", "Turn chance", listOf("ebb_turn"), "%", 5.0, 80.0, "How often a rise or fall changes direction partway."),
        Row("New themes", "Short file threshold", listOf("short_file_threshold"), "s", 3.0, 60.0, "Files shorter than this start as Intermittent in a new theme."),
        Row("Announcements and sounds", "Longest sound", listOf("sound_max_length"), "s", 5.0, 600.0, "Announcements and sounds longer than this are cut. 0 is no limit."),
        Row("Announcements and sounds", "Duck amount", listOf("duck_amount"), "dB", 6.0, 30.0, "How much quieter a channel gets while a sound plays over it, unless the sound sets its own."),
        Row("Announcements and sounds", "Duck attack", listOf("duck_attack"), "s", 0.1, 3.0, "How long the channel takes to get quieter. The sound starts once it has."),
        Row("Announcements and sounds", "Duck release", listOf("duck_release"), "s", 0.2, 5.0, "How long the channel takes to come back after the sound."),
        Row("Announcements and sounds", "Wait for speakers", listOf("wake_wait"), "s", 5.0, 60.0, "A stopped channel's speakers are started for an announcement: the longest wait for them to connect."),
        Row("Announcements and sounds", "Lead", listOf("wake_lead"), "s", 0.0, 5.0, "Then this long before the announcement plays."),
        Row("Announcements and sounds", "Tail", listOf("wake_tail"), "s", 0.0, 5.0, "After it, this long before the speakers stop."),
    )

    /** JS `+n.toFixed(4)`: up to four decimals, no trailing zeros. */
    fun num(v: Double): String = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    fun value(o: JsonObject?, key: String): Double? = (o?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    /** The value as shown: `%` rows × 100. */
    fun shown(r: Row, v: Double?): String = v?.let { num(if (r.percent) it * 100 else it) }.orEmpty()

    fun defaultText(r: Row, defaults: JsonObject?): String =
        r.keys.joinToString("–") { shown(r, value(defaults, it)) } + " " + r.unit

    fun changed(r: Row, values: JsonObject?, defaults: JsonObject?): Boolean = r.keys.any { value(values, it) != value(defaults, it) }

    /** The amber line on a changed row, or null. */
    fun warning(r: Row, values: JsonObject?): String? {
        val shownValues = r.keys.mapNotNull { value(values, it) }.map { if (r.percent) it * 100 else it }
        val range = if (r.percent) "${num(r.lo)} to ${num(r.hi)}%" else "${num(r.lo)} to ${num(r.hi)} ${r.unit}"
        if (shownValues.any { it < r.lo }) return "Far below the usual $range" + (r.below?.let { ": $it" } ?: "")
        if (shownValues.any { it > r.hi }) return "Far above the usual $range"
        return null
    }

    /**
     * `pvChanged`: the body to save from the row's typed texts, or the keys to mark red (nothing saved).
     * Empty = null (default); not a number, negative, or `%` above 100 → red; a range whose low > high → both red.
     */
    fun body(r: Row, texts: List<String>, defaults: JsonObject?): Pair<JsonObject?, Set<String>> {
        val bad = mutableSetOf<String>()
        val parsed = r.keys.mapIndexed { i, k ->
            val t = texts.getOrNull(i).orEmpty().trim()
            if (t.isEmpty()) null else {
                val x = t.toDoubleOrNull()
                if (x == null || x.isNaN() || x.isInfinite() || x < 0 || (r.percent && x > 100)) { bad += k; null } else x
            }
        }
        if (bad.isNotEmpty()) return null to bad
        if (r.keys.size == 2) {
            val lo = parsed[0]?.let { if (r.percent) it / 100 else it } ?: value(defaults, r.keys[0])
            val hi = parsed[1]?.let { if (r.percent) it / 100 else it } ?: value(defaults, r.keys[1])
            if (lo != null && hi != null && lo > hi) return null to r.keys.toSet()
        }
        val out = r.keys.mapIndexed { i, k ->
            k to (parsed[i]?.let { JsonPrimitive(if (r.percent) it / 100 else it) } ?: JsonNull) as JsonElement
        }.toMap()
        return JsonObject(out) to emptySet()
    }

    fun reset(r: Row): JsonObject = JsonObject(r.keys.associateWith { JsonNull })

    val FEATURES = listOf(
        "network_speakers" to ("Network speakers" to "Add speakers by IP address, outside Home Assistant"),
        "plugin_packages" to ("Plugin packages" to "Plugins may install Python packages from the internet. Packages run with Sonorium's rights: allow only for plugins you trust."),
    )

    const val ZONES_DESCRIPTION = "Channels can belong to a user. A speaker playing in one channel can't be taken by another, except by its home channel, the one it was added to first. Child Mode lets only an admin change a channel's speakers. An admin can link channels to play one thing in all of them."

    fun streamUrlOk(text: String): Boolean = text.isEmpty() || Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(text)
}
