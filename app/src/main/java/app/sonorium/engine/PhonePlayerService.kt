package app.sonorium.engine

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A theme played on this phone: the engine's output ([EngineDataSource]) in ExoPlayer, in the
 * background, with the lock screen and notification controls of a media session. Pause (lock screen,
 * headset) pauses; Stop, and Play in the app, start the stream again.
 */
@OptIn(UnstableApi::class)
class PhonePlayerService : MediaSessionService() {
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private var title = ""
    private var wanted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        running = this
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EngineDataSource.Factory()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // Little in hand: the engine is right here, and preset or volume changes are heard soon
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 3_000, 500, 1_000).build())
            .build()
        exo.addListener(object : Player.Listener {
            // Playing, or about to (buffering, or held back for a moment by audio focus): the Play button shows Stop.
            // isPlaying alone never changes while audio focus holds playback back, so follow every change.
            override fun onEvents(player: Player, events: Player.Events) {
                val on = exo.isPlaying || (wanted && exo.playWhenReady && exo.playbackState != Player.STATE_IDLE && exo.playbackState != Player.STATE_ENDED)
                PhonePlayer.onLocalPlaying(on)
            }

            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) android.util.Log.w("PhonePlayer", "Playback held back (reason $reason)")
            }

            override fun onPlaybackStateChanged(state: Int) {
                // The long WAV "file" ran out (hours): start a new one
                if (state == Player.STATE_ENDED && wanted) start()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("PhonePlayer", "Playback error ${error.errorCodeName}", error)
                PhonePlayer.onError("Playback stopped unexpectedly. Press Play to start again.")
                end()
            }
        })
        val player = object : ForwardingPlayer(exo) {
            override fun play() = if (wanted) exo.play() else PhonePlayer.resume(this@PhonePlayerService)
            // Pause keeps the stream (Play resumes it); Stop ends it
            override fun pause() = if (wanted) exo.pause() else end()
            override fun stop() = end()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
        }
        // Tapping the notification: back to the main screen already open (not a second one)
        val open = OpenApp.pendingIntent(this, OPEN_REQUEST)
        OpenApp.addLastServer(this, OPEN_REQUEST, scope)
        session = MediaSession.Builder(this, player).setId("phone").setSessionActivity(open).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                wanted = true
                start()
            }
            ACTION_STOP -> end()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun item(): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(title.ifEmpty { "Sonorium" }).setArtist("On this phone").setIsPlayable(true).build()
        return MediaItem.Builder().setUri("sonorium-engine:play").setMimeType(MimeTypes.AUDIO_WAV).setMediaMetadata(metadata).build()
    }

    private fun start() {
        exo.setMediaItem(item())
        exo.prepare()
        exo.play()
    }

    /** The theme changed while playing: same stream, new title in the notification. */
    private fun rename(newTitle: String) {
        title = newTitle
        if (exo.mediaItemCount > 0) exo.replaceMediaItem(0, item())
    }

    private fun end() {
        wanted = false
        exo.stop()
        exo.clearMediaItems()
        PhonePlayer.onLocalPlaying(false)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while stopped or paused: nothing to keep going
        if (!wanted || !exo.playWhenReady) end()
    }

    override fun onDestroy() {
        if (running === this) running = null
        scope.cancel()
        session?.release()
        session = null
        exo.release()
        PhonePlayer.onLocalPlaying(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "app.sonorium.engine.PLAY"
        const val ACTION_STOP = "app.sonorium.engine.STOP"
        const val EXTRA_TITLE = "title"

        private const val OPEN_REQUEST = 1

        @Volatile private var running: PhonePlayerService? = null

        /** The service exists (playing, paused, or stopping). */
        val isRunning: Boolean get() = running != null

        /** Renames what's playing (main thread), when the service is running. */
        fun retitle(title: String) {
            running?.rename(title)
        }
    }
}
