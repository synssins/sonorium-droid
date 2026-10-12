package app.sonorium.cast

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.sonorium.engine.PhonePlayer
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.net.Inet4Address
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
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val casting: Boolean get() = _state.value.speaker != null

    private var app: Context? = null
    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var title = ""

    /** Sets up Cast once (main thread); quietly unavailable without Google Play services. */
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val c = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull() ?: return
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
        val route = r.routes.firstOrNull { it.id == speaker.id } ?: return
        title = themeName
        _state.update { it.copy(speaker = speaker, streaming = false, error = null) }
        r.selectRoute(route)
    }

    /** Stops casting (the speaker stops; the phone stays stopped, as with a TV). */
    fun stop() {
        val a = app ?: return
        runCatching { cast?.sessionManager?.endCurrentSession(true) }
        a.startService(Intent(a, CastService::class.java).setAction(CastService.ACTION_STOP))
        ended()
    }

    private fun ended() {
        val was = _state.value.speaker != null
        _state.update { it.copy(speaker = null, streaming = false) }
        if (was) PhonePlayer.onCastEnded()
    }

    /** The phone player's engine is ready: serve its mix to the speaker. */
    fun startStream(themeName: String) {
        val a = app ?: return
        title = themeName
        val address = wifiAddress(a)
        if (address == null) {
            _state.update { it.copy(error = "Connect this phone to Wi-Fi to cast") }
            stop()
            return
        }
        a.startForegroundService(
            Intent(a, CastService::class.java)
                .putExtra(CastService.EXTRA_TITLE, themeName)
                .putExtra(CastService.EXTRA_SPEAKER, _state.value.speaker?.name.orEmpty())
                .putExtra(CastService.EXTRA_ADDRESS, address),
        )
    }

    /** CastService is serving on [port] (null: stopped): load the stream on the speaker. */
    internal fun onServing(port: Int?) {
        val a = app ?: return
        if (port == null) return
        val session = cast?.sessionManager?.currentCastSession ?: return
        val address = wifiAddress(a) ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title.ifEmpty { "Sonorium" })
            putString(MediaMetadata.KEY_ARTIST, "Sonorium")
        }
        val info = MediaInfo.Builder("http://$address:$port${StreamServer.PATH}")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/aac")
            .setMetadata(meta)
            .build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        _state.update { it.copy(streaming = true) }
        PhonePlayer.onPlaying(true)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = begin(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = begin(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.update { it.copy(error = "Couldn't connect to ${it.speaker?.name ?: "the speaker"}") }
            ended()
        }
        override fun onSessionEnded(session: CastSession, error: Int) = stop()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) = ended()
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun begin(session: CastSession) {
        val a = app ?: return
        val device = session.castDevice
        if (_state.value.speaker == null && device != null) {
            _state.update { it.copy(speaker = Speaker(device.deviceId, device.friendlyName)) }
        }
        // The phone stops playing itself; its engine now feeds the speaker
        PhonePlayer.castStarted(a)
    }

    /** The phone's IPv4 address on its current network (the speaker fetches the stream there). */
    fun wifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}
