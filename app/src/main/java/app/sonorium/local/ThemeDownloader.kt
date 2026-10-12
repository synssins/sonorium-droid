package app.sonorium.local

import android.content.Context
import app.sonorium.net.SonoriumApi
import app.sonorium.net.ThemePackage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Downloads a server theme to the phone (APP_API.md 10): every file into app-private storage first
 * (resumable, each checked against its sha256), then the finished theme into the themes folder with
 * [ThemeStore.MARKER] (theme id, content hash, the server's playback values), so updates show later.
 */
class ThemeDownloader(private val context: Context, private val store: ThemeStore) {
    class Problem(message: String) : Exception(message)

    /** [progress] gets (bytes done, bytes in all). Returns the theme's folder name on the phone. */
    suspend fun download(api: SonoriumApi, themeId: String, replacing: LocalTheme?, progress: (Long, Long) -> Unit): String {
        val dir = File(context.filesDir, "downloads/${themeId.replace(Regex("[^A-Za-z0-9-]"), "_")}")
        val pkg = fetch(api, themeId, dir, progress)
        val folder = store.install(dir, pkg.files.map { it.path }, pkg.name.ifBlank { themeId }, marker(pkg), replacing)
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        return folder
    }

    /** Every file of the theme's package into [dir] (resuming what's there), each checked. */
    suspend fun fetch(api: SonoriumApi, themeId: String, dir: File, progress: (Long, Long) -> Unit): ThemePackage {
        val pkg = api.themePackage(themeId)
        if (pkg.files.isEmpty()) throw Problem("“${pkg.name}” has no files")
        pkg.files.firstOrNull { !PackageRules.safePath(it.path) }?.let { throw Problem("The server sent an odd file name: ${it.path}") }
        if (PackageRules.contentHash(pkg.files) != pkg.contentHash) throw Problem("The server's file list doesn't match its hash; try again")
        val total = pkg.files.sumOf { it.size }
        var done = 0L
        pkg.files.forEach { f ->
            val dest = File(dir, f.path).apply { parentFile?.mkdirs() }
            if (dest.exists() && dest.length() > f.size) dest.delete()
            if (dest.exists()) done += dest.length()
        }
        progress(done, total)
        pkg.files.forEach { f ->
            val dest = File(dir, f.path)
            var tries = 0
            while (true) {
                api.downloadThemeFile(themeId, f, dest) { n -> done += n; progress(done, total) }
                if (sha256(dest) == f.sha256.lowercase()) break
                done -= dest.length()
                dest.delete()
                if (++tries >= 2) throw Problem("${f.path} arrived damaged; try again")
            }
        }
        return pkg
    }

    /** [ThemeStore.MARKER]'s text: theme id, content hash, name, the server's playback values. */
    fun marker(pkg: ThemePackage): String = buildJsonObject {
        put("theme_id", pkg.themeId)
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
}
