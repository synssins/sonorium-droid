package app.sonorium.enginelab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import app.sonorium.audio.PcmDecoder
import java.io.File
import kotlin.concurrent.thread

/**
 * The engine lab's one screen: get a theme onto the phone, predecode it (MediaCodec → 44.1 kHz
 * mono WAV), benchmark rendering, and play it in the background for battery runs.
 */
class LabActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var out: TextView
    private lateinit var themes: Spinner
    private lateinit var fast: CheckBox
    private val themesRoot by lazy { File(filesDir, "themes").apply { mkdirs() } }
    private val cacheRoot by lazy { File(filesDir, "pcm").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun button(text: String, action: () -> Unit) = col.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        themes = Spinner(this)
        col.addView(TextView(this).apply { text = "Theme" })
        col.addView(themes)
        fast = CheckBox(this).apply { text = "Fast random events (test theme)" }
        col.addView(fast)
        button("Make the test theme") { work("Making the test theme…") { Py.lab(this).callAttr("make_test_theme", themesRoot.path).toString().let { "Made $it" } } }
        button("Import a theme folder…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER) }
        button("Predecode (MediaCodec → WAV)") { theme()?.let { t -> work("Predecoding ${t.name}…") { predecode(t) } } }
        button("Benchmark 60 s: Python engine") {
            theme()?.let { t -> work("Rendering 60 s with the engine…") { "Python engine: " + Py.lab(this).callAttr("bench", t.path, cacheFor(t)?.path, 60, 4096, fast.isChecked).toString() } }
        }
        button("Benchmark 60 s: Kotlin mixer") { theme()?.let { t -> work("Mixing 60 s in Kotlin…") { benchKotlin(t) } } }
        button("Play in background: Python engine") { theme()?.let { LabService.start(this, LabService.MODE_PYTHON, it, cacheFor(it), fast.isChecked); say("Playing (Python engine). Turn the screen off for a battery run; the log is in lab-log.csv.") } }
        button("Play in background: Kotlin mixer") { theme()?.let { LabService.start(this, LabService.MODE_KOTLIN, it, cacheFor(it) ?: it, false); say("Playing (Kotlin mixer).") } }
        button("Stop") { LabService.stop(this); say("Stopped. ${LabService.status}") }
        out = TextView(this).apply { setPadding(0, 32, 0, 0); setTextIsSelectable(true) }
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
        refreshThemes()
        say("Log: ${File(getExternalFilesDir(null), "lab-log.csv").path}")
    }

    private fun refreshThemes() {
        val names = themesRoot.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()
        themes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun theme(): File? = (themes.selectedItem as? String)?.let { File(themesRoot, it) } ?: run { say("Make or import a theme first."); null }

    /** Predecoded copies when they exist; the test theme (16-bit WAVs) is read as it is. */
    private fun cacheFor(theme: File): File? = File(cacheRoot, theme.name).takeIf { it.isDirectory }

    private fun predecode(theme: File): String {
        val target = File(cacheRoot, theme.name)
        var files = 0
        var seconds = 0.0
        val start = System.nanoTime()
        theme.walkTopDown().filter { it.isFile && it.extension.lowercase() in AUDIO }.forEach { f ->
            val rel = f.relativeTo(theme).path.substringBeforeLast('.') + ".wav"
            val r = PcmDecoder.decodeToWav(f, File(target, rel))
            files++
            seconds += r.samples / 44100.0
        }
        val took = (System.nanoTime() - start) / 1e9
        val mb = target.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1e6
        return "Predecoded $files files (${"%.0f".format(seconds / 60)} min of audio, ${"%.0f".format(mb)} MB) in ${"%.1f".format(took)} s"
    }

    private fun benchKotlin(theme: File): String {
        val source = KotlinMixerSource(cacheFor(theme) ?: theme)
        val block = ShortArray(4096)
        val cpu0 = Debug.threadCpuTimeNanos()
        var done = 0
        while (done < 60 * 44100) {
            source.read(block)
            done += block.size
        }
        return "${source.label}: ${"%.2f".format((Debug.threadCpuTimeNanos() - cpu0) / 1e9)} s CPU for 60 s of audio"
    }

    private fun work(start: String, block: () -> String) {
        say(start)
        thread {
            val text = try { block() } catch (e: Throwable) { "Failed: ${e.message}" }
            main.post {
                say(text)
                refreshThemes()
            }
        }
    }

    private fun say(text: String) {
        out.text = "$text\n\n${out.text}"
    }

    @Deprecated("Activity result API is enough for a lab")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data ?: return
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK) return
        work("Copying the theme…") { copyTree(tree) }
    }

    /** Copies a picked folder (a theme: audio, group folders, metadata.json, presets.json). */
    private fun copyTree(tree: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = rootId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Imported" }
        val target = File(themesRoot, name)
        var files = 0
        fun copy(docId: String, dir: File) {
            dir.mkdirs()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val display = c.getString(1)
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) copy(id, File(dir, display))
                    else {
                        contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { input ->
                            File(dir, display).outputStream().use { input.copyTo(it) }
                        }
                        files++
                    }
                }
            }
        }
        copy(rootId, target)
        return "Imported \"$name\": $files files"
    }

    private companion object {
        const val PICK_FOLDER = 7
        val AUDIO = setOf("mp3", "wav", "flac", "ogg", "m4a")
    }
}
