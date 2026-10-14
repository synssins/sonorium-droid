package app.sonorium.engine

import android.content.Context
import android.util.Log
import app.sonorium.local.LocalTheme
import app.sonorium.local.ThemeStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gets a theme on this phone ready for the engine, in app-private storage (rebuilt in moments):
 * `files/engine/themes/<folder>/` holds the theme's settings (metadata.json, presets.json) and, for each
 * audio file, a stand-in naming the file in the user's themes folder ([StandIns]). The engine lists the
 * stand-ins and reads the files themselves live while it mixes ([app.sonorium.audio.LiveDecoder]); nothing
 * is decoded ahead. (Before, each file was decoded to a WAV copy under `files/engine/pcm/`: [dropOldCopies]
 * removes those.)
 */
object ThemePrep {
    data class Prepared(
        val themeDir: File,
        /** No longer used (the decoded copies are gone); kept for the engine's call shape. */
        val pcmDir: File,
        /** Audio files that couldn't be read and were left out. */
        val skipped: List<String> = emptyList(),
    )

    /** A problem the user can read (the details are in the log). */
    class Problem(message: String) : Exception(message)

    private const val TAG = "ThemePrep"
    private val SETTINGS = Regex("""(?i).*\.(json)$""")
    private val mutex = Mutex()

    /** [progress] gets (done, total) audio files. Throws [Problem] with a message for the user when it can't. */
    suspend fun prepare(
        context: Context, store: ThemeStore, theme: LocalTheme,
        /** No longer used: every file is listed, and only what plays is read. */
        @Suppress("UNUSED_PARAMETER") only: Set<String>? = null,
        progress: (Int, Int) -> Unit,
    ): Prepared = mutex.withLock {
        withContext(Dispatchers.IO) {
            val themeDir = themeDir(context, theme.folder)
            val files = runCatching { store.files(theme) }
                .onFailure { Log.w(TAG, "Couldn't list ${theme.folder}", it) }
                .getOrElse { throw Problem("Couldn't open “${theme.name}”. Check that it's still in your themes folder.") }
            val audio = files.filter { ThemeStore.isAudio(it.path) }
            if (audio.isEmpty()) throw Problem("“${theme.name}” has no audio files")
            themeDir.deleteRecursively()
            themeDir.mkdirs()
            // Settings files, copied as they are
            files.filter { SETTINGS.matches(it.path) }.forEach { f ->
                ensureActive()
                val out = File(themeDir, f.path)
                out.parentFile?.mkdirs()
                runCatching { context.contentResolver.openInputStream(f.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } }
                    .onFailure { Log.w(TAG, "Couldn't copy ${f.path}", it) }
            }
            StandIns.write(themeDir, files)
            progress(audio.size, audio.size)
            Prepared(themeDir, pcmDir(context, theme.folder))
        }
    }

    /** The decoded copies made before the engine read files live: deleted (once), freeing their space. */
    suspend fun dropOldCopies(context: Context) = withContext(Dispatchers.IO) {
        val old = File(base(context), "pcm")
        if (old.exists()) {
            Log.i(TAG, "Removing the decoded copies (files are read live now)")
            old.deleteRecursively()
        }
    }

    /** A theme's prepared copy and decoded audio, where [prepare] puts them (prepared already). */
    fun prepared(context: Context, folder: String) = Prepared(themeDir(context, folder), pcmDir(context, folder))

    private fun base(context: Context) = File(context.filesDir, "engine")
    private fun themeDir(context: Context, folder: String) = File(base(context), "themes/${safe(folder)}")
    private fun pcmDir(context: Context, folder: String) = File(base(context), "pcm/${safe(folder)}")

    /** Deletes a theme's prepared copy (and an old decoded copy) (the theme was removed from the phone). */
    suspend fun clear(context: Context, folder: String) = withContext(Dispatchers.IO) {
        themeDir(context, folder).deleteRecursively()
        pcmDir(context, folder).deleteRecursively()
    }

    /** Deletes every prepared theme (the themes folder changed), but those in [keep] (open in the editor). */
    suspend fun clearAll(context: Context, keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (keep.isEmpty()) {
            base(context).deleteRecursively()
            return@withContext
        }
        val kept = keep.map(::safe).toSet()
        listOf(File(base(context), "themes"), File(base(context), "pcm")).forEach { dir ->
            dir.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
        }
        base(context).listFiles()?.filter { it.name != "themes" && it.name != "pcm" }?.forEach { it.deleteRecursively() }
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

/**
 * Stand-ins: in a prepared copy, a small file in place of each audio file, holding
 * [app.sonorium.audio.LiveDecoder.SOURCE_PREFIX] and the URI of the file in the user's themes folder.
 */
object StandIns {
    fun text(f: ThemeStore.ThemeFile) = app.sonorium.audio.LiveDecoder.SOURCE_PREFIX + f.uri

    /** A file in a prepared copy is a stand-in (or an old empty one), not real audio. */
    fun isStandIn(file: File): Boolean = file.isFile && (file.length() == 0L ||
        (file.length() < 4096 && runCatching { file.readText().startsWith(app.sonorium.audio.LiveDecoder.SOURCE_PREFIX) }.getOrDefault(false)))

    /** A stand-in in [dir] for each audio file of [files] (URIs follow moves); stand-ins of files gone are removed. */
    fun write(dir: File, files: List<ThemeStore.ThemeFile>) {
        val audio = files.filter { ThemeStore.isAudio(it.path) }
        audio.forEach { f ->
            val out = File(dir, f.path)
            val text = text(f)
            if (out.isFile && !isStandIn(out)) return@forEach // a real file (an upload being saved): left alone
            if (out.isFile && runCatching { out.readText() }.getOrNull() == text) return@forEach
            out.parentFile?.mkdirs()
            out.writeText(text)
        }
        val keep = audio.map { it.path }.toSet()
        dir.walkTopDown().filter { ThemeStore.isAudio(it.name) && isStandIn(it) && it.relativeTo(dir).path.replace(File.separatorChar, '/') !in keep }
            .forEach { it.delete() }
    }
}
