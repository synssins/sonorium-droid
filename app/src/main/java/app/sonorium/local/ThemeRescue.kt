package app.sonorium.local

import android.content.Context
import android.util.Log
import app.sonorium.engine.StandIns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A theme being edited whose themes folder went away (removed, moved, access lost): its prepared copy still has
 * the edits. Rescued into the themes folder now: the settings from the prepared copy (with the editor's unsaved
 * name and details), and each audio file read from where it was if it still can be; the files that can't be read
 * are named.
 */
object ThemeRescue {
    data class Result(val folder: String, val missing: List<String>)

    suspend fun rescue(
        context: Context, store: ThemeStore, preparedFolder: String, name: String, description: String, icon: String,
        categories: List<String>,
    ): Result = withContext(Dispatchers.IO) {
        val prepared = File(PhoneThemeApi.themesRoot(context), preparedFolder)
        if (!prepared.isDirectory) throw ThemeStore.ImportProblem("The edits can't be found any more")
        val work = File(context.cacheDir, "rescue-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val missing = mutableListOf<String>()
            val paths = mutableListOf<String>()
            prepared.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(prepared).path.replace(File.separatorChar, '/')
                if (rel.split('/').any { it.startsWith(".") }) return@forEach
                val out = File(work, rel).apply { parentFile?.mkdirs() }
                val ok = if (ThemeStore.isAudio(f.name) && StandIns.isStandIn(f)) {
                    // The file it stands for, if it can still be read
                    val source = app.sonorium.audio.LiveDecoder.sourceOf(f.path)
                    runCatching {
                        context.contentResolver.openInputStream(android.net.Uri.parse(source))?.use { input -> out.outputStream().use { input.copyTo(it) } } != null
                    }.getOrDefault(false)
                } else {
                    runCatching { f.copyTo(out, overwrite = true); true }.getOrDefault(false)
                }
                if (ok) paths += rel else {
                    out.delete()
                    missing += rel
                }
            }
            // The details the editor had not saved yet
            val meta = File(work, "metadata.json")
            val json = runCatching { JSONObject(meta.readText()) }.getOrDefault(JSONObject())
            json.put("name", name)
            if (description.isNotBlank()) json.put("description", description) else json.remove("description")
            if (icon.isNotBlank()) json.put("icon", icon)
            json.put("categories", JSONArray(categories))
            meta.writeText(json.toString(2))
            if ("metadata.json" !in paths) paths += "metadata.json"
            val folder = store.install(work, paths, name, null, null)
            Log.w(TAG, "Rescued $preparedFolder into $folder; missing ${missing.size}")
            Result(folder, missing)
        } finally {
            work.deleteRecursively()
        }
    }

    private const val TAG = "ThemeRescue"
}
