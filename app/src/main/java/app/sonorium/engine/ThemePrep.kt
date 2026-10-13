package app.sonorium.engine

import android.content.Context
import android.util.Log
import app.sonorium.audio.PcmDecoder
import app.sonorium.local.LocalTheme
import app.sonorium.local.SpaceRules
import app.sonorium.local.ThemeStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Gets a theme on this phone ready for the engine, in app-private storage (rebuildable):
 * - `files/engine/themes/<folder>/`: the theme's settings (metadata.json, presets.json) and an empty
 *   stand-in for each audio file (the engine lists the files there);
 * - `files/engine/pcm/<folder>/`: each audio file decoded once to 16-bit mono 44100 Hz WAV, which the
 *   engine reads instead (`mirror_lookup`). Files already decoded at the same size are kept.
 * One theme is prepared at a time; cancelling the caller stops between files.
 */
object ThemePrep {
    data class Prepared(
        val themeDir: File,
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
        /** Only these tracks' audio ("Bells", "Group/Bells"; null: all), e.g. the files another theme links. */
        only: Set<String>? = null,
        progress: (Int, Int) -> Unit,
    ): Prepared = mutex.withLock {
        withContext(Dispatchers.IO) {
            val themeDir = themeDir(context, theme.folder)
            val pcmDir = pcmDir(context, theme.folder)
            val files = runCatching { store.files(theme) }
                .onFailure { Log.w(TAG, "Couldn't list ${theme.folder}", it) }
                .getOrElse { throw Problem("Couldn't open “${theme.name}”. Check that it's still in your themes folder.") }
            val all = files.filter { ThemeStore.isAudio(it.path) }
            fun keyOf(f: ThemeStore.ThemeFile) = f.path.substringBeforeLast('.')
            val audio = if (only == null) all else all.filter { keyOf(it) in only }
            if (all.isEmpty()) throw Problem("“${theme.name}” has no audio files")
            val stampFile = File(pcmDir, ".stamps.json")
            val stamps = runCatching { if (stampFile.exists()) JSONObject(stampFile.readText()) else JSONObject() }.getOrDefault(JSONObject())
            fun stampOf(f: ThemeStore.ThemeFile) = "${f.bytes}:${f.modified}"
            fun wavOf(f: ThemeStore.ThemeFile) = File(pcmDir, f.path.substringBeforeLast('.') + ".wav")
            // Room for the files still to decode
            val needed = audio.filter { !wavOf(it).exists() || stamps.optString(it.path) != stampOf(it) }.sumOf { SpaceRules.decodedBytes(it.path, it.bytes) }
            if (!SpaceRules.enough(needed, SpaceRules.available(context.filesDir))) {
                throw Problem(SpaceRules.notEnoughText("to get “${theme.name}” ready", needed))
            }
            themeDir.deleteRecursively()
            themeDir.mkdirs()
            pcmDir.mkdirs()
            // Settings files, copied as they are
            files.filter { SETTINGS.matches(it.path) }.forEach { f ->
                ensureActive()
                val out = File(themeDir, f.path)
                out.parentFile?.mkdirs()
                runCatching { context.contentResolver.openInputStream(f.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } }
                    .onFailure { Log.w(TAG, "Couldn't copy ${f.path}", it) }
            }
            // Stand-ins and decoded audio; a file that can't be read is left out (with a warning)
            val skipped = mutableListOf<String>()
            try {
                audio.forEachIndexed { i, f ->
                    ensureActive()
                    progress(i, audio.size)
                    val wav = wavOf(f)
                    val stamp = stampOf(f)
                    if (!wav.exists() || stamps.optString(f.path) != stamp) {
                        val ok = runCatching { PcmDecoder.decodeToWav(context, f.uri, wav) }
                            .onFailure { Log.w(TAG, "Couldn't decode ${theme.folder}/${f.path}", it) }
                            .isSuccess
                        if (!ok) {
                            wav.delete()
                            stamps.remove(f.path)
                            skipped += f.path
                            return@forEachIndexed
                        }
                        stamps.put(f.path, stamp)
                    }
                    File(themeDir, f.path).apply { parentFile?.mkdirs(); createNewFile() }
                }
            } finally {
                runCatching { stampFile.writeText(stamps.toString()) }
            }
            // The others listed (stand-ins), so the theme's folder is whole; their audio comes when it plays itself
            if (only != null) all.filter { keyOf(it) !in only }.forEach { f -> File(themeDir, f.path).apply { parentFile?.mkdirs(); createNewFile() } }
            if (audio.isNotEmpty() && skipped.size == audio.size) throw Problem("Couldn't read any of “${theme.name}”'s audio files")
            progress(audio.size, audio.size)
            Prepared(themeDir, pcmDir, skipped)
        }
    }

    /** A theme's prepared copy and decoded audio, where [prepare] puts them (prepared already). */
    fun prepared(context: Context, folder: String) = Prepared(themeDir(context, folder), pcmDir(context, folder))

    private fun base(context: Context) = File(context.filesDir, "engine")
    private fun themeDir(context: Context, folder: String) = File(base(context), "themes/${safe(folder)}")
    private fun pcmDir(context: Context, folder: String) = File(base(context), "pcm/${safe(folder)}")

    /** Deletes a theme's prepared copy and decoded audio (the theme was removed from the phone). */
    suspend fun clear(context: Context, folder: String) = withContext(Dispatchers.IO) {
        themeDir(context, folder).deleteRecursively()
        pcmDir(context, folder).deleteRecursively()
    }

    /** Deletes every prepared theme (the themes folder changed). */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        base(context).deleteRecursively()
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}
