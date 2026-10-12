package app.sonorium.engine

import android.content.Context
import android.content.Intent
import app.sonorium.SonoriumApp
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

    /** Picks a theme (and its default preset); opening happens on Play. */
    fun choose(theme: LocalTheme) {
        if (_state.value.theme?.docId == theme.docId) return
        stop(null)
        openFor = null
        _state.update { it.copy(theme = theme, presets = emptyList(), presetId = null, error = null) }
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
                    val presets = withContext(Dispatchers.IO) { PhoneEngine.open(app, prepared, s.presetId, s.volume / 100f) }
                    openFor = theme.docId
                    _state.update { st -> st.copy(presets = presets, presetId = st.presetId ?: presets.firstOrNull { it.isDefault }?.id, preparing = null) }
                }
                app.startService(Intent(app, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, theme.name))
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
        context?.applicationContext?.let { it.startService(Intent(it, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP)) }
    }

    fun setPreset(presetId: String?) {
        _state.update { it.copy(presetId = presetId) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setPreset(presetId) } }
    }

    fun setVolume(volume: Int) {
        _state.update { it.copy(volume = volume) }
        if (openFor != null) scope.launch(Dispatchers.IO) { runCatching { PhoneEngine.setVolume(volume / 100f) } }
    }

    internal fun onPlaying(playing: Boolean) = _state.update { it.copy(playing = playing) }

    internal fun onError(text: String) = _state.update { it.copy(error = text, playing = false) }
}
