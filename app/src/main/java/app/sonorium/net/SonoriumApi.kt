package app.sonorium.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The server's HTTPS API. [client] must be pinned to the server's certificate
 * (or be a recording client for the first, token-less contact).
 */
class SonoriumApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String? = null,
) {
    suspend fun info(): ServerInfo = get("info")

    suspend fun pair(request: PairRequest): PairResult = post("pair", json.encodeToString(request))

    suspend fun pairLogin(request: PairLoginRequest): PairResult =
        post("pair/login", json.encodeToString(request))

    suspend fun me(): Me = get("auth/me")

    // ---------- Channels ----------

    suspend fun sessions(): List<Session> = get("sessions")

    suspend fun createSession(body: JsonObject): Session = send("POST", "sessions", body)

    /** Only the fields in [body] change (theme_id, preset_id, volume, custom_name, speakers, announcements). */
    suspend fun updateSession(id: String, body: JsonObject): Session = send("PUT", "sessions/$id", body)

    suspend fun deleteSession(id: String) {
        execute(request("sessions/$id").delete().build())
    }

    suspend fun play(id: String) {
        execute(request("sessions/$id/play").post(EMPTY).build())
    }

    suspend fun stop(id: String) {
        execute(request("sessions/$id/stop").post(EMPTY).build())
    }

    suspend fun setSpeakersMuted(id: String, muted: Boolean) {
        sendRaw("PUT", "sessions/$id/speakers-muted", buildJsonObject { put("muted", muted) })
    }

    suspend fun setAnnouncements(id: String, on: Boolean) {
        sendRaw("PUT", "sessions/$id/announcements", buildJsonObject { put("on", on) })
    }

    suspend fun setPlaylist(id: String, playlistId: String) {
        sendRaw("PUT", "sessions/$id/playlist", buildJsonObject { put("playlist_id", playlistId) })
    }

    suspend fun clearPlaylist(id: String) {
        execute(request("sessions/$id/playlist").delete().build())
    }

    suspend fun next(id: String) {
        execute(request("sessions/$id/next").post(EMPTY).build())
    }

    // ---------- Zones (Users plugin; 404 without it) ----------

    suspend fun zones(): ZonesView = get("zones")

    suspend fun leaveLink(linkId: String, channelId: String) {
        sendRaw("POST", "links/$linkId/leave", buildJsonObject { put("channel_id", channelId) })
    }

    suspend fun rejoinLink(linkId: String, channelId: String) {
        sendRaw("POST", "links/$linkId/rejoin", buildJsonObject { put("channel_id", channelId) })
    }

    suspend fun updateZone(channelId: String, body: JsonObject) {
        sendRaw("PUT", "zones/$channelId", body)
    }

    suspend fun createLink(body: JsonObject) {
        sendRaw("POST", "links", body)
    }

    suspend fun updateLink(linkId: String, body: JsonObject) {
        sendRaw("PUT", "links/$linkId", body)
    }

    suspend fun removeLink(linkId: String) {
        execute(request("links/$linkId").delete().build())
    }

    // ---------- Themes, playlists, speakers ----------

    suspend fun themes(): List<ThemeSummary> = get("themes")

    suspend fun themePresets(themeId: String): ThemePresets = get("themes/$themeId/presets")

    suspend fun categories(): List<String> = get<Categories>("categories").categories

    suspend fun refreshThemes() {
        execute(request("themes/refresh").post(EMPTY).build())
    }

    suspend fun toggleFavorite(themeId: String): FavoriteResult =
        decode(execute(request("themes/$themeId/favorite").post(EMPTY).build()))

    suspend fun createTheme(name: String): CreatedTheme =
        send("POST", "themes/create", buildJsonObject { put("name", name); put("description", ""); put("icon", "") })

    /** Replaces the theme's whole category list. */
    suspend fun setThemeCategories(themeId: String, categories: List<String>) {
        sendRaw("POST", "themes/$themeId/categories", buildJsonObject {
            put("categories", kotlinx.serialization.json.JsonArray(categories.map { JsonPrimitive(it) }))
        })
    }

    suspend fun deleteTheme(themeId: String) {
        execute(request("themes/$themeId").delete().build())
    }

    /** The theme as a zip: file name (from Content-Disposition) and bytes. */
    suspend fun exportTheme(themeId: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        client.newCall(request("themes/$themeId/export").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException.from(response.code, response.body.string(), null)
            val name = response.header("Content-Disposition")?.let { Regex("filename=\"?([^\";]+)").find(it)?.groupValues?.get(1) } ?: "$themeId.zip"
            name to response.body.bytes()
        }
    }

    suspend fun importTheme(fileName: String, bytes: ByteArray): ImportedTheme {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody("application/zip".toMediaType()))
            .build()
        return decode(execute(request("themes/import").post(body).build()))
    }

    suspend fun addCategory(name: String) {
        sendRaw("POST", "categories", buildJsonObject { put("name", name) })
    }

    suspend fun deleteCategory(name: String) {
        val url = baseUrl.newBuilder().addPathSegment("api").addPathSegment("categories").addPathSegment(name).build()
        execute(request("categories").url(url).delete().build())
    }

    suspend fun playlists(): List<Playlist> = get<Playlists>("playlists").playlists

    suspend fun createPlaylist(body: JsonObject): Playlist = send("POST", "playlists", body)

    suspend fun updatePlaylist(id: String, body: JsonObject): Playlist = send("PUT", "playlists/$id", body)

    suspend fun deletePlaylist(id: String) {
        execute(request("playlists/$id").delete().build())
    }

    suspend fun groups(): List<SpeakerGroup> = get("groups")

    suspend fun createGroup(body: JsonObject): SpeakerGroup = send("POST", "groups", body)

    suspend fun updateGroup(id: String, body: JsonObject): SpeakerGroup = send("PUT", "groups/$id", body)

    suspend fun deleteGroup(id: String) {
        execute(request("groups/$id").delete().build())
    }

    // ---------- Floors & Areas: every write answers the whole spaces object ----------

    suspend fun spaces(): Spaces = get("spaces")

    suspend fun addFloor(body: JsonObject): Spaces = send("POST", "spaces/floors", body)

    suspend fun addArea(body: JsonObject): Spaces = send("POST", "spaces/areas", body)

    suspend fun updateFloor(localId: String, body: JsonObject): Spaces = send("PUT", "spaces/floors/$localId", body)

    suspend fun updateArea(localId: String, body: JsonObject): Spaces = send("PUT", "spaces/areas/$localId", body)

    suspend fun deleteFloor(localId: String): Spaces = decode(execute(request("spaces/floors/$localId").delete().build()))

    suspend fun deleteArea(localId: String): Spaces = decode(execute(request("spaces/areas/$localId").delete().build()))

    suspend fun speakerHierarchy(): SpeakerHierarchy = get("speakers/hierarchy")

    suspend fun enabledSpeakers(): List<String> = get<EnabledSpeakers>("settings/speakers").enabledSpeakers

    suspend fun setSpeakerUsed(entityId: String, on: Boolean) {
        sendRaw("POST", "settings/speakers/${if (on) "enable" else "disable"}", buildJsonObject { put("entity_id", entityId) })
    }

    /** Only the keys sent change; JSON null resets that key. */
    suspend fun speakerSettings(entityId: String, body: JsonObject) {
        sendRaw("PUT", "speakers/${seg(entityId)}/settings", body)
    }

    suspend fun testSpeaker(entityId: String): TestResult =
        decode(execute(request("speakers/${seg(entityId)}/test").post(EMPTY).build()))

    suspend fun refreshSpeakers(): RefreshResult = decode(execute(request("speakers/refresh").post(EMPTY).build()))

    suspend fun networkSpeakers(): NetworkSpeakers = get("network-speakers")

    suspend fun connection(): ConnectionInfo = get("connection")

    suspend fun checkManualSpeaker(body: JsonObject): ManualResult = send("POST", "speakers/manual/check", body)

    suspend fun addManualSpeaker(body: JsonObject): ManualResult = send("POST", "speakers/manual", body)

    suspend fun removeManualSpeaker(id: String) {
        execute(request("speakers/manual/${seg(id)}").delete().build())
    }

    /** Speaker ids (`media_player.x`, `net:cast:…`) are single path segments; OkHttp encodes the rest. */
    private fun seg(id: String): String = id

    // ---------- The personal channel (planned: /api/info.personal_channel) ----------

    suspend fun myChannel(): Session = get("me/channel")

    suspend fun updateMyChannel(body: JsonObject): Session = send("PUT", "me/channel", body)

    suspend fun listenMyChannel(holdSeconds: Int = HOLD_SECONDS): ListenSlot =
        send("POST", "me/channel/listen", buildJsonObject { put("hold_seconds", holdSeconds) })

    suspend fun stopOutput(sessionId: String, outputId: String) {
        execute(request("sessions/$sessionId/outputs/$outputId").delete().build())
    }

    suspend fun status(): ServerStatus = get("status")

    suspend fun nowPlaying(): NowPlaying = get("now-playing")

    suspend fun install(): InstallInfo = get("install")

    suspend fun stopListeningMyChannel() {
        execute(request("me/channel/listen").delete().build())
    }

    /** Unlinks this phone on the server (G1): the token stops working at once. */
    suspend fun unlinkSelf() {
        execute(request("devices/self").delete().build())
    }

    /** Starts (or keeps) a channel's mix for a listener; APP_API.md 9.3. */
    /** [holdSeconds]: how long the server keeps the slot after the stream drops (0-300). */
    suspend fun listen(sessionId: String, holdSeconds: Int = HOLD_SECONDS): ListenSlot =
        send("POST", "sessions/$sessionId/listen", buildJsonObject { put("hold_seconds", holdSeconds) })

    suspend fun stopListening(sessionId: String) {
        execute(request("sessions/$sessionId/listen").delete().build())
    }

    private suspend inline fun <reified T> get(path: String): T =
        decode(execute(request(path).get().build()))

    private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject): T =
        decode(sendRaw(method, path, body))

    private suspend fun sendRaw(method: String, path: String, body: JsonObject): String =
        execute(request(path).method(method, body.toString().toRequestBody(JSON)).build())

    private suspend inline fun <reified T> post(path: String, body: String): T =
        decode(execute(request(path).post(body.toRequestBody(JSON)).build()))

    private fun request(path: String): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegment("api").addPathSegments(path).build()
        return Request.Builder().url(url).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }
    }

    /** The pinned client and base URL, for the event stream. */
    fun eventStreamRequest(types: String, lastEventId: String?): Request =
        request("events/stream").apply {
            url(baseUrl.newBuilder().addPathSegment("api").addPathSegments("events/stream").addQueryParameter("types", types).build())
            header("Accept", "text/event-stream")
            lastEventId?.let { header("Last-Event-ID", it) }
        }.build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw ApiException.from(response.code, body, response.header("Retry-After"))
            body
        }
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    companion object {
        /** The newest `api` version this app understands (APP_API.md 11.3). */
        const val API_VERSION = 1

        /** A phone on flaky Wi-Fi keeps its listening slot this long (native-api.md 1.2). */
        const val HOLD_SECONDS = 120
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun baseUrl(host: String, port: Int): HttpUrl =
            HttpUrl.Builder().scheme("https").host(host).port(port).build()
    }
}

/**
 * An error answer. [detail] is the server's message for the user.
 * [unlinked] is the 401 `"pair": true` rule (APP_API.md 4.6): forget the token.
 */
class ApiException(
    val status: Int,
    val detail: String,
    val unlinked: Boolean,
    val starting: Boolean,
    val retryAfterSeconds: Int?,
) : Exception("HTTP $status: $detail") {
    companion object {
        fun from(status: Int, body: String, retryAfter: String?): ApiException {
            val obj = runCatching { SonoriumApi.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val detail = (obj?.get("detail") as? JsonPrimitive)?.content ?: body.take(200)
            fun flag(name: String) = (obj?.get(name) as? JsonPrimitive)?.booleanOrNull == true
            return ApiException(
                status = status,
                detail = detail,
                unlinked = status == 401 && flag("pair"),
                starting = status == 503 && flag("starting"),
                retryAfterSeconds = retryAfter?.toIntOrNull(),
            )
        }
    }
}
