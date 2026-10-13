package app.sonorium.local

import android.content.Context
import android.util.Log
import app.sonorium.SonoriumApp
import app.sonorium.cast.CastController
import app.sonorium.engine.PhonePlayer
import app.sonorium.net.Session
import app.sonorium.net.SonoriumApi
import app.sonorium.net.ThemeSummary
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer

/**
 * This device as an entry of its own: the app's usual `/api/...` requests (the same screens as for a
 * Sonorium server) answered in the app. Its themes are the themes folder's, its one channel is the mix
 * this device plays ([PhonePlayer]), its playlists are [PhonePlaylists]; the Theme Editor's requests go to
 * [PhoneThemeApi] (Core's own code). What a device doesn't have (speakers, plugins, accounts, …) it doesn't
 * report in `capabilities`, and those routes answer 404 as on a server without them.
 */
class DeviceApi(private val context: Context) : Interceptor {
    private val sapp get() = context.applicationContext as SonoriumApp
    private val store get() = sapp.themeStore

    /** What changed, as a server's event types ("channel.updated", "theme.changed", "playlist.changed"). */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** The Theme Editor's API per theme folder, opened once. */
    private val editors = mutableMapOf<String, PhoneThemeApi>()

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != PhoneThemeApi.HOST) return chain.proceed(request)
        val s = request.url.pathSegments.dropWhile { it.isEmpty() }.let { if (it.firstOrNull() == "api") it.drop(1) else it }
        return try {
            route(chain, request, s)
        } catch (e: Answer) {
            respond(request, e.status, error(e.message ?: ""))
        } catch (e: PhonePlaylists.Problem) {
            respond(request, e.status, error(e.message ?: ""))
        } catch (e: app.sonorium.engine.ThemePrep.Problem) {
            respond(request, 409, error(e.message ?: ""))
        } catch (e: ThemeStore.ImportProblem) {
            respond(request, 400, error(e.message ?: ""))
        } catch (e: Exception) {
            Log.e(TAG, "${request.method} ${s.joinToString("/")} failed", e)
            respond(request, 500, error("Something went wrong on this device"))
        }
    }

    private class Answer(val status: Int, message: String) : Exception(message)

    private fun route(chain: Interceptor.Chain, request: Request, s: List<String>): Response {
        val m = request.method
        // Beyond playing the sample (more themes, editing, playlists), the device needs Pro, as its screens ask first
        if (!sapp.entitlement.isPro.value && m != "GET" && (s.firstOrNull() == "playlists" || s.firstOrNull() == "categories" ||
                (s.firstOrNull() == "themes" && s.getOrNull(2) != "presets"))) {
            throw Answer(403, "Unlock Pro to change themes and playlists on this device")
        }
        fun ok(body: JsonElement, status: Int = 200) = respond(request, status, body.toString())
        when {
            s == listOf("info") -> return ok(info())
            // A theme's live mix for previews (the server's /stream/<id>): a second engine on this device
            s.size == 2 && s[0] == "stream" && m == "GET" -> {
                val t = theme(s[1])
                val prepared = runBlocking { PhonePlayer.ensurePrepared(sapp, t) }
                val source = app.sonorium.engine.PreviewEngine.open(context, prepared)
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(object : okhttp3.ResponseBody() {
                        override fun contentType() = "audio/wav".toMediaType()
                        override fun contentLength() = -1L
                        private val buffered = source.buffer()
                        override fun source(): okio.BufferedSource = buffered
                    }).build()
            }
            s == listOf("auth", "me") -> return ok(buildJsonObject {
                put("kind", "device")
                put("perms", buildJsonArray { PERMS.forEach { add(JsonPrimitive(it)) } })
            })
            s == listOf("sessions") && m == "GET" -> return ok(buildJsonArray { add(session()) })
            s == listOf("me", "channel") && m == "GET" -> return ok(session())
            s == listOf("me", "channel") && m == "PUT" -> return ok(updateChannel(body(request)))
            s.size >= 2 && s[0] == "sessions" && s[1] == CHANNEL_ID -> return ok(channelAction(m, s.drop(2), request))
            s == listOf("themes") && m == "GET" -> return ok(themes())
            s == listOf("themes", "refresh") -> { refreshPlaylists(); return ok(JsonObject(mapOf("status" to JsonPrimitive("ok")))) }
            s == listOf("themes", "create") && m == "POST" -> return ok(createTheme(body(request)))
            s == listOf("themes", "import") && m == "POST" -> return ok(importTheme(request))
            s == listOf("categories") && m == "GET" -> return ok(buildJsonObject {
                put("categories", buildJsonArray { categories().forEach { add(JsonPrimitive(it)) } })
            })
            s.firstOrNull() == "categories" -> return ok(JsonObject(mapOf("status" to JsonPrimitive("ok"))))
            s.firstOrNull() == "playlists" -> return ok(playlists(m, s.getOrNull(1), request), if (m == "POST") 201 else 200)
            s == listOf("groups") && m == "GET" -> return ok(JsonArray(emptyList()))
            s == listOf("settings") && m == "GET" -> return ok(audioSettings())
            s == listOf("settings") && m == "PUT" -> return ok(saveAudioSettings(body(request)))
            s == listOf("extensions") -> return ok(buildJsonObject { put("connections", JsonArray(emptyList())); put("pages", JsonArray(emptyList())) })
            s.size == 2 && s[0] == "themes" && m == "DELETE" -> return ok(deleteTheme(s[1]))
            s.size == 3 && s[0] == "themes" && s[2] == "favorite" && m == "POST" -> return ok(favorite(s[1]))
            s.size == 3 && s[0] == "themes" && s[2] == "export" && m == "GET" -> return export(request, s[1])
            // A linked file's ▶ preview: the file in the theme it comes from
            s.size == 5 && s[0] == "themes" && s[2] == "tracks" && s[4] == "audio" && m == "GET" -> linkedSource(s[1], s[3])?.let { (src, key) ->
                return editorFor(src).audio(request, key)
            }
            s.size >= 3 && s[0] == "themes" -> {
                // Linking from another theme: its prepared copy is where Core reads it
                if (s.getOrNull(2) == "links") {
                    if (s.getOrNull(3) == "source") s.getOrNull(4)?.let { editorFor(it) }
                    if (s.size == 3 && m == "POST") (body(request)["theme"] as? JsonPrimitive)?.contentOrNullSafe()?.let { runCatching { editorFor(it) } }
                    if (s.size == 5 && s[4] == "copy-in") linkedSource(s[1], s[3])?.let { (src, _) -> runCatching { editorFor(src) } }
                }
                // The Theme Editor's routes (and a theme's presets): Core's code on the theme's prepared copy
                val editor = editorFor(s[1])
                val response = editor.intercept(chain)
                if (m != "GET" && response.isSuccessful) changed("theme.changed")
                return response
            }
        }
        // Not on this device (speakers, zones, plugins, logs, …): as on a server without them
        throw Answer(404, "Not found")
    }

    // ---------- info ----------

    private fun info(): JsonObject = buildJsonObject {
        put("id", SonoriumApp.DEVICE_ID)
        put("name", sapp.deviceRecord().label)
        put("version", app.sonorium.BuildConfig.VERSION_NAME)
        put("api", 1)
        put("ports", buildJsonObject { put("http", 0) })
        put("personal_channel", true)
        put("capabilities", buildJsonArray { CAPABILITIES.forEach { add(JsonPrimitive(it)) } })
    }

    // ---------- the channel: what this device plays ----------

    @Volatile private var listed: Pair<Long, List<LocalTheme>>? = null

    /** The themes in the themes folder (read again after 2 s, or at once after a change made here). */
    private fun themesNow(): List<LocalTheme> {
        listed?.let { (at, list) -> if (System.currentTimeMillis() - at < 2_000) return list }
        // The sample always; the themes folder's with Pro
        val folder = if (sapp.entitlement.isPro.value) runBlocking { runCatching { store.list() }.getOrDefault(emptyList()) } else emptyList()
        val list = SampleTheme.with(context, folder)
        listed = System.currentTimeMillis() to list
        return list
    }

    private fun idOf(t: LocalTheme) = themeId(t)

    private fun session(): JsonObject {
        val themes = themesNow()
        PhonePlayer.sync(context, themes)
        val st = PhonePlayer.state.value
        val now = st.now
        val playlistOn = st.playlistMode && st.playlistId != null
        val cast = CastController.state.value.speaker
        // A playlist chosen: what the engine plays of it, else (themes getting ready, or after a restart) its first entry
        if (playlistOn && PhonePlaylists.playlists.value.isEmpty()) refreshPlaylists()
        val running = now.playlist?.takeIf { playlistOn && it.id == st.playlistId }
        val chosen = if (playlistOn) PhonePlaylists.playlists.value.firstOrNull { it.id == st.playlistId } else null
        val firstEntry = chosen?.entries?.firstOrNull()
        val playlistStatus = running ?: chosen?.let { p ->
            app.sonorium.net.PlaylistStatus(id = p.id, name = p.name, entryIndex = 0, position = 0, entries = p.entries.size, themeId = firstEntry?.themeId)
        }
        val entry = if (running != null) now.playlistEntry else firstEntry
        val themeId = if (playlistOn) playlistStatus?.themeId ?: st.theme?.let(::idOf) else st.theme?.let(::idOf)
        val presetId = if (playlistOn) entry?.presetId ?: (if (running != null) now.presetId else null) else st.presetId
        val sequenceId = if (playlistOn) entry?.sequenceId else st.sequenceId
        val session = Session(
            id = CHANNEL_ID,
            name = MY_CHANNEL,
            themeId = themeId,
            presetId = presetId,
            volume = st.volume,
            isPlaying = st.playing || st.preparing != null,
            speakerSummary = cast?.name ?: sapp.deviceRecord().label,
            sequenceId = sequenceId,
            presetChoice = sequenceId?.let { "seq:$it" } ?: presetId ?: "",
            playlistId = if (st.playlistMode) st.playlistId else null,
            sequence = if (!playlistOn) now.sequence?.takeIf { st.sequenceId != null } else null,
            playlist = if (playlistOn) playlistStatus else null,
            speakersMuted = st.muted,
            sleepSecondsLeft = PhonePlayer.sleepSecondsLeft(),
            sleepUntil = PhonePlayer.sleepSecondsLeft()?.let { java.time.Instant.now().plusSeconds(it.toLong()).toString() },
            recent = now.recent,
            announcements = false,
            personal = true,
        )
        return SonoriumApi.json.encodeToJsonElement(Session.serializer(), session) as JsonObject
    }

    private fun updateChannel(body: JsonObject): JsonObject {
        (body["theme_id"] as? JsonPrimitive)?.contentOrNullSafe()?.let { id ->
            val t = themesNow().firstOrNull { idOf(it) == id } ?: throw Answer(404, "Theme not found")
            if (PhonePlayer.state.value.playlistMode) PhonePlayer.setPlaylistMode(context, false)
            PhonePlayer.choose(context, t)
        }
        (body["preset_id"] as? JsonPrimitive)?.let { p ->
            val v = p.contentOrNullSafe().orEmpty()
            when {
                v.startsWith("seq:") -> PhonePlayer.startSequence(v.removePrefix("seq:"))
                else -> PhonePlayer.setPreset(v.ifEmpty { null })
            }
        }
        (body["volume"] as? JsonPrimitive)?.intOrNull?.let { PhonePlayer.setVolume(context, it.coerceIn(0, 100)) }
        changed("channel.updated")
        return session()
    }

    private fun channelAction(m: String, rest: List<String>, request: Request): JsonObject {
        when {
            rest.isEmpty() && m == "GET" -> return session()
            rest.isEmpty() && m == "PUT" -> return updateChannel(body(request))
            rest == listOf("play") && m == "POST" -> runOnMain { PhonePlayer.play(context) }
            rest == listOf("stop") && m == "POST" -> runOnMain { PhonePlayer.stop(context) }
            rest == listOf("next") && m == "POST" -> PhonePlayer.next()
            rest == listOf("sleep") && m == "GET" -> return sleepAnswer()
            rest == listOf("sleep") && m == "PUT" -> {
                val minutes = (body(request)["minutes"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                    ?.takeIf { it > 0 && it <= 240 } ?: throw Answer(400, "'minutes' must be more than 0 and at most 240")
                if (!PhonePlayer.setSleep(minutes)) throw Answer(409, "The channel isn't playing")
                changed("channel.sleep_timer")
                return sleepAnswer()
            }
            rest == listOf("sleep") && m == "DELETE" -> {
                val had = PhonePlayer.cancelSleep()
                changed("channel.sleep_timer")
                return buildJsonObject { put("cancelled", had); put("channel_id", CHANNEL_ID); put("sleep_until", JsonNull); put("seconds_left", JsonNull) }
            }
            rest == listOf("speakers-muted") && m == "PUT" -> {
                val muted = (body(request)["muted"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (muted != PhonePlayer.state.value.muted) PhonePlayer.toggleMute()
            }
            rest == listOf("playlist") && m == "PUT" -> {
                val id = (body(request)["playlist_id"] as? JsonPrimitive)?.contentOrNullSafe() ?: throw Answer(400, "playlist_id is required")
                if (PhonePlaylists.playlists.value.none { it.id == id }) throw Answer(404, "Playlist not found")
                // As on a server: picking a playlist plays it
                runOnMain {
                    val playing = PhonePlayer.state.value.playing
                    PhonePlayer.choosePlaylist(context, id)
                    if (!playing) PhonePlayer.play(context)
                }
            }
            rest == listOf("playlist") && m == "DELETE" -> runOnMain { PhonePlayer.setPlaylistMode(context, false) }
            else -> throw Answer(404, "Not found")
        }
        changed("channel.updated")
        return session()
    }

    /** The source (theme id, track key) of linked file [key] of theme [id], or null when it isn't a linked file. */
    private fun linkedSource(id: String, key: String): Pair<String, String>? {
        val meta = runBlocking { runCatching { store.readFile(theme(id), "metadata.json") }.getOrNull() } ?: return null
        val link = runCatching { org.json.JSONObject(meta).optJSONObject("links")?.optJSONObject(key) }.getOrNull() ?: return null
        val src = link.optString("theme").ifEmpty { return null }
        val track = link.optString("track").ifEmpty { return null }
        return src to track
    }

    // ---------- Audio Settings ----------

    private fun audioSettings(): JsonObject = DeviceSettings.get(context).let { a ->
        buildJsonObject { put("crossfade_duration", a.crossfade); put("default_volume", a.volume); put("master_gain", a.gain) }
    }

    private fun saveAudioSettings(body: JsonObject): JsonObject {
        val now = DeviceSettings.get(context)
        fun n(k: String) = (body[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val audio = DeviceSettings.Audio(
            crossfade = (n("crossfade_duration") ?: now.crossfade).coerceIn(0.0, 60.0),
            volume = (n("default_volume")?.toInt() ?: now.volume).coerceIn(0, 100),
            gain = (n("master_gain")?.toInt() ?: now.gain).coerceIn(0, 100),
        )
        DeviceSettings.set(context, audio)
        runCatching { app.sonorium.engine.PhoneEngine.setAudio(audio.crossfade, audio.gain) }
        changed("settings.changed")
        return audioSettings()
    }

    private fun sleepAnswer(): JsonObject {
        val left = PhonePlayer.sleepSecondsLeft()
        return buildJsonObject {
            put("channel_id", CHANNEL_ID)
            if (left == null) { put("sleep_until", JsonNull); put("seconds_left", JsonNull) }
            else { put("sleep_until", java.time.Instant.now().plusSeconds(left.toLong()).toString()); put("seconds_left", left) }
        }
    }

    // ---------- themes ----------

    private fun themes(): JsonArray {
        val favorites = favoriteIds()
        return buildJsonArray {
            themesNow().forEach { t ->
                add(SonoriumApi.json.encodeToJsonElement(ThemeSummary.serializer(), ThemeSummary(
                    id = idOf(t), name = t.name, icon = t.icon, totalTracks = t.tracks, hasAudio = t.tracks > 0,
                    isFavorite = idOf(t) in favorites, categories = t.categories, description = t.description,
                )))
            }
        }
    }

    private fun theme(id: String): LocalTheme = themesNow().firstOrNull { idOf(it) == id } ?: throw Answer(404, "Theme not found")

    private fun editorFor(id: String): PhoneThemeApi {
        val t = theme(id)
        return synchronized(editors) {
            editors[t.folder] ?: runBlocking { PhoneThemeApi.openApi(context, store, t) }.also { editors[t.folder] = it }
        }
    }

    /** GET /themes/{id}/export: the server's export zip ("<folder>/<path>"), linked files copied in. */
    private fun export(request: Request, id: String): Response {
        val t = theme(id)
        editorFor(id)
        val root = PhoneThemeApi.themesRoot(context)
        val docs = SonoriumApi.json.parseToJsonElement(
            app.sonorium.engine.PhoneEngine.module(context, "phone_api")
                .callAttr("export_documents", File(root, PhoneThemeApi.safe(t.folder)).path, root.path).toString(),
        ) as JsonObject
        val prefix = t.folder
        val out = File.createTempFile("export", ".zip", context.cacheDir)
        val resolver = context.contentResolver
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, input: java.io.InputStream) {
                zip.putNextEntry(java.util.zip.ZipEntry("$prefix/$name"))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val files = runBlocking { store.files(t) }
            val skip = Regex("""^(metadata|presets)\.json(\..*)?$""")
            files.filter { !(it.path.indexOf('/') < 0 && skip.matches(it.path)) && it.path != ThemeStore.MARKER }
                .sortedBy { it.path }
                .forEach { f -> resolver.openInputStream(f.uri)?.let { put(f.path, it) } }
            val all = themesNow()
            (docs["linked"] as? JsonArray).orEmpty().forEach { e ->
                val row = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return@forEach
                if (row.size != 3) return@forEach
                val source = all.firstOrNull { PhoneThemeApi.safe(it.folder) == row[1] } ?: return@forEach
                val file = runBlocking { store.files(source) }.firstOrNull { it.path == row[2] } ?: return@forEach
                resolver.openInputStream(file.uri)?.let { put(row[0], it) }
            }
            put("metadata.json", ((docs["metadata"] as JsonPrimitive).content).byteInputStream())
            put("presets.json", ((docs["presets"] as JsonPrimitive).content).byteInputStream())
        }
        val safeName = t.folder.map { c -> if (c.isLetterOrDigit() || c in "._- ") c else '_' }.joinToString("")
        val bytes = out.readBytes().also { out.delete() }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Disposition", "attachment; filename=\"$safeName.zip\"")
            .body(bytes.toResponseBody("application/zip".toMediaType())).build()
    }

    private fun createTheme(body: JsonObject): JsonObject {
        val name = (body["name"] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()
        if (name.isEmpty()) throw Answer(400, "Theme name is required")
        val description = (body["description"] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()
        val icon = (body["icon"] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()
        val folder = name.lowercase().replace(Regex("""[^\w\s-]"""), "").replace(Regex("""[-\s]+"""), "_").trim('_')
        if (folder.isEmpty()) throw Answer(400, "Invalid theme name - could not generate folder name")
        if (themesNow().any { it.folder.equals(folder, ignoreCase = true) }) throw Answer(409, "Theme folder '$folder' already exists")
        val id = java.util.UUID.randomUUID().toString()
        val metadata = buildJsonObject {
            put("spec_version", 2); put("id", id); put("name", name)
            if (description.isNotEmpty()) put("description", description)
            if (icon.isNotEmpty() && icon != "🎵") put("icon", icon)
        }
        runBlocking { store.createTheme(folder, metadata.toString()) }
        refreshPlaylists()
        changed("theme.created")
        return buildJsonObject { put("status", "ok"); put("theme_id", id); put("message", "Theme '$name' created successfully") }
    }

    private fun deleteTheme(id: String): JsonObject {
        val t = theme(id)
        runBlocking {
            app.sonorium.server.PhoneThemeActions.removing(context, t)
            if (!store.remove(t)) throw Answer(500, "Couldn't delete “${t.name}”. Check that your themes folder is still there.")
            app.sonorium.server.PhoneThemeActions.removed(context, t)
        }
        synchronized(editors) { editors.remove(t.folder) }
        refreshPlaylists()
        changed("theme.deleted")
        return buildJsonObject { put("status", "ok") }
    }

    private fun importTheme(request: Request): JsonObject {
        val part = (request.body as? okhttp3.MultipartBody)?.parts?.firstOrNull() ?: throw Answer(400, "No file provided")
        val name = Regex("""filename="([^"]*)"""").find(part.headers?.get("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: "theme.zip"
        val bytes = Buffer().also { part.body.writeTo(it) }.readByteArray()
        val plan = runBlocking { store.importZip(bytes.inputStream(), name) }
        refreshPlaylists()
        changed("theme.created")
        return buildJsonObject { put("theme_folder", plan.folderName); put("files_extracted", plan.files.size) }
    }

    /** Favorites: kept on this device (not in the theme's files, so they don't change its package). */
    private fun favoritePrefs() = context.getSharedPreferences("device_api", Context.MODE_PRIVATE)
    private fun favoriteIds(): Set<String> = favoritePrefs().getStringSet("favorites", emptySet()).orEmpty()

    private fun favorite(id: String): JsonObject {
        theme(id)
        val now = favoriteIds().toMutableSet()
        val on = if (id in now) { now.remove(id); false } else { now.add(id); true }
        favoritePrefs().edit().putStringSet("favorites", now).apply()
        changed("theme.changed")
        return buildJsonObject { put("is_favorite", on) }
    }

    private fun categories(): List<String> = themesNow().flatMap { it.categories }.distinct()

    // ---------- playlists ----------

    private fun refreshPlaylists() = runBlocking { PhonePlaylists.refresh(context, store, themesNow()) }

    private fun playlists(m: String, id: String?, request: Request): JsonElement {
        if (m == "GET" && id == null) {
            refreshPlaylists()
            return buildJsonObject {
                put("playlists", SonoriumApi.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(app.sonorium.net.Playlist.serializer()), PhonePlaylists.playlists.value,
                ))
            }
        }
        return runBlocking {
            when {
                m == "POST" && id == null -> SonoriumApi.json.encodeToJsonElement(app.sonorium.net.Playlist.serializer(), PhonePlaylists.save(context, null, body(request)))
                m == "PUT" && id != null -> SonoriumApi.json.encodeToJsonElement(app.sonorium.net.Playlist.serializer(), PhonePlaylists.save(context, id, body(request)))
                m == "DELETE" && id != null -> {
                    PhonePlaylists.delete(context, id)
                    if (PhonePlayer.state.value.playlistId == id) PhonePlayer.setPlaylistMode(context, false)
                    buildJsonObject { put("status", "ok") }
                }
                else -> throw Answer(404, "Not found")
            }
        }.also { if (m != "GET") changed("playlist.changed") }
    }

    // ---------- helpers ----------

    private fun body(request: Request): JsonObject = request.body?.let { b ->
        runCatching { SonoriumApi.json.parseToJsonElement(Buffer().also { b.writeTo(it) }.readUtf8()) as? JsonObject }.getOrNull()
    } ?: JsonObject(emptyMap())

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun changed(type: String) {
        if (type.startsWith("theme.")) listed = null
        _events.tryEmit(type)
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun respond(request: Request, status: Int, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message(if (status in 200..299) "OK" else "Error")
            .body(body.toResponseBody(JSON)).build()

    private fun error(text: String) = buildJsonObject { put("detail", text); put("error", text) }.toString()

    companion object {
        private const val TAG = "DeviceApi"
        private val JSON = "application/json".toMediaType()
        /** The device's one channel: what it plays. */
        const val CHANNEL_ID = "device"
        private const val MY_CHANNEL = "My channel"

        /** What the device offers of the capabilities a Sonorium server can have (none of the server-only ones). */
        val CAPABILITIES = listOf("audio_settings")

        /** Everything a device's own user may do with its channel and themes. */
        private val PERMS = listOf("ch.view", "ch.play", "th.edit", "pl.edit", "st.change")

        /** A device theme's id: its metadata.json id, else its folder (as the server's ids). */
        fun themeId(t: LocalTheme) = t.metaId ?: PhoneThemeApi.safe(t.folder)
    }
}
