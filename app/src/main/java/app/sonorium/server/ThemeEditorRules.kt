package app.sonorium.server

import app.sonorium.net.EditorTrack
import app.sonorium.net.TrackGroup
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The Theme Editor's client-side rules (native-api.md Phase 3, "Theme Editor"; app.js `te*`). */
object ThemeEditorRules {

    /** `TE_SECTIONS`: always these four, in this order. */
    enum class Section(
        val mode: String,
        val label: String,
        val description: String,
        val groupMode: String?,
        val groupLabel: String,
        val second: String?,
        val newGroupTip: String,
        val empty: String,
    ) {
        Unassigned("unassigned", "Unassigned", "Not played. Pick a Mode, or drag files into a section or a group.", null, "", null, "", "Uploads and files from another theme start here"),
        Background("continuous", "Background", "Plays all the time, looping smoothly. Groups here are Merry-go-round.", "merry_go_round", "Merry-go-round", null, "A Merry-go-round group: one file crossfades into the next", "Drag files here, or make a group"),
        Intermittent("sparse", "Intermittent", "Plays now and then. Groups here take turns, one track at a time with a gap.", "intermittent", "Intermittent", "Interval", "A group whose tracks take turns, with a gap between them", "Drag files here, or make a group"),
        EbbFlow("presence", "Ebb & Flow", "Always heard, rising and falling. Groups here play every file at once.", "ebb_flow", "Ebb & Flow", "Duration", "A group whose files all play at once, each rising and falling", "Drag files here, or make a group");

        companion object {
            fun ofMode(mode: String?): Section? = entries.firstOrNull { it.mode == mode }
            fun ofGroupMode(mode: String): Section = entries.firstOrNull { it.groupMode == mode } ?: Intermittent
        }
    }

    val TRACK_FIELDS = listOf("volume", "presence", "muted", "playback_mode", "seamless_loop", "exclusive", "fade_in", "fade_out", "volume_low", "pause_between", "pause_min", "pause_max", "rise")
    val GROUP_FIELDS = listOf("volume", "presence", "muted", "gap_min", "gap_max", "mode", "crossfade", "pause_between", "rise")
    private val FADE_FIELDS = setOf("fade_in", "fade_out")
    val EBB_FIELDS = setOf("volume_low", "pause_between", "pause_min", "pause_max", "rise")

    fun num(o: JsonObject, field: String): Double? = (o[field] as? JsonPrimitive)?.doubleOrNull
    fun bool(o: JsonObject, field: String): Boolean? = (o[field] as? JsonPrimitive)?.booleanOrNull
    fun str(o: JsonObject, field: String): String? = (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `teGroupMode`: missing or unknown → intermittent. */
    fun groupMode(g: TrackGroup?): String {
        val m = g?.let { str(it.settings, "mode") }
        return if (m in setOf("merry_go_round", "intermittent", "ebb_flow", "intrusion")) m!! else "intermittent"
    }

    /** The group named by the key's folder (`Birds/robin` → Birds). */
    fun groupOf(t: EditorTrack): String? = t.group ?: t.name.substringBeforeLast('/', "").ifEmpty { null }

    /** Groups from the server plus folders that only show as a key prefix (`teLoadTracks`), sorted by name. */
    fun allGroups(groups: List<TrackGroup>, tracks: List<EditorTrack>): List<TrackGroup> {
        val names = groups.map { it.name }.toMutableSet()
        val extra = tracks.mapNotNull { groupOf(it) }.distinct().filter { names.add(it) }.map { TrackGroup(it) }
        return (groups + extra).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** `teListedGroups`: never the old `intrusion` groups. */
    fun listedGroups(groups: List<TrackGroup>): List<TrackGroup> = groups.filter { groupMode(it) != "intrusion" }

    /** The listed group a track plays in, if any. */
    fun listedGroupOf(t: EditorTrack, groups: List<TrackGroup>): TrackGroup? =
        groupOf(t)?.let { name -> groups.firstOrNull { it.name == name } }?.takeIf { groupMode(it) != "intrusion" }

    /** `teSectionOf`, ported exactly. */
    fun sectionOf(t: EditorTrack, groups: List<TrackGroup>): Section {
        listedGroupOf(t, groups)?.let { return Section.ofGroupMode(groupMode(it)) }
        Section.ofMode(t.playbackMode ?: "auto")?.let { return it }
        return when {
            t.presence >= 1 -> Section.Background
            t.isShortFile -> Section.Intermittent
            else -> Section.EbbFlow
        }
    }

    /** `teGaplessAllowed`. */
    fun gaplessAllowed(t: EditorTrack): Boolean =
        t.playbackMode == "continuous" || ((t.playbackMode ?: "auto") == "auto" && !t.isShortFile)

    /** The dot on a row's ⋯ (`TE_VIEW.advOn`). */
    fun advancedDot(t: EditorTrack, section: Section, grouped: Boolean): Boolean =
        (!grouped && t.seamlessLoop && gaplessAllowed(t)) || t.exclusive || t.fadeIn > 0 || t.fadeOut > 0 ||
            (section == Section.EbbFlow && (t.pauseBetween || t.rise > 0))

    /** A group's master volume or presence (absent = 100 %). */
    fun master(g: TrackGroup?, field: String): Double = g?.let { num(it.settings, field) } ?: 1.0

    /** `plays at N%` (only shown when the master is below 0.999). */
    fun playsAt(value: Double, master: Double): Int = (value * master * 100).roundToInt()

    /** First free `New group`, `New group 2`, … (case-insensitive). */
    fun newGroupName(existing: List<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if ("new group" !in taken) return "New group"
        var n = 2
        while ("new group $n" in taken) n++
        return "New group $n"
    }

    /** `pkFormatLength`: m:ss, `—` when unknown. */
    fun formatLength(seconds: Double): String {
        if (seconds <= 0) return "—"
        val s = seconds.roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Seconds shown as minutes: `round(s/6)/10`, without a trailing `.0`. */
    fun minutes(seconds: Double?): String {
        seconds ?: return ""
        val tenths = (seconds / 6).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10.0}"
    }

    /** A Gap field: empty/not a number → null, else max(0, minutes) × 60. */
    fun gapSeconds(text: String): Double? = text.trim().toDoubleOrNull()?.let { maxOf(0.0, it) * 60 }

    /** Both Gap fields; swapped when max < min. */
    fun gapPair(min: Double?, max: Double?): Pair<Double?, Double?> =
        if (min != null && max != null && max < min) max to min else min to max

    /** Fade field: empty → 0, else clamped 0..30. */
    fun fadeSeconds(text: String): Double = text.trim().toDoubleOrNull()?.coerceIn(0.0, 30.0) ?: 0.0

    /** Pause fields: each `max(0, n || 0)`, swapped when max < min. */
    fun pausePair(min: String, max: String): Pair<Double, Double> {
        val a = maxOf(0.0, min.trim().toDoubleOrNull() ?: 0.0)
        val b = maxOf(0.0, max.trim().toDoubleOrNull() ?: 0.0)
        return if (b < a) b to a else a to b
    }

    /** Crossfade field: empty → null, else clamped to the Advanced range. */
    fun crossfade(text: String, min: Double = 1.0, max: Double = 60.0): Double? = text.trim().toDoubleOrNull()?.coerceIn(min, max)

    // ---------- Categories chip field ----------

    data class CategoryOption(val name: String, val create: Boolean)

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Known categories not chosen that contain the text (starting-with first), plus `Create “text”`. */
    fun categoryOptions(known: List<String>, chosen: List<String>, text: String): List<CategoryOption> {
        val q = norm(text)
        val chosenLower = chosen.map { it.lowercase() }.toSet()
        val matches = known.filter { it.lowercase() !in chosenLower && norm(it).contains(q) }
            .sortedBy { if (norm(it).startsWith(q)) 0 else 1 }
            .map { CategoryOption(it, false) }
        val typed = text.trim().replace(Regex("\\s+"), " ")
        val exists = known.any { it.equals(typed, ignoreCase = true) } || chosen.any { it.equals(typed, ignoreCase = true) }
        return if (typed.isNotEmpty() && !exists) matches + CategoryOption(typed, true) else matches
    }

    /** Adds a category with the known spelling; never a duplicate. */
    fun addCategory(chosen: List<String>, known: List<String>, text: String): List<String> {
        val typed = text.trim().replace(Regex("\\s+"), " ")
        if (typed.isEmpty() || chosen.any { it.equals(typed, ignoreCase = true) }) return chosen
        return chosen + (known.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed)
    }

    // ---------- Cancel: put the mix back (`teMixSnapshot` / `teRestoreMix`) ----------

    data class Snapshot(val tracks: Map<String, Map<String, JsonElement>>, val groups: Map<String, Map<String, JsonElement>>)

    fun trackValues(t: EditorTrack): Map<String, JsonElement> = mapOf(
        "volume" to JsonPrimitive(t.volume), "presence" to JsonPrimitive(t.presence), "muted" to JsonPrimitive(t.muted),
        "playback_mode" to (t.playbackMode?.let { JsonPrimitive(it) } ?: JsonNull), "seamless_loop" to JsonPrimitive(t.seamlessLoop),
        "exclusive" to JsonPrimitive(t.exclusive), "fade_in" to JsonPrimitive(t.fadeIn), "fade_out" to JsonPrimitive(t.fadeOut),
        "volume_low" to JsonPrimitive(t.volumeLow), "pause_between" to JsonPrimitive(t.pauseBetween),
        "pause_min" to JsonPrimitive(t.pauseMin), "pause_max" to JsonPrimitive(t.pauseMax), "rise" to JsonPrimitive(t.rise),
    )

    fun groupValues(g: TrackGroup): Map<String, JsonElement> = GROUP_FIELDS.associateWith { g.settings[it] ?: JsonNull }

    fun snapshot(tracks: List<EditorTrack>, groups: List<TrackGroup>) =
        Snapshot(tracks.associate { it.name to trackValues(it) }, groups.associate { it.name to groupValues(it) })

    /** One request of a restore: `PUT tracks/{key}/{setting}` or `PUT groups/{name}` ([setting] null). */
    data class Restore(val key: String, val setting: String?, val body: JsonObject)

    private fun same(a: JsonElement?, b: JsonElement?): Boolean {
        val x = a ?: JsonNull
        val y = b ?: JsonNull
        if (x is JsonPrimitive && y is JsonPrimitive && x !is JsonNull && y !is JsonNull) {
            val dx = x.doubleOrNull
            val dy = y.doubleOrNull
            if (dx != null && dy != null && !x.isString && !y.isString) return dx == dy
        }
        return x == y
    }

    fun restoreCalls(snap: Snapshot, tracks: List<EditorTrack>, groups: List<TrackGroup>): List<Restore> {
        val out = mutableListOf<Restore>()
        tracks.forEach { t ->
            val old = snap.tracks[t.name] ?: return@forEach
            val cur = trackValues(t)
            val fades = mutableMapOf<String, JsonElement>()
            val ebb = mutableMapOf<String, JsonElement>()
            TRACK_FIELDS.forEach { f ->
                val was = old[f] ?: return@forEach
                if (same(was, cur[f]) || was is JsonNull) return@forEach
                when (f) {
                    in FADE_FIELDS -> fades[f] = was
                    in EBB_FIELDS -> ebb[f] = was
                    else -> out += Restore(t.name, f, JsonObject(mapOf(f to was)))
                }
            }
            if (fades.isNotEmpty()) out += Restore(t.name, "fades", JsonObject(fades))
            if (ebb.isNotEmpty()) out += Restore(t.name, "ebb", JsonObject(ebb))
        }
        groups.forEach { g ->
            val old = snap.groups[g.name] ?: return@forEach
            val cur = groupValues(g)
            val changed = GROUP_FIELDS.filter { !same(old[it], cur[it]) }.associateWith { old[it] ?: JsonNull }
            if (changed.isNotEmpty()) out += Restore(g.name, null, JsonObject(changed))
        }
        return out
    }

    /** Reset track: the requests in order (`playback_mode` and the group are not touched). */
    fun resetTrackCalls(t: EditorTrack): List<Pair<String, JsonObject>> {
        val out = mutableListOf<Pair<String, JsonObject>>(
            "volume" to obj("volume" to JsonPrimitive(1)),
            "presence" to obj("presence" to JsonPrimitive(1)),
            "muted" to obj("muted" to JsonPrimitive(false)),
            "seamless_loop" to obj("seamless_loop" to JsonPrimitive(false)),
        )
        if (t.exclusive) out += "exclusive" to obj("exclusive" to JsonPrimitive(false))
        if (t.fadeIn != 0.0 || t.fadeOut != 0.0) out += "fades" to obj("fade_in" to JsonPrimitive(0), "fade_out" to JsonPrimitive(0))
        if (t.volumeLow != 0.2 || t.pauseBetween || t.pauseMin != 30.0 || t.pauseMax != 300.0 || t.rise != 0.0) {
            out += "ebb" to obj(
                "volume_low" to JsonPrimitive(0.2), "pause_between" to JsonPrimitive(false),
                "pause_min" to JsonPrimitive(30), "pause_max" to JsonPrimitive(300), "rise" to JsonPrimitive(0),
            )
        }
        return out
    }

    fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
}
