package app.sonorium.local

import android.content.Context
import app.sonorium.net.SonoriumApi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sync a phone copy with the server's theme it came from (instead of replacing it): the phone's copy never
 * changes by itself. New and changed audio files come down; the server's new presets, sequences, tracks
 * and groups are added. With [overwrite] off the phone's mix stays (a preset edited on both sides keeps the
 * phone's); on, the server's presets and settings replace the phone's.
 */
object ThemeSync {
    private val SETTINGS = setOf("metadata.json", "presets.json")

    /** What a sync did: audio files that came down, presets and sequences added. */
    data class Result(val files: Int, val presets: Int, val sequences: Int)

    suspend fun sync(
        context: Context, store: ThemeStore, downloader: ThemeDownloader, api: SonoriumApi, themeId: String,
        theme: LocalTheme, overwrite: Boolean, serverId: String?, progress: (Long, Long) -> Unit,
    ): Result {
        val dir = File(context.filesDir, "sync/${themeId.replace(Regex("[^A-Za-z0-9-]"), "_")}")
        val pkg = downloader.fetch(api, themeId, dir, checkSpace = true, progress = progress)
        return withContext(Dispatchers.IO) {
            try {
                val mine = store.files(theme).associateBy { it.path }
                // Audio and other files: the new ones and the changed ones (the phone's own files are kept)
                val changed = pkg.files.map { it.path }.filter { it !in SETTINGS }.filter { path ->
                    val local = mine[path] ?: return@filter true
                    val f = File(dir, path)
                    local.bytes != f.length() || sha256(context, local) != sha256(f)
                }
                if (changed.isNotEmpty()) store.applyOps(theme, changed.map { ThemeStore.FileOp("write", null, it) }, dir)
                // A track whose file the server replaced by another format (x.wav → x.mp3, same key): the old file goes,
                // so the theme doesn't hold two files for one track
                val replaced = replacedFiles(mine.keys, pkg.files.map { it.path })
                if (replaced.isNotEmpty()) store.applyOps(theme, replaced.map { ThemeStore.FileOp("delete", it, null) }, dir)

                // Settings: the server's, or the phone's with what the server has that the phone doesn't
                var presetsAdded = 0
                var sequencesAdded = 0
                for (name in SETTINGS) {
                    val server = File(dir, name).takeIf { it.isFile }?.readText() ?: continue
                    val text = if (overwrite) server else {
                        val phone = store.readFile(theme, name)
                        if (phone == null) server else {
                            val merged = merge(name, parse(phone), parse(server))
                            presetsAdded += merged.second
                            sequencesAdded += merged.third
                            SonoriumApi.json.encodeToString(JsonObject.serializer(), merged.first)
                        }
                    }
                    store.writeFile(theme, name, text.toByteArray())
                }
                // The copy now matches this package of this server (no "update ready" until the server changes)
                store.writeFile(theme, ThemeStore.MARKER, downloader.marker(pkg, serverId).toByteArray())
                Result(changed.size, presetsAdded, sequencesAdded)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * The phone's audio files that a package replaces with a file of another type for the same track (same path
     * without its extension, e.g. `Birds/robin.wav` when the package has `Birds/robin.mp3`). The phone's own files
     * with no counterpart in the package are kept.
     */
    fun replacedFiles(phone: Collection<String>, pkg: Collection<String>): List<String> {
        fun stem(path: String) = path.substringBeforeLast('.')
        val incoming = pkg.filter { ThemeStore.isAudio(it) }
        val stems = incoming.map { stem(it) }.toSet()
        val exact = pkg.toSet()
        return phone.filter { ThemeStore.isAudio(it) && it !in exact && stem(it) in stems }
    }

    private fun parse(text: String): JsonObject = runCatching { SonoriumApi.json.parseToJsonElement(text) as JsonObject }.getOrDefault(JsonObject(emptyMap()))

    /**
     * The phone's document with the server's additions: in metadata.json new tracks, groups and links; in
     * presets.json (or metadata.json's own presets) new presets and sequences. Returns it with the numbers of
     * presets and sequences added.
     */
    fun merge(name: String, phone: JsonObject, server: JsonObject): Triple<JsonObject, Int, Int> {
        val out = phone.toMutableMap()
        var presets = 0
        var sequences = 0
        fun addMissing(key: String): Int {
            val s = server[key] as? JsonObject ?: return 0
            val p = (phone[key] as? JsonObject).orEmpty()
            val added = s.filterKeys { it !in p }
            if (added.isNotEmpty()) out[key] = JsonObject(p + added)
            return added.size
        }
        if (name == "metadata.json") {
            addMissing("tracks")
            addMissing("groups")
            addMissing("links")
        }
        presets += addMissing("presets")
        sequences += addMissing("sequences")
        return Triple(JsonObject(out), presets, sequences)
    }

    private fun Map<String, JsonElement>?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun sha256(file: File): String = file.inputStream().use { digest(it) }

    private fun sha256(context: Context, f: ThemeStore.ThemeFile): String =
        context.contentResolver.openInputStream(f.uri)?.use { digest(it) }.orEmpty()

    private fun digest(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return PackageRules.hex(md.digest())
    }
}
