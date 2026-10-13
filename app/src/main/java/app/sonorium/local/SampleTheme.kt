package app.sonorium.local

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The sample: one theme in the app itself that everyone can play at once, offline, with no server, no
 * themes folder and nothing unlocked. It is a read-only copy in the app's own storage (unpacked from the
 * APK the first time, and again when the app is updated).
 *
 * To change the sample, replace the one file [ASSET] (`app/src/main/assets/sample/sample-theme.zip`) with a
 * theme zip as Sonorium's Export makes it (one `<folder>/` with the theme's files). See docs/SAMPLE_THEME.md.
 */
object SampleTheme {
    /** THE sample theme: a theme zip in the APK's assets (a drop-in file). */
    const val ASSET = "sample/sample-theme.zip"

    /** Its document id and folder name on this device (never a folder in the themes folder). */
    const val DOC_ID = "sample:theme"
    const val FOLDER = "_sample"

    private const val TAG = "SampleTheme"
    private val lock = Any()

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "sample/theme")

    /** The sample's files in the app's storage, copied from the APK first when needed. */
    fun ensure(context: Context): File = synchronized(lock) {
        val app = context.applicationContext
        val dir = dir(app)
        val stamp = File(dir.parentFile, "version")
        val version = "${ASSET}|" + runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (dir.isDirectory && stamp.isFile && stamp.readText() == version) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        // The zip's one top folder is the theme; only safe relative paths are unpacked
        runCatching {
            java.util.zip.ZipInputStream(app.assets.open(ASSET).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.substringAfter('/', "")
                    if (entry.isDirectory || path.isEmpty() || !PackageRules.safePath(path)) continue
                    val out = File(dir, path).apply { parentFile?.mkdirs() }
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }.onFailure { Log.e(TAG, "Couldn't unpack the sample theme", it) }
        stamp.writeText(version)
        dir
    }

    /** The sample as a theme on this device, or null when the APK has none. */
    fun theme(context: Context): LocalTheme? {
        val dir = ensure(context)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null
        val meta = runCatching { JSONObject(File(dir, "metadata.json").readText()) }.getOrNull()
        fun s(k: String) = meta?.optString(k)?.ifBlank { null }
        return LocalTheme(
            docId = DOC_ID, folder = FOLDER, name = s("name") ?: dir.name,
            icon = s("icon").orEmpty(), description = s("description").orEmpty(),
            categories = meta?.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            tracks = files.count { ThemeStore.isAudio(it.name) }, bytes = files.sumOf { it.length() },
            metaId = s("id"), sample = true,
        )
    }

    /** The sample's files ("Birds/Robin.mp3" paths), read from the app's storage. */
    fun files(context: Context): List<ThemeStore.ThemeFile> {
        val dir = ensure(context)
        return dir.walkTopDown().filter { it.isFile }.map { f ->
            ThemeStore.ThemeFile(f.relativeTo(dir).path.replace('\\', '/'), Uri.fromFile(f), f.length(), f.lastModified())
        }.toList()
    }

    fun readFile(context: Context, name: String): String? = runCatching { File(ensure(context), name).takeIf { it.isFile }?.readText() }.getOrNull()

    /**
     * The device's themes: [themes] (the themes folder's) and the sample, unless one of them is the same theme
     * (the starter copy of it, or a copy saved from a server).
     */
    fun with(context: Context, themes: List<LocalTheme>): List<LocalTheme> {
        val sample = theme(context) ?: return themes
        if (themes.any { it.metaId != null && it.metaId == sample.metaId }) {
            // Its prepared copy goes too: two copies of one theme (one id) mustn't sit side by side for the engine
            val base = File(context.applicationContext.filesDir, "engine")
            File(base, "themes/$FOLDER").deleteRecursively()
            File(base, "pcm/$FOLDER").deleteRecursively()
            return themes
        }
        return (themes + sample).sortedBy { it.name.lowercase() }
    }
}
