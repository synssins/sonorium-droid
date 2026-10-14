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
        /** The channel heard (shown and controlled): the session itself, or the one playing on a virtual speaker. */
        val channelId: String? = sessionId,
        /** A virtual speaker held by this client: stopping lets go of it. */
        val virtualClient: String? = null,
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

    /** What the notification shows; refreshed from the server while listening. */
    private data class Shown(val title: String, val subtitle: String, val artwork: ByteArray?)
    private var shown: Shown? = null
    private var poll: Job? = null
    private var themes: List<app.sonorium.net.ThemeSummary> = emptyList()
    private var themesAt = 0L
    private val presets = HashMap<String, List<app.sonorium.net.PresetInfo>>()
    private var heard: app.sonorium.net.Session? = null

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
        ListenController.bufferProbe = { exo.totalBufferedDuration / 1000.0 }
        // The same controls as a theme played on this device: previous/next through presets or sequence steps,
        // Next theme and Stop (MediaControls), acting on the channel through the server
        val player = object : ForwardingPlayer(exo) {
            override fun play() = resume()
            override fun pause() = stopByUser()
            override fun stop() = stopByUser()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) resume() else stopByUser()
            override fun getAvailableCommands(): Player.Commands = exo.availableCommands.buildUpon()
                .removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
            override fun seekToNext() = skip(forward = true)
            override fun seekToNextMediaItem() = skip(forward = true)
            override fun seekToPrevious() = skip(forward = false)
            override fun seekToPreviousMediaItem() = skip(forward = false)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setId("listen")
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                    app.sonorium.engine.MediaControls.accept(session)

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle,
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    when (customCommand.customAction) {
                        app.sonorium.engine.MediaControls.COMMAND_NEXT_THEME -> nextTheme()
                        // As Stop for a theme on this device: playback here ends (the channel plays on where it plays)
                        app.sonorium.engine.MediaControls.COMMAND_STOP -> end(tellServer = true)
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS),
                    )
                }
            })
            .setMediaButtonPreferences(app.sonorium.engine.MediaControls.buttons())
            .build().also {
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
                    channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: intent.getStringExtra(EXTRA_SESSION_ID),
                    virtualClient = intent.getStringExtra(EXTRA_VIRTUAL_CLIENT),
                )
                start(next)
            }
            ACTION_CHANNEL -> if (intent.getStringExtra(EXTRA_SESSION_ID) == current?.sessionId) {
                // Another channel plays on the virtual speaker heard here: show and control that one
                val channel = intent.getStringExtra(EXTRA_CHANNEL_ID)
                if (channel != current?.channelId) {
                    current = current?.copy(channelId = channel)
                    heard = null
                    refreshSoon()
                }
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

    /** The HTTP client pinned to [serverId]'s certificate (the server's own, as when linking), or null. */
    private fun pinnedClient(serverId: String): okhttp3.OkHttpClient? {
        val sonorium = application as app.sonorium.SonoriumApp
        val record = kotlinx.coroutines.runBlocking { sonorium.servers.find(serverId) } ?: return null
        return runCatching { app.sonorium.net.PinnedTls.pinnedClient(sonorium.baseClient, record.fingerprint) }.getOrNull()
    }

    private fun start(next: Current) {
        reconnect?.cancel()
        pausedByUser = false
        failures = 0
        val sameChannel = current?.channelId == next.channelId && current?.serverId == next.serverId
        current = next
        if (!sameChannel) {
            heard = null
            shown = null
        }
        play(next.url)
        startPolling()
    }

    private fun play(url: String) {
        val c = current ?: return
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata()).build()
        // A stream at the server's HTTPS address: through a client pinned to that server's certificate
        val pinned = if (url.startsWith("https:")) pinnedClient(c.serverId) else null
        if (pinned != null) {
            exo.setMediaSource(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(pinned)).createMediaSource(item),
            )
        } else {
            exo.setMediaItem(item)
        }
        exo.volume = c.volume / 100f
        exo.prepare()
        exo.play()
        publish()
    }

    /** The notification's title, subtitle and artwork, as for a theme played on this device. */
    private fun metadata(): MediaMetadata {
        val c = current
        val now = shown ?: Shown(
            title = c?.themeName?.ifBlank { null } ?: c?.name?.ifBlank { null } ?: "Sonorium",
            subtitle = c?.name.orEmpty().ifBlank { "Sonorium" },
            artwork = null,
        )
        return MediaMetadata.Builder()
            .setTitle(now.title)
            .setDisplayTitle(now.title)
            .setArtist(now.subtitle)
            .setSubtitle(now.subtitle)
            .setAlbumTitle("Sonorium")
            .setIsPlayable(true)
            .setArtworkData(now.artwork ?: app.sonorium.engine.MediaControls.appArtwork(resources), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
    }

    /** Reads the channel heard every few seconds while listening: the notification follows its theme and preset. */
    private fun startPolling() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (current != null) {
                runCatching { refresh() }.onFailure { android.util.Log.w(TAG, "Couldn't read the channel heard", it) }
                delay(POLL_MS)
            }
        }
    }

    private fun refreshSoon() {
        scope.launch {
            delay(600)
            runCatching { refresh() }
        }
    }

    /** The channel heard, as the server has it now. */
    private suspend fun channelNow(api: SonoriumApi, c: Current): app.sonorium.net.Session? {
        val channelId = c.channelId ?: return null
        return if (c.personal) api.myChannel() else api.sessions().firstOrNull { it.id == channelId }
    }

    private suspend fun refresh() {
        val c = current ?: return
        val api = api(c.serverId) ?: return
        val channel = channelNow(api, c) ?: return
        if (current?.channelId != c.channelId) return
        heard = channel
        val now = System.currentTimeMillis()
        if (themes.isEmpty() || themes.none { it.id == channel.themeId } || now - themesAt > THEMES_MS) {
            themes = runCatching { api.themes() }.getOrDefault(themes)
            themesAt = now
        }
        val themeId = channel.themeId
        val themePresets = themeId?.let { id ->
            presets[id] ?: runCatching { api.themePresets(id).presets }.getOrNull()?.also { presets[id] = it }
        }.orEmpty()
        val sonorium = application as SonoriumApp
        val serverName = sonorium.servers.find(c.serverId)?.displayName
        val channelName = if (c.personal) "My Channel" else channel.name.ifBlank { c.name }
        val playlistTheme = channel.playlist?.takeIf { it.running }?.themeId?.let { id -> themes.firstOrNull { it.id == id } }
        val iconTheme = playlistTheme ?: themes.firstOrNull { it.id == themeId }
        val art = iconTheme?.let { app.sonorium.engine.MediaControls.iconArtwork(app.sonorium.server.ChannelRules.themeIcon(it.id, it)) }
        val next = Shown(
            title = NowHeard.title(channel, themes, channelName),
            subtitle = NowHeard.subtitle(channel, themePresets, channelName, serverName),
            artwork = art,
        )
        val before = shown
        if (before != null && next.title == before.title && next.subtitle == before.subtitle && next.artwork === before.artwork) return
        shown = next
        if (exo.mediaItemCount > 0) {
            // Same stream, new words and picture: the player keeps playing
            exo.replaceMediaItem(0, exo.getMediaItemAt(0).buildUpon().setMediaMetadata(metadata()).build())
        }
        session?.let { s -> runCatching { onUpdateNotification(s, false) } }
    }

    /** Previous/next: the sequence's or playlist's next step (the server has no step back), else the previous or next preset. */
    private fun skip(forward: Boolean) {
        val c = current ?: return
        scope.launch {
            runCatching {
                val api = api(c.serverId) ?: return@runCatching
                val channel = heard ?: channelNow(api, c) ?: return@runCatching
                if (channel.sequence?.running == true || channel.playlist?.running == true) {
                    if (forward) api.next(channel.id)
                    return@runCatching
                }
                val themeId = channel.themeId ?: return@runCatching
                val list = presets[themeId] ?: api.themePresets(themeId).presets.also { presets[themeId] = it }
                val preset = NowHeard.nextPreset(channel.presetId, list, forward) ?: return@runCatching
                val body = kotlinx.serialization.json.buildJsonObject { put("preset_id", kotlinx.serialization.json.JsonPrimitive(preset)) }
                if (c.personal) api.updateMyChannel(body) else api.updateSession(channel.id, body)
            }.onFailure { android.util.Log.w(TAG, "Skip failed", it) }
            runCatching { refresh() }
        }
    }

    /** Next theme: the playlist's next entry while one plays, else the next theme on the server. */
    private fun nextTheme() {
        val c = current ?: return
        scope.launch {
            runCatching {
                val api = api(c.serverId) ?: return@runCatching
                val channel = heard ?: channelNow(api, c) ?: return@runCatching
                if (channel.playlist?.running == true) {
                    api.next(channel.id)
                    return@runCatching
                }
                if (themes.isEmpty()) themes = api.themes()
                val next = NowHeard.nextTheme(channel.themeId, themes) ?: return@runCatching
                val body = kotlinx.serialization.json.buildJsonObject { put("theme_id", kotlinx.serialization.json.JsonPrimitive(next.id)) }
                if (c.personal) api.updateMyChannel(body) else api.updateSession(channel.id, body)
            }.onFailure { android.util.Log.w(TAG, "Next theme failed", it) }
            runCatching { refresh() }
        }
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
        if (c.virtualClient != null) {
            // A virtual speaker: this device lets go of it (Stop here), so its poll doesn't start it again
            ListenController.virtualStopped?.invoke(c.sessionId)
            return
        }
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
            val url = slot?.let { "${c.streamBase}${it.playPath}" } ?: c.url
            current = c.copy(url = url)
            play(url)
        }
    }

    private fun end(tellServer: Boolean) {
        val c = current ?: return
        reconnect?.cancel()
        poll?.cancel()
        current = null
        shown = null
        heard = null
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
        ListenController.bufferProbe = null
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
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_VIRTUAL_CLIENT = "virtual_client"
        const val ACTION_CHANNEL = "app.sonorium.listen.CHANNEL"
        private const val MAX_FAILURES = 6
        private const val TAG = "ListenService"
        private const val POLL_MS = 5_000L
        private const val THEMES_MS = 60_000L
    }
}
