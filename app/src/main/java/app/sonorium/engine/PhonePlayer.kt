package app.sonorium.engine

import android.content.Context
import android.content.Intent
import app.sonorium.SonoriumApp
import app.sonorium.cast.CastController
import app.sonorium.local.LocalTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        val volume: Int = 80,
        val playing: Boolean = false,
        /** Getting the theme ready: files done of total (null when not preparing). */
        val preparing: Pair<Int, Int>? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    /** The engine is open on [State.theme] (prepared). */
    private var openFor: String? = null

    /**
     * Picks a theme. Its presets show at once (read from its presets.json). While playing, the engine
     * crossfades to it (after getting it ready the first time); when stopped, the next Play uses it.
     */
    fun choose(context: Context, theme: LocalTheme) {
        if (_state.value.theme?.docId == theme.docId) return
        val app = context.applicationContext as SonoriumApp
        val switching = _state.value.playing
        _state.update { it.copy(theme = theme, presets = emptyList(), presetId = null, sequences = emptyList(), sequenceId = null, step = null, error = null) }
        scope.launch {
            val text = app.themeStore.readFile(theme, "presets.json").orEmpty()
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
        val theme = _state.value.theme ?: return
        val app = context.applicationContext as SonoriumApp
        job?.cancel()
        job = scope.launch {
            try {
                if (openFor != theme.docId) {
                    _state.update { it.copy(preparing = 0 to theme.tracks, error = null) }
                    val prepared = ThemePrep.prepare(app, app.themeStore, theme) { done, total -> _state.update { it.copy(preparing = done to total) } }
                    val s = _state.value
                    val presets = withContext(Dispatchers.IO) {
                        // Already playing another theme: crossfade to this one; else open the engine on it
                        if (PhoneEngine.isOpen && openFor != null) PhoneEngine.setTheme(prepared, s.presetId, s.sequenceId).also { PhoneEngine.setVolume(s.volume / 100f) }
                        else PhoneEngine.open(app, prepared, s.presetId, s.volume / 100f, s.sequenceId)
                    }
                    val sequences = withContext(Dispatchers.IO) { PhoneEngine.sequences() }
                    openFor = theme.docId
                    _state.update { st -> st.copy(presets = presets, sequences = sequences, presetId = st.presetId ?: presets.firstOrNull { it.isDefault }?.id, preparing = null) }
                }
                when {
                    // Casting: the speaker plays the engine's stream (already flowing after a theme change)
                    CastController.casting ->
                        if (!CastController.state.value.streaming) CastController.startStream(theme.name)
                    !_state.value.playing ->
                        app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, theme.name))
                    else -> PhonePlayerService.retitle(theme.name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(preparing = null, error = e.message ?: "Couldn't play “${theme.name}”") }
            }
        }
    }

    /** Lock screen Play after a stop. */
    internal fun resume(context: Context) = play(context)

    fun stop(context: Context?) {
        job?.cancel()
        _state.update { it.copy(preparing = null) }
        if (CastController.casting) {
            CastController.stop()
            return
        }
        context?.applicationContext?.let { it.startService(Intent(it, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP)) }
    }

    /** Runs one of the theme's sequences (from its first preset); a preset pick stops it. */
    fun startSequence(sequenceId: String) {
        _state.update { it.copy(sequenceId = sequenceId) }
        if (openFor != null) scope.launch(Dispatchers.IO) {
            runCatching { PhoneEngine.startSequence(sequenceId) }.onFailure { e -> _state.update { it.copy(sequenceId = null, error = e.message) } }
            refreshStep()
        }
    }

    /** The running sequence's next step now. */
    fun nextStep() {
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.next() }; refreshStep() }
    }

    private fun refreshStep() {
        val step = runCatching { PhoneEngine.step() }.getOrNull()
        _state.update { it.copy(step = if (it.sequenceId == null) null else step) }
    }

    private var stepPoll: Job? = null

    /** While playing a sequence: its step and countdown, once a second. */
    private fun pollSteps(on: Boolean) {
        stepPoll?.cancel()
        if (!on) return
        stepPoll = scope.launch(Dispatchers.IO) {
            while (true) {
                if (_state.value.sequenceId != null) refreshStep()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun setPreset(presetId: String?) {
        _state.update { it.copy(presetId = presetId, sequenceId = null, step = null) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setPreset(presetId) } }
    }

    fun setVolume(volume: Int) {
        _state.update { it.copy(volume = volume) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setVolume(volume / 100f) } }
    }

    /** A cast session started: the phone stops playing itself and its engine feeds the speaker. */
    fun castStarted(context: Context) {
        val app = context.applicationContext
        if (_state.value.playing) app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP))
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
        _state.update { it.copy(playing = playing) }
        if (playing != was) pollSteps(playing)
    }

    internal fun onError(text: String) = _state.update { it.copy(error = text, playing = false) }
}
