package app.sonorium.local

import android.content.Context
import android.util.Log
import app.sonorium.net.SonoriumApi
import app.sonorium.net.ThemePackage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Downloads a server theme to the phone (APP_API.md 10): every file into app-private storage first
 * (resumable, each checked against its sha256), then the finished theme into the themes folder with
 * [ThemeStore.MARKER] (theme id, content hash, the server's playback values), so updates show later.
 * Cancelling keeps the files downloaded so far: the next Save to phone picks up from there.
 */
class ThemeDownloader(private val context: Context, private val store: ThemeStore) {
    class Problem(message: String) : Exception(message)

    /** The theme saved: its folder on the phone and its name. */
    data class Saved(val folder: String, val name: String)

    private fun dirOf(themeId: String) = File(context.filesDir, "$DOWNLOADS/${themeId.replace(Regex("[^A-Za-z0-9-]"), "_")}")

    /** [progress] gets (bytes done, bytes in all). */
    suspend fun download(api: SonoriumApi, themeId: String, replacing: LocalTheme?, serverId: String? = null, progress: (Long, Long) -> Unit): Saved {
        val dir = dirOf(themeId)
        val pkg = fetch(api, themeId, dir, checkSpace = true, progress = progress)
        val name = pkg.name.ifBlank { themeId }
        val folder = store.install(dir, pkg.files.map { it.path }, name, marker(pkg, serverId), replacing)
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        return Saved(folder, name)
    }

    /** Every file of the theme's package into [dir] (resuming what's there), each checked. */
    suspend fun fetch(api: SonoriumApi, themeId: String, dir: File, checkSpace: Boolean = false, progress: (Long, Long) -> Unit): ThemePackage {
        val pkg = api.themePackage(themeId)
        if (pkg.files.isEmpty()) throw Problem("“${pkg.name}” has no files")
        pkg.files.firstOrNull { !PackageRules.safePath(it.path) }?.let {
            Log.w(TAG, "Unsafe path from the server: ${it.path}")
            throw Problem("The server sent a file name this device can't use, so “${pkg.name}” can't be saved")
        }
        if (PackageRules.contentHash(pkg.files) != pkg.contentHash) throw Problem("The server's file list doesn't match its hash; try again")
        val total = pkg.files.sumOf { it.size }
        var done = 0L
        withContext(Dispatchers.IO) {
            pkg.files.forEach { f ->
                val dest = File(dir, f.path).apply { parentFile?.mkdirs() }
                if (dest.exists() && dest.length() > f.size) dest.delete()
                if (dest.exists()) done += dest.length()
            }
        }
        // The rest to download here, then the whole theme again in the themes folder
        if (checkSpace) {
            val needed = (total - done) + total
            if (!SpaceRules.enough(needed, SpaceRules.available(context.filesDir))) {
                throw Problem(SpaceRules.notEnoughText("to save “${pkg.name.ifBlank { themeId }}”", needed))
            }
        }
        progress(done, total)
        pkg.files.forEach { f ->
            val dest = File(dir, f.path)
            var tries = 0
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                api.downloadThemeFile(themeId, f, dest) { n -> done += n; progress(done, total) }
                if (sha256(dest) == f.sha256.lowercase()) break
                done -= dest.length()
                dest.delete()
                if (++tries >= 2) throw Problem("${f.path} arrived damaged; try again")
            }
        }
        return pkg
    }

    /** Removes downloads left unfinished for more than [days] days (except [keep]: theme ids downloading now). */
    suspend fun cleanStale(keep: Set<String>, days: Int = 7) = withContext(Dispatchers.IO) {
        val keepDirs = keep.map { dirOf(it).name }.toSet()
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        File(context.filesDir, DOWNLOADS).listFiles()?.forEach { d ->
            val newest = d.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
            if (d.name !in keepDirs && newest < cutoff) {
                Log.i(TAG, "Removing an unfinished download: ${d.name}")
                d.deleteRecursively()
            }
        }
    }

    /** [ThemeStore.MARKER]'s text: theme id, content hash, name, the server's playback values. */
    fun marker(pkg: ThemePackage, serverId: String? = null): String = buildJsonObject {
        put("theme_id", pkg.themeId)
        // Which Sonorium it came from: the same theme id on another server is another theme
        serverId?.let { put("server_id", it) }
        put("content_hash", pkg.contentHash)
        put("name", pkg.name)
        pkg.playbackValues?.let { put("playback_values", it) }
    }.toString()

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        PackageRules.hex(md.digest())
    }

    private companion object {
        const val TAG = "ThemeDownloader"
        const val DOWNLOADS = "downloads"
    }
}
