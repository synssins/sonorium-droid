package app.sonorium.local

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The sample: one theme in the app itself that everyone can play at once, offline, with no server, no
 * themes folder and nothing unlocked. It is a read-only copy in the app's own storage (copied from the
 * APK's assets the first time, and again when the app is updated).
 *
 * To change the sample, point [ASSET] at another folder under `assets/`.
 */
object SampleTheme {
    /** THE sample theme: a theme folder in the APK's assets. */
    const val ASSET = "starter-themes/Tavern"

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
        fun copy(asset: String, to: File) {
            val children = app.assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                app.assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
            } else {
                children.forEach { copy("$asset/$it", File(to, it)) }
            }
        }
        runCatching { copy(ASSET, dir) }.onFailure { Log.e(TAG, "Couldn't copy the sample theme", it) }
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
