package app.sonorium.server

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sonorium.SonoriumApp
import app.sonorium.data.ServerRecord
import app.sonorium.listen.ListenController
import app.sonorium.net.ApiException
import app.sonorium.net.Me
import app.sonorium.net.NowPlaying
import app.sonorium.net.PinnedTls
import app.sonorium.net.Playlist
import app.sonorium.net.ServerInfo
import app.sonorium.net.Session
import app.sonorium.net.SonoriumApi
import app.sonorium.net.SpeakerGroup
import app.sonorium.net.Spaces
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
    /** Why the personal channel isn't available (e.g. 401 "Sign in to use your personal channel"). */
    val personalProblem: String? = null,
    val zones: app.sonorium.net.ZonesView? = null,
    /** Channels switched to playlist mode on their card, until a playlist is picked (1.5.2). */
    val playlistMode: Set<String> = emptySet(),
    val spaces: Spaces? = null,
    /** Standalone servers (GET /network-speakers answers); null on the add-on. */
    val network: app.sonorium.net.NetworkSpeakers? = null,
    /** Whether the server has Home Assistant, as far as this caller can tell (GET /connection). */
    val hasHa: Boolean = true,
    val categories: List<String> = emptyList(),
    /** Settings › Connection is listed: GET /connection answered or a plugin declares a connection. */
    val connectionPage: Boolean = false,
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
        loadExpanded()
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
        viewModelScope.launch { refreshConnectionPage() }
        viewModelScope.launch { guard { api.status() }?.let { s -> _state.update { it.copy(version = s.version) } } }
        viewModelScope.launch { guard { api.install() }?.let { i -> _state.update { it.copy(installLabel = i.label) } } }
        if (_state.value.personalChannel) viewModelScope.launch { refreshPersonal() }
        viewModelScope.launch { refreshZones() }
    }

    /** Zones: only with the Users plugin (404 otherwise, which means "off"). */
    suspend fun refreshZones() {
        val api = api ?: return
        val zones = guard { api.zones() }
        _state.update { it.copy(zones = zones?.takeIf { z -> z.on }) }
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
        try {
            val p = api.myChannel()
            _state.update { it.copy(personal = p, personalProblem = null) }
            p.themeId?.takeIf { it !in _state.value.presets }?.let { loadPresets(it) }
        } catch (e: ApiException) {
            if (e.unlinked) markUnlinked() else _state.update { it.copy(personalProblem = e.detail) }
        } catch (_: Exception) {
        }
    }

    suspend fun loadPresets(themeId: String) {
        val api = api ?: return
        val presets = runCatching { api.themePresets(themeId) }.getOrDefault(ThemePresets())
        _state.update { it.copy(presets = it.presets + (themeId to presets)) }
    }

    fun loadPresetsAsync(themeId: String) {
        viewModelScope.launch { loadPresets(themeId) }
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
            type.startsWith("zone.") || type.startsWith("link.") -> {
                refreshZones()
                refreshSessionsSoon()
            }
            type.startsWith("channel.") || type.startsWith("sequence.") || type == "playlist.entry" -> refreshSessionsSoon()
            type == "theme.changed" -> {
                field("theme_id")?.let { id -> _state.update { it.copy(presets = it.presets - id) }; loadPresets(id) }
                api?.let { a -> guard { a.themes() }?.let { t -> _state.update { it.copy(themes = t) } } }
            }
            type.startsWith("theme.") -> api?.let { a -> guard { a.themes() }?.let { t -> _state.update { it.copy(themes = t) } } }
            type.startsWith("playlist.") -> api?.let { a -> guard { a.playlists() }?.let { p -> _state.update { it.copy(playlists = p) } } }
            type.startsWith("group.") -> api?.let { a -> guard { a.groups() }?.let { g -> _state.update { it.copy(groups = g) } } }
            type.startsWith("spaces.") -> {
                refreshSpeakers()
                if (_state.value.spaces != null) refreshSpaces()
            }
            type.startsWith("speaker.") || type.startsWith("settings.") -> refreshSpeakers()
        }
    }

    /** The web UI polls every 15 s; the app keeps that as a safety net under the event stream. */
    private fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (visible) {
                    refreshSessions()
                    if (_state.value.zones != null) refreshZones()
                }
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

    fun leaveLink(session: Session, linkId: String) = act {
        it.leaveLink(linkId, session.id)
        refreshZones()
        refreshSessions()
    }

    fun rejoinLink(session: Session, linkId: String) = act {
        it.rejoinLink(linkId, session.id)
        refreshZones()
        refreshSessions()
    }

    fun unlink(linkId: String) = act {
        it.removeLink(linkId)
        refreshZones()
        refreshSessions()
    }

    /** Link channels (1.6): errors go back to the dialog, shown under the form (not a toast). */
    fun saveLink(linkId: String?, body: JsonObject, onError: (String) -> Unit, onDone: () -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            if (linkId == null) api.createLink(body) else api.updateLink(linkId, body)
            onDone()
            refreshZones()
            refreshSessions()
        } catch (e: ApiException) {
            if (e.unlinked) markUnlinked() else onError(if (e.detail.startsWith("[")) "Request failed" else e.detail)
        } catch (_: Exception) {
            onError("Can't reach the server.")
        }
    }

    fun stopCasting(session: Session, outputId: String) = act {
        it.stopOutput(session.id, outputId)
        refreshSessions()
    }

    /** Create or edit from the channel editor; [onDone] closes it (1.4). */
    fun saveChannel(
        edit: Session?,
        body: JsonObject,
        playlistId: String?,
        clearPlaylistFirst: Boolean,
        zoneChanges: JsonObject? = null,
        onDone: () -> Unit,
    ) {
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
                // Zones (admin): the changed owner / Kid mode / hours only (1.4)
                if (zoneChanges != null && zoneChanges.isNotEmpty()) {
                    api.updateZone(id, zoneChanges)
                    refreshZones()
                }
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
            val slot = try {
                if (personal) api.listenMyChannel() else api.listen(session.id)
            } catch (e: Exception) {
                // A channel's button just stays off (1.1); the personal channel says why (e.g. no theme)
                if (personal) fail(e)
                return@launch
            }
            val port = _state.value.info?.ports?.http ?: _state.value.record?.httpPort ?: return@launch
            val url = "http://${host ?: return@launch}:$port${slot.playPath}"
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

    // ---------- Collapsed cards: each channel's open/closed state, kept on the device ----------

    private val _expanded = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expanded: StateFlow<Map<String, Boolean>> = _expanded.asStateFlow()

    private fun expandedKey() = "expanded:${serverId.orEmpty()}"

    private fun loadExpanded() {
        val stored = prefs.getStringSet(expandedKey(), null) ?: emptySet()
        // "id=1" open, "id=0" closed; channels never toggled follow the screen's default
        _expanded.value = stored.mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1] == "1") } }.toMap()
    }

    fun setExpanded(sessionId: String, open: Boolean) {
        _expanded.update { it + (sessionId to open) }
        prefs.edit().putStringSet(expandedKey(), _expanded.value.map { (id, o) -> "$id=${if (o) 1 else 0}" }.toSet()).apply()
    }

    fun setListenVolume(volume: Int) {
        _listenVolume.value = volume
        prefs.edit().putInt("listenVolume", volume).apply()
        ListenController.state.value?.let { ListenController.setVolume(getApplication(), it.sessionId, volume) }
    }

    // ---------- Playlists (native-api.md Phase 2, Playlists) ----------

    suspend fun refreshPlaylists() {
        val api = api ?: return
        guard { api.playlists() }?.let { p -> _state.update { it.copy(playlists = p) } }
    }

    /** Preset names for every theme the playlists use (cached per theme). */
    fun loadPlaylistPresets() {
        val s = _state.value
        val known = s.themes.map { it.id }.toSet()
        s.playlists.flatMap { p -> p.entries.mapNotNull { it.themeId } }.distinct()
            .filter { it in known && it !in s.presets }
            .forEach { viewModelScope.launch { loadPresets(it) } }
    }

    /** Add (id null) or save; [onDone] gets the playlist so the list can open its row. */
    fun savePlaylist(id: String?, body: JsonObject, onDone: (Playlist) -> Unit) = act(if (id == null) "Playlist added" else "Playlist saved") {
        val saved = if (id == null) it.createPlaylist(body) else it.updatePlaylist(id, body)
        onDone(saved)
        refreshPlaylists()
        refreshSessions()
    }

    fun deletePlaylist(playlist: Playlist, onDone: () -> Unit = {}) = act("Playlist deleted") {
        it.deletePlaylist(playlist.id)
        onDone()
        refreshPlaylists()
        refreshSessions()
    }

    /** ⋯ › Duplicate: the copy shows right under the original until the next reload. */
    fun duplicatePlaylist(playlist: Playlist) = act("Duplicated") {
        val copy = it.createPlaylist(PlaylistRules.duplicateBody(playlist, _state.value.playlists.map { p -> p.name }))
        _state.update { s ->
            val list = s.playlists.filter { p -> p.id != copy.id }.toMutableList()
            val at = list.indexOfFirst { p -> p.id == playlist.id }
            if (at >= 0) list.add(at + 1, copy) else list.add(copy)
            s.copy(playlists = list)
        }
    }

    // ---------- Themes ----------

    suspend fun refreshThemeList() {
        val api = api ?: return
        guard { api.themes() }?.let { t -> _state.update { it.copy(themes = t) } }
    }

    suspend fun refreshCategories() {
        val api = api ?: return
        guard { api.categories() }?.let { c -> _state.update { it.copy(categories = c) } }
    }

    fun toggleFavorite(themeId: String) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val r = api.toggleFavorite(themeId)
            _state.update { s -> s.copy(themes = s.themes.map { if (it.id == themeId) it.copy(isFavorite = r.isFavorite) else it }) }
            toast(if (r.isFavorite) "Added to favorites" else "Removed from favorites", error = false)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Create theme; [onDone] gets the new theme's id when exactly one new theme appeared (null on error). */
    fun createTheme(name: String, category: String?, onDone: (ok: Boolean, newId: String?) -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val before = _state.value.themes.map { it.id }.toSet()
            val created = api.createTheme(name.trim())
            if (category != null && created.themeId != null) api.setThemeCategories(created.themeId, listOf(category))
            refreshThemeList()
            toast("Created \"${name.trim()}\"", error = false)
            val fresh = _state.value.themes.map { it.id }.filter { it !in before }
            onDone(true, fresh.singleOrNull())
        } catch (e: Exception) {
            failOr(e, "Failed to create theme")
            onDone(false, null)
        }
    }

    fun deleteTheme(theme: ThemeSummary) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.deleteTheme(theme.id)
            if (_preview.value?.themeId == theme.id) closePreview()
            _state.update { s -> s.copy(themes = s.themes.filter { it.id != theme.id }) }
            toast("Theme \"${theme.name}\" deleted", error = false)
        } catch (e: Exception) {
            failOr(e, "Failed to delete theme")
        }
    }

    fun refreshThemesNow() = viewModelScope.launch {
        val api = api ?: return@launch
        refreshCategories()
        toast("Rescanning themes...", error = true)
        try {
            api.refreshThemes()
            refreshThemeList()
            toast("Themes refreshed", error = false)
        } catch (e: Exception) {
            failOr(e, "Failed to refresh themes")
        }
    }

    /** Export: the zip's name and bytes, handed to [save] (the system's save dialog). */
    fun exportTheme(theme: ThemeSummary, save: (String, ByteArray) -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        toast("Preparing export...", error = true)
        try {
            val (name, bytes) = api.exportTheme(theme.id)
            save(name, bytes)
        } catch (e: Exception) {
            failOr(e, "Failed to export theme")
        }
    }

    fun importTheme(fileName: String, bytes: ByteArray) = viewModelScope.launch {
        val api = api ?: return@launch
        toast("Importing theme...", error = true)
        try {
            val r = api.importTheme(fileName, bytes)
            api.refreshThemes()
            refreshThemeList()
            toast("Imported \"${r.themeFolder}\" (${r.filesExtracted} files)", error = false)
        } catch (e: Exception) {
            failOr(e, "Failed to import theme")
        }
    }

    fun addCategory(name: String, onDone: () -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.addCategory(ThemeRules.collapseSpaces(name))
            refreshCategories()
            onDone()
        } catch (e: Exception) {
            failOr(e, "Failed to add category")
        }
    }

    fun deleteCategory(name: String, onDone: () -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.deleteCategory(name)
            onDone()
            refreshCategories()
            refreshThemeList()
            toast("Category \"$name\" deleted", error = false)
        } catch (e: Exception) {
            failOr(e, "Failed to delete category")
        }
    }

    // ---------- Theme preview: `/stream/<theme id>` played on this phone only ----------

    data class Preview(val themeId: String, val name: String, val paused: Boolean = false, val volume: Int = 80)

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()
    private var previewPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    fun togglePreview(theme: ThemeSummary) {
        if (_preview.value?.themeId == theme.id) return closePreview()
        val host = host ?: return
        val port = _state.value.info?.ports?.http ?: _state.value.record?.httpPort ?: return
        val player = previewPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(getApplication()).build().also { p ->
            previewPlayer = p
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    toast("Failed to load theme stream", error = true)
                    closePreview()
                }
            })
        }
        val volume = _preview.value?.volume ?: 80
        _preview.value = Preview(theme.id, theme.name, volume = volume)
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri("http://$host:$port/stream/${android.net.Uri.encode(theme.id)}"))
        player.volume = volume / 100f
        player.prepare()
        player.play()
    }

    fun pausePreview() {
        val p = _preview.value ?: return
        val player = previewPlayer ?: return
        if (p.paused) player.play() else player.pause()
        _preview.value = p.copy(paused = !p.paused)
    }

    fun setPreviewVolume(volume: Int) {
        _preview.value = _preview.value?.copy(volume = volume)
        previewPlayer?.volume = volume / 100f
    }

    fun closePreview() {
        previewPlayer?.stop()
        previewPlayer?.clearMediaItems()
        _preview.value = null
    }

    // ---------- Phase 3 Settings pages ----------

    suspend fun refreshConnectionPage() {
        val api = api ?: return
        val core = runCatching { api.json("GET", "connection") }.isSuccess
        val plugins = !core && runCatching { (api.json("GET", "extensions")["connections"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true }.getOrDefault(false)
        _state.update { it.copy(connectionPage = core || plugins) }
    }


    /** Settings › Audio Settings: saved values and the unsaved draft, kept while the app runs. */
    var audioSaved by androidx.compose.runtime.mutableStateOf<app.sonorium.ui.server.AudioValues?>(null)
    var audioDraft by androidx.compose.runtime.mutableStateOf<app.sonorium.ui.server.AudioValues?>(null)

    /** Settings › Plugins: typed form values and open forms per plugin, and the tab, kept while the app runs. */
    val pluginForms = androidx.compose.runtime.mutableStateMapOf<String, Map<String, String>>()
    val pluginOpen = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var pluginsCatalogTab by androidx.compose.runtime.mutableStateOf(false)

    /** A plugin went on/off, in or out: perms and plugin-made menu entries may change. */
    fun afterPluginsChanged() = viewModelScope.launch {
        val api = api ?: return@launch
        guard { api.me() }?.let { me -> _state.update { it.copy(me = me) } }
        refreshConnectionPage()
        refreshZones()
    }

    /** Settings › Logs: what the page collected, its filter, search and pause, kept while the app runs. */
    class LogsHolder {
        var entries by androidx.compose.runtime.mutableStateOf(emptyList<LogsRules.Entry>())
        /** What the list shows; frozen while paused. */
        var shown by androidx.compose.runtime.mutableStateOf(emptyList<LogsRules.Entry>())
        var after = 0L
        var version = ""
        var filter by androidx.compose.runtime.mutableStateOf("Info")
        var search by androidx.compose.runtime.mutableStateOf("")
        var paused by androidx.compose.runtime.mutableStateOf(false)
        var meta by androidx.compose.runtime.mutableStateOf("")
    }
    val logs = LogsHolder()

    /** Settings › Advanced: the features as they ran at the first look after connect / restart (`advancedRunning`). */
    var advancedRunning: Map<String, Boolean>? = null

    /** After Home Assistant is removed: speakers, groups, channels and network info change (`afterHomeAssistantRemoved`). */
    fun afterHomeAssistantRemoved() = viewModelScope.launch {
        refreshSpeakers()
        refreshGroups()
        refreshSessions()
        refreshNetwork()
    }

    /** One server call for a page; an unlinked answer forgets the token. */
    suspend fun <T> call(block: suspend (SonoriumApi) -> T): Result<T> {
        val api = api ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            Result.success(block(api))
        } catch (e: ApiException) {
            if (e.unlinked) markUnlinked()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The message a page shows for a failed call (`error.message || fallback`). */
    fun messageOf(e: Throwable?, fallback: String): String = when (e) {
        is ApiException -> e.firstMessage ?: e.detail.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: fallback
        null -> fallback
        else -> fallback.ifEmpty { "Can't reach the server." }
    }

    /** `spWaitForRestart`: 3 s, then up to 30 tries of GET /install every 2 s; then everything reloads. */
    fun waitForRestart() = viewModelScope.launch {
        val api = api ?: return@launch
        delay(3_000)
        repeat(30) {
            if (runCatching { api.install() }.isSuccess) {
                loadAll()
                _restarted.value = System.nanoTime()
                return@launch
            }
            delay(2_000)
        }
        toast("Sonorium is taking a while to restart. Refresh the page in a moment.", error = true)
    }

    private val _restarted = MutableStateFlow(0L)
    /** Changes after a restart finished: pages refetch their own data. */
    val restarted: StateFlow<Long> = _restarted.asStateFlow()

    // ---------- Theme Editor (Phase 3) ----------

    private val _editor = MutableStateFlow<ThemeEditor?>(null)
    val editor: StateFlow<ThemeEditor?> = _editor.asStateFlow()

    fun openThemeEditor(theme: ThemeSummary) {
        val api = api ?: return
        val client = client ?: return
        closePreview()
        _editor.value?.close()
        val fresh = _state.value.theme(theme.id) ?: theme
        _editor.value = ThemeEditor(
            fresh, api, viewModelScope, getApplication(), client,
            mixUrl = { id ->
                val port = _state.value.info?.ports?.http ?: _state.value.record?.httpPort
                host?.let { h -> port?.let { "http://$h:$it/stream/${android.net.Uri.encode(id)}?preview=${System.currentTimeMillis()}" } }
            },
            toast = { text, error -> toast(text, error) },
            onThemeSaved = { viewModelScope.launch { refreshThemeList() } },
        )
    }

    /** Closes the editor; channel cards on the theme refetch its presets (`refreshSessionPresets`). */
    fun closeThemeEditor() {
        val e = _editor.value ?: return
        e.close()
        _editor.value = null
        if (_state.value.presets.containsKey(e.themeId) || _state.value.sessions.any { it.themeId == e.themeId }) loadPresetsAsync(e.themeId)
    }

    // ---------- Status ----------

    /** One `GET /now-playing`; errors are ignored (the page simply polls again). */
    suspend fun nowPlaying(): NowPlaying? {
        val api = api ?: return null
        return guard { api.nowPlaying() }
    }

    // ---------- Settings › Speakers ----------

    fun loadSpeakersPage() = viewModelScope.launch {
        val api = api ?: return@launch
        refreshSpeakers()
        refreshNetwork()
        // 403/404 → treated as "has HA" (the web UI's rule)
        val conn = guard { api.connection() }
        _state.update { it.copy(hasHa = conn == null || !conn.haUrl.isNullOrEmpty()) }
    }

    private suspend fun refreshNetwork() {
        val api = api ?: return
        val net = try { api.networkSpeakers() } catch (_: Exception) { null }
        _state.update { it.copy(network = net) }
    }

    fun setSpeakerUsed(entityId: String, on: Boolean) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.setSpeakerUsed(entityId, on)
        } catch (e: Exception) {
            fail(e)
        }
        refreshSpeakers()
    }

    /** `saveSpeakerSettings`: reload the hierarchy, then the toast. */
    fun saveSpeaker(entityId: String, body: JsonObject, success: String?) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.speakerSettings(entityId, body)
            guard { api.speakerHierarchy() }?.let { h -> _state.update { it.copy(hierarchy = h) } }
            success?.let { toast(it, error = false) }
        } catch (e: Exception) {
            failOr(e, "Failed to save speaker")
            _state.update { it.copy(hierarchy = it.hierarchy?.copy()) }
        }
    }

    /** Test sound; [onDone] gets the busy time in seconds (0 on error). */
    fun testSpeaker(entityId: String, name: String, onDone: (Double) -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val r = api.testSpeaker(entityId)
            toast("Playing a test sound on $name", error = false)
            onDone(r.seconds ?: 4.0)
        } catch (e: Exception) {
            failOr(e, "Test sound failed")
            onDone(0.0)
        }
    }

    fun rescanSpeakers(onDone: () -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val r = api.refreshSpeakers()
            refreshSpeakers()
            refreshNetwork()
            toast("Found ${r.totalSpeakers} speaker${if (r.totalSpeakers == 1) "" else "s"}", error = false)
        } catch (e: Exception) {
            failOr(e, "Rescan failed")
        }
        onDone()
    }

    fun removeManualSpeaker(id: String) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            api.removeManualSpeaker(id)
            refreshSpeakers()
            toast("Speaker removed", error = false)
        } catch (e: Exception) {
            failOr(e, "Failed to remove speaker")
        }
    }

    suspend fun checkManualSpeaker(body: JsonObject): Result<app.sonorium.net.ManualResult> {
        val api = api ?: return Result.failure(IllegalStateException())
        return try {
            Result.success(api.checkManualSpeaker(body))
        } catch (e: ApiException) {
            if (e.unlinked) markUnlinked()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addManualSpeaker(body: JsonObject, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val r = api.addManualSpeaker(body)
            refreshSpeakers()
            refreshNetwork()
            val name = r.name ?: (body["name"] as? JsonPrimitive)?.content.orEmpty()
            toast(if (r.mergedInto != null) "Added $name (same device as a Home Assistant speaker)" else "Added $name", error = false)
            onDone(true)
        } catch (e: Exception) {
            failOr(e, "Could not add the speaker")
            onDone(false)
        }
    }

    // ---------- Speaker Groups ----------

    suspend fun refreshGroups() {
        val api = api ?: return
        guard { api.groups() }?.let { g -> _state.update { it.copy(groups = g) } }
    }

    fun saveGroup(id: String?, body: JsonObject, onDone: (SpeakerGroup) -> Unit) = act(if (id == null) "Group created" else "Group updated") {
        val saved = if (id == null) it.createGroup(body) else it.updateGroup(id, body)
        onDone(saved)
        refreshGroups()
    }

    fun deleteGroup(group: SpeakerGroup) = act("Group deleted") {
        it.deleteGroup(group.id)
        refreshGroups()
    }

    fun refreshHierarchy() {
        viewModelScope.launch { refreshSpeakers() }
    }

    // ---------- Floors & Areas: every write answers the whole object; no success toasts ----------

    fun refreshSpaces() = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val spaces = api.spaces()
            _state.update { it.copy(spaces = spaces) }
        } catch (e: Exception) {
            _state.update { it.copy(spaces = Spaces()) }
            fail(e)
        }
    }

    /** [onDone] runs only on success (the add dialog closes then). */
    fun changeSpaces(onDone: () -> Unit = {}, block: suspend (SonoriumApi) -> Spaces) = viewModelScope.launch {
        val api = api ?: return@launch
        try {
            val spaces = block(api)
            _state.update { it.copy(spaces = spaces) }
            onDone()
            refreshSpeakers()
        } catch (e: Exception) {
            // Re-render reverts the field to the server's value
            _state.update { it.copy(spaces = it.spaces?.copy()) }
            fail(e)
        }
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

    /** Like [fail], with the web UI's fallback text for an empty message. */
    private suspend fun failOr(e: Exception, fallback: String) {
        if (e is ApiException && !e.unlinked && e.detail.isBlank()) toast(fallback, error = true) else fail(e)
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
        _editor.value?.close()
        previewPlayer?.release()
        previewPlayer = null
        events?.cancel()
        super.onCleared()
    }

    private companion object {
        const val EVENT_TYPES =
            "channel.*,sequence.*,playlist.*,theme.*,zone.*,link.*,group.*,spaces.*,settings.*,speaker.*,sonorium.started"
    }
}
