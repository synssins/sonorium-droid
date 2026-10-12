package app.sonorium.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray

/** sonorium.engine through Chaquopy: one theme playing at a time (`phone.py`). */
object PhoneEngine {
    data class Preset(val id: String, val name: String, val isDefault: Boolean)

    @Volatile private var player: PyObject? = null
    private val lock = Any()

    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    /** Opens the engine on a prepared theme (closing any other); returns its presets. */
    fun open(context: Context, prepared: ThemePrep.Prepared, presetId: String?, volume: Float): List<Preset> = synchronized(lock) {
        player?.callAttr("close")
        val p = python(context).getModule("phone").callAttr("Player", prepared.themeDir.path, prepared.pcmDir.path, presetId, volume.toDouble(), System.nanoTime() and 0x7fffffff)
        player = p
        presetsOf(p)
    }

    /** The engine is open (on some theme). */
    val isOpen: Boolean get() = player != null

    /** Crossfades the open engine to another prepared theme (its default preset when [presetId] is null). */
    fun setTheme(prepared: ThemePrep.Prepared, presetId: String?): List<Preset> = synchronized(lock) {
        val p = player ?: throw IllegalStateException("The engine isn't open")
        p.callAttr("set_theme", prepared.themeDir.path, prepared.pcmDir.path, presetId)
        presetsOf(p)
    }

    /** A theme folder's presets.json read directly (before the engine opens it), in the engine's terms. */
    fun presetsFromJson(text: String): List<Preset> = runCatching {
        val root = org.json.JSONObject(text)
        val presets = root.optJSONObject("presets") ?: root
        presets.keys().asSequence().mapNotNull { id ->
            val p = presets.optJSONObject(id) ?: return@mapNotNull null
            Preset(id, p.optString("name", id).ifBlank { id }, p.optBoolean("is_default"))
        }.toList()
    }.getOrDefault(emptyList())

    private fun presetsOf(p: PyObject): List<Preset> {
        val arr = JSONArray(p.callAttr("presets").toString())
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Preset(it.getString("id"), it.optString("name", it.getString("id")), it.optBoolean("is_default")) } }
    }

    /** Fills [out] with the next 16-bit samples; silence when nothing is open. */
    fun render(out: ByteArray, offset: Int, length: Int): Int {
        val samples = length / 2
        val bytes = synchronized(lock) { player?.callAttr("render", samples)?.toJava(ByteArray::class.java) }
        if (bytes == null) {
            java.util.Arrays.fill(out, offset, offset + samples * 2, 0)
        } else {
            System.arraycopy(bytes, 0, out, offset, minOf(bytes.size, samples * 2))
        }
        return samples * 2
    }

    fun setPreset(presetId: String?) = synchronized(lock) { player?.callAttr("set_preset", presetId) }

    fun setVolume(volume: Float) = synchronized(lock) { player?.callAttr("set_volume", volume.toDouble()) }

    fun close() = synchronized(lock) {
        player?.callAttr("close")
        player = null
    }

    /** Bytes of PCM one "file" holds: about 6.7 hours; the player starts a new one when it ends. */
    const val STREAM_BYTES = 0x7FFF_FFF0

    /** A WAV header for a long 44100 Hz mono 16-bit stream. */
    fun wavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(STREAM_BYTES + 36); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(44100); putInt(44100 * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(STREAM_BYTES)
    }.array()
}
