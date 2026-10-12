package app.sonorium.server

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sonorium.SonoriumApp
import app.sonorium.data.ServerRecord
import app.sonorium.listen.ListenController
import app.sonorium.net.ApiException
import app.sonorium.net.Me
import app.sonorium.net.PinnedTls
import app.sonorium.net.Playlist
import app.sonorium.net.ServerInfo
import app.sonorium.net.Session
import app.sonorium.net.SonoriumApi
import app.sonorium.net.SpeakerGroup
import app.sonorium.net.SpeakerHierarchy
import app.sonorium.net.ThemePresets
import app.sonorium.net.ThemeSummary
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** Everything the native screens show for one server (shared/native-api.md, Phase 1). */
data class ServerState(
    val record: ServerRecord? = null,
    val connecting: Boolean = true,
    val failure: String? = null,
    val unlinked: Boolean = false,
    val info: ServerInfo? = null,
    val me: Me? = null,
    val sessions: List<Session> = emptyList(),
    val sessionsLoaded: Boolean = false,
    val themes: List<ThemeSummary> = emptyList(),
    val presets: Map<String, ThemePresets> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val groups: List<SpeakerGroup> = emptyList(),
    val hierarchy: SpeakerHierarchy? = null,
    val enabledSpeakers: Set<String> = emptySet(),
    val version: String = "",
    val installLabel: String = "",
    val personal: Session? = null,
    /** Channels switched to playlist mode on their card, until a playlist is picked (1.5.2). */
    val playlistMode: Set<String> = emptySet(),
) {
    val personalChannel: Boolean get() = info?.personalChannel == true
    val playingCount: Int get() = sessions.count { it.isPlaying }
    fun theme(id: String?) = themes.firstOrNull { it.id == id }
    fun playlist(id: String?) = playlists.firstOrNull { it.id == id }
}

/** A toast: the server's message verbatim (errors) or the web UI's fixed texts (successes). */
data class Toast(val text: String, val error: Boolean, val id: Long = System.nanoTime())

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SonoriumApp
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()
    private val _toasts = MutableStateFlow<Toast?>(null)
    val toasts: StateFlow<Toast?> = _toasts.asStateFlow()

    private var serverId: String? = null
    private var api: SonoriumApi? = null
    private var client: OkHttpClient? = null
    private var host: String? = null
    private var events: EventSource? = null
    private var lastEventId: String? = null
    private var eventsRetry: Job? = null
    private var poll: Job? = null
    private var sessionsRefresh: Job? = null

    private val prefs = application.getSharedPreferences("sonorium", Context.MODE_PRIVATE)

    /** This device's listen volume (native-api.md 1.2), remembered on the device. */
    private val _listenVolume = MutableStateFlow(prefs.getInt("listenVolume", 100))
    val listenVolume: StateFlow<Int> = _listenVolume.asStateFlow()

    fun open(id: String) {
        if (serverId == id && api != null) return
        serverId = id
        connect()
    }

    fun connect() {
        val id = serverId ?: return
        viewModelScope.launch {
            val record = app.servers.find(id)
            if (record == null) {
                _state.value = ServerState(connecting = false, failure = "This server isn't linked any more.")
                return@launch
            }
            _state.update { it.copy(record = record, connecting = true, failure = null) }
            val token = app.servers.token(id)
            if (token == null) {
                _state.update { it.copy(connecting = false, failure = "This phone isn't linked to ${record.displayName}. Link it again.") }
                return@launch
            }
            val pinned = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
            val reached = app.linker.reach(pinned, record.hosts, record.httpsPort)
            if (reached == null || reached.info.id != record.id) {
                _state.update {
                    it.copy(
                        connecting = false,
                        failure = if (reached == null) "Can't reach ${record.displayName}. Check that this phone is on the same network."
                        else "A different server answered at ${reached.host}.",
                    )
                }
                return@launch
            }
            val updated = record.preferring(reached.host).copy(version = reached.info.version)
            if (updated != record) app.servers.update(updated)
            app.servers.select(record.id)
            client = pinned
            host = reached.host
            api = SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), token)
            _state.update { it.copy(record = updated, info = reached.info, connecting = false) }
            loadAll()
            startEvents()
            startPolling()
        }
    }

    /** All the data the Channels page loads, in parallel; a failing call leaves the rest working (1.1). */
    private suspend fun loadAll() {
        val api = api ?: return
        viewModelScope.launch { guard { api.me() }?.let { me -> _state.update { it.copy(me = me) } } }
        viewModelScope.launch { refreshSessions() }
        viewModelScope.launch { guard { api.themes() }?.let { t -> _state.update { it.copy(themes = t) } } }
        viewModelScope.launch { guard { api.playlists() }?.let { p -> _state.update { it.copy(playlists = p) } } }
        viewModelScope.launch { guard { api.groups() }?.let { g -> _state.update { it.copy(groups = g) } } }
        viewModelScope.launch { refreshSpeakers() }
        viewModelScope.launch { guard { api.status() }?.let { s -> _state.update { it.copy(version = s.version) } } }
        viewModelScope.launch { guard { api.install() }?.let { i -> _state.update { it.copy(installLabel = i.label) } } }
        if (_state.value.personalChannel) viewModelScope.launch { refreshPersonal() }
    }

    suspend fun refreshSessions() {
        val api = api ?: return
        val sessions = guard { api.sessions() } ?: return
        _state.update { it.copy(sessions = sessions, sessionsLoaded = true) }
        // Preset menus for every theme in use (cached per theme)
        sessions.mapNotNull { it.themeId }.distinct().filter { it !in _state.value.presets }.forEach { loadPresets(it) }
    }

    private suspend fun refreshSpeakers() {
        val api = api ?: return
        guard { api.speakerHierarchy() }?.let { h -> _state.update { it.copy(hierarchy = h) } }
        guard { api.enabledSpeakers() }?.let { e -> _state.update { it.copy(enabledSpeakers = e.toSet()) } }
    }

    suspend fun refreshPersonal() {
        val api = api ?: return
        guard { api.myChannel() }?.let { p ->
            _state.update { it.copy(personal = p) }
            p.themeId?.takeIf { it !in _state.value.presets }?.let { loadPresets(it) }
        }
    }

    suspend fun loadPresets(themeId: String) {
        val api = api ?: return
        val presets = runCatching { api.themePresets(themeId) }.getOrDefault(ThemePresets())
        _state.update { it.copy(presets = it.presets + (themeId to presets)) }
    }

    private fun refreshSessionsSoon() {
        if (sessionsRefresh?.isActive == true) return
        sessionsRefresh = viewModelScope.launch {
            delay(250)
            refreshSessions()
            if (_state.value.personalChannel) refreshPersonal()
        }
    }

    // ---------- Live updates: the event stream (native-api.md 1.7), with a slow poll as a safety net ----------

    private fun startEvents() {
        events?.cancel()
        val api = api ?: return
        val streamClient = (client ?: return).newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val request = api.eventStreamRequest(EVENT_TYPES, lastEventId)
        events = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Every (re)connect: refetch once, then apply events (APP_API.md 8.3)
                viewModelScope.launch { refreshSessions() }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                id?.let { lastEventId = it }
                viewModelScope.launch { onServerEvent(type.orEmpty(), data) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response?.code == 401) viewModelScope.launch { guard { api.me() } }
                scheduleEventsRetry()
            }

            override fun onClosed(eventSource: EventSource) = scheduleEventsRetry()
        })
    }

    private var eventsBackoff = 5_000L

    private fun scheduleEventsRetry() {
        if (eventsRetry?.isActive == true) return
        eventsRetry = viewModelScope.launch {
            delay(eventsBackoff)
            eventsBackoff = (eventsBackoff * 2).coerceAtMost(60_000L)
            startEvents()
        }
    }

    private suspend fun onServerEvent(type: String, data: String) {
        eventsBackoff = 5_000L
        val payload = runCatching { SonoriumApi.json.parseToJsonElement(data).jsonObject["data"]?.jsonObject }.getOrNull()
        fun field(name: String) = (payload?.get(name) as? JsonPrimitive)?.content
        when {
            type == "sonorium.started" -> {
                lastEventId = null
                loadAll()
            }
            type.startsWith("channel.") || type.startsWith("sequence.") || type == "playlist.entry" ||
                type.startsWith("zone.") || type.startsWith("link.") -> refreshSessionsSoon()
            type == "theme.changed" -> {
                field("theme_id")?.let { id -> _state.update { it.copy(presets = it.presets - id) }; loadPresets(id) }
                api?.let { a -> guard { a.themes() }?.let { t -> _state.update { it.copy(themes = t) } } }
            }
            type.startsWith("theme.") -> api?.let { a -> guard { a.themes() }?.let { t -> _state.update { it.copy(themes = t) } } }
            type.startsWith("playlist.") -> api?.let { a -> guard { a.playlists() }?.let { p -> _state.update { it.copy(playlists = p) } } }
            type.startsWith("group.") -> api?.let { a -> guard { a.groups() }?.let { g -> _state.update { it.copy(groups = g) } } }
            type.startsWith("speaker.") || type.startsWith("spaces.") || type.startsWith("settings.") -> refreshSpeakers()
        }
    }

    /** The web UI polls every 15 s; the app keeps that as a safety net under the event stream. */
    private fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (visible) refreshSessions()
            }
        }
    }

    @Volatile
    var visible: Boolean = true

    fun onResume() {
        visible = true
        if (api != null) viewModelScope.launch { refreshSessions() }
    }

    fun onPause() {
        visible = false
    }

    // ---------- Card actions (native-api.md 1.1, "Card actions") ----------

    fun togglePlay(session: Session) = act {
        if (session.isPlaying) it.stop(session.id) else it.play(session.id)
        refreshSessions()
    }

    fun setTheme(session: Session, themeId: String) {
        if (themeId == session.themeId) return
        act("Theme updated") {
            it.updateSession(session.id, buildJsonObject { put("theme_id", themeId) })
            if (themeId !in _state.value.presets) loadPresets(themeId)
            refreshSessions()
        }
    }

    fun setPreset(session: Session, choice: String) {
        if (choice == session.choice) return
        val success = when {
            choice.startsWith("seq:") -> "Sequence started"
            choice.isNotEmpty() -> "Preset applied"
            else -> "Using default settings"
        }
        act(success) {
            it.updateSession(session.id, buildJsonObject { put("preset_id", choice) })
            refreshSessions()
        }
    }

    fun setVolume(session: Session, volume: Int) {
        _state.update { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(volume = volume) else it }) }
        act { it.updateSession(session.id, buildJsonObject { put("volume", volume) }) }
    }

    /** Optimistic: flips at once, reverts with an error toast on failure (1.2). */
    fun toggleSpeakersMuted(session: Session) {
        val muted = !session.speakersMuted
        fun set(value: Boolean) = _state.update { s ->
            s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(speakersMuted = value) else it })
        }
        set(muted)
        viewModelScope.launch {
            val api = api ?: return@launch
            try {
                api.setSpeakersMuted(session.id, muted)
            } catch (e: Exception) {
                set(!muted)
                fail(e)
            }
        }
    }

    fun setAnnouncements(session: Session, on: Boolean) = act {
        it.setAnnouncements(session.id, on)
        refreshSessions()
    }

    fun next(session: Session) {
        val success = if (session.playlistId != null) "Next entry" else "Next step"
        act(success) {
            it.next(session.id)
            refreshSessions()
        }
    }

    /** The playlist button: local playlist mode until a playlist is picked (1.2, Mode buttons). */
    fun enterPlaylistMode(session: Session) {
        if (session.playlistId == null) _state.update { it.copy(playlistMode = it.playlistMode + session.id) }
    }

    /** The theme button: stops a set playlist (the channel keeps its theme), or leaves local playlist mode. */
    fun enterThemeMode(session: Session) {
        _state.update { it.copy(playlistMode = it.playlistMode - session.id) }
        if (session.playlistId != null) act("Playlist stopped") {
            it.clearPlaylist(session.id)
            refreshSessions()
        }
    }

    fun pickPlaylist(session: Session, playlist: Playlist) {
        _state.update { it.copy(playlistMode = it.playlistMode - session.id) }
        if (playlist.id == session.playlistId) return
        act("Playing \u201c${playlist.name}\u201d") {
            it.setPlaylist(session.id, playlist.id)
            refreshSessions()
        }
    }

    fun delete(session: Session) = act("Channel deleted") {
        if (ListenController.state.value?.sessionId == session.id) stopListening()
        it.deleteSession(session.id)
        refreshSessions()
    }

    fun stopCasting(session: Session, outputId: String) = act {
        it.stopOutput(session.id, outputId)
        refreshSessions()
    }

    /** Create or edit from the channel editor; [onDone] closes it (1.4). */
    fun saveChannel(edit: Session?, body: JsonObject, playlistId: String?, clearPlaylistFirst: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            val api = api ?: return@launch
            try {
                val id = if (edit != null) {
                    if (clearPlaylistFirst) api.clearPlaylist(edit.id)
                    api.updateSession(edit.id, body)
                    edit.id
                } else {
                    api.createSession(body).id
                }
                if (playlistId != null && playlistId != edit?.playlistId) api.setPlaylist(id, playlistId)
                toast(if (edit != null) "Channel updated" else "Channel created", error = false)
                onDone()
                refreshSessions()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // ---------- Listen here (this device) ----------

    fun isListening(sessionId: String, personal: Boolean = false): Boolean {
        val listening = ListenController.state.value ?: return false
        return listening.serverId == serverId && listening.playing &&
            (if (personal) listening.personal else listening.sessionId == sessionId && !listening.personal)
    }

    /** One channel at a time per device: turning it on for another first stops the current one (1.2). */
    fun toggleListen(session: Session, personal: Boolean = false) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val api = api ?: return@launch
            if (isListening(session.id, personal)) {
                stopListening()
                return@launch
            }
            stopListening()
            val slot = runCatching { if (personal) api.listenMyChannel() else api.listen(session.id) }.getOrNull()
                ?: return@launch // on failure the button just stays off (1.1)
            val port = _state.value.info?.ports?.http ?: _state.value.record?.httpPort ?: return@launch
            val url = "http://${host ?: return@launch}:$port${slot.streamPath}?local=1"
            ListenController.listen(
                context, serverId ?: return@launch, session.id, url,
                name = if (personal) "My channel" else session.name,
                themeName = _state.value.theme(session.themeId)?.name.orEmpty(),
                volume = _listenVolume.value,
                personal = personal,
            )
        }
    }

    private suspend fun stopListening() {
        val current = ListenController.state.value ?: return
        if (current.serverId != serverId) return
        ListenController.stop(getApplication(), current.sessionId)
        val api = api ?: return
        runCatching { if (current.personal) api.stopListeningMyChannel() else api.stopListening(current.sessionId) }
    }

    fun setListenVolume(volume: Int) {
        _listenVolume.value = volume
        prefs.edit().putInt("listenVolume", volume).apply()
        ListenController.state.value?.let { ListenController.setVolume(getApplication(), it.sessionId, volume) }
    }

    // ---------- The personal channel ----------

    fun updatePersonal(body: JsonObject, success: String? = null) = act(success) {
        val updated = it.updateMyChannel(body)
        _state.update { s -> s.copy(personal = updated) }
        updated.themeId?.takeIf { id -> id !in _state.value.presets }?.let { id -> loadPresets(id) }
    }

    // ---------- Helpers ----------

    private fun act(success: String? = null, block: suspend (SonoriumApi) -> Unit) {
        viewModelScope.launch {
            val api = api ?: return@launch
            try {
                block(api)
                success?.let { toast(it, error = false) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        if (e.unlinked) markUnlinked()
        null
    } catch (_: Exception) {
        null
    }

    private suspend fun fail(e: Exception) {
        if (e is ApiException && e.unlinked) {
            markUnlinked()
            return
        }
        val text = when {
            e is ApiException && e.detail.startsWith("[") -> "Request failed" // 422 validation list (0.1)
            e is ApiException -> e.detail
            else -> "Can't reach the server."
        }
        toast(text, error = true)
    }

    private suspend fun markUnlinked() {
        val record = _state.value.record ?: return
        events?.cancel()
        app.servers.remove(record.id)
        _state.update { it.copy(unlinked = true, failure = "This phone was unlinked from ${record.displayName}. Link it again.") }
    }

    fun toast(text: String, error: Boolean) {
        _toasts.value = Toast(text, error)
    }

    override fun onCleared() {
        events?.cancel()
        super.onCleared()
    }

    private companion object {
        const val EVENT_TYPES =
            "channel.*,sequence.*,playlist.*,theme.*,zone.*,link.*,group.*,spaces.*,settings.*,speaker.*,sonorium.started"
    }
}
