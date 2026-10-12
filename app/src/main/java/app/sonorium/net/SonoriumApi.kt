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

    // ---------- Themes, playlists, speakers ----------

    suspend fun themes(): List<ThemeSummary> = get("themes")

    suspend fun themePresets(themeId: String): ThemePresets = get("themes/$themeId/presets")

    suspend fun playlists(): List<Playlist> = get<Playlists>("playlists").playlists

    suspend fun groups(): List<SpeakerGroup> = get("groups")

    suspend fun speakerHierarchy(): SpeakerHierarchy = get("speakers/hierarchy")

    suspend fun enabledSpeakers(): List<String> = get<EnabledSpeakers>("settings/speakers").enabledSpeakers

    // ---------- The personal channel (planned: /api/info.personal_channel) ----------

    suspend fun myChannel(): Session = get("me/channel")

    suspend fun updateMyChannel(body: JsonObject): Session = send("PUT", "me/channel", body)

    suspend fun listenMyChannel(holdSeconds: Int = HOLD_SECONDS): ListenSlot =
        send("POST", "me/channel/listen", buildJsonObject { put("hold_seconds", holdSeconds) })

    suspend fun stopOutput(sessionId: String, outputId: String) {
        execute(request("sessions/$sessionId/outputs/$outputId").delete().build())
    }

    suspend fun status(): ServerStatus = get("status")

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
