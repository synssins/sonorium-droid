package app.sonorium.server

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.sonorium.net.ApiException
import app.sonorium.net.EditorPreset
import app.sonorium.net.EditorSequence
import app.sonorium.net.EditorTrack
import app.sonorium.net.SequenceDefaults
import app.sonorium.net.SonoriumApi
import app.sonorium.net.SourceTracks
import app.sonorium.net.ThemeSummary
import app.sonorium.net.TrackGroup
import app.sonorium.server.ThemeEditorRules.Section
import app.sonorium.server.ThemeEditorRules.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** What the Theme Editor shows (native-api.md Phase 3, "Theme Editor"). */
data class EditorUi(
    val themeId: String,
    val title: String,
    val name: String,
    val description: String = "",
    val icon: String = "",
    val categories: List<String> = emptyList(),
    /** The details as of open / last Save theme (`JSON.stringify([...])` in the web UI). */
    val savedDetails: List<Any> = emptyList(),
    val knownCategories: List<String> = emptyList(),
    val tracks: List<EditorTrack> = emptyList(),
    val groups: List<TrackGroup> = emptyList(),
    val groupsOk: Boolean = true,
    val loading: Boolean = true,
    val loadError: String? = null,
    /** Greyed while waiting for the server's rebuild after an upload. */
    val waiting: Boolean = false,
    val presets: List<EditorPreset> = emptyList(),
    val sequences: List<EditorSequence> = emptyList(),
    val defaults: SequenceDefaults = SequenceDefaults(),
    /** `''` = Current settings, a preset id, or `seq:<id>`. */
    val selected: String = "",
    val mixDirty: Boolean = false,
    val previewMix: Boolean = false,
    val filePreview: String? = null,
    val collapsed: Set<String> = emptySet(),
    /** A group whose name is being edited inline. */
    val renaming: String? = null,
) {
    val details: List<Any> get() = listOf(name.trim(), icon, description.trim(), categories)
    val unsaved: Boolean get() = details != savedDetails
    val listedGroups: List<TrackGroup> get() = ThemeEditorRules.listedGroups(groups)
    val selectedPreset: EditorPreset? get() = presets.firstOrNull { it.id == selected }
    val selectedSequence: EditorSequence? get() = selected.removePrefix("seq:").takeIf { selected.startsWith("seq:") }?.let { id -> sequences.firstOrNull { it.id == id } }
    fun group(name: String?): TrackGroup? = name?.let { n -> groups.firstOrNull { it.name == n } }
    fun sectionOf(t: EditorTrack): Section = ThemeEditorRules.sectionOf(t, groups)
    fun listedGroupOf(t: EditorTrack): TrackGroup? = ThemeEditorRules.listedGroupOf(t, groups)
}

/**
 * One open Theme Editor: every control saves as it changes; Cancel puts back the snapshot.
 * Lives in [scope] (the server view model's); [close] stops its players.
 */
class ThemeEditor(
    theme: ThemeSummary,
    private val api: SonoriumApi,
    private val scope: CoroutineScope,
    private val context: Context,
    private val client: OkHttpClient,
    /** The theme's live mix for Preview mix (`<streamBase>/stream/<id>?preview=<ms>`). */
    private val mixUrl: (String) -> String?,
    private val toast: (String, Boolean) -> Unit,
    /** After Save theme: refresh the Themes page and theme pickers. */
    private val onThemeSaved: () -> Unit,
    /**
     * A theme on this phone ([app.sonorium.local.PhoneThemeApi] answers [api]): the screens hide what
     * the phone can't do yet (Preview mix, a linked file's ▶ preview).
     */
    val onPhone: Boolean = false,
    /** Linked files work here (a server, or this device's own entry with its other themes). */
    val linksHere: Boolean = !onPhone,
) {
    val themeId = theme.id
    private val _ui = MutableStateFlow(
        EditorUi(theme.id, title = theme.name, name = theme.name, description = theme.description, icon = theme.icon, categories = theme.categories)
            .let { it.copy(savedDetails = it.details) },
    )
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()
    private var savedName = theme.name
    private var snapshot: ThemeEditorRules.Snapshot? = null
    private var loadSeq = 0
    private var player: ExoPlayer? = null
    private var mixRestart: Job? = null

    /** Advanced's `group_crossfade_min`/`max` (1/60 until loaded). */
    private var crossfadeRange = 1.0 to 60.0

    init {
        scope.launch { runCatching { api.categories() }.onSuccess { c -> _ui.update { it.copy(knownCategories = c) } } }
        scope.launch {
            runCatching { api.json("GET", "settings/playback-values")["values"] as? JsonObject }.getOrNull()?.let { v ->
                val lo = ThemeEditorRules.num(v, "group_crossfade_min") ?: 1.0
                val hi = ThemeEditorRules.num(v, "group_crossfade_max") ?: 60.0
                crossfadeRange = lo to maxOf(lo, hi)
            }
        }
        loadPresets()
        scope.launch { loadTracks() }
    }

    /** A success toast from a dialog's own action (`teFlash`). */
    fun toastInfo(text: String) = toast(text, false)

    // ---------- Loading ----------

    suspend fun loadTracks(afterUpload: Boolean = false) {
        val seq = ++loadSeq
        if (afterUpload) {
            _ui.update { it.copy(waiting = true) }
            delay(REBUILD_WAIT_MS)
        }
        try {
            val tracks = api.editorTracks(themeId).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val groups = runCatching { api.trackGroups(themeId) }
            if (seq != loadSeq) return
            val all = ThemeEditorRules.allGroups(groups.getOrDefault(emptyList()), tracks)
            _ui.update { it.copy(tracks = tracks, groups = if (groups.isSuccess) all else emptyList(), groupsOk = groups.isSuccess, loading = false, loadError = null, waiting = false) }
            if (snapshot == null) snapshot = ThemeEditorRules.snapshot(tracks, all)
        } catch (e: Exception) {
            if (seq != loadSeq) return
            _ui.update { it.copy(loading = false, waiting = false, loadError = message(e, "Failed to load tracks")) }
        }
    }

    private fun reload(restartMix: Boolean = false) = scope.launch {
        loadTracks()
        if (restartMix) restartMixSoon(0)
    }

    fun loadPresets() = scope.launch {
        runCatching { api.editorPresets(themeId) }.onSuccess { p -> _ui.update { it.copy(presets = p.presets, sequences = p.sequences) } }
        runCatching { api.sequenceDefaults(themeId) }.onSuccess { d -> _ui.update { it.copy(defaults = d) } }
    }

    // ---------- Details ----------

    fun setName(v: String) = _ui.update { it.copy(name = v) }
    fun setDescription(v: String) = _ui.update { it.copy(description = v) }
    fun setIcon(v: String) = _ui.update { it.copy(icon = v) }
    fun setCategories(v: List<String>) = _ui.update { it.copy(categories = v) }

    /** `teSaveTheme`: false when it failed (or the name is empty). [pending] is a typed, not yet added category. */
    suspend fun saveTheme(quiet: Boolean, pending: String = ""): Boolean {
        if (pending.isNotBlank()) _ui.update { it.copy(categories = ThemeEditorRules.addCategory(it.categories, it.knownCategories, pending)) }
        val u = _ui.value
        val name = u.name.trim()
        if (name.isEmpty()) {
            toast("Please enter a theme name", true)
            return false
        }
        return try {
            if (name != savedName) api.renameTheme(themeId, name)
            api.setThemeMetadata(themeId, u.description.trim(), u.icon)
            api.setThemeCategories(themeId, u.categories)
            savedName = name
            snapshot = ThemeEditorRules.snapshot(_ui.value.tracks, _ui.value.groups)
            _ui.update { it.copy(title = name, name = name, savedDetails = it.copy(name = name).details) }
            scope.launch { runCatching { api.categories() }.onSuccess { c -> _ui.update { it.copy(knownCategories = c) } } }
            onThemeSaved()
            if (!quiet) toast("Saved", false)
            true
        } catch (e: Exception) {
            toast(message(e, "Failed to save theme"), true)
            false
        }
    }

    /** Cancel: put back the mix as it was at open / last save; the caller then closes. */
    suspend fun restoreMix() {
        val snap = snapshot ?: return
        val u = _ui.value
        val calls = ThemeEditorRules.restoreCalls(snap, u.tracks, u.groups)
        val ok = calls.map { c ->
            scope.async {
                runCatching {
                    if (c.setting == null) api.updateTrackGroup(themeId, c.key, c.body) else api.setTrack(themeId, c.key, c.setting, c.body)
                }.isSuccess
            }
        }.awaitAll()
        if (ok.any { !it }) toast("Some settings could not be put back", true)
    }

    // ---------- Tracks ----------

    private fun track(key: String) = _ui.value.tracks.firstOrNull { it.name == key }

    private fun updateTrack(key: String, change: (EditorTrack) -> EditorTrack) =
        _ui.update { u -> u.copy(tracks = u.tracks.map { if (it.name == key) change(it) else it }, mixDirty = true) }

    private fun setTrack(key: String, setting: String, body: JsonObject, fail: String, local: (EditorTrack) -> EditorTrack, after: () -> Unit = {}) =
        scope.launch {
            try {
                api.setTrack(themeId, key, setting, body)
                updateTrack(key, local)
                after()
            } catch (e: Exception) {
                toast(message(e, fail), true)
                _ui.update { it.copy() } // redraw from the unchanged values
            }
        }

    fun setVolume(key: String, percent: Int) =
        setTrack(key, "volume", obj("volume" to JsonPrimitive(percent / 100.0)), "Failed to set volume", { it.copy(volume = percent / 100.0) }) { followPreview() }

    fun setPresence(key: String, percent: Int) =
        setTrack(key, "presence", obj("presence" to JsonPrimitive(percent / 100.0)), "Failed to set interval", { it.copy(presence = percent / 100.0) })

    fun setVolumeLow(key: String, percent: Int) =
        setTrack(key, "ebb", obj("volume_low" to JsonPrimitive(percent / 100.0)), "Failed to set volume", { it.copy(volumeLow = percent / 100.0) })

    fun toggleMute(t: EditorTrack) =
        setTrack(t.name, "muted", obj("muted" to JsonPrimitive(!t.muted)), "Failed to save", { it.copy(muted = !t.muted) })

    fun setMode(t: EditorTrack, section: Section) =
        setTrack(t.name, "playback_mode", obj("playback_mode" to JsonPrimitive(section.mode)), "Failed to save", { it.copy(playbackMode = section.mode) }) { restartMixSoon() }

    fun toggleGapless(t: EditorTrack) =
        setTrack(t.name, "seamless_loop", obj("seamless_loop" to JsonPrimitive(!t.seamlessLoop)), "Failed to save", { it.copy(seamlessLoop = !t.seamlessLoop) }) { restartMixSoon() }

    fun exclusiveOff(t: EditorTrack) =
        setTrack(t.name, "exclusive", obj("exclusive" to JsonPrimitive(false)), "Failed to save", { it.copy(exclusive = false) })

    fun setFade(t: EditorTrack, fadeIn: Boolean, text: String) {
        val s = ThemeEditorRules.fadeSeconds(text)
        if (s == (if (fadeIn) t.fadeIn else t.fadeOut)) return
        val field = if (fadeIn) "fade_in" else "fade_out"
        setTrack(t.name, "fades", obj(field to JsonPrimitive(s)), "Failed to save", { if (fadeIn) it.copy(fadeIn = s) else it.copy(fadeOut = s) })
    }

    fun setRise(t: EditorTrack, percent: Int) =
        setTrack(t.name, "ebb", obj("rise" to JsonPrimitive(percent / 100.0)), "Failed to save", { it.copy(rise = percent / 100.0) })

    fun togglePauseBetween(t: EditorTrack) =
        setTrack(t.name, "ebb", obj("pause_between" to JsonPrimitive(!t.pauseBetween)), "Failed to save", { it.copy(pauseBetween = !t.pauseBetween) })

    fun setPause(t: EditorTrack, min: String, max: String) {
        val (a, b) = ThemeEditorRules.pausePair(min, max)
        setTrack(t.name, "ebb", obj("pause_min" to JsonPrimitive(a), "pause_max" to JsonPrimitive(b)), "Failed to save", { it.copy(pauseMin = a, pauseMax = b) })
    }

    fun resetTrack(t: EditorTrack) = scope.launch {
        try {
            ThemeEditorRules.resetTrackCalls(t).forEach { (setting, body) -> api.setTrack(themeId, t.name, setting, body) }
            toast("Reset ${t.shortName}", false)
            _ui.update { it.copy(mixDirty = true) }
        } catch (e: Exception) {
            toast(message(e, "Failed to reset track"), true)
        }
        loadTracks()
    }

    fun removeLink(t: EditorTrack) = scope.launch {
        try {
            api.removeTrackLink(themeId, t.name)
            toast("Removed ${t.shortName}", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to remove the link"), true)
        }
        loadTracks()
        loadPresets()
    }

    /**
     * `teCopyLinkIn`: the linked file copied into this theme where it is (its group, or the top level). The row
     * stays in place and becomes the theme's own file (a new key on a name clash, e.g. "Rain (2)"), with its
     * settings; Cancel's snapshot follows the new key.
     */
    fun copyLinkIn(t: EditorTrack) = scope.launch {
        if (t.linked == null || t.missing) return@launch
        val newKey = try {
            api.copyLinkIn(themeId, t.name, ThemeEditorRules.groupOf(t))
        } catch (e: Exception) {
            toast(message(e, "Failed to copy the file"), true)
            return@launch
        }
        _ui.update { u -> u.copy(tracks = u.tracks.map { if (it.name == t.name) it.copy(name = newKey, linked = null) else it }) }
        snapshot?.let { s ->
            if (newKey != t.name) s.tracks[t.name]?.let { v -> snapshot = s.copy(tracks = s.tracks - t.name + (newKey to v)) }
        }
        loadTracks()
        loadPresets()
    }

    /** `teMoveTrack`: into [group] (null = the theme). */
    fun moveTrack(t: EditorTrack, group: String?) = scope.launch {
        if (ThemeEditorRules.groupOf(t) == group) return@launch
        try {
            val moved = api.moveTrack(themeId, t.name, group)
            val target = group?.let { _ui.value.group(it) }
            val mode = target?.let { Section.ofGroupMode(ThemeEditorRules.groupMode(it)).mode }
            if (mode != null && mode != t.playbackMode) api.setTrack(themeId, moved.track.ifEmpty { t.name }, "playback_mode", obj("playback_mode" to JsonPrimitive(mode)))
            toast("Moved ${t.shortName} to ${group ?: "theme"}", false)
            _ui.update { it.copy(mixDirty = true) }
        } catch (e: Exception) {
            toast(message(e, "Failed to move track"), true)
        }
        loadTracks()
        restartMixSoon(0)
    }

    // ---------- Groups ----------

    fun setGroup(name: String, body: JsonObject, fail: String = "Failed to save group") = scope.launch {
        try {
            val saved = api.updateTrackGroup(themeId, name, body)
            _ui.update { u ->
                u.copy(groups = u.groups.map { g -> if (g.name == name) g.copy(settings = saved.settings ?: merge(g.settings, body)) else g }, mixDirty = true)
            }
            followPreview()
        } catch (e: Exception) {
            toast(message(e, fail), true)
            _ui.update { it.copy() }
        }
    }

    private fun merge(settings: JsonObject, body: JsonObject): JsonObject =
        JsonObject((settings + body).filterValues { it !is JsonNull })

    fun setGap(g: TrackGroup, minText: String, maxText: String) {
        val (a, b) = ThemeEditorRules.gapPair(ThemeEditorRules.gapSeconds(minText), ThemeEditorRules.gapSeconds(maxText))
        setGroup(g.name, obj("gap_min" to (a?.let { JsonPrimitive(it) } ?: JsonNull), "gap_max" to (b?.let { JsonPrimitive(it) } ?: JsonNull)), "Failed to save gap")
    }

    fun setCrossfade(g: TrackGroup, text: String) =
        setGroup(g.name, obj("crossfade" to (ThemeEditorRules.crossfade(text, crossfadeRange.first, crossfadeRange.second)?.let { JsonPrimitive(it) } ?: JsonNull)))

    fun toggleCollapsed(name: String) = _ui.update { u -> u.copy(collapsed = if (name in u.collapsed) u.collapsed - name else u.collapsed + name) }

    fun startRename(name: String?) = _ui.update { it.copy(renaming = name) }

    /** `teNewGroup`: a free name, the section's mode, then inline rename; [move] goes in after. */
    fun newGroup(section: Section, move: EditorTrack? = null) = scope.launch {
        val mode = section.groupMode ?: return@launch
        val name = ThemeEditorRules.newGroupName(_ui.value.groups.map { it.name })
        try {
            api.createTrackGroup(themeId, name)
            api.updateTrackGroup(themeId, name, obj("mode" to JsonPrimitive(mode)))
            move?.let { t ->
                val moved = api.moveTrack(themeId, t.name, name)
                if (t.playbackMode != section.mode) api.setTrack(themeId, moved.track.ifEmpty { t.name }, "playback_mode", obj("playback_mode" to JsonPrimitive(section.mode)))
            }
            loadTracks()
            _ui.update { it.copy(renaming = name, collapsed = it.collapsed - name) }
        } catch (e: Exception) {
            toast(message(e, "Failed to create group"), true)
            loadTracks()
        }
    }

    fun renameGroup(old: String, text: String) = scope.launch {
        _ui.update { it.copy(renaming = null) }
        val name = text.trim()
        if (name.isEmpty() || name == old) return@launch
        if (_ui.value.groups.any { it.name != old && it.name.equals(name, ignoreCase = true) }) {
            toast("A group with that name exists", true)
            return@launch
        }
        try {
            api.renameTrackGroup(themeId, old, name)
        } catch (e: Exception) {
            toast(message(e, "Failed to rename group"), true)
        }
        loadTracks()
        restartMixSoon(0)
    }

    fun deleteGroup(name: String) = scope.launch {
        try {
            api.deleteTrackGroup(themeId, name)
            toast("Deleted group $name", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to delete group"), true)
        }
        loadTracks()
        restartMixSoon(0)
    }

    // ---------- Whole theme ----------

    fun resetAll() = scope.launch {
        try {
            api.resetTracks(themeId)
            val clear = obj("volume" to JsonNull, "presence" to JsonNull, "muted" to JsonNull)
            _ui.value.groups.filter { g -> listOf("volume", "presence", "muted").any { it in g.settings } }
                .forEach { api.updateTrackGroup(themeId, it.name, clear) }
            toast("Tracks and groups reset", false)
            _ui.update { it.copy(mixDirty = true) }
        } catch (e: Exception) {
            toast(message(e, "Failed to reset tracks"), true)
        }
        loadTracks()
        restartMixSoon(0)
    }

    /** Uploads one file at a time; [group] null = the theme. */
    fun upload(files: List<Pair<String, ByteArray>>, group: String?) = scope.launch {
        toast("Uploading ${files.size} file(s)…", false)
        var done = 0
        files.forEach { (name, bytes) ->
            try {
                api.uploadTrack(themeId, name, bytes, group)
                done++
            } catch (e: Exception) {
                toast("$name: ${(e as? ApiException)?.detail?.ifBlank { null } ?: "Upload failed"}", true)
            }
        }
        if (done > 0) toast("Uploaded $done file(s)", false)
        loadTracks(afterUpload = true)
        restartMixSoon(0)
    }

    suspend fun sourceTracks(sourceId: String): Result<SourceTracks> = runCatching { api.sourceTracks(themeId, sourceId) }

    /** Add from another theme; false keeps the picker open. */
    suspend fun addLinks(sourceId: String, keys: List<String>): Boolean = try {
        api.addLinks(themeId, sourceId, keys)
        toast("Added ${keys.size} linked file(s)", false)
        loadTracks()
        loadPresets()
        true
    } catch (e: Exception) {
        toast(message(e, "Failed to add the files"), true)
        false
    }

    // ---------- Presets ----------

    fun selectCurrent() = _ui.update { it.copy(selected = "") }

    fun selectSequence(id: String) = _ui.update { it.copy(selected = "seq:$id") }

    /** Picking a preset loads it at once (also Reload). */
    fun loadPreset(p: EditorPreset) = scope.launch {
        _ui.update { it.copy(selected = p.id) }
        try {
            api.loadPreset(themeId, p.id)
            _ui.update { it.copy(mixDirty = false) }
            toast("Loaded “${p.name}”", false)
            loadTracks()
            restartMixSoon(0)
        } catch (e: Exception) {
            toast(message(e, "Failed to load preset"), true)
        }
    }

    fun setDefault(p: EditorPreset) = scope.launch {
        try {
            api.setDefaultPreset(themeId, p.id)
            toast("“${p.name}” is the default", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to set default preset"), true)
        }
        loadPresets()
    }

    /** New: false keeps the popover open (duplicate name or a failure). */
    suspend fun newPreset(text: String): Boolean {
        val name = text.trim()
        if (_ui.value.presets.any { it.name.equals(name, ignoreCase = true) }) {
            toast("A preset with that name exists", true)
            return false
        }
        if (!saveTheme(quiet = true)) return false
        return try {
            val created = api.createPreset(themeId, name)
            loadPresets().join()
            _ui.update { it.copy(selected = created.presetId, mixDirty = false) }
            toast("Saved", false)
            true
        } catch (e: Exception) {
            toast(message(e, "Failed to save preset"), true)
            false
        }
    }

    suspend fun renamePreset(p: EditorPreset, text: String): Boolean {
        val name = text.trim()
        if (_ui.value.presets.any { it.id != p.id && it.name.equals(name, ignoreCase = true) }) {
            toast("A preset with that name exists", true)
            return false
        }
        return try {
            api.renamePreset(themeId, p.id, name)
            loadPresets()
            toast("Renamed to “$name”", false)
            true
        } catch (e: Exception) {
            toast(message(e, "Failed to rename preset"), true)
            false
        }
    }

    fun deletePreset(p: EditorPreset) = scope.launch {
        try {
            api.deletePreset(themeId, p.id)
            _ui.update { it.copy(selected = "") }
            toast("Deleted “${p.name}”", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to delete preset"), true)
        }
        loadPresets()
    }

    fun savePreset() = scope.launch {
        val p = _ui.value.selectedPreset ?: return@launch
        if (!saveTheme(quiet = true)) return@launch
        try {
            api.savePreset(themeId, p.id)
            _ui.update { it.copy(mixDirty = false) }
            toast("Saved", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to save preset"), true)
        }
        loadPresets()
    }

    suspend fun importPreset(name: String, json: String): Boolean {
        if (json.isBlank()) {
            toast("Please paste preset JSON", true)
            return false
        }
        return try {
            val r = api.importPreset(themeId, json, name.trim().ifEmpty { null })
            toast("Imported preset: ${r.name}" + (r.warning?.let { " ($it)" } ?: ""), false)
            loadPresets()
            true
        } catch (e: Exception) {
            toast("Import failed: ${message(e, "")}", true)
            false
        }
    }

    /** The selected preset's JSON, pretty-printed; null after a toast. */
    suspend fun exportPreset(): String? {
        val p = _ui.value.selectedPreset ?: run {
            toast("Select a preset first", true)
            return null
        }
        return try {
            val raw = api.exportPreset(themeId, p.id)
            PRETTY.encodeToString(JsonElement.serializer(), SonoriumApi.json.parseToJsonElement(raw))
        } catch (e: Exception) {
            toast(message(e, "Failed to export preset"), true)
            null
        }
    }

    /** Sequences menu › Theme defaults (`teSetSeqDefaults`); returns which fields are wrong. */
    fun setSequenceDefaults(minText: String, maxText: String, rampText: String): Set<String> {
        val min = minText.trim().toDoubleOrNull()
        val max = maxText.trim().toDoubleOrNull()
        val ramp = rampText.trim().toDoubleOrNull()
        if (min == null || max == null || min <= 0 || max <= 0 || min > max) {
            toast(if (min != null && max != null && min > max) "Minimum is more than maximum" else "Enter the time on each preset", true)
            return setOf("min", "max")
        }
        if (ramp == null || ramp < 0) {
            toast("Enter the ramp in seconds", true)
            return setOf("ramp")
        }
        scope.launch {
            try {
                api.setSequenceDefaults(themeId, buildJsonObject {
                    put("hold_min", Math.round(min * 60))
                    put("hold_max", Math.round(max * 60))
                    put("ramp", Math.round(ramp))
                })
                toast("Theme defaults saved", false)
            } catch (e: Exception) {
                toast(message(e, "Failed to save the defaults"), true)
            }
            loadPresets()
        }
        return emptySet()
    }

    // ---------- Sequence editor (`#sq-modal`) ----------

    /** An open sequence: [id] null = new. Times are the fields' text (empty = theme default). */
    data class SeqDraft(
        val id: String?,
        val name: String = "",
        val min: String = "",
        val max: String = "",
        val ramp: String = "",
        val atEnd: String = "reverse",
        val steps: List<String> = emptyList(),
        val transitions: JsonObject = JsonObject(emptyMap()),
        /** Unsaved While-playing layers from Advanced, by step. */
        val layers: Map<String, JsonObject> = emptyMap(),
        val whileSteps: Set<String> = emptySet(),
        val bad: Set<String> = emptySet(),
    )

    private val _seq = MutableStateFlow<SeqDraft?>(null)
    val seq: StateFlow<SeqDraft?> = _seq.asStateFlow()

    /** `sqOpen`: [id] null = Add sequence. */
    fun openSequence(id: String?) {
        val s = id?.let { i -> _ui.value.sequences.firstOrNull { it.id == i } }
        if (id != null && s == null) return toast("Sequence not found", true)
        val presets = _ui.value.presets.map { it.id }.toSet()
        _seq.value = if (s == null) SeqDraft(null) else SeqDraft(
            id = s.id, name = s.name,
            min = SequenceRules.minutes(s.holdMin), max = SequenceRules.minutes(s.holdMax), ramp = SequenceRules.plain(s.ramp),
            atEnd = s.atEnd.ifEmpty { "reverse" }, steps = s.presets.filter { it in presets },
            transitions = s.transitions, whileSteps = s.whileSteps.toSet(),
        )
    }

    fun editSequence(change: (SeqDraft) -> SeqDraft) = _seq.update { it?.let(change) }

    fun closeSequence() {
        _seq.value = null
    }

    /** ⋯ › Duplicate: a new unsaved copy. */
    fun duplicateSequence() = _seq.update { it?.copy(id = null, name = "${it.name} (copy)", bad = emptySet()) }

    fun deleteSequence() = scope.launch {
        val d = _seq.value ?: return@launch
        val id = d.id ?: return@launch
        val name = _ui.value.sequences.firstOrNull { it.id == id }?.name ?: d.name
        try {
            api.deleteSequence(themeId, id)
            if (_ui.value.selected == "seq:$id") _ui.update { it.copy(selected = "") }
            _seq.value = null
            toast("Deleted “$name”", false)
        } catch (e: Exception) {
            toast(message(e, "Failed to delete the sequence"), true)
        }
        loadPresets()
    }

    /** `sqSave`: [keepOpen] when saved from Advanced. True when saved. */
    suspend fun saveSequence(keepOpen: Boolean = false, quiet: Boolean = false): Boolean {
        val d = _seq.value ?: return false
        val t = SequenceRules.timing(d.min, d.max, d.ramp, _ui.value.defaults)
        SequenceRules.check(d.name, d.id, _ui.value.sequences, t, d.steps.size)?.let { (text, bad) ->
            _seq.update { it?.copy(bad = bad) }
            toast(text, true)
            return false
        }
        val name = d.name.trim()
        return try {
            val saved = api.saveSequence(themeId, d.id, SequenceRules.body(name, d.steps, d.atEnd, t, d.transitions))
            val id = saved.id.ifEmpty { d.id.orEmpty() }
            var whileSteps = d.whileSteps
            d.layers.forEach { (preset, layer) ->
                if (preset in d.steps) {
                    api.setWhileLayer(themeId, id, preset, layer)
                    val empty = (layer["tracks"] as? JsonObject).isNullOrEmpty() && (layer["groups"] as? JsonObject).isNullOrEmpty()
                    whileSteps = if (empty) whileSteps - preset else whileSteps + preset
                }
            }
            if (keepOpen) {
                _seq.update { it?.copy(id = id, transitions = saved.transitions, layers = emptyMap(), whileSteps = whileSteps, bad = emptySet()) }
                if (!quiet) toast("Saved “$name”", false)
            } else {
                _seq.value = null
                toast("Saved “$name”", false)
            }
            loadPresets()
            true
        } catch (e: Exception) {
            toast(message(e, "Failed to save the sequence"), true)
            false
        }
    }

    /** Advanced's data: transition tracks, the preset's values, and the step's saved While-playing layer. */
    suspend fun advancedData(step: String, prev: String?, next: String?): Result<Triple<JsonObject, JsonObject, JsonObject?>> = runCatching {
        val d = _seq.value
        val layer = d?.layers?.get(step) ?: d?.id?.let { id ->
            val saved = _ui.value.sequences.firstOrNull { it.id == id }
            if (saved != null && step in saved.presets) api.whileLayer(themeId, id, step) else null
        }
        Triple(api.transitionTracks(themeId, step, prev, next), api.presetValues(themeId, step), layer)
    }.onFailure { e -> toast(message(e as? Exception ?: Exception(e), "Failed to load the tracks"), true) }

    /** Advanced › Save: into the draft, then the whole sequence is saved (the editor stays open). */
    fun saveAdvanced(step: String, transitions: JsonObject?, layer: JsonObject?, presetName: String) = scope.launch {
        editSequence { d ->
            val tr = d.transitions.toMutableMap()
            if (transitions != null) tr[step] = transitions else tr.remove(step)
            d.copy(transitions = JsonObject(tr), layers = if (layer != null) d.layers + (step to layer) else d.layers)
        }
        if (saveSequence(keepOpen = true, quiet = true)) toast("Saved “$presetName”", false)
    }

    // ---------- Previews (this phone only) ----------

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun player(): ExoPlayer = player ?: run {
        val source = OkHttpDataSource.Factory(client).apply {
            api.authorization?.let { setDefaultRequestProperties(mapOf("Authorization" to it)) }
        }
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().also { p ->
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val u = _ui.value
                    if (u.previewMix) toast("Preview mix stopped", true)
                    else if (u.filePreview != null) toast("Failed to load audio", true)
                    stopPreviews()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && _ui.value.filePreview != null) stopPreviews()
                }
            })
        }
    }

    /** The volume a file preview plays at: row Volume × group master. */
    private fun previewVolume(key: String): Float {
        val u = _ui.value
        val t = track(key) ?: return 0.8f
        return (t.volume * ThemeEditorRules.master(u.listedGroupOf(t), "volume")).toFloat().coerceIn(0f, 1f)
    }

    private fun followPreview() {
        _ui.value.filePreview?.let { key -> player?.volume = previewVolume(key) }
    }

    /** ▶ on a row (or a picker row, [url] given): one at a time; stops Preview mix. */
    fun toggleFilePreview(key: String, url: String? = null) {
        if (_ui.value.filePreview == key) return stopPreviews()
        val p = player()
        val target = url?.let { api.absolute(it)?.toString() } ?: api.trackAudioUrl(themeId, key).toString()
        _ui.update { it.copy(filePreview = key, previewMix = false) }
        p.setMediaItem(MediaItem.fromUri(target))
        p.volume = if (url != null) 0.8f else previewVolume(key)
        p.prepare()
        p.play()
    }

    fun togglePreviewMix() {
        if (_ui.value.previewMix) return stopPreviews()
        val url = mixUrl(themeId) ?: return toast("Could not play the preview", true)
        val p = player()
        _ui.update { it.copy(previewMix = true, filePreview = null) }
        p.setMediaItem(MediaItem.fromUri(url))
        p.volume = 1f
        p.prepare()
        p.play()
    }

    /** Restarts Preview mix (a new URL) when it is on. */
    private fun restartMixSoon(delayMs: Long = 300) {
        if (!_ui.value.previewMix) return
        mixRestart?.cancel()
        mixRestart = scope.launch {
            delay(delayMs)
            if (!_ui.value.previewMix) return@launch
            val url = mixUrl(themeId) ?: return@launch
            player?.apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
        }
    }

    fun stopPreviews() {
        player?.stop()
        player?.clearMediaItems()
        _ui.update { it.copy(previewMix = false, filePreview = null) }
    }

    fun close() {
        mixRestart?.cancel()
        player?.release()
        player = null
    }

    private fun message(e: Exception, fallback: String): String = when {
        e is ApiException -> e.firstMessage ?: e.detail.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: fallback
        else -> fallback.ifEmpty { if (onPhone) "Something went wrong on this phone." else "Can't reach the server." }
    }

    private companion object {
        /** The server rebuilds about 2 s after an upload (`TE_REBUILD_WAIT_MS`). */
        const val REBUILD_WAIT_MS = 2_600L
        val PRETTY = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
