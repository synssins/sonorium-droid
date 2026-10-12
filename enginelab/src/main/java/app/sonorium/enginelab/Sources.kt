package app.sonorium.enginelab

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** Something that fills 44100 Hz mono int16 blocks. */
interface PcmSource : AutoCloseable {
    val label: String
    fun read(out: ShortArray)
}

object Py {
    fun get(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    fun lab(context: Context): PyObject = get(context).getModule("lab")
}

/** sonorium.engine through Chaquopy (one Python call per block; bytes across the boundary). */
class EngineSource(context: Context, themeDir: File, cacheDir: File?, fast: Boolean) : PcmSource {
    private val runner: PyObject = Py.lab(context).callAttr("Runner", themeDir.path, cacheDir?.path, null, 1, fast)
    override val label = "Python engine: ${runner.callAttr("name")}"

    override fun read(out: ShortArray) {
        val bytes = runner.callAttr("render", out.size).toJava(ByteArray::class.java)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    }

    override fun close() {
        runner.callAttr("close")
    }
}

/**
 * The baseline: a minimal Kotlin mixer. Every predecoded WAV loops at an equal share of the
 * level; no modes, groups or fades. It only shows what plain mixing costs on the phone.
 */
class KotlinMixerSource(cacheDir: File) : PcmSource {
    private class Track(val data: ShortBuffer, var pos: Int = 0)

    private val tracks: List<Track> = cacheDir.walkTopDown().filter { it.isFile && it.extension == "wav" }.sorted().mapNotNull { f ->
        RandomAccessFile(f, "r").use { raf ->
            val map: MappedByteBuffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            map.order(ByteOrder.LITTLE_ENDIAN)
            if (raf.length() <= 44) return@use null
            map.position(44)
            Track(map.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
        }
    }.toList()
    override val label = "Kotlin mixer: ${tracks.size} tracks"
    private val gain = if (tracks.isEmpty()) 0f else 1f / tracks.size
    private val mix = FloatArray(16_384)

    override fun read(out: ShortArray) {
        java.util.Arrays.fill(mix, 0, out.size, 0f)
        for (t in tracks) {
            val n = t.data.limit()
            if (n == 0) continue
            for (i in out.indices) {
                mix[i] += t.data.get(t.pos) * gain
                t.pos = if (t.pos + 1 >= n) 0 else t.pos + 1
            }
        }
        for (i in out.indices) out[i] = mix[i].coerceIn(-32768f, 32767f).toInt().toShort()
    }

    override fun close() {}
}
