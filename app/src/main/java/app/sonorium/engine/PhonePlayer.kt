package app.sonorium.engine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import app.sonorium.SonoriumApp
import app.sonorium.cast.CastController
import app.sonorium.local.LocalTheme
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
        val volume = prefs(context).getInt(KEY_VOLUME, 80).coerceIn(0, 100)
        _state.update { it.copy(volume = volume) }
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
            val text = runCatching { app.themeStore.readFile(theme, "presets.json") }.getOrNull().orEmpty()
            val presets = PhoneEngine.presetsFromJson(text)
            val sequences = PhoneEngine.sequencesFromJson(text)
            if (_state.value.theme?.docId == theme.docId && _state.value.presets.isEmpty()) {
                _state.update { it.copy(presets = presets, sequences = sequences, presetId = it.presetId ?: presets.firstOrNull { p -> p.isDefault }?.id) }
            }
        }
        if (switching) play(context)
    }

    /** Play: prepares the theme the first time (decoding its audio once), opens the engine, starts playback. */
    fun play(context: Context) {
        restore(context)
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
                    val s = _state.value
                    val presets = withContext(Dispatchers.IO) {
                        // Already playing another theme: crossfade to this one; else open the engine on it
                        if (PhoneEngine.isOpen && openFor != null) PhoneEngine.setTheme(prepared, s.presetId, s.sequenceId).also { PhoneEngine.setVolume(s.volume / 100f) }
                        else PhoneEngine.open(app, prepared, s.presetId, s.volume / 100f, s.sequenceId)
                    }
                    val sequences = withContext(Dispatchers.IO) { PhoneEngine.sequences() }
                    openFor = theme.version
                    openFolder = theme.folder
                    resetSequenceWatch()
                    _state.update { st ->
                        st.copy(
                            presets = presets, sequences = sequences, presetId = st.presetId ?: presets.firstOrNull { it.isDefault }?.id,
                            preparing = null, notice = skippedText(prepared.skipped.size),
                        )
                    }
                }
                ensureActive()
                when {
                    // Casting: the speaker plays the engine's stream (already flowing after a theme change)
                    CastController.casting ->
                        if (!CastController.state.value.streaming) CastController.startStream(theme.name)
                    !_state.value.playing -> {
                        val started = runCatching {
                            app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, theme.name))
                        }
                        started.exceptionOrNull()?.let { e ->
                            Log.w(TAG, "Couldn't start the player service", e)
                            _state.update { it.copy(error = "Couldn't start playing. Open Sonorium and press Play again.") }
                        }
                    }
                    else -> PhonePlayerService.retitle(theme.name)
                }
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
        _state.update { it.copy(volume = volume) }
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
    private const val KEY_THEME_DOC = "theme_doc"
    private const val KEY_THEME_FOLDER = "theme_folder"
}
