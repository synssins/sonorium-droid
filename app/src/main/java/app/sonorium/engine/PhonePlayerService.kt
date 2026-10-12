package app.sonorium.engine

import android.app.PendingIntent
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
import app.sonorium.MainActivity

/**
 * A theme played on this phone: the engine's output ([EngineDataSource]) in ExoPlayer, in the
 * background, with the lock screen and notification controls of a media session. Pause stops it.
 */
@OptIn(UnstableApi::class)
class PhonePlayerService : MediaSessionService() {
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private var title = ""
    private var wanted = false

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EngineDataSource.Factory()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // Little in hand: the engine is right here, and preset or volume changes are heard soon
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 3_000, 500, 1_000).build())
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = PhonePlayer.onPlaying(isPlaying || (wanted && exo.playWhenReady))

            override fun onPlaybackStateChanged(state: Int) {
                // The long WAV "file" ran out (hours): start a new one
                if (state == Player.STATE_ENDED && wanted) start()
            }

            override fun onPlayerError(error: PlaybackException) {
                PhonePlayer.onError("Playback stopped: ${error.message ?: error.errorCodeName}")
                end()
            }
        })
        val player = object : ForwardingPlayer(exo) {
            override fun play() = if (wanted) exo.play() else PhonePlayer.resume(this@PhonePlayerService)
            override fun pause() = end()
            override fun stop() = end()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else end()
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    private fun start() {
        val metadata = MediaMetadata.Builder().setTitle(title.ifEmpty { "Sonorium" }).setArtist("On this phone").setIsPlayable(true).build()
        exo.setMediaItem(MediaItem.Builder().setUri("sonorium-engine:play").setMimeType(MimeTypes.AUDIO_WAV).setMediaMetadata(metadata).build())
        exo.prepare()
        exo.play()
    }

    private fun end() {
        wanted = false
        exo.stop()
        exo.clearMediaItems()
        PhonePlayer.onPlaying(false)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!wanted) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        exo.release()
        PhonePlayer.onPlaying(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "app.sonorium.engine.PLAY"
        const val ACTION_STOP = "app.sonorium.engine.STOP"
        const val EXTRA_TITLE = "title"
    }
}
