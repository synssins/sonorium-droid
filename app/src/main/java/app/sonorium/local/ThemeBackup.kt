package app.sonorium.local

import android.content.Context
import app.sonorium.net.ApiException
import app.sonorium.net.PackageFile
import app.sonorium.net.SonoriumApi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Backs up what's on this phone to a Sonorium it's linked to (the server's `/api/me/backups`, per account and
 * phone), and brings a backup back onto a phone:
 * - a theme saved from that server: only the phone's mix of it (presets, sequences, track settings, groups);
 * - a theme of the phone's own (made, imported, the starters): the whole theme, files the account already
 *   holds aren't sent again;
 * - the phone's playlists.
 * Themes saved from another Sonorium are backed up to that one. Unchanged things aren't sent again.
 */
object ThemeBackup {
    /** File types a whole-theme backup takes (the server's list). */
    private val FILE_TYPES = setOf("mp3", "wav", "flac", "ogg", "json", "md", "txt", "png", "jpg", "jpeg", "webp")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("backups", Context.MODE_PRIVATE)

    /** "Back up to this server" for [serverId] (off until the user turns it on). */
    fun isOn(context: Context, serverId: String): Boolean = prefs(context).getBoolean("on:$serverId", false)

    fun setOn(context: Context, serverId: String, on: Boolean) {
        prefs(context).edit().putBoolean("on:$serverId", on).apply()
    }

    /** Forgets what was sent to [serverId] (its backups of this phone were deleted): the next run sends all. */
    fun forget(context: Context, serverId: String) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("mix:$serverId:") || it == "playlists:$serverId" }.forEach { remove(it) } }.apply()
    }

    /** When the last backup to [serverId] finished (ms), 0 when never. */
    fun lastDone(context: Context, serverId: String): Long = prefs(context).getLong("done:$serverId", 0)

    /** What a backup did. [full]: the account's backup space ran out, the rest wasn't sent. */
    data class Result(val sent: Int, val unchanged: Int, val skipped: List<String>, val full: Boolean)

    /** What a restore brought back. */
    data class Restored(val themes: Int, val playlists: Int, val missing: List<String>)

    /**
     * One backup run of everything on this phone to the server [api] talks to ([serverId]; [serverThemes]: its
     * theme ids, for phone copies saved before the copy recorded its server). Throws on a failed request
     * (the next run picks up from there).
     */
    suspend fun backUp(context: Context, store: ThemeStore, api: SonoriumApi, serverId: String, serverThemes: Set<String>): Result {
        val themes = if (store.folderState() == ThemeStore.FolderState.OK) store.list() else emptyList()
        val listing = api.backups()
        val mine = (listing["devices"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["this_device"] as? JsonPrimitive)?.booleanOrNull == true }
        val held = (mine?.get("themes") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.associateBy { (it["key"] as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val p = prefs(context)
        var sent = 0
        var unchanged = 0
        val skipped = mutableListOf<String>()
        try {
            for (theme in themes) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val fromServer = theme.themeId
                when {
                    // Saved from this Sonorium: the mix only (the server has the audio)
                    fromServer != null && (theme.serverId == serverId || (theme.serverId == null && fromServer in serverThemes)) -> {
                        val mix = mixOf(store, theme)
                        val hash = sha(mix.toString())
                        val pref = "mix:$serverId:$fromServer"
                        if (p.getString(pref, null) == hash && held[fromServer]?.kind() == "mix") {
                            unchanged++
                            continue
                        }
                        api.putBackupMix(fromServer, mix)
                        p.edit().putString(pref, hash).apply()
                        sent++
                    }
                    // Saved from another Sonorium: backed up there
                    fromServer != null -> continue
                    // The phone's own: the whole theme
                    else -> {
                        val key = keyOf(theme)
                        val files = manifest(context, store, theme, skipped)
                        if (files.isEmpty()) continue
                        val hash = PackageRules.contentHash(files.map { it.first })
                        val there = held[key]
                        if (there?.kind() == "theme" && (there["content_hash"] as? JsonPrimitive)?.content == hash &&
                            (there["uploading"] as? JsonPrimitive)?.booleanOrNull != true
                        ) {
                            unchanged++
                            continue
                        }
                        uploadTheme(context, api, key, theme.name, files)
                        sent++
                    }
                }
            }
            PhonePlaylists.stored(context)?.let { playlists ->
                val hash = sha(playlists.toString())
                val pref = "playlists:$serverId"
                if (p.getString(pref, null) == hash && mine?.get("playlists").let { it is JsonPrimitive && it.longOrNull != null }) {
                    unchanged++
                } else {
                    api.putBackupPlaylists(buildJsonObject { put("playlists", playlists) })
                    p.edit().putString(pref, hash).apply()
                    sent++
                }
            }
        } catch (e: ApiException) {
            if (e.status == 507) return Result(sent, unchanged, skipped, full = true)
            throw e
        }
        p.edit().putLong("done:$serverId", System.currentTimeMillis()).apply()
        return Result(sent, unchanged, skipped, full = false)
    }

    private fun JsonObject.kind() = (this["kind"] as? JsonPrimitive)?.content

    /** The backup's key for a theme of the phone's own: its metadata.json id, else its folder name. */
    private fun keyOf(theme: LocalTheme): String =
        (theme.metaId ?: theme.folder).filter { it >= ' ' && it != '/' && it != '\\' }.take(200).ifEmpty { "theme" }

    /**
     * The phone's mix of a theme: from presets.json (or metadata.json's own presets) its presets, sequences
     * and sequence defaults; from metadata.json its track settings, groups and plugin values.
     */
    suspend fun mixOf(store: ThemeStore, theme: LocalTheme): JsonObject {
        val meta = parse(store.readFile(theme, "metadata.json"))
        val presetsDoc = store.readFile(theme, "presets.json")?.let { parse(it) } ?: meta
        fun objects(o: JsonElement?) = (o as? JsonObject)?.filterValues { it is JsonObject }?.let { JsonObject(it) }
        return buildJsonObject {
            put("name", theme.name.filter { it >= ' ' }.take(200))
            // A preset's tracks, groups and plugins are objects (the server refuses a mix with anything else there)
            objects(presetsDoc["presets"])?.let { presets ->
                put("presets", JsonObject(presets.mapValues { (_, v) ->
                    JsonObject((v as JsonObject).filter { (k, x) -> k !in setOf("tracks", "groups", "plugins") || x is JsonObject })
                }))
            }
            objects(presetsDoc["sequences"])?.let { put("sequences", it) }
            ((presetsDoc["sequence_defaults"] ?: meta["sequence_defaults"]) as? JsonObject)?.let { put("sequence_defaults", it) }
            objects(meta["tracks"])?.let { put("tracks", it) }
            (meta["groups"] as? JsonObject)?.let { put("groups", it) }
            (meta["plugins"] as? JsonObject)?.let { put("plugins", it) }
        }
    }

    /** The files of a theme a backup takes, each with its sha256 (kept between runs while the file is unchanged). */
    private suspend fun manifest(context: Context, store: ThemeStore, theme: LocalTheme, skipped: MutableList<String>): List<Pair<PackageFile, ThemeStore.ThemeFile>> {
        val p = prefs(context)
        return store.files(theme).mapNotNull { f ->
            val parts = f.path.split('/')
            val ext = f.path.substringAfterLast('.', "").lowercase()
            if (parts.any { it.startsWith(".") }) return@mapNotNull null
            if (ext !in FILE_TYPES || parts.size > 4 || f.path.length > 255) {
                skipped += "${theme.name}/${f.path}"
                return@mapNotNull null
            }
            val stamp = "${f.bytes}|${f.modified}"
            val pref = "sha:${f.uri}"
            val cached = p.getString(pref, null)?.takeIf { it.startsWith("$stamp|") }?.substringAfterLast('|')
            val sha = cached ?: withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(f.uri)?.use { digest(it) }
            }?.also { p.edit().putString(pref, "$stamp|$it").apply() } ?: return@mapNotNull null
            PackageFile(f.path, f.bytes, sha) to f
        }
    }

    /** A whole theme: its list of files, then each file the server doesn't hold yet, resumed where it stopped. */
    private suspend fun uploadTheme(context: Context, api: SonoriumApi, key: String, name: String, files: List<Pair<PackageFile, ThemeStore.ThemeFile>>) {
        val manifest = buildJsonObject {
            put("name", name.filter { it >= ' ' }.take(200))
            put("files", buildJsonArray {
                files.forEach { (f, _) -> add(buildJsonObject { put("path", f.path); put("size", f.size); put("sha256", f.sha256) }) }
            })
        }
        var answer = api.putBackupPackage(key, manifest)
        val byPath = files.associate { it.first.path to it }
        var tries = 0
        while ((answer["complete"] as? JsonPrimitive)?.booleanOrNull != true) {
            val missing = (answer["missing"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (missing.isEmpty() || ++tries > 3) throw IllegalStateException("The backup of “$name” didn't complete")
            for (m in missing) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val path = (m["path"] as? JsonPrimitive)?.content ?: continue
                val (pf, tf) = byPath[path] ?: continue
                val received = (m["received"] as? JsonPrimitive)?.longOrNull ?: 0L
                answer = try {
                    api.uploadBackupFile(key, path, pf.size, received.coerceIn(0, pf.size)) {
                        context.contentResolver.openInputStream(tf.uri) ?: throw java.io.IOException("Can't read ${tf.path}")
                    }
                } catch (e: ApiException) {
                    // 409: it has a different part of the file; 422: arrived damaged. Ask where it stands and go on
                    if (e.status == 409 || e.status == 422) api.putBackupPackage(key, manifest) else throw e
                }
            }
        }
    }

    private fun parse(text: String?): JsonObject =
        text?.let { runCatching { SonoriumApi.json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private fun sha(text: String): String = PackageRules.hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

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

    /**
     * Brings the backup of phone [deviceId] (its row of `GET /api/me/backups`) onto this phone: a mix goes into
     * the phone's copy of that server theme (saved from the server first when the phone has none); a whole
     * theme is downloaded and replaces a theme of the phone's own with the same id; playlists are added (one
     * with the same id replaced). [progress] gets the name of what's being restored.
     */
    suspend fun restore(
        context: Context, store: ThemeStore, downloader: ThemeDownloader, api: SonoriumApi, serverId: String,
        deviceId: String, row: JsonObject, serverThemes: Set<String>, progress: (String) -> Unit,
    ): Restored {
        var themes = 0
        val missing = mutableListOf<String>()
        val entries = (row["themes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { (it["uploading"] as? JsonPrimitive)?.booleanOrNull != true }
        for (entry in entries) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val key = (entry["key"] as? JsonPrimitive)?.content ?: continue
            val name = (entry["name"] as? JsonPrimitive)?.content?.ifBlank { null } ?: key
            progress(name)
            try {
                if (entry.kind() == "mix") {
                    val mix = api.backupTheme(deviceId, key)["mix"] as? JsonObject ?: continue
                    suspend fun copy() = store.list().firstOrNull { it.themeId == key && (it.serverId == serverId || it.serverId == null) }
                    var local = copy()
                    if (local == null) {
                        if (key !in serverThemes) {
                            missing += name
                            continue
                        }
                        downloader.download(api, key, null, serverId) { _, _ -> }
                        local = copy() ?: continue
                    }
                    applyMix(store, local, mix)
                } else {
                    val pkg = api.backupPackage(deviceId, key)
                    if (pkg.files.any { !PackageRules.safePath(it.path) }) {
                        missing += name
                        continue
                    }
                    val needed = pkg.files.sumOf { it.size } * 2
                    if (!SpaceRules.enough(needed, SpaceRules.available(context.filesDir))) throw ThemeStore.ImportProblem(SpaceRules.notEnoughText("to restore “$name”", needed))
                    val dir = File(context.filesDir, "restore/${key.replace(Regex("[^A-Za-z0-9-]"), "_")}")
                    try {
                        for (f in pkg.files) {
                            val dest = File(dir, f.path).apply { parentFile?.mkdirs() }
                            var tries = 0
                            while (true) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                api.downloadBackupFile(deviceId, key, f, dest) { }
                                if (withContext(Dispatchers.IO) { dest.inputStream().use { digest(it) } } == f.sha256.lowercase()) break
                                dest.delete()
                                if (++tries >= 2) throw ThemeStore.ImportProblem("${f.path} arrived damaged; try again")
                            }
                        }
                        val replacing = store.list().firstOrNull { it.themeId == null && keyOf(it) == key }
                        store.install(dir, pkg.files.map { it.path }, pkg.name.ifBlank { name }, null, replacing)
                    } finally {
                        withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    }
                }
                themes++
            } catch (e: ApiException) {
                if (e.status != 404) throw e
                missing += name
            }
        }
        var playlists = 0
        if ((row["playlists"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true) {
            progress("Playlists")
            (api.backupPlaylists(deviceId)["playlists"] as? JsonObject)?.let { playlists = PhonePlaylists.restore(context, it) }
        }
        return Restored(themes, playlists, missing)
    }

    /** A backed-up mix into the phone's copy: its presets, sequences, track settings, groups and plugin values. */
    private suspend fun applyMix(store: ThemeStore, theme: LocalTheme, mix: JsonObject) {
        val meta = parse(store.readFile(theme, "metadata.json"))
        val presetsText = store.readFile(theme, "presets.json")
        val presetParts = listOf("presets", "sequences", "sequence_defaults").mapNotNull { k -> (mix[k] as? JsonObject)?.let { k to it } }
        val metaParts = listOf("tracks", "groups", "plugins").mapNotNull { k -> (mix[k] as? JsonObject)?.let { k to it } }
        if (presetsText != null) {
            store.writeFile(theme, "presets.json", JsonObject(parse(presetsText) + presetParts).toString().toByteArray())
            store.writeFile(theme, "metadata.json", JsonObject(meta + metaParts).toString().toByteArray())
        } else {
            // A theme keeping its presets in metadata.json keeps them there
            store.writeFile(theme, "metadata.json", JsonObject(meta + metaParts + presetParts).toString().toByteArray())
        }
    }
}
