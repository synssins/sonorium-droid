package app.sonorium.server

import app.sonorium.net.Playlist
import app.sonorium.net.PlaylistEntry
import app.sonorium.net.Session
import app.sonorium.net.ThemePresets
import app.sonorium.net.ThemeSummary
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The web UI's client-side playlist logic (native-api.md Phase 2, Playlists). */
object PlaylistRules {

    /** `+x.toFixed(1)`: one decimal, trailing zeros dropped. */
    fun fixed1(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    /** `seqMinutes(seconds)`: whole minutes when integral, else one decimal. */
    fun minutes(seconds: Double): String = fixed1(seconds / 60.0)

    /** The list's "Play each" cell: `<min>–<max> min`. */
    fun playEach(p: Playlist): String = "${minutes(p.playMin)}–${minutes(p.playMax)} min"

    /** `plDuration(minutes)`. */
    fun duration(minutes: Double): String {
        val m = minutes.roundToInt()
        if (m < 60) return "$m min"
        return if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    }

    fun entries(n: Int): String = "$n ${if (n == 1) "entry" else "entries"}"

    /** The editor footer summary. */
    fun summary(n: Int, min: Double?, max: Double?): String {
        if (n == 0) return "No entries"
        var s = entries(n)
        if (min != null && max != null && min > 0 && max >= min) s += " · ${duration(n * min)}–${duration(n * max)} per pass"
        return s
    }

    /** `plFreeName(base)`: "<base> (copy)", then "(copy 2)", … not taken (case-insensitive). */
    fun freeName(base: String, taken: List<String>): String {
        val names = taken.map { it.lowercase() }.toSet()
        var name = "$base (copy)"
        var n = 2
        while (name.lowercase() in names) name = "$base (copy ${n++})"
        return name
    }

    /** Channels playing this playlist now: the green dot and its tooltip. */
    fun playingOn(id: String, sessions: List<Session>) = sessions.filter { it.playlistId == id && it.isPlaying }

    /** The delete confirm's sub line, or null when no channel has this playlist. */
    fun deleteNote(id: String, sessions: List<Session>): String? {
        val on = sessions.filter { it.playlistId == id }
        if (on.isEmpty()) return null
        return "${on.joinToString(", ") { it.name }} ${if (on.size == 1) "stops" else "stop"} playing it."
    }

    /** `plEntryNote`: the note under a sequence entry. */
    fun entryNote(entry: PlaylistEntry, presets: ThemePresets?, fit: Boolean, min: Double?, max: Double?): String? {
        val seqId = entry.sequenceId ?: return null
        if (fit) {
            return if (min != null && max != null && min.isFinite() && max.isFinite()) "Fits into ${fixed1(min)}–${fixed1(max)} min" else null
        }
        val seq = presets?.sequences?.firstOrNull { it.id == seqId } ?: return null
        val lo = seq.passMin ?: return null
        val hi = seq.passMax ?: return null
        return "Plays the whole sequence, about ${(lo / 60).roundToInt()}–${(hi / 60).roundToInt()} min"
    }

    /** Save checks in the web UI's order; the first failure's toast, or null when it can be sent. */
    sealed interface Check {
        data object Name : Check
        data object Range : Check
        data object Entries : Check
        data class Themes(val rows: Set<Int>) : Check
    }

    data class Problem(val check: Check, val toast: String)

    fun validate(
        id: String?,
        name: String,
        minText: String,
        maxText: String,
        entries: List<PlaylistEntry>,
        playlists: List<Playlist>,
        themes: List<ThemeSummary>,
    ): Problem? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Problem(Check.Name, "Enter a name")
        if (playlists.any { it.id != id && it.name.trim().equals(trimmed, ignoreCase = true) }) {
            return Problem(Check.Name, "A playlist with that name exists")
        }
        val min = minText.trim().toDoubleOrNull()
        val max = maxText.trim().toDoubleOrNull()
        if (min == null || max == null || !(min > 0) || !(max > 0) || min > max) {
            return Problem(Check.Range, if (min != null && max != null && min > max) "Minimum is more than maximum" else "Enter the play time")
        }
        if (entries.isEmpty()) return Problem(Check.Entries, "Add at least one entry")
        val ids = themes.map { it.id }.toSet()
        val bad = entries.indices.filter { entries[it].themeId !in ids }.toSet()
        if (bad.isNotEmpty()) return Problem(Check.Themes(bad), "Choose a theme for every entry")
        return null
    }

    /** Exactly what `plSave` sends. */
    fun body(
        name: String,
        entries: List<PlaylistEntry>,
        shuffle: Boolean,
        repeat: Boolean,
        minMinutes: Double,
        maxMinutes: Double,
        fit: Boolean,
    ): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("entries", buildJsonArray { entries.forEach { add(entryJson(it)) } })
        put("order", if (shuffle) "shuffle" else "in_order")
        put("repeat", repeat)
        put("play_min", (minMinutes * 60).roundToInt())
        put("play_max", (maxMinutes * 60).roundToInt())
        put("fit_sequences", fit)
    }

    /** Duplicate (row menu): same fields under a free name. */
    fun duplicateBody(p: Playlist, taken: List<String>): JsonObject = buildJsonObject {
        put("name", freeName(p.name, taken))
        put("entries", buildJsonArray { p.entries.forEach { add(entryJson(it)) } })
        put("order", p.order)
        put("repeat", p.repeat)
        put("play_min", p.playMin)
        put("play_max", p.playMax)
        put("fit_sequences", p.fitSequences)
    }

    private fun entryJson(e: PlaylistEntry) = buildJsonObject {
        put("theme_id", e.themeId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("preset_id", e.presetId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sequence_id", e.sequenceId?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}
