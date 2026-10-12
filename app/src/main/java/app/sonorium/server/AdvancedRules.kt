package app.sonorium.server

import app.sonorium.net.EditorTrack
import app.sonorium.net.TrackGroup
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A sequence step's Advanced dialog (native-api.md Phase 3; app.js `tw*`, `tx*`). */
object AdvancedRules {
    val TRACK_FIELDS = listOf("volume", "volume_low", "presence", "muted", "rise", "pause_between", "pause_min", "pause_max")
    val GROUP_FIELDS = listOf("volume", "presence", "muted", "gap_min", "gap_max", "pause_between", "rise")
    private val SHARES = setOf("volume", "volume_low", "presence", "rise")
    private val TRACK_DEFAULTS: Map<String, JsonElement> = mapOf(
        "volume" to JsonPrimitive(1), "presence" to JsonPrimitive(1), "muted" to JsonPrimitive(false),
        "volume_low" to JsonPrimitive(0.2), "pause_between" to JsonPrimitive(false), "pause_min" to JsonPrimitive(30),
        "pause_max" to JsonPrimitive(300), "rise" to JsonPrimitive(0),
    )
    private val GROUP_DEFAULTS: Map<String, JsonElement> = mapOf(
        "volume" to JsonPrimitive(1), "presence" to JsonPrimitive(1), "muted" to JsonPrimitive(false), "pause_between" to JsonPrimitive(false),
    )

    /** Values per track key / group name, per field. */
    typealias Values = Map<String, Map<String, JsonElement>>

    data class While(
        val baseTracks: Values,
        val baseGroups: Values,
        val tracks: Values,
        val groups: Values,
        val savedTracks: Values,
        val savedGroups: Values,
        val keepTracks: JsonObject,
        val keepGroups: JsonObject,
        /** Each track's section in the preset. */
        val modes: Map<String, ThemeEditorRules.Section>,
    ) {
        fun part(groups: Boolean) = if (groups) this.groups else tracks
        fun base(groups: Boolean) = if (groups) baseGroups else baseTracks
    }

    private fun isNull(e: JsonElement?) = e == null || e is JsonNull

    /** `twStart`, ported exactly. */
    fun start(tracks: List<EditorTrack>, groups: List<TrackGroup>, preset: JsonObject, layer: JsonObject?): While {
        val presetTracks = preset["tracks"] as? JsonObject
        val presetGroups = preset["groups"] as? JsonObject
        val baseT = linkedMapOf<String, Map<String, JsonElement>>()
        val modes = mutableMapOf<String, ThemeEditorRules.Section>()
        tracks.forEach { t ->
            val own = presetTracks?.get(t.name) as? JsonObject
            val probe = t.copy(
                playbackMode = (own?.get("playback_mode") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: t.playbackMode,
                presence = (own?.get("presence") as? JsonPrimitive)?.doubleOrNull ?: t.presence,
            )
            val mode = ThemeEditorRules.sectionOf(probe, groups)
            if (mode == ThemeEditorRules.Section.Unassigned) return@forEach
            modes[t.name] = mode
            val theme = ThemeEditorRules.trackValues(t)
            baseT[t.name] = TRACK_FIELDS.associateWith { f ->
                own?.get(f)?.takeIf { !isNull(it) }
                    ?: if (own != null && f !in ThemeEditorRules.EBB_FIELDS) TRACK_DEFAULTS.getValue(f)
                    else theme[f]?.takeIf { !isNull(it) } ?: TRACK_DEFAULTS.getValue(f)
            }
        }
        val baseG = linkedMapOf<String, Map<String, JsonElement>>()
        ThemeEditorRules.listedGroups(groups).forEach { g ->
            val own = presetGroups?.get(g.name) as? JsonObject ?: JsonObject(emptyMap())
            baseG[g.name] = GROUP_FIELDS.associateWith { f ->
                (if (f in own) own[f] else g.settings[f])?.takeIf { !isNull(it) } ?: GROUP_DEFAULTS[f] ?: JsonNull
            }
        }
        fun over(base: Values, part: String): Values = base.mapValues { (name, values) ->
            val step = (layer?.get(part) as? JsonObject)?.get(name) as? JsonObject
            values.mapValues { (f, v) -> step?.get(f)?.takeIf { !isNull(it) } ?: v }
        }
        fun keep(base: Values, part: String) = JsonObject(((layer?.get(part) as? JsonObject) ?: JsonObject(emptyMap())).filterKeys { it !in base })
        val vt = over(baseT, "tracks")
        val vg = over(baseG, "groups")
        return While(baseT, baseG, vt, vg, vt, vg, keep(baseT, "tracks"), keep(baseG, "groups"), modes)
    }

    private fun d(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    /** `twSame`: shares in whole percents, gaps in tenths of a minute. */
    fun same(field: String, a: JsonElement?, b: JsonElement?): Boolean {
        if (isNull(a) || isNull(b)) return isNull(a) && isNull(b)
        val x = d(a)
        val y = d(b)
        if (x != null && y != null && (a as JsonPrimitive).booleanOrNull == null && (b as JsonPrimitive).booleanOrNull == null) {
            return when (field) {
                in SHARES -> (x * 100).roundToInt() == (y * 100).roundToInt()
                "gap_min", "gap_max" -> (x / 6).roundToInt() == (y / 6).roundToInt()
                else -> x == y
            }
        }
        return a == b
    }

    fun differs(w: While, groups: Boolean, name: String, fields: List<String>, from: Values? = null): Boolean {
        val vals = w.part(groups)[name] ?: return false
        val ref = (from ?: w.base(groups))[name] ?: return false
        return fields.any { !same(it, vals[it], ref[it]) }
    }

    fun count(w: While): Int =
        w.tracks.keys.count { differs(w, false, it, TRACK_FIELDS) } + w.groups.keys.count { differs(w, true, it, GROUP_FIELDS) }

    fun unsaved(w: While): Boolean =
        w.tracks.keys.any { differs(w, false, it, TRACK_FIELDS, w.savedTracks) } || w.groups.keys.any { differs(w, true, it, GROUP_FIELDS, w.savedGroups) }

    /** `twLayer`: null where equal to the preset; all-null entries dropped; kept entries added back. */
    fun layer(w: While): JsonObject {
        fun part(vals: Values, base: Values, fields: List<String>, keep: JsonObject): JsonObject {
            val out = keep.toMutableMap()
            vals.forEach { (name, v) ->
                val b = base[name] ?: return@forEach
                val entry = fields.associateWith { f -> if (same(f, v[f], b[f])) JsonNull else v[f] ?: JsonNull }
                if (entry.values.any { it !is JsonNull }) out[name] = JsonObject(entry)
            }
            return JsonObject(out)
        }
        return JsonObject(mapOf(
            "tracks" to part(w.tracks, w.baseTracks, TRACK_FIELDS, w.keepTracks),
            "groups" to part(w.groups, w.baseGroups, GROUP_FIELDS, w.keepGroups),
        ))
    }

    fun set(w: While, groups: Boolean, name: String, values: Map<String, JsonElement>): While {
        val part = w.part(groups).toMutableMap()
        part[name] = (part[name] ?: return w) + values
        return if (groups) w.copy(groups = part) else w.copy(tracks = part)
    }

    /** Back to the last save (one entry, or all when [name] is null). */
    fun revert(w: While, groups: Boolean?, name: String?): While = when {
        name == null -> w.copy(tracks = w.savedTracks, groups = w.savedGroups)
        groups == true -> w.copy(groups = w.groups + (name to w.savedGroups.getValue(name)))
        else -> w.copy(tracks = w.tracks + (name to w.savedTracks.getValue(name)))
    }

    // ---------- Coming in / Leaving ----------

    val STYLES = listOf("gusts", "swell", "build", "dist", "fresh")
    val DEFAULTS = mapOf("gusts" to 50, "swell" to 20, "build" to 60, "dist" to 50)
    val NAMES = mapOf(
        "in" to mapOf("gusts" to "Gusts", "swell" to "Swell", "build" to "Build", "dist" to "Distance", "fresh" to "Start fresh"),
        "out" to mapOf("gusts" to "Gusts dying down", "swell" to "Smooth fade", "build" to "Thin out", "dist" to "Recede", "fresh" to "Finish then stop"),
    )
    val TIPS = mapOf(
        "in" to mapOf(
            "gusts" to "Gusts: comes in with uneven surges", "swell" to "Swell: one curved rise",
            "build" to "Build: plays more often and louder over the fade (Intermittent tracks)",
            "dist" to "Distance: starts faint and muffled, ends clear at the preset volume",
            "fresh" to "Start fresh: plays from the start of the file, with a short fade",
        ),
        "out" to mapOf(
            "gusts" to "Gusts dying down: falls away in uneven surges", "swell" to "Smooth fade: one curved fade out",
            "build" to "Thin out: plays less often and quieter over the fade (Intermittent tracks)",
            "dist" to "Recede: drifts into the distance, fainter and muffled",
            "fresh" to "Finish then stop: lets the current play finish, then stops",
        ),
    )
    val SLIDER = mapOf("gusts" to "Intensity", "swell" to "Speed", "build" to "Amount", "dist" to "How far")

    data class Row(
        val key: String,
        val name: String,
        val mode: String,
        val from: Double,
        val to: Double,
        val presenceFrom: Double,
        val presenceTo: Double,
        val on: Set<String>,
        val v: Map<String, Int>,
        val cap: Int,
        val maxVol: Int,
        val pre: Int,
    ) {
        val intermittent: Boolean get() = mode == "Intermittent"
        /** The styles that are on and apply, in button order (`txActive`). */
        val active: List<String> get() = STYLES.filter { it in on && !(it == "build" && !intermittent) }
        val isSet: Boolean get() = pre > 0 || on.isNotEmpty()
    }

    private fun num(o: JsonObject, f: String) = (o[f] as? JsonPrimitive)?.doubleOrNull

    /** `txRowFrom`. */
    fun rowFrom(track: JsonObject, side: String, saved: JsonObject?): Row {
        val key = track["key"]?.jsonPrimitive?.content.orEmpty()
        val cfg = saved?.get(key) as? JsonObject ?: JsonObject(emptyMap())
        val styles = (cfg["styles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val values = (cfg["values"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.doubleOrNull?.roundToInt() }
            ?.filterValues { it != null }?.mapValues { it.value!! }.orEmpty()
        val from = num(track, "from") ?: 0.0
        val to = num(track, "to") ?: 0.0
        val cap = ((if (side == "in") to else from) * 100).roundToInt()
        val max = num(cfg, "max_volume")
        return Row(
            key = key,
            name = track["name"]?.jsonPrimitive?.content ?: key,
            mode = track["mode"]?.jsonPrimitive?.content.orEmpty(),
            from = from, to = to,
            presenceFrom = num(track, "presence_from") ?: 1.0, presenceTo = num(track, "presence_to") ?: 1.0,
            on = STYLES.filter { it in styles }.toSet(),
            v = DEFAULTS + values,
            cap = cap,
            maxVol = if (max != null) minOf(cap, (max * 100).roundToInt()) else cap,
            pre = (num(cfg, "pre_fade") ?: 0.0).roundToInt(),
        )
    }

    /** `txRowConfig`: null when nothing is set. */
    fun rowConfig(r: Row): JsonObject? {
        if (!r.isSet) return null
        val styles = r.active
        val out = linkedMapOf<String, JsonElement>(
            "styles" to JsonArray(styles.map { JsonPrimitive(it) }),
            "values" to JsonObject(styles.filter { it != "fresh" }.associateWith { JsonPrimitive(r.v.getValue(it)) }),
            "pre_fade" to JsonPrimitive(r.pre),
        )
        if ("gusts" in r.on && r.maxVol < r.cap) out["max_volume"] = JsonPrimitive(r.maxVol / 100.0)
        return JsonObject(out)
    }

    /** `txSliderState`: range, value and text of one style's slider. */
    data class SliderState(val min: Int, val max: Int, val value: Int, val text: String)

    fun slider(r: Row, k: String, ramp: Int): SliderState {
        val win = r.pre + ramp
        return if (k == "swell") {
            val min = minOf(5, win)
            val max = maxOf(min, win)
            val value = r.v.getValue("swell").coerceIn(min, max)
            SliderState(min, max, value, "$value s of $win s")
        } else {
            SliderState(0, 100, r.v.getValue(k), "${r.v.getValue(k)}%")
        }
    }

    /** `txHints`: Build's Interval and Volume lines. */
    fun hints(r: Row, out: Boolean): Pair<String, String> {
        val amt = r.v.getValue("build") / 100.0
        val iv = ((if (out) r.presenceFrom else r.presenceTo) * 100).roundToInt()
        val vol = ((if (out) r.from else r.to) * 100).roundToInt()
        val ivA = maxOf(1, (iv * (1 - amt)).roundToInt())
        val volA = (vol * (1 - amt * 0.7)).roundToInt()
        return if (out) "Interval $iv% → $ivA%" to "Volume $vol% → $volA%" else "Interval $ivA% → $iv%" to "Volume $volA% → $vol%"
    }

    /** `txPct`. */
    fun pct(v: Double): String = if (v > 0) "${(v * 100).roundToInt()}%" else "off"

    /** The step's transitions after Save: kept entries plus the set rows; empty sides dropped (null = remove the step). */
    fun stepTransitions(rowsIn: List<Row>, rowsOut: List<Row>, keepIn: JsonObject, keepOut: JsonObject): JsonObject? {
        fun side(rows: List<Row>, keep: JsonObject): JsonObject {
            val m = keep.toMutableMap()
            rows.forEach { r -> rowConfig(r)?.let { m[r.key] = it } ?: m.remove(r.key) }
            return JsonObject(m)
        }
        val data = linkedMapOf<String, JsonElement>()
        side(rowsIn, keepIn).takeIf { it.isNotEmpty() }?.let { data["in"] = it }
        side(rowsOut, keepOut).takeIf { it.isNotEmpty() }?.let { data["out"] = it }
        return if (data.isEmpty()) null else JsonObject(data)
    }
}
