package app.sonorium.cast

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.sonorium.engine.PhonePlayer
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Casting what the phone plays to one speaker, like casting to a TV (Chris, to-app/45-46): Google Cast
 * first. Picking a speaker starts a Cast session; the phone then serves its mix ([CastService]) and the
 * speaker plays that stream. Picking another speaker moves the cast there; Stop ends it.
 */
object CastController {
    data class Speaker(val id: String, val name: String, val description: String = "")

    data class State(
        /** Google Play services' Cast framework is there (not on every phone or emulator). */
        val available: Boolean = false,
        val speakers: List<Speaker> = emptyList(),
        /** The speaker being cast to (connecting or playing). */
        val speaker: Speaker? = null,
        val streaming: Boolean = false,
        /** Why casting stopped or couldn't start, in plain words (cleared when a cast starts). */
        val error: String? = null,
    )

    private const val TAG = "CastController"
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val casting: Boolean get() = _state.value.speaker != null

    private var app: Context? = null
    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var title = ""
    /** Moving the cast to another speaker: the old session's end is expected, not the end of casting. */
    private var switching = false
    /** Stopping now (the user asked): the session's end that follows is expected. */
    private var stopping = false

    /** Sets up Cast once (main thread); quietly unavailable without Google Play services. */
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val c = runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .onFailure { Log.i(TAG, "Cast isn't available", it) }.getOrNull() ?: return
        cast = c
        router = MediaRouter.getInstance(context.applicationContext)
        selector = c.mergedSelector
        @Suppress("UNCHECKED_CAST")
        c.sessionManager.addSessionManagerListener(sessions as SessionManagerListener<CastSession>, CastSession::class.java)
        _state.update { it.copy(available = selector != null) }
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private fun refresh() {
        val r = router ?: return
        val s = selector ?: return
        val list = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(s) }
            .map { Speaker(it.id, it.name, it.description.orEmpty()) }
        _state.update { it.copy(speakers = list) }
    }

    /** While the speaker picker is open: look for speakers. */
    fun discover(on: Boolean) {
        val r = router ?: return
        val s = selector ?: return
        if (on) {
            r.addCallback(s, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refresh()
        } else {
            r.removeCallback(routes)
        }
    }

    /** Casts to [speaker] (moving the cast there if it was on another one). */
    fun castTo(speaker: Speaker, themeName: String) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == speaker.id }
        if (route == null) {
            _state.update { it.copy(error = "${speaker.name} isn't available any more") }
            return
        }
        if (_state.value.speaker?.id == speaker.id) return
        title = themeName
        switching = _state.value.speaker != null
        _state.update { it.copy(speaker = speaker, streaming = false, error = null) }
        r.selectRoute(route)
    }

    /** The user stops casting (the speaker stops; the phone stays stopped, as with a TV). */
    fun stop() {
        if (stopping) return
        stopping = true
        try {
            switching = false
            runCatching { cast?.sessionManager?.endCurrentSession(true) }.onFailure { Log.w(TAG, "endCurrentSession failed", it) }
            stopService()
            ended(null)
        } finally {
            stopping = false
        }
    }

    /** "Stop casting" in the cast notification: as [stop], the service stops itself. */
    internal fun stopFromNotification() {
        if (stopping) return
        stopping = true
        try {
            switching = false
            runCatching { cast?.sessionManager?.endCurrentSession(true) }.onFailure { Log.w(TAG, "endCurrentSession failed", it) }
            ended(null)
        } finally {
            stopping = false
        }
    }

    /** CastService went away (stopped, or ended by the system): casting can't go on without it. */
    internal fun onServiceStopped() {
        if (stopping || switching || !_state.value.streaming) return
        Log.i(TAG, "The cast stream stopped")
        runCatching { cast?.sessionManager?.endCurrentSession(true) }
        ended(_state.value.speaker?.let { "Casting to ${it.name} ended" })
    }

    /** CastService couldn't open the stream. */
    internal fun onStreamFailed() {
        stop()
        _state.update { it.copy(error = "Couldn't start casting. Open Sonorium and try again.") }
    }

    private fun stopService() {
        val a = app ?: return
        runCatching { a.stopService(Intent(a, CastService::class.java)) }.onFailure { Log.w(TAG, "Couldn't stop the cast service", it) }
    }

    private fun ended(message: String?) {
        val was = _state.value.speaker != null
        _state.update { it.copy(speaker = null, streaming = false, error = message) }
        if (was) PhonePlayer.onCastEnded()
    }

    /** The phone player's engine is ready: serve its mix to the speaker. */
    fun startStream(themeName: String) {
        val a = app ?: return
        title = themeName
        val address = CastAddress.of(a)
        if (address == null) {
            Log.i(TAG, "No Wi-Fi or Ethernet address to cast from")
            stop()
            _state.update { it.copy(error = "Connect this device to a network to cast") }
            return
        }
        val started = runCatching {
            a.startForegroundService(
                Intent(a, CastService::class.java)
                    .putExtra(CastService.EXTRA_TITLE, themeName)
                    .putExtra(CastService.EXTRA_SPEAKER, _state.value.speaker?.name.orEmpty())
                    .putExtra(CastService.EXTRA_ADDRESS, address),
            )
        }
        started.exceptionOrNull()?.let { e ->
            Log.w(TAG, "Couldn't start the cast service", e)
            stop()
            _state.update { it.copy(error = "Couldn't start casting. Open Sonorium and try again.") }
        }
    }

    /** CastService is serving on [port] at [address]: load the stream on the speaker. */
    internal fun onServing(port: Int, address: String) {
        val session = cast?.sessionManager?.currentCastSession ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title.ifEmpty { "Sonorium" })
            putString(MediaMetadata.KEY_ARTIST, "Sonorium")
        }
        val info = MediaInfo.Builder("http://$address:$port${StreamServer.PATH}")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/aac")
            .setMetadata(meta)
            .build()
        val client = session.remoteMediaClient
        if (client == null) {
            Log.w(TAG, "No media client on the cast session")
            _state.update { it.copy(error = "${it.speaker?.name ?: "The speaker"} didn't accept the stream. Try casting again.") }
            return
        }
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        _state.update { it.copy(streaming = true, error = null) }
        PhonePlayer.onPlaying(true)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = begin(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = begin(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session failed to start ($error)")
            switching = false
            val name = _state.value.speaker?.name ?: "the speaker"
            stopService()
            ended("Couldn't connect to $name. Make sure it's powered on and on the same network as this device.")
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            // Moving to another speaker ends the old session first: the new one carries on
            if (switching || stopping) return
            Log.i(TAG, "Cast session ended ($error)")
            val name = _state.value.speaker?.name
            stopService()
            ended(name?.let { "Casting to $it ended" })
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session failed to resume ($error)")
            switching = false
            stopService()
            ended(null)
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun begin(session: CastSession) {
        val a = app ?: return
        switching = false
        val device = session.castDevice
        if (device != null && _state.value.speaker?.name != device.friendlyName && _state.value.speaker == null) {
            _state.update { it.copy(speaker = Speaker(device.deviceId, device.friendlyName)) }
        }
        _state.update { it.copy(streaming = false, error = null) }
        // The phone stops playing itself; its engine now feeds the speaker
        PhonePlayer.castStarted(a)
    }
}
