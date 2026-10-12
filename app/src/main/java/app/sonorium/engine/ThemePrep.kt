package app.sonorium.engine

import android.content.Context
import app.sonorium.audio.PcmDecoder
import app.sonorium.local.LocalTheme
import app.sonorium.local.ThemeStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Gets a theme on this phone ready for the engine, in app-private storage (rebuildable):
 * - `files/engine/themes/<folder>/`: the theme's settings (metadata.json, presets.json) and an empty
 *   stand-in for each audio file (the engine lists the files there);
 * - `files/engine/pcm/<folder>/`: each audio file decoded once to 16-bit mono 44100 Hz WAV, which the
 *   engine reads instead (`mirror_lookup`). Files already decoded at the same size are kept.
 */
object ThemePrep {
    data class Prepared(val themeDir: File, val pcmDir: File)

    private val AUDIO_OR_SETTINGS = Regex("""(?i).*\.(json)$""")

    /** [progress] gets (done, total) audio files. Throws with a message for the user when it can't. */
    suspend fun prepare(context: Context, store: ThemeStore, theme: LocalTheme, progress: (Int, Int) -> Unit): Prepared = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "engine")
        val themeDir = File(base, "themes/${safe(theme.folder)}")
        val pcmDir = File(base, "pcm/${safe(theme.folder)}")
        val files = store.files(theme)
        val audio = files.filter { ThemeStore.isAudio(it.path) }
        if (audio.isEmpty()) throw IllegalStateException("“${theme.name}” has no audio files")
        themeDir.deleteRecursively()
        themeDir.mkdirs()
        // Settings files, copied as they are
        files.filter { AUDIO_OR_SETTINGS.matches(it.path) }.forEach { f ->
            val out = File(themeDir, f.path)
            out.parentFile?.mkdirs()
            context.contentResolver.openInputStream(f.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        // Stand-ins and decoded audio
        val stamps = File(pcmDir, ".stamps.json").let { if (it.exists()) JSONObject(it.readText()) else JSONObject() }
        audio.forEachIndexed { i, f ->
            progress(i, audio.size)
            File(themeDir, f.path).apply { parentFile?.mkdirs(); createNewFile() }
            val wav = File(pcmDir, f.path.substringBeforeLast('.') + ".wav")
            val stamp = "${f.bytes}:${f.modified}"
            if (!wav.exists() || stamps.optString(f.path) != stamp) {
                runCatching { PcmDecoder.decodeToWav(context, f.uri, wav) }
                    .onFailure { throw IllegalStateException("Couldn't read ${f.path}: ${it.message}") }
                stamps.put(f.path, stamp)
            }
        }
        pcmDir.mkdirs()
        File(pcmDir, ".stamps.json").writeText(stamps.toString())
        progress(audio.size, audio.size)
        Prepared(themeDir, pcmDir)
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}
