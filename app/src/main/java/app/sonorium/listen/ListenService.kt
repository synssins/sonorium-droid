package app.sonorium.listen

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.sonorium.MainActivity
import app.sonorium.SonoriumApp
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listen here, played natively (APP_API.md 9.3): the channel's live MP3 stream
 * in ExoPlayer, in the background, with the lock screen and notification
 * controls of a media session.
 *
 * Pausing (lock screen, headphones) stops listening: the stream is live, so the
 * app tells the server it left, and Play asks the server for the channel again.
 * When the stream drops, the app asks again and reconnects.
 */
@OptIn(UnstableApi::class)
class ListenService : MediaSessionService() {
    private data class Current(
        val serverId: String,
        val sessionId: String,
        val url: String,
        val name: String,
        val themeName: String,
        val volume: Int,
        /** The account's personal channel: re-listen and stop through /api/me/channel/listen. */
        val personal: Boolean = false,
    ) {
        /** `http://host:port`, for a new slot's stream path. */
        val streamBase: String get() = url.substringBefore("/stream/")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private var current: Current? = null
    private var pausedByUser = false
    private var reconnect: Job? = null
    private var failures = 0

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // A few seconds in hand rides out Wi-Fi blips on a live stream.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(6_000, 15_000, 2_000, 4_000).build())
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) failures = 0
                publish()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A zero-byte answer or a closed stream: the channel's mix stopped.
                if (state == Player.STATE_ENDED && current != null && !pausedByUser) reconnectSoon()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Headphones unplugged or audio focus lost for good: same as Pause.
                if (!playWhenReady && current != null && !pausedByUser) stopByUser()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (current != null && !pausedByUser) reconnectSoon()
            }
        })
        val player = object : ForwardingPlayer(exo) {
            override fun play() = resume()
            override fun pause() = stopByUser()
            override fun stop() = stopByUser()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) resume() else stopByUser()
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build().also {
            // The app starts this service itself (no controller binds first), so the
            // session must be added for its notification and lock screen controls.
            addSession(it)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The newest start request; stopping with it keeps a newer one (switching channels) alive. */
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_LISTEN -> {
                val next = Current(
                    serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return START_NOT_STICKY,
                    sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY,
                    url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY,
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    themeName = intent.getStringExtra(EXTRA_THEME).orEmpty(),
                    volume = intent.getIntExtra(EXTRA_VOLUME, 100),
                    personal = intent.getBooleanExtra(EXTRA_PERSONAL, false),
                )
                start(next)
            }
            ACTION_STOP -> if (intent.getStringExtra(EXTRA_SESSION_ID) == current?.sessionId) {
                // The page turned it off and told the server itself.
                end(tellServer = false)
            }
            ACTION_VOLUME -> if (intent.getStringExtra(EXTRA_SESSION_ID) == current?.sessionId) {
                val volume = intent.getIntExtra(EXTRA_VOLUME, 100).coerceIn(0, 100)
                current = current?.copy(volume = volume)
                exo.volume = volume / 100f
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start(next: Current) {
        reconnect?.cancel()
        pausedByUser = false
        failures = 0
        current = next
        play(next.url)
    }

    private fun play(url: String) {
        val c = current ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(c.name.ifEmpty { "Sonorium" })
            .setArtist(c.themeName.ifEmpty { "Sonorium" })
            .setIsPlayable(true)
            .build()
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build())
        exo.volume = c.volume / 100f
        exo.prepare()
        exo.play()
        publish()
    }

    /** Lock screen or notification Play after a pause: ask the server for the channel again. */
    private fun resume() {
        val c = current ?: return
        if (!pausedByUser && exo.playbackState != Player.STATE_IDLE) {
            exo.play()
            return
        }
        pausedByUser = false
        relisten(c)
    }

    /** Pause from the lock screen, notification or headphones: listening stops. */
    private fun stopByUser() {
        val c = current ?: return
        if (pausedByUser) return
        pausedByUser = true
        reconnect?.cancel()
        exo.stop()
        publish()
        scope.launch { runCatching { unlisten(c) } }
    }

    private suspend fun unlisten(c: Current) {
        val api = api(c.serverId) ?: return
        if (c.personal) api.stopListeningMyChannel() else api.stopListening(c.sessionId)
    }

    private fun reconnectSoon() {
        val c = current ?: return
        if (reconnect?.isActive == true) return
        failures++
        if (failures > MAX_FAILURES) {
            end(tellServer = true)
            return
        }
        reconnect = scope.launch {
            delay(1_000L * failures)
            relisten(c)
        }
    }

    /** Takes the channel's slot again (it may have changed) and reconnects. */
    private fun relisten(c: Current) {
        reconnect?.cancel()
        reconnect = scope.launch {
            val slot = runCatching {
                api(c.serverId)?.let { if (c.personal) it.listenMyChannel() else it.listen(c.sessionId) }
            }.getOrNull()
            if (current?.sessionId != c.sessionId || pausedByUser) return@launch
            val url = slot?.let { "${c.streamBase}${it.streamPath}?local=1" } ?: c.url
            current = c.copy(url = url)
            play(url)
        }
    }

    private fun end(tellServer: Boolean) {
        val c = current ?: return
        reconnect?.cancel()
        current = null
        exo.stop()
        exo.clearMediaItems()
        ListenController.update(ListenController.Listening(c.serverId, c.sessionId, c.name, c.themeName, playing = false, personal = c.personal))
        ListenController.update(null)
        if (tellServer) scope.launch { runCatching { unlisten(c) } }
        stopSelf(lastStartId)
    }

    private fun publish() {
        val c = current ?: return ListenController.update(null)
        val playing = !pausedByUser && (exo.playWhenReady || reconnect?.isActive == true)
        ListenController.update(ListenController.Listening(c.serverId, c.sessionId, c.name, c.themeName, playing, c.personal))
    }

    private suspend fun api(serverId: String): SonoriumApi? {
        val app = application as SonoriumApp
        val record = app.servers.find(serverId) ?: return null
        val token = app.servers.token(serverId) ?: return null
        val client = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
        return SonoriumApi(client, SonoriumApi.baseUrl(record.hosts.first(), record.httpsPort), token)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps playing; a stopped player goes too.
        if (!exo.playWhenReady || current == null || pausedByUser) {
            current?.let { if (!pausedByUser) end(tellServer = true) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        current?.takeIf { !pausedByUser }?.let { c ->
            // Best effort: the server also releases a listener 15 s after its stream closes.
            CoroutineScope(Dispatchers.IO).launch { runCatching { unlisten(c) } }
        }
        ListenController.update(null)
        session?.release()
        session = null
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LISTEN = "app.sonorium.listen.LISTEN"
        const val ACTION_STOP = "app.sonorium.listen.STOP"
        const val ACTION_VOLUME = "app.sonorium.listen.VOLUME"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_THEME = "theme"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_PERSONAL = "personal"
        private const val MAX_FAILURES = 6
    }
}
