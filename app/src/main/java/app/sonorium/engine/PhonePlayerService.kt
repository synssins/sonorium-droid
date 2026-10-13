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
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

            // Android 15 refuses audio focus to a player started in the background (lock screen,
            // headset, a service start) until it runs in the foreground: try again shortly after
            // instead of staying paused (seen in the device tests: "Focus request DENIED ... hardening")
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || !wanted) return
                // Lost to this app's own server stream (Listen): that one plays now, no taking focus back
                if (app.sonorium.listen.ListenController.state.value?.playing == true) return
                if (System.currentTimeMillis() - startedAt > FOCUS_RETRY_WINDOW_MS || focusRetries >= FOCUS_RETRIES) {
                    if (System.currentTimeMillis() - startedAt <= FOCUS_RETRY_WINDOW_MS) {
                        PhonePlayer.onError("Android didn't let the sound start. Open Sonorium and press Play.")
                    }
                    return
                }
                focusRetries++
                handler.postDelayed({ if (wanted && !exo.playWhenReady) exo.play() }, 700L * focusRetries)
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
        // What the lock screen, the notification, headsets, cars and watches see: a live stream (no
        // seek bar, no length: a theme plays on and on), ⏮ ⏭ through presets or sequence steps
        val player = object : ForwardingPlayer(exo) {
            override fun play() = if (wanted) exo.play() else PhonePlayer.resume(this@PhonePlayerService)
            // Pause keeps the stream (Play resumes it); Stop ends it
            override fun pause() = if (wanted) exo.pause() else end()
            override fun stop() = end()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
            override fun getDuration(): Long = C.TIME_UNSET
            override fun getContentDuration(): Long = C.TIME_UNSET
            override fun isCurrentMediaItemLive(): Boolean = true
            override fun isCurrentMediaItemSeekable(): Boolean = false
            override fun isCurrentMediaItemDynamic(): Boolean = true
            // Controllers (the lock screen, Bluetooth, cars) read live/seekable from the timeline's window
            override fun getCurrentTimeline(): Timeline = exo.currentTimeline.let { t -> if (t.isEmpty) t else LiveTimeline(t) }
            override fun getAvailableCommands(): Player.Commands = exo.availableCommands.buildUpon()
                .removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
            override fun seekToNext() = PhonePlayer.skip(forward = true)
            override fun seekToNextMediaItem() = PhonePlayer.skip(forward = true)
            override fun seekToPrevious() = PhonePlayer.skip(forward = false)
            override fun seekToPreviousMediaItem() = PhonePlayer.skip(forward = false)
        }
        // Tapping the notification: back to the main screen already open (not a second one)
        val open = OpenApp.pendingIntent(this, OPEN_REQUEST)
        OpenApp.addLastServer(this, OPEN_REQUEST, scope)
        session = MediaSession.Builder(this, player)
            .setId("phone")
            .setSessionActivity(open)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(buttons())
            .build()
            .also { addSession(it) }
        // The title and subtitle follow the theme, preset and sequence step
        scope.launch {
            PhonePlayer.state.collect { st ->
                val name = PhonePlayer.title(st)
                val sub = PhonePlayer.subtitle(st)
                if ((name != null && name != title) || sub != subtitle) {
                    launch(Dispatchers.Main) {
                        if (name != null) title = name
                        subtitle = sub
                        if (exo.mediaItemCount > 0) exo.replaceMediaItem(0, item())
                        // The notification and lock screen at once, not at the next playback change
                        session?.let { s -> runCatching { onUpdateNotification(s, false) } }
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The control's own buttons beside ⏮ ▶ ⏭: Next theme and Stop. */
    private fun buttons(): List<androidx.media3.session.CommandButton> = listOf(
        androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Next theme")
            .setCustomIconResId(app.sonorium.R.drawable.ic_next_theme)
            .setSessionCommand(androidx.media3.session.SessionCommand(COMMAND_NEXT_THEME, android.os.Bundle.EMPTY))
            .setSlots(androidx.media3.session.CommandButton.SLOT_OVERFLOW)
            .build(),
        androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_STOP)
            .setDisplayName("Stop")
            .setSessionCommand(androidx.media3.session.SessionCommand(COMMAND_STOP, android.os.Bundle.EMPTY))
            .setSlots(androidx.media3.session.CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(androidx.media3.session.SessionCommand(COMMAND_NEXT_THEME, android.os.Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(COMMAND_STOP, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            when (customCommand.customAction) {
                COMMAND_NEXT_THEME -> PhonePlayer.nextTheme(this@PhonePlayerService)
                COMMAND_STOP -> end()
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }
    }

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

    private var subtitle = "On this phone"

    /** The app's icon on the Sonorium background, square, for the lock screen and notification. */
    private val artwork: ByteArray? by lazy {
        runCatching {
            val icon = android.graphics.BitmapFactory.decodeResource(resources, app.sonorium.R.mipmap.ic_launcher)
            val size = 512
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply {
                drawColor(0xFF1A1A2E.toInt())
                val inset = size / 6
                drawBitmap(icon, null, android.graphics.Rect(inset, inset, size - inset, size - inset), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }

    private fun item(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifEmpty { "Sonorium" })
            .setArtist(subtitle)
            .setAlbumTitle("Sonorium")
            .setDisplayTitle(title.ifEmpty { "Sonorium" })
            .setSubtitle(subtitle)
            .setIsPlayable(true)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        return MediaItem.Builder().setUri("sonorium-engine:play").setMimeType(MimeTypes.AUDIO_WAV).setMediaMetadata(metadata).build()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAt = 0L
    private var focusRetries = 0

    private fun start() {
        startedAt = System.currentTimeMillis()
        focusRetries = 0
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
        private const val COMMAND_NEXT_THEME = "app.sonorium.NEXT_THEME"
        private const val COMMAND_STOP = "app.sonorium.STOP"
        private const val FOCUS_RETRY_WINDOW_MS = 8_000L
        private const val FOCUS_RETRIES = 4

        @Volatile private var running: PhonePlayerService? = null

        /** The service exists (playing, paused, or stopping). */
        val isRunning: Boolean get() = running != null

        /** Renames what's playing (main thread), when the service is running. */
        fun retitle(title: String) {
            running?.rename(title)
        }
    }
}

/** The engine's stream as a live one: no seek bar, no length (a theme plays on and on). */
@OptIn(UnstableApi::class)
private class LiveTimeline(timeline: Timeline) : ForwardingTimeline(timeline) {
    override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.isSeekable = false
        window.isDynamic = true
        window.liveConfiguration = window.liveConfiguration ?: MediaItem.LiveConfiguration.UNSET
        window.durationUs = C.TIME_UNSET
        return window
    }
}
