package app.sonorium.local

import android.content.Context
import android.util.Log
import app.sonorium.engine.PhoneEngine
import app.sonorium.engine.ThemePrep
import app.sonorium.net.SonoriumApi
import app.sonorium.net.ThemeSummary
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import okio.source

/**
 * The Theme Editor on a theme on this phone, "loopback": the editor (server/ThemeEditor.kt) sends its
 * usual `/api/themes/...` requests to [BASE_URL], and this interceptor answers them with `phone_api.py`,
 * which makes each change with the server's own Core code (sonorium.core.theme_edit) on the theme's
 * prepared copy (`files/engine/themes/<folder>`, as [ThemePrep] lays it out).
 *
 * After a request changed metadata.json or presets.json there, the files go back to where the theme
 * lives ([Source.write]: the user's themes folder) and an open engine re-reads them, so a playing mix
 * follows at once. `GET .../tracks/{key}/audio` (a track's ▶ preview) streams the theme's own file.
 * Requests run one at a time (the editor sends some together; each edit reads, changes and writes the files).
 */
class PhoneThemeApi(
    private val context: Context,
    /** The prepared copies (`files/engine/themes`) and the decoded audio beside them (`files/engine/pcm`). */
    private val themesRoot: File,
    private val pcmRoot: File,
    /** The theme's folder name under [themesRoot]: its id in the editor's requests. */
    val folder: String,
    private val source: Source,
) : Interceptor {
    /** Where the theme itself lives. */
    interface Source {
        /** A changed settings file (metadata.json, presets.json) written back. Throws when it can't be. */
        fun write(name: String, bytes: ByteArray)

        /** A track's own audio file ([key] "Rain", "Birds/Robin"): its data, length and file name; null when not found. */
        fun audio(key: String): Triple<InputStream, Long, String>?

        /**
         * A change's file operations (group made, renamed or removed, a file uploaded) repeated where the
         * theme lives; "write"/"copy" take the file from [from] (the prepared copy). Throws when they can't be.
         */
        fun apply(ops: List<ThemeStore.FileOp>, from: File)
    }

    /** What the editor needs: its API on this theme, the client (for ▶ previews), and the theme. */
    class Session(val api: SonoriumApi, val client: OkHttpClient, val theme: ThemeSummary)

    private val themeDir get() = File(themesRoot, folder)

    fun session(theme: ThemeSummary): Session {
        val client = OkHttpClient.Builder().addInterceptor(this).build()
        return Session(SonoriumApi(client, BASE_URL), client, theme)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val segments = request.url.pathSegments.dropWhile { it.isEmpty() }.let { if (it.firstOrNull() == "api") it.drop(1) else it }
        if (request.method == "GET" && segments.size == 5 && segments[0] == "themes" && segments[4] == "audio") return audio(request, segments[3])
        // An upload's file (multipart) is saved first; phone_api gets its name and where it is
        val multipart = request.body as? okhttp3.MultipartBody
        val saved = multipart?.let { saveUpload(it) }
        val (status, body) = try {
            val text = when {
                multipart != null -> saved?.let { (name, file) ->
                    JsonObject(mapOf("filename" to JsonPrimitive(name), "path" to JsonPrimitive(file.path))).toString()
                }.orEmpty()
                // Only JSON bodies go to phone_api
                else -> request.body?.takeIf { it.contentType()?.subtype?.contains("json") != false }
                    ?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            }
            call(request.method, segments, query(request.url), text)
        } finally {
            saved?.second?.delete()
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message(if (status in 200..299) "OK" else "Error")
            .body(body.toResponseBody(JSON)).build()
    }

    /** The multipart body's "file" part saved in the cache: its file name and where; null when there's none. */
    private fun saveUpload(body: okhttp3.MultipartBody): Pair<String, File>? {
        val part = body.parts.firstOrNull { p -> p.headers?.get("Content-Disposition")?.contains("name=\"file\"") == true }
            ?: return null
        val name = UPLOAD_NAME.find(part.headers?.get("Content-Disposition").orEmpty())?.groupValues?.get(1)?.ifBlank { null }
            ?: return null
        val file = File.createTempFile("upload", ".part", context.cacheDir)
        file.outputStream().use { out -> Buffer().also { part.body.writeTo(it) }.copyTo(out) }
        return name to file
    }

    /** One request through phone_api.handle: (status, JSON body). */
    fun call(method: String, segments: List<String>, query: JsonObject, body: String): Pair<Int, String> = synchronized(LOCK) {
        val before = SETTINGS.associateWith { File(themeDir, it).takeIf(File::exists)?.readBytes() }
        val answer = try {
            PhoneEngine.module(context, "phone_api").callAttr(
                "handle", method, JsonArray(segments.map { JsonPrimitive(it) }).toString(), query.toString(), body,
                themesRoot.path, pcmRoot.path, folder,
            ).toString()
        } catch (e: Exception) {
            Log.e(TAG, "phone_api failed on $method ${segments.joinToString("/")}", e)
            return error(500, "Something went wrong on this phone")
        }
        val parsed = runCatching { SonoriumApi.json.parseToJsonElement(answer) as JsonObject }.getOrNull() ?: return error(500, "Something went wrong on this phone")
        val status = (parsed["status"] as? JsonPrimitive)?.intOrNull ?: 500
        val out = parsed["body"]?.toString() ?: "{}"
        val ops = (parsed["ops"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ThemeStore.FileOp(s("op") ?: return@mapNotNull null, s("src"), s("dst"))
        }
        // What changed goes back to the theme's folder (file operations first), and a playing mix follows it
        val written = ops.filter { it.op == "write" }.mapNotNull { it.dst }.toSet()
        val changed = SETTINGS.mapNotNull { name ->
            val now = File(themeDir, name).takeIf(File::exists)?.readBytes() ?: return@mapNotNull null
            if (name in written || before[name]?.contentEquals(now) == true) null else name to now
        }
        if (changed.isNotEmpty() || ops.isNotEmpty()) {
            try {
                if (ops.isNotEmpty()) {
                    source.apply(ops, themeDir)
                    afterOps(ops)
                }
                changed.forEach { (name, bytes) -> source.write(name, bytes) }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't write the theme's files back", e)
                return error(500, "Couldn't save to your themes folder. Check that it's still there.")
            }
            if (PhoneEngine.isOpen) runCatching { PhoneEngine.reloadTheme() }.onFailure { Log.w(TAG, "The engine couldn't reload the theme", it) }
        }
        status to out
    }

    /**
     * The prepared copy after its file operations reached the theme's folder: an uploaded file goes back
     * to an empty stand-in, and decoded audio follows moved files and folders (nothing is decoded again).
     */
    private fun afterOps(ops: List<ThemeStore.FileOp>) {
        val pcm = File(pcmRoot, folder)
        ops.forEach { op ->
            val src = op.src
            val dst = op.dst
            when (op.op) {
                "write", "copy" -> if (dst != null && ThemeStore.isAudio(dst)) runCatching { File(themeDir, dst).writeBytes(ByteArray(0)) }
                "move" -> if (src != null && dst != null) runCatching {
                    val dir = File(pcm, src)
                    if (dir.isDirectory) {
                        File(pcm, dst).parentFile?.mkdirs()
                        dir.renameTo(File(pcm, dst))
                    } else if (ThemeStore.isAudio(src)) {
                        val wav = File(pcm, src.substringBeforeLast('.') + ".wav")
                        val to = File(pcm, dst.substringBeforeLast('.') + ".wav")
                        to.parentFile?.mkdirs()
                        if (wav.exists()) wav.renameTo(to)
                    }
                }
                "delete" -> if (src != null) runCatching { File(pcm, src).takeIf { it.isDirectory }?.delete() }
            }
        }
    }

    /** A track's own audio file as the answer to [request] (404 when it has none). */
    fun audio(request: okhttp3.Request, key: String): Response {
        val found = runCatching { source.audio(key) }.onFailure { Log.w(TAG, "No audio for $key", it) }.getOrNull()
            ?: return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not found")
                .body(error(404, "Track not found").second.toResponseBody(JSON)).build()
        val (input, length, name) = found
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Length", length.toString())
            .body(input.source().buffer().asResponseBody(ThemeStore.mimeOf(name).toMediaType(), length)).build()
    }

    private fun query(url: HttpUrl): JsonObject =
        JsonObject(url.queryParameterNames.associateWith { JsonPrimitive(url.queryParameter(it)) })

    private fun error(status: Int, text: String): Pair<Int, String> =
        status to JsonObject(mapOf("detail" to JsonPrimitive(text), "error" to JsonPrimitive(text))).toString()

    companion object {
        private const val TAG = "PhoneThemeApi"
        const val HOST = "phone.sonorium.local"
        /** The editor's server address for a theme on this phone; never leaves the app. */
        val BASE_URL: HttpUrl = "https://$HOST".toHttpUrl()
        private val JSON = "application/json".toMediaType()
        private val SETTINGS = listOf("metadata.json", "presets.json")
        private val LOCK = Any()
        private val SETTINGS_FILE = Regex("""(?i).*\.json$""")
        private val UPLOAD_NAME = Regex("""filename="([^"]*)"""")

        fun themesRoot(context: Context) = File(context.filesDir, "engine/themes")
        fun pcmRoot(context: Context) = File(context.filesDir, "engine/pcm")

        /** A folder name as [ThemePrep] names its copy. */
        fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")

        /**
         * The editor's API on [theme] (in the user's themes folder). Gets its prepared copy ready first:
         * only the settings files and stand-ins (no decoding), unless Core needs the files' lengths
         * (an older theme's "auto" modes), when the theme is fully prepared as for playing.
         * Throws [ThemePrep.Problem] with a message for the user.
         */
        suspend fun open(context: Context, store: ThemeStore, theme: LocalTheme): Session =
            openApi(context, store, theme).session(ThemeSummary(safe(theme.folder), name = theme.name, icon = theme.icon, description = theme.description, categories = theme.categories, totalTracks = theme.tracks))

        /** The editor's API on [theme] as an interceptor (this device's own entry routes a theme's requests to it). */
        suspend fun openApi(context: Context, store: ThemeStore, theme: LocalTheme): PhoneThemeApi = withContext(Dispatchers.IO) {
            val cantOpen = "Couldn't open “${theme.name}”. Check that it's still in your themes folder."
            try {
                openChecked(context, store, theme, cantOpen)
            } catch (e: ThemePrep.Problem) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${theme.folder} for editing", e)
                throw ThemePrep.Problem(cantOpen)
            }
        }

        private suspend fun openChecked(context: Context, store: ThemeStore, theme: LocalTheme, cantOpen: String): PhoneThemeApi {
            val app = context.applicationContext
            val files = store.files(theme)
            if (files.isEmpty()) throw ThemePrep.Problem(cantOpen)
            val folder = safe(theme.folder)
            val dir = File(themesRoot(app), folder)
            // Core opens it without converting (phone_api), so the stand-ins are enough: nothing to decode first
            prepareSettings(app, dir, files)
            var audio = files.filter { ThemeStore.isAudio(it.path) }.associateBy { it.path.substringBeforeLast('.') }
            val api = PhoneThemeApi(app, themesRoot(app), pcmRoot(app), folder, object : Source {
                override fun write(name: String, bytes: ByteArray) = kotlinx.coroutines.runBlocking { store.writeFile(theme, name, bytes) }
                override fun audio(key: String) = audio[key]?.let { f ->
                    app.contentResolver.openInputStream(f.uri)?.let { Triple(it, f.bytes, f.path) }
                }
                override fun apply(ops: List<ThemeStore.FileOp>, from: File) = kotlinx.coroutines.runBlocking {
                    try {
                        store.applyOps(theme, ops, from)
                    } finally {
                        // Files moved or added: previews find them where they are now
                        audio = store.files(theme).filter { ThemeStore.isAudio(it.path) }.associateBy { it.path.substringBeforeLast('.') }
                    }
                }
            })
            return api
        }

        /**
         * The editor's API on a theme folder already laid out under `files/engine/themes` (tests, or a
         * theme prepared elsewhere): changed settings files are copied into [writeBackTo] (null: kept only
         * in the prepared copy); previews play the files in [audioDir].
         */
        fun forFolder(context: Context, folder: String, writeBackTo: File?, audioDir: File?): Session {
            val app = context.applicationContext
            val api = PhoneThemeApi(app, themesRoot(app), pcmRoot(app), folder, object : Source {
                override fun write(name: String, bytes: ByteArray) {
                    writeBackTo?.let { File(it, name).writeBytes(bytes) }
                }
                override fun audio(key: String) = audioDir?.walkTopDown()
                    ?.firstOrNull { it.isFile && ThemeStore.isAudio(it.name) && it.relativeTo(audioDir).path.substringBeforeLast('.') == key }
                    ?.let { Triple(it.inputStream(), it.length(), it.name) }
                override fun apply(ops: List<ThemeStore.FileOp>, from: File) {
                    val to = writeBackTo ?: return
                    ops.forEach { op ->
                        val src = op.src
                        val dst = op.dst
                        when (op.op) {
                            "mkdir" -> if (dst != null) File(to, dst).mkdirs()
                            "move" -> if (src != null && dst != null) File(to, dst).also { it.parentFile?.mkdirs(); File(to, src).renameTo(it) }
                            "write", "copy" -> if (dst != null) File(from, dst).copyTo(File(to, dst), overwrite = true)
                            "delete" -> if (src != null) File(to, src).deleteRecursively()
                        }
                    }
                }
            })
            return api.session(ThemeSummary(folder, name = folder))
        }

        /**
         * The prepared copy's settings files from the theme's folder, and an empty stand-in for each audio
         * file (stand-ins of files no longer there are removed). Nothing is deleted otherwise, so an engine
         * playing this theme keeps its files; decoded audio is left as it is.
         */
        private fun prepareSettings(context: Context, dir: File, files: List<ThemeStore.ThemeFile>) {
            dir.mkdirs()
            val resolver = context.contentResolver
            files.filter { SETTINGS_FILE.matches(it.path) }.forEach { f ->
                val out = File(dir, f.path)
                out.parentFile?.mkdirs()
                resolver.openInputStream(f.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            val audio = files.filter { ThemeStore.isAudio(it.path) }.map { it.path }.toSet()
            audio.forEach { p -> File(dir, p).apply { parentFile?.mkdirs(); if (!exists()) createNewFile() } }
            dir.walkTopDown().filter { it.isFile && it.length() == 0L && ThemeStore.isAudio(it.name) && it.relativeTo(dir).path !in audio }.forEach { it.delete() }
        }
    }
}
