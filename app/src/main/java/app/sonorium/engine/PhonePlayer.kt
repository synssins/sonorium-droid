package app.sonorium.engine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import app.sonorium.SonoriumApp
import app.sonorium.cast.CastController
import app.sonorium.local.LocalTheme
import app.sonorium.local.PhonePlaylists
import app.sonorium.local.PhoneThemeApi
import app.sonorium.net.Playlist
import app.sonorium.net.PlaylistEntry
import app.sonorium.net.PlaylistStatus
import app.sonorium.net.RecentChange
import app.sonorium.net.SequenceStatus
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playing a theme on this phone: what the "On this phone" view shows and controls. */
object PhonePlayer {
    data class State(
        val theme: LocalTheme? = null,
        val presets: List<PhoneEngine.Preset> = emptyList(),
        val presetId: String? = null,
        val sequences: List<PhoneEngine.Sequence> = emptyList(),
        /** A sequence runs (picked where the preset is picked); its step below. */
        val sequenceId: String? = null,
        val step: PhoneEngine.Step? = null,
        /** The sequence picked last came to its end (it doesn't repeat): the mix stays on its last preset. */
        val sequenceFinished: Boolean = false,
        val volume: Int = 80,
        val playing: Boolean = false,
        /** Getting the theme ready: files done of total (null when not preparing). */
        val preparing: Pair<Int, Int>? = null,
        val error: String? = null,
        /** Something to know that isn't an error (audio files left out). */
        val notice: String? = null,
        /** Playlist mode (the channel's Theme / Playlist switch) and the playlist chosen in it. */
        val playlistMode: Boolean = false,
        val playlistId: String? = null,
        /** What the engine plays now (polled while playing), in the server's terms for the channel card. */
        val now: Now = Now(),
        /** Muted: the volume is kept, the mix is silent. */
        val muted: Boolean = false,
    )

    /** The engine's status as a server channel reports it (sequence step, playlist entry, recent changes). */
    data class Now(
        val themeId: String? = null,
        val presetId: String? = null,
        val sequence: SequenceStatus? = null,
        val playlist: PlaylistStatus? = null,
        val playlistEntry: PlaylistEntry? = null,
        val recent: List<RecentChange> = emptyList(),
    )

    private const val TAG = "PhonePlayer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    /** The engine is open on this version of [State.theme] ([LocalTheme.version]), from this folder. */
    private var openFor: String? = null
    private var openFolder: String? = null
    private var restored = false

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("phone_player", Context.MODE_PRIVATE)

    /** The volume and theme used last (once per process). */
    private fun restore(context: Context) {
        if (restored) return
        restored = true
        val volume = prefs(context).getInt(KEY_VOLUME, app.sonorium.local.DeviceSettings.get(context).volume).coerceIn(0, 100)
        val p = prefs(context)
        _state.update { it.copy(volume = volume, playlistMode = p.getBoolean(KEY_PLAYLIST_MODE, false), playlistId = p.getString(KEY_PLAYLIST, null)) }
    }

    /**
     * The themes on the phone changed (read again): keeps the chosen theme in step with its folder.
     * An updated theme is got ready again on the next Play; a theme that's gone stops and is let go.
     * With no theme chosen, chooses the one used last, else the first.
     */
    fun sync(context: Context, themes: List<LocalTheme>) {
        restore(context)
        val current = _state.value.theme
        if (current != null) {
            val now = themes.firstOrNull { it.docId == current.docId } ?: themes.firstOrNull { it.folder == current.folder }
            if (now != null) {
                if (now != current) _state.update { it.copy(theme = now) }
                return
            }
            forget(context, current.docId)
        }
        val p = prefs(context)
        val lastDoc = p.getString(KEY_THEME_DOC, null)
        val lastFolder = p.getString(KEY_THEME_FOLDER, null)
        (themes.firstOrNull { it.docId == lastDoc } ?: themes.firstOrNull { it.folder == lastFolder } ?: themes.firstOrNull())
            ?.let { choose(context, it) }
    }

    /**
     * Picks a theme. Its presets show at once (read from its presets.json). While playing, the engine
     * crossfades to it (after getting it ready the first time); when stopped, the next Play uses it.
     */
    fun choose(context: Context, theme: LocalTheme) {
        restore(context)
        if (_state.value.theme?.docId == theme.docId) return
        val app = context.applicationContext as SonoriumApp
        val switching = _state.value.playing
        prefs(context).edit { putString(KEY_THEME_DOC, theme.docId).putString(KEY_THEME_FOLDER, theme.folder) }
        _state.update {
            it.copy(theme = theme, presets = emptyList(), presetId = null, sequences = emptyList(), sequenceId = null, step = null, sequenceFinished = false, error = null, notice = null)
        }
        scope.launch {
            val (presets, sequences) = readLists(app, theme) ?: (emptyList<PhoneEngine.Preset>() to emptyList())
            if (_state.value.theme?.docId == theme.docId && _state.value.presets.isEmpty()) {
                _state.update { it.copy(presets = presets, sequences = sequences, presetId = it.presetId ?: presets.firstOrNull { p -> p.isDefault }?.id) }
            }
        }
        if (switching) play(context)
    }

    /**
     * The chosen theme's presets or sequences may have changed (the Theme Editor closed): reads its
     * presets.json again. The playing preset or sequence stays chosen while it still exists.
     */
    fun reloadLists(context: Context) {
        val theme = _state.value.theme ?: return
        val app = context.applicationContext as SonoriumApp
        scope.launch {
            val (presets, sequences) = readLists(app, theme) ?: return@launch
            _state.update { st ->
                if (st.theme?.docId != theme.docId) st
                else st.copy(
                    presets = presets, sequences = sequences,
                    presetId = st.presetId?.let { id -> if (presets.any { it.id == id }) id else presets.firstOrNull { it.isDefault }?.id },
                    sequenceId = st.sequenceId?.takeIf { id -> sequences.any { it.id == id } },
                )
            }
        }
    }

    /**
     * A theme's presets and sequences read from its folder: presets.json, or the presets in its
     * metadata.json when presets.json is missing or has none (starter themes). Null when neither reads.
     */
    private suspend fun readLists(app: SonoriumApp, theme: LocalTheme): Pair<List<PhoneEngine.Preset>, List<PhoneEngine.Sequence>>? {
        val text = runCatching { app.themeStore.readFile(theme, "presets.json") }.getOrNull()
        val presets = text?.let { PhoneEngine.presetsFromJson(it) }.orEmpty()
        val sequences = text?.let { PhoneEngine.sequencesFromJson(it) }.orEmpty()
        if (presets.isNotEmpty()) return presets to sequences
        val meta = runCatching { app.themeStore.readFile(theme, "metadata.json") }.getOrNull()
        val fromMeta = meta?.let { PhoneEngine.presetsFromMetadata(it) }.orEmpty()
        if (text == null && meta == null) return null
        return fromMeta to sequences
    }

    /** Play: prepares the theme the first time (decoding its audio once), opens the engine, starts playback. */
    fun play(context: Context) {
        restore(context)
        if (_state.value.playlistMode) {
            _state.value.playlistId?.let { playPlaylist(context, it) }
            return
        }
        val theme = _state.value.theme ?: return
        val app = context.applicationContext as SonoriumApp
        job?.cancel()
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                if (openFor != theme.version) {
                    // This theme's files changed since the engine opened it: let go of them before they're rebuilt
                    if (openFolder == theme.folder) closeEngine()
                    _state.update { it.copy(preparing = 0 to theme.tracks, error = null, notice = null) }
                    val prepared = ThemePrep.prepare(app, app.themeStore, theme) { done, total ->
                        if (self?.isActive == true) _state.update { if (it.preparing != null) it.copy(preparing = done to total) else it }
                    }
                    ensureActive()
                    prepareLinked(app, theme)
                    ensureActive()
                    val s = _state.value
                    val presets = withContext(Dispatchers.IO) {
                        // Already playing another theme: crossfade to this one; else open the engine on it
                        if (PhoneEngine.isOpen && openFor != null) PhoneEngine.setTheme(prepared, s.presetId, s.sequenceId).also { PhoneEngine.setVolume(s.volume / 100f) }
                        else PhoneEngine.open(app, prepared, s.presetId, s.volume / 100f, s.sequenceId)
                    }
                    val sequences = withContext(Dispatchers.IO) { PhoneEngine.sequences() }
                    openFor = theme.version
                    openFolder = theme.folder
                    ready[theme.folder] = theme.version
                    resetSequenceWatch()
                    _state.update { st ->
                        st.copy(
                            presets = presets, sequences = sequences, presetId = st.presetId ?: presets.firstOrNull { it.isDefault }?.id,
                            preparing = null, notice = skippedText(prepared.skipped.size),
                        )
                    }
                }
                ensureActive()
                startOutput(app, theme.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ThemePrep.Problem) {
                Log.w(TAG, "Couldn't prepare ${theme.folder}", e)
                _state.update { it.copy(error = e.message) }
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't play ${theme.folder}", e)
                _state.update { it.copy(error = "Couldn't play “${theme.name}”. Try again; if it keeps happening, remove it from this phone and add it again.") }
            } finally {
                if (job === self) _state.update { if (it.preparing != null) it.copy(preparing = null) else it }
            }
        }
    }

    /** The sound starts (the player service, or the cast's stream), or the title follows when it already plays. */
    private fun startOutput(app: Context, title: String) {
        when {
            // Casting: the speaker plays the engine's stream (already flowing after a theme change)
            CastController.casting ->
                if (!CastController.state.value.streaming) CastController.startStream(title)
            !_state.value.playing -> {
                val started = runCatching {
                    app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, title))
                }
                started.exceptionOrNull()?.let { e ->
                    Log.w(TAG, "Couldn't start the player service", e)
                    _state.update { it.copy(error = "Couldn't start playing. Open Sonorium and press Play again.") }
                }
            }
            else -> PhonePlayerService.retitle(title)
        }
    }

    /** The themes [theme] links files from, got ready too (their decoded audio plays the linked tracks). */
    private suspend fun prepareLinked(app: SonoriumApp, theme: LocalTheme) {
        val meta = runCatching { app.themeStore.readFile(theme, "metadata.json") }.getOrNull() ?: return
        val ids = runCatching {
            val links = org.json.JSONObject(meta).optJSONObject("links") ?: return
            links.keys().asSequence().mapNotNull { k -> links.optJSONObject(k)?.optString("theme")?.ifEmpty { null } }.toSet()
        }.getOrNull().orEmpty()
        if (ids.isEmpty()) return
        val themes = withContext(Dispatchers.IO) { runCatching { app.themeStore.list() }.getOrDefault(emptyList()) }
        for (t in themes) {
            if (t.metaId !in ids || t.docId == theme.docId || ready[t.folder] == t.version || openFolder == t.folder) continue
            runCatching { ThemePrep.prepare(app, app.themeStore, t) { _, _ -> } }
                .onSuccess { ready[t.folder] = t.version }
                .onFailure { Log.w(TAG, "Couldn't get ${t.folder} ready for its linked files", it) }
        }
    }

    /** Versions of the themes whose prepared copy is current (folder → [LocalTheme.version]). */
    private val ready = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Plays a playlist kept on this phone: gets every theme in it ready (once), opens the engine on the
     * first entry's theme when nothing plays, then the engine's playlist runner takes over (as a server
     * channel's), changing entries when their time comes.
     */
    private fun playPlaylist(context: Context, playlistId: String) {
        val app = context.applicationContext as SonoriumApp
        val playlist = PhonePlaylists.playlists.value.firstOrNull { it.id == playlistId } ?: run {
            _state.update { it.copy(error = "That playlist isn't on this phone any more. Choose another one.") }
            return
        }
        job?.cancel()
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                val themes = withContext(Dispatchers.IO) { app.themeStore.list() }
                val inIt = playlist.entries.mapNotNull { e -> themes.firstOrNull { it.metaId == e.themeId } }.distinctBy { it.docId }
                if (inIt.isEmpty()) {
                    _state.update { it.copy(error = "None of this playlist's themes are on this phone.") }
                    return@launch
                }
                val total = inIt.filter { ready[it.folder] != it.version }.sumOf { it.tracks }
                var done = 0
                if (total > 0) _state.update { it.copy(preparing = 0 to total, error = null, notice = null) }
                for (t in inIt) {
                    if (ready[t.folder] == t.version) continue
                    // The theme playing now keeps its files while it plays
                    if (openFolder == t.folder && openFor == t.version) { ready[t.folder] = t.version; continue }
                    val before = done
                    ThemePrep.prepare(app, app.themeStore, t) { d, _ ->
                        if (self?.isActive == true) _state.update { if (it.preparing != null) it.copy(preparing = (before + d) to total) else it }
                    }
                    done = before + t.tracks
                    ready[t.folder] = t.version
                    ensureActive()
                }
                val first = inIt.first()
                val json = SonoriumApi.json.encodeToString(Playlist.serializer(), playlist)
                withContext(Dispatchers.IO) {
                    if (!PhoneEngine.isOpen) PhoneEngine.open(app, ThemePrep.prepared(app, first.folder), null, effectiveVolume())
                    PhoneEngine.startPlaylist(json, PhoneThemeApi.themesRoot(app), PhoneThemeApi.pcmRoot(app))
                }
                // The engine plays the playlist's themes now: a theme picked later is crossfaded to
                openFor = "playlist:${playlist.id}"
                openFolder = null
                _state.update { it.copy(preparing = null, sequenceId = null, step = null, sequenceFinished = false) }
                refreshNow()
                startOutput(app, _state.value.now.playlist?.themeName ?: first.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ThemePrep.Problem) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't play playlist ${playlist.id}", e)
                _state.update { it.copy(error = "Couldn't play “${playlist.name}”. Try again.") }
            } finally {
                if (job === self) _state.update { if (it.preparing != null) it.copy(preparing = null) else it }
            }
        }
    }

    /** The channel's Theme / Playlist switch. Back to Theme while a playlist plays: the chosen theme plays. */
    fun setPlaylistMode(context: Context, on: Boolean) {
        restore(context)
        if (_state.value.playlistMode == on) return
        prefs(context).edit { putBoolean(KEY_PLAYLIST_MODE, on) }
        val playing = _state.value.playing
        _state.update { it.copy(playlistMode = on) }
        if (!on && openFor?.startsWith("playlist:") == true) {
            if (playing) play(context) else scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.stopPlaylist() } }
        }
    }

    /** Picks a playlist (playlist mode); while playing, it starts at once. */
    fun choosePlaylist(context: Context, playlistId: String) {
        restore(context)
        prefs(context).edit { putString(KEY_PLAYLIST, playlistId).putBoolean(KEY_PLAYLIST_MODE, true) }
        val playing = _state.value.playing
        _state.update { it.copy(playlistMode = true, playlistId = playlistId, error = null) }
        if (playing) play(context)
    }

    /** Next: the playlist's next entry, else the sequence's next step (as a server channel's Next). */
    fun next() {
        if (openFor == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { PhoneEngine.next() }
            refreshStep()
            refreshNow()
        }
    }

    /** Mute: the mix goes silent, the volume stays as set. */
    fun toggleMute() {
        _state.update { it.copy(muted = !it.muted) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setVolume(effectiveVolume()) } }
    }

    private fun effectiveVolume(): Float = if (_state.value.muted) 0f else _state.value.volume / 100f

    /** Reads what the engine plays now (theme, preset, step, playlist entry, recent changes). */
    private fun refreshNow() {
        val status = PhoneEngine.status() ?: return
        val now = System.currentTimeMillis()
        val rendered = status.optDouble("seconds_rendered", 0.0)
        fun at(secondsLeft: Double?): String? = secondsLeft?.let { java.time.Instant.ofEpochMilli(now + (it * 1000).toLong()).toString() }
        fun str(o: org.json.JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        val seq = status.optJSONObject("sequence")?.let { q ->
            SequenceStatus(
                id = str(q, "id"), name = str(q, "name"), running = q.optBoolean("running"),
                step = q.optInt("step").takeIf { !q.isNull("step") }, steps = q.optInt("steps"),
                currentPresetId = str(q, "current_preset_id"), currentPresetName = str(q, "current_preset_name"),
                nextChangeAt = if (q.isNull("seconds_left")) null else at(q.optDouble("seconds_left")),
            )
        }
        val pl = status.optJSONObject("playlist")?.let { q ->
            PlaylistStatus(
                id = str(q, "id"), name = str(q, "name"), running = q.optBoolean("running"),
                entryIndex = q.optInt("entry_index").takeIf { !q.isNull("entry_index") },
                position = q.optInt("position").takeIf { !q.isNull("position") }, entries = q.optInt("entries"),
                themeId = str(q, "theme_id"), themeName = str(q, "theme_name"),
                nextChangeAt = if (q.isNull("seconds_left")) null else at(q.optDouble("seconds_left")),
            )
        }
        val entry = status.optJSONObject("playlist")?.optJSONObject("entry")?.let { e ->
            PlaylistEntry(str(e, "theme_id"), str(e, "preset_id"), str(e, "sequence_id"))
        }
        val recent = status.optJSONArray("recent")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map { r ->
                val ago = rendered - r.optDouble("at_seconds", rendered)
                RecentChange(java.time.Instant.ofEpochMilli(now - (ago * 1000).toLong()).toString(), r.optString("text"))
            }.reversed()
        }.orEmpty()
        _state.update {
            it.copy(now = Now(str(status, "theme_id"), str(status, "preset_id"), seq, pl, entry, recent))
        }
    }

    private fun skippedText(n: Int): String? = when (n) {
        0 -> null
        1 -> "1 audio file couldn't be read and was left out."
        else -> "$n audio files couldn't be read and were left out."
    }

    private suspend fun closeEngine() {
        openFor = null
        openFolder = null
        withContext(Dispatchers.IO) { PhoneEngine.close() }
    }

    /** Lock screen Play after a stop. */
    internal fun resume(context: Context) = play(context)

    /** Stops playing (and getting a theme ready). */
    fun stop(context: Context?) {
        job?.cancel()
        job = null
        _state.update { it.copy(preparing = null) }
        if (CastController.casting) {
            CastController.stop()
            return
        }
        val app = context?.applicationContext ?: return
        if (PhonePlayerService.isRunning) {
            runCatching { app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP)) }
                .onFailure { Log.w(TAG, "Couldn't stop the player service", it) }
        }
    }

    /**
     * The theme [docId] was removed from the phone (or its folder can't be found): stops it if it's the
     * one chosen, lets go of it in the engine and forgets it.
     */
    fun forget(context: Context, docId: String) {
        if (_state.value.theme?.docId != docId) return
        reset(context)
    }

    /** Stops, closes the engine and forgets the chosen theme (e.g. the themes folder changed). */
    fun reset(context: Context) {
        stop(context)
        stepPoll?.cancel()
        val volume = _state.value.volume
        _state.value = State(volume = volume)
        prefs(context).edit { remove(KEY_THEME_DOC).remove(KEY_THEME_FOLDER) }
        openFor = null
        openFolder = null
        scope.launch(Dispatchers.IO) { PhoneEngine.close() }
    }

    /** Runs one of the theme's sequences (from its first preset); a preset pick stops it. */
    fun startSequence(sequenceId: String) {
        resetSequenceWatch()
        _state.update { it.copy(sequenceId = sequenceId, step = null, sequenceFinished = false) }
        if (openFor != null) scope.launch(Dispatchers.IO) {
            runCatching { PhoneEngine.startSequence(sequenceId) }.onFailure { e ->
                Log.w(TAG, "Couldn't start sequence $sequenceId", e)
                _state.update { it.copy(sequenceId = null, error = "Couldn't start that sequence") }
            }
            refreshStep()
        }
    }

    /** The running sequence's next step now. */
    fun nextStep() {
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.next() }; refreshStep() }
    }

    /** Whether the running sequence was seen running, and how often it wasn't since. */
    @Volatile private var seqSeen = false
    @Volatile private var seqMissing = 0

    private fun resetSequenceWatch() {
        seqSeen = false
        seqMissing = 0
    }

    private fun refreshStep() {
        if (_state.value.sequenceId == null) return
        val status = runCatching { PhoneEngine.sequenceStatus() }.getOrNull() ?: return
        if (status.running) {
            seqSeen = true
            seqMissing = 0
            _state.update { if (it.sequenceId == null) it.copy(step = null) else it.copy(step = status.step) }
            return
        }
        // A sequence that doesn't repeat came to its end (or never started): it stays on its last preset
        if (seqSeen || ++seqMissing >= 3) {
            _state.update {
                if (it.sequenceId == null) it
                else it.copy(sequenceId = null, step = null, sequenceFinished = true, presetId = status.presetId ?: it.presetId)
            }
        }
    }

    private var stepPoll: Job? = null

    /** While playing a sequence: its step and countdown, once a second. */
    private fun pollSteps(on: Boolean) {
        stepPoll?.cancel()
        if (!on) return
        stepPoll = scope.launch(Dispatchers.IO) {
            while (true) {
                refreshStep()
                refreshNow()
                delay(1_000)
            }
        }
    }

    /**
     * The lock screen's ⏭ / ⏮ (also headsets, cars, watches): the running sequence's next or previous
     * step, else the next or previous preset of the theme (Default settings included), round the list.
     */
    fun skip(forward: Boolean) {
        val s = _state.value
        if (s.sequenceId != null) {
            if (openFor != null) scope.launch(Dispatchers.IO) {
                runCatching { if (forward) PhoneEngine.next() else PhoneEngine.previous() }
                refreshStep()
            }
            return
        }
        val ids = listOf<String?>(null) + s.presets.map { it.id }
        if (ids.size < 2) return
        val i = ids.indexOf(s.presetId).coerceAtLeast(0)
        setPreset(ids[(i + (if (forward) 1 else -1) + ids.size) % ids.size])
    }

    /** The lock screen's "Next theme": the next theme on this phone, crossfading to it while it plays. */
    fun nextTheme(context: Context) {
        val app = context.applicationContext as SonoriumApp
        scope.launch {
            val themes = runCatching { app.themeStore.list() }.getOrDefault(emptyList())
            if (themes.size < 2) return@launch
            val i = themes.indexOfFirst { it.docId == _state.value.theme?.docId }
            choose(app, themes[(i + 1).mod(themes.size)])
        }
    }

    /** What the lock screen shows under the theme's name: the sequence step, else the preset. */
    fun subtitle(s: State = _state.value): String = when {
        s.sequenceId != null && s.step != null -> s.step.let { st -> "Step ${st.step} of ${st.steps} · ${st.name}" }
        s.sequenceId != null -> s.sequences.firstOrNull { it.id == s.sequenceId }?.name ?: "Sequence"
        s.presetId != null -> s.presets.firstOrNull { it.id == s.presetId }?.name ?: "On this phone"
        else -> "Default settings"
    }

    fun setPreset(presetId: String?) {
        _state.update { it.copy(presetId = presetId, sequenceId = null, step = null, sequenceFinished = false) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setPreset(presetId) } }
    }

    fun setVolume(context: Context, volume: Int) {
        _state.update { it.copy(volume = volume, muted = false) }
        prefs(context).edit { putInt(KEY_VOLUME, volume) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setVolume(volume / 100f) } }
    }

    /** A cast session started: the phone stops playing itself and its engine feeds the speaker. */
    fun castStarted(context: Context) {
        val app = context.applicationContext
        if (_state.value.playing && PhonePlayerService.isRunning) {
            runCatching { app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP)) }
        }
        play(context)
    }

    /** The cast ended: stopped, as when a TV cast ends. */
    internal fun onCastEnded() = onPlaying(false)

    /** The phone's own player (not casting): while casting, its stop doesn't mean the mix stopped. */
    internal fun onLocalPlaying(playing: Boolean) {
        if (!CastController.casting) onPlaying(playing)
    }

    internal fun onPlaying(playing: Boolean) {
        val was = _state.value.playing
        _state.update { if (playing) it.copy(playing = true, error = null) else it.copy(playing = false) }
        if (playing != was) pollSteps(playing)
    }

    internal fun onError(text: String) = _state.update { it.copy(error = text, playing = false) }

    private const val KEY_VOLUME = "volume"
    private const val KEY_PLAYLIST_MODE = "playlist_mode"
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_THEME_DOC = "theme_doc"
    private const val KEY_THEME_FOLDER = "theme_folder"
}
