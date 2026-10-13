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
        /** The theme being got ready, when it isn't [theme] (a playlist's). */
        val preparingName: String? = null,
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
        /** Today's free listening to the sample is used up (it plays again tomorrow, or with Pro). */
        val sampleEnded: Boolean = false,
        /** Sleep timer: when it ends (elapsed-realtime ms), null without one. */
        val sleepAt: Long? = null,
        /** A theme on this device being got ready in the background (it just arrived): name, files done, files. */
        val readying: Triple<String, Int, Int>? = null,
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
        appContext = context.applicationContext
        if (restored) return
        restored = true
        // The themes got ready before (their versions), so a restart doesn't get them ready again
        prefs(context).all.forEach { (k, v) -> if (k.startsWith(KEY_READY) && v is String) ready[k.removePrefix(KEY_READY)] = v }
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
        // The choice kept for this theme shows at once; it's checked against the theme's lists once they're read
        val keptNow = prefs(context).let { p -> p.getString(KEY_CHOICE + theme.docId, null) ?: p.getString(KEY_CHOICE + theme.folder, null) }
        _state.update {
            it.copy(
                theme = theme, presets = emptyList(), sequences = emptyList(), step = null, sequenceFinished = false, error = null, notice = null,
                presetId = keptNow?.takeIf { k -> k.isNotEmpty() && !k.startsWith("seq:") },
                sequenceId = keptNow?.takeIf { k -> k.startsWith("seq:") }?.removePrefix("seq:"),
            )
        }
        scope.launch {
            // Read again a few times when the theme's files can't be read yet (right after a start)
            var lists = readLists(app, theme)
            var tries = 0
            while ((lists == null || lists.first.isEmpty() && lists.second.isEmpty()) && tries++ < 5) {
                delay(400)
                lists = readLists(app, theme)
            }
            val (presets, sequences) = lists ?: (emptyList<PhoneEngine.Preset>() to emptyList())
            if (_state.value.theme?.docId == theme.docId && _state.value.presets.isEmpty()) {
                // The preset or sequence chosen last for this theme (kept like the volume), else its default
                val kept = prefs(app).getString(KEY_CHOICE + theme.docId, null) ?: prefs(app).getString(KEY_CHOICE + theme.folder, null)
                val keptSeq = kept?.removePrefix("seq:")?.takeIf { kept.startsWith("seq:") && sequences.any { q -> q.id == it } }
                val keptPreset = kept?.takeIf { !it.startsWith("seq:") && (it.isEmpty() || presets.any { p -> p.id == it }) }
                Log.println(Log.WARN, TAG, "Kept choice for ${theme.folder}: ${kept ?: "none"} (${presets.size} presets, ${sequences.size} sequences)")
                _state.update {
                    // A choice no longer there (preset deleted meanwhile) gives way to the kept one or the default
                    val seq = it.sequenceId?.takeIf { id -> sequences.any { q -> q.id == id } } ?: keptSeq
                    val preset = it.presetId?.takeIf { id -> presets.any { p -> p.id == id } }
                    it.copy(
                        presets = presets, sequences = sequences, sequenceId = seq,
                        presetId = preset ?: keptPreset?.ifEmpty { null } ?: if (keptPreset == null && seq == null) presets.firstOrNull { p -> p.isDefault }?.id else null,
                    )
                }
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

    // ---------- The free sample's daily time ----------

    /** How long the sample plays per day without Pro (counted only while it plays; a new day starts at local midnight). */
    const val SAMPLE_SECONDS_PER_DAY = 30 * 60

    private fun isPro(): Boolean = (appContext as? SonoriumApp)?.entitlement?.isPro?.value == true

    /** Seconds of the sample played today (and the day they're for). */
    private fun sampleUsed(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_SAMPLE_DAY, null) == java.time.LocalDate.now().toString()) p.getInt(KEY_SAMPLE_SECONDS, 0) else 0
    }

    /** Without Pro, playing the sample: today's time used up. */
    private fun sampleUsedUp(context: Context): Boolean =
        !isPro() && _state.value.theme?.sample == true && sampleUsed(context) >= SAMPLE_SECONDS_PER_DAY

    /** Once a second while playing: counts the sample's time; at the day's end of it, the mix fades out and stops. */
    private suspend fun countSample() {
        val ctx = appContext ?: return
        val s = _state.value
        if (isPro() || !s.playing || s.theme?.sample != true || s.sampleEnded) return
        val used = sampleUsed(ctx) + 1
        prefs(ctx).edit { putString(KEY_SAMPLE_DAY, java.time.LocalDate.now().toString()).putInt(KEY_SAMPLE_SECONDS, used) }
        if (used < SAMPLE_SECONDS_PER_DAY) return
        // The normal stop: a fade over the crossfade time, then stopped; the screen says why
        val fade = app.sonorium.local.DeviceSettings.get(ctx).crossfade.coerceIn(1.0, 10.0)
        _state.update { it.copy(sampleEnded = true) }
        runCatching { PhoneEngine.fadeOut(fade) }
        delay((fade * 1000).toLong())
        withContext(Dispatchers.Main) { stop(ctx) }
    }

    // ---------- Sleep timer ----------

    /** Sleep timer: the playing mix fades out and stops after [minutes] (0 < minutes <= 240); false when it isn't playing. */
    fun setSleep(minutes: Double): Boolean {
        if (!_state.value.playing) return false
        _state.update { it.copy(sleepAt = android.os.SystemClock.elapsedRealtime() + (minutes * 60_000).toLong()) }
        return true
    }

    fun cancelSleep(): Boolean {
        val had = _state.value.sleepAt != null
        _state.update { it.copy(sleepAt = null) }
        return had
    }

    /** Seconds left on the sleep timer, or null. */
    fun sleepSecondsLeft(): Int? = _state.value.sleepAt?.let { ((it - android.os.SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0) }

    /** Once a second while playing: at the timer's end, the normal stop (a fade over the crossfade time). */
    private suspend fun checkSleep() {
        val at = _state.value.sleepAt ?: return
        if (android.os.SystemClock.elapsedRealtime() < at) return
        val ctx = appContext ?: return
        _state.update { it.copy(sleepAt = null) }
        val fade = app.sonorium.local.DeviceSettings.get(ctx).crossfade.coerceIn(1.0, 10.0)
        runCatching { PhoneEngine.fadeOut(fade) }
        delay((fade * 1000).toLong())
        withContext(Dispatchers.Main) { stop(ctx) }
    }

    /** The sample ended message goes (Pro unlocked, or another day). */
    fun clearSampleEnded() = _state.update { it.copy(sampleEnded = false) }

    /** Play: prepares the theme the first time (decoding its audio once), opens the engine, starts playback. */
    fun play(context: Context) {
        restore(context)
        if (sampleUsedUp(context)) {
            _state.update { it.copy(sampleEnded = true) }
            return
        }
        if (_state.value.sampleEnded) _state.update { it.copy(sampleEnded = false) }
        if (_state.value.playlistMode) {
            _state.value.playlistId?.let { playPlaylist(context, it) }
            return
        }
        val theme = _state.value.theme ?: return
        val app = context.applicationContext as SonoriumApp
        job?.cancel()
        laterPrep?.cancel()
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                if (openFor != theme.version) {
                    // This theme's files changed since the engine opened it: let go of them before they're rebuilt
                    if (openFolder == theme.folder) closeEngine()
                    // Got ready when it arrived: straight to playing; else (or changed since) now, with progress
                    val prepared = if (isReady(app, theme)) ThemePrep.prepared(app, theme.folder) else {
                        _state.update { it.copy(preparing = 0 to theme.tracks, preparingName = null, error = null, notice = null) }
                        ThemePrep.prepare(app, app.themeStore, theme) { done, total ->
                            if (self?.isActive == true) _state.update { if (it.preparing != null) it.copy(preparing = done to total) else it }
                        }
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
                    markReady(app, theme)
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
                if (job === self) _state.update { if (it.preparing != null) it.copy(preparing = null, preparingName = null) else it }
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

    /** [theme] ready for an engine (its prepared copy and decoded audio), getting it ready only when needed. */
    suspend fun ensurePrepared(app: SonoriumApp, theme: LocalTheme): ThemePrep.Prepared {
        if (ready[theme.folder] == theme.version || (openFolder == theme.folder && openFor == theme.version)) {
            return ThemePrep.prepared(app, theme.folder)
        }
        val prepared = ThemePrep.prepare(app, app.themeStore, theme) { _, _ -> }
        ready[theme.folder] = theme.version
        prepareLinked(app, theme)
        return prepared
    }

    /** The themes [theme] links files from, got ready too (their decoded audio plays the linked tracks). */
    private suspend fun prepareLinked(app: SonoriumApp, theme: LocalTheme) {
        val meta = runCatching { app.themeStore.readFile(theme, "metadata.json") }.getOrNull() ?: return
        // Source theme id → the tracks of it that are linked
        val wanted = runCatching {
            val links = org.json.JSONObject(meta).optJSONObject("links") ?: return
            links.keys().asSequence().mapNotNull { k -> links.optJSONObject(k) }
                .mapNotNull { l -> l.optString("theme").ifEmpty { null }?.let { it to l.optString("track") } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        }.getOrNull().orEmpty()
        if (wanted.isEmpty()) return
        val themes = withContext(Dispatchers.IO) { runCatching { app.themeStore.list() }.getOrDefault(emptyList()) }
        for (t in themes) {
            val tracks = wanted[t.metaId] ?: continue
            if (t.docId == theme.docId || ready[t.folder] == t.version || openFolder == t.folder) continue
            // Only the linked files are decoded (the rest of that theme when it plays itself)
            runCatching { ThemePrep.prepare(app, app.themeStore, t, only = tracks) { _, _ -> } }
                .onFailure { Log.w(TAG, "Couldn't get ${t.folder}'s linked files ready", it) }
        }
    }

    /** A playlist's later themes being got ready while it plays. */
    private var laterPrep: Job? = null

    /** Themes that arrived on this device (download, import, starter themes, the sample), got ready one at a time. */
    private var arrivals: Job? = null

    /**
     * Gets every theme in [themes] that isn't ready yet ready in the background, one at a time, so a theme is
     * never first decoded when it's played. The theme the engine has open is left as it is.
     */
    fun prepareInBackground(context: Context, themes: List<LocalTheme>) {
        val app = context.applicationContext as SonoriumApp
        restore(app)
        val p = prefs(app)
        // A theme whose space was freed on purpose waits for its next Play
        val todo = themes.filter { !isReady(app, it) && it.tracks > 0 && it.folder != openFolder && p.getString(KEY_FREED + it.folder, null) != it.version }
        if (todo.isEmpty() || arrivals?.isActive == true) return
        arrivals = scope.launch {
            try {
                for (t in todo) {
                    if (isReady(app, t) || t.folder == openFolder) continue
                    _state.update { it.copy(readying = Triple(t.name, 0, t.tracks)) }
                    runCatching {
                        ThemePrep.prepare(app, app.themeStore, t) { done, total -> _state.update { it.copy(readying = Triple(t.name, done, total)) } }
                    }.onSuccess { markReady(app, t) }
                        .onFailure { e -> if (e is CancellationException) throw e; Log.w(TAG, "Couldn't get ${t.folder} ready", e) }
                }
            } finally {
                _state.update { it.copy(readying = null) }
            }
        }
    }

    /** [theme] was got ready at its current version and its prepared copy is still there. */
    private fun isReady(context: Context, theme: LocalTheme): Boolean =
        ready[theme.folder] == theme.version && ThemePrep.prepared(context, theme.folder).let { it.themeDir.isDirectory && it.pcmDir.isDirectory }

    /** [theme]'s prepared copy is current (kept across restarts, so nothing is got ready twice). */
    private fun markReady(context: Context, theme: LocalTheme) {
        ready[theme.folder] = theme.version
        prefs(context).edit { putString(KEY_READY + theme.folder, theme.version).remove(KEY_FREED + theme.folder) }
    }

    /** The theme's ready-to-play copy was deleted to free space: not made again in the background (only when it plays). */
    fun cacheFreed(context: Context, theme: LocalTheme) {
        ready.remove(theme.folder)
        prefs(context).edit { remove(KEY_READY + theme.folder).putString(KEY_FREED + theme.folder, theme.version) }
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
        // The sequence playing before stops at once (the playlist's first entry follows when it's ready)
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.stopSequence() } }
        _state.update { it.copy(sequenceId = null, step = null, sequenceFinished = false, now = it.now.copy(sequence = null)) }
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                val themes = withContext(Dispatchers.IO) { app.themeStore.list() }
                val inIt = playlist.entries.mapNotNull { e -> themes.firstOrNull { it.metaId == e.themeId } }.distinctBy { it.docId }
                if (inIt.isEmpty()) {
                    _state.update { it.copy(error = "None of this playlist's themes are on this phone.") }
                    return@launch
                }
                laterPrep?.cancel()
                val inEngine = _state.value.now.themeId
                // A theme the engine has open keeps its files while it plays (even updated meanwhile: the next
                // Play of it gets it ready)
                fun isReady(t: LocalTheme) = ready[t.folder] == t.version || openFolder == t.folder ||
                    (t.metaId != null && t.metaId == inEngine) || ready.containsKey(t.folder)
                // The first entry's theme now (all of them for a shuffled playlist); the others after it starts
                val first = themes.firstOrNull { it.metaId == playlist.entries.firstOrNull()?.themeId } ?: inIt.first()
                val now = if (playlist.order == "shuffle") inIt else listOf(first)
                val later = inIt.filter { t -> now.none { it.docId == t.docId } && !isReady(t) }
                val total = now.filter { !isReady(it) }.sumOf { it.tracks }
                var done = 0
                for (t in now) {
                    if (isReady(t)) continue
                    val before = done
                    _state.update { it.copy(preparing = before to total, preparingName = t.name, error = null, notice = null) }
                    ThemePrep.prepare(app, app.themeStore, t) { d, _ ->
                        if (self?.isActive == true) _state.update { if (it.preparing != null) it.copy(preparing = (before + d) to total) else it }
                    }
                    done = before + t.tracks
                    ready[t.folder] = t.version
                    ensureActive()
                }
                // The later entries' themes are listed (settings and stand-ins) so the playlist knows them from the start
                for (t in later) runCatching { PhoneThemeApi.openApi(app, app.themeStore, t) }
                val json = SonoriumApi.json.encodeToString(Playlist.serializer(), playlist)
                withContext(Dispatchers.IO) {
                    if (!PhoneEngine.isOpen) PhoneEngine.open(app, ThemePrep.prepared(app, first.folder), null, effectiveVolume())
                    PhoneEngine.startPlaylist(json, PhoneThemeApi.themesRoot(app), PhoneThemeApi.pcmRoot(app))
                }
                // The engine plays the playlist's themes now: a theme picked later is crossfaded to
                openFor = "playlist:${playlist.id}"
                openFolder = null
                _state.update { it.copy(preparing = null, preparingName = null, sequenceId = null, step = null, sequenceFinished = false) }
                refreshNow()
                startOutput(app, _state.value.now.playlist?.themeName ?: first.name)
                // Their audio while the first entry plays (one at a time; entries change after minutes)
                if (later.isNotEmpty()) laterPrep = scope.launch {
                    for (t in later) {
                        runCatching { ThemePrep.prepare(app, app.themeStore, t) { _, _ -> } }
                            .onSuccess { ready[t.folder] = t.version }
                            .onFailure { e -> if (e is CancellationException) throw e; Log.w(TAG, "Couldn't get ${t.folder} ready for the playlist", e) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ThemePrep.Problem) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't play playlist ${playlist.id}", e)
                _state.update { it.copy(error = "Couldn't play “${playlist.name}”. Try again.") }
            } finally {
                if (job === self) _state.update { if (it.preparing != null) it.copy(preparing = null, preparingName = null) else it }
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
        // A step or entry with no end (the last of a pass, "never") has no countdown
        fun at(secondsLeft: Double?): String? = secondsLeft?.takeIf { it.isFinite() && it >= 0 && it < 365.0 * 24 * 3600 }
            ?.let { java.time.Instant.ofEpochMilli(now + (it * 1000).toLong()).toString() }
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
        // Stopped any way: the sleep timer goes with it (as on a server)
        _state.update { it.copy(sleepAt = null) }
        laterPrep?.cancel()
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
        keepChoice("seq:$sequenceId")
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
                runCatching { countSample() }.onFailure { Log.w(TAG, "Couldn't count the sample's time", it) }
                runCatching { checkSleep() }.onFailure { Log.w(TAG, "Couldn't check the sleep timer", it) }
                // One bad status never stops the step line, the card and the notification from following
                runCatching { refreshStep() }.onFailure { Log.w(TAG, "Couldn't read the sequence's step", it) }
                runCatching { refreshNow() }.onFailure { Log.w(TAG, "Couldn't read the engine's status", it) }
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

    /** What the lock screen shows as the title: the playlist entry's theme while a playlist plays, else the theme. */
    fun title(s: State = _state.value): String? =
        (if (s.playlistMode) s.now.playlist?.themeName else null)?.takeIf { it.isNotBlank() } ?: s.theme?.name?.takeIf { it.isNotBlank() }

    /** What the lock screen shows under the theme's name: the playlist entry, the sequence step, else the preset. */
    fun subtitle(s: State = _state.value): String = when {
        s.playlistMode && s.now.playlist?.entries != null && s.now.playlist.entries > 0 && s.now.playlist.id == s.playlistId ->
            "Entry ${(s.now.playlist.position ?: 0) + 1} of ${s.now.playlist.entries}"
        // A playlist picked, its themes getting ready: its first entry, not the preset playing before
        s.playlistMode && s.playlistId != null ->
            PhonePlaylists.playlists.value.firstOrNull { it.id == s.playlistId }?.let { "Entry 1 of ${it.entries.size}" } ?: "Default settings"
        s.sequenceId != null && s.step != null -> s.step.let { st -> "Step ${st.step} of ${st.steps} · ${st.name}" }
        s.sequenceId != null -> s.sequences.firstOrNull { it.id == s.sequenceId }?.name ?: "Sequence"
        s.presetId != null -> s.presets.firstOrNull { it.id == s.presetId }?.name ?: "On this phone"
        else -> "Default settings"
    }

    fun setPreset(presetId: String?) {
        _state.update { it.copy(presetId = presetId, sequenceId = null, step = null, sequenceFinished = false) }
        keepChoice(presetId.orEmpty())
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
    private const val KEY_SAMPLE_DAY = "sample_day"
    private const val KEY_READY = "ready:"
    private const val KEY_FREED = "freed:"
    private const val KEY_SAMPLE_SECONDS = "sample_seconds"
    private const val KEY_CHOICE = "choice:"
    @Volatile private var appContext: Context? = null

    /** The preset ("" = default settings) or "seq:<id>" chosen for the current theme, kept for the next start. */
    private fun keepChoice(choice: String) {
        val ctx = appContext ?: return
        val theme = _state.value.theme ?: return
        // By document and by folder name (a folder replaced by an update gets a new document)
        prefs(ctx).edit { putString(KEY_CHOICE + theme.docId, choice).putString(KEY_CHOICE + theme.folder, choice) }
    }
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_THEME_DOC = "theme_doc"
    private const val KEY_THEME_FOLDER = "theme_folder"
}
