package app.sonorium.local

import android.content.Context
import android.util.Log
import app.sonorium.engine.PhoneEngine
import app.sonorium.net.Playlist
import app.sonorium.net.Playlists
import app.sonorium.net.SonoriumApi
import app.sonorium.net.ThemePresets
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Playlists kept on this phone, made of the themes on this phone: the server's playlist JSON, checked
 * and saved by Core's `sonorium.core.playlists` (`phone_api.playlists`), so a playlist could travel to a
 * server later. Themes are known by their metadata.json id, as on a server.
 *
 * The catalog (`files/phone/catalog/<folder>/`) holds each phone theme's metadata.json and presets.json,
 * what Core reads to check entries and to list presets and sequences.
 */
object PhonePlaylists {
    /** A change that couldn't be made: [message] says why, as the server would. */
    class Problem(val status: Int, message: String) : Exception(message)

    private const val TAG = "PhonePlaylists"
    private val lock = Mutex()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _presets = MutableStateFlow<Map<String, ThemePresets>>(emptyMap())
    /** Every phone theme's presets and sequences, by theme id. */
    val presets: StateFlow<Map<String, ThemePresets>> = _presets.asStateFlow()

    private fun base(context: Context) = File(context.applicationContext.filesDir, "phone")
    private fun store(context: Context) = File(base(context), "playlists.json")
    private fun catalog(context: Context) = File(base(context), "catalog")

    /** Reads the catalog from [themes] (the themes on this phone) and the playlists again. */
    suspend fun refresh(context: Context, store: ThemeStore, themes: List<LocalTheme>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val root = catalog(context).apply { mkdirs() }
            val keep = mutableSetOf<String>()
            themes.filter { it.metaId != null }.forEach { t ->
                val dir = File(root, PhoneThemeApi.safe(t.folder)).apply { mkdirs() }
                keep += dir.name
                for (name in listOf("metadata.json", "presets.json")) {
                    val text = runCatching { store.readFile(t, name) }.getOrNull()
                    val file = File(dir, name)
                    if (text != null) file.writeText(text) else file.delete()
                }
            }
            root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
            val presets = runCatching {
                val text = PhoneEngine.module(context, "phone_api").callAttr("catalog_presets", root.path).toString()
                SonoriumApi.json.decodeFromString<Map<String, ThemePresets>>(text)
            }.onFailure { Log.w(TAG, "Couldn't read the themes' presets", it) }.getOrDefault(emptyMap())
            _presets.value = presets
            _playlists.value = runCatching { SonoriumApi.json.decodeFromString<Playlists>(call(context, "GET", "", "").toString()).playlists }
                .onFailure { Log.w(TAG, "Couldn't read the playlists", it) }.getOrDefault(_playlists.value)
        }
    }

    /** Adds ([id] null) or saves a playlist; returns it as saved. Throws [Problem]. */
    suspend fun save(context: Context, id: String?, body: JsonObject): Playlist = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = SonoriumApi.json.decodeFromJsonElement(Playlist.serializer(), call(context, if (id == null) "POST" else "PUT", id.orEmpty(), body.toString()))
            _playlists.value = _playlists.value.filter { it.id != saved.id }.let { list ->
                val at = _playlists.value.indexOfFirst { it.id == saved.id }
                if (at >= 0) list.toMutableList().apply { add(at, saved) } else list + saved
            }
            saved
        }
    }

    suspend fun delete(context: Context, id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            call(context, "DELETE", id, "")
            _playlists.value = _playlists.value.filter { it.id != id }
        }
    }

    /** The playlists as kept on this phone (`{id: playlist}`), for a backup; null when there are none. */
    suspend fun stored(context: Context): JsonObject? = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = store(context)
            if (!file.isFile) return@withContext null
            runCatching { (SonoriumApi.json.parseToJsonElement(file.readText()) as? JsonObject)?.get("playlists") as? JsonObject }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Playlists from a backup ([playlists]: `{id: playlist}`) added to the ones on this phone; one with the
     * same id is replaced by the backup's. Returns how many came in. [refresh] afterwards shows them.
     */
    suspend fun restore(context: Context, playlists: JsonObject): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = store(context)
            // The file Core keeps: {"playlists": {id: playlist}, …}
            val doc = runCatching { SonoriumApi.json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val mine = doc["playlists"] as? JsonObject ?: JsonObject(emptyMap())
            val incoming = playlists.filterValues { it is JsonObject }
            file.parentFile?.mkdirs()
            file.writeText(JsonObject(doc + ("playlists" to JsonObject(mine + incoming))).toString())
            incoming.size
        }
    }

    /** One request through phone_api.playlists; its body, or [Problem] for an error answer. */
    private fun call(context: Context, method: String, id: String, body: String): kotlinx.serialization.json.JsonElement {
        val answer = PhoneEngine.module(context, "phone_api").callAttr("playlists", method, id, body, store(context).path, catalog(context).path).toString()
        val parsed = SonoriumApi.json.parseToJsonElement(answer) as JsonObject
        val status = (parsed["status"] as? JsonPrimitive)?.intOrNull ?: 500
        val out = parsed["body"] ?: JsonObject(emptyMap())
        if (status !in 200..299) {
            val text = ((out as? JsonObject)?.get("detail") as? JsonPrimitive)?.content ?: "Something went wrong on this phone"
            throw Problem(status, text.replaceFirstChar { it.uppercase() })
        }
        return out
    }
}
