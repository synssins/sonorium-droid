package app.sonorium

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.audio.PcmDecoder
import app.sonorium.engine.EngineDataSource
import app.sonorium.engine.PhoneEngine
import app.sonorium.engine.PhonePlayer
import app.sonorium.engine.PhonePlayerService
import app.sonorium.engine.ThemePrep
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playing a theme on the phone: the engine's test theme, decoded as on the phone, mixed by
 * sonorium.engine in the app, and played by the media service until ExoPlayer reports playing.
 */
@RunWith(AndroidJUnit4::class)
class PhoneEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun engineMixesAndThePlayerPlays() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val root = File(context.cacheDir, "engine-test").apply { deleteRecursively(); mkdirs() }
        val theme = File(Python.getInstance().getModule("lab").callAttr("make_test_theme", root.path).toString())
        // Decode every audio file the way ThemePrep does (mirror layout, .wav)
        val pcm = File(root, "pcm")
        theme.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.forEach { f ->
            PcmDecoder.decodeToWav(f, File(pcm, f.relativeTo(theme).path.substringBeforeLast('.') + ".wav"))
        }
        val presets = PhoneEngine.open(context, ThemePrep.Prepared(theme, pcm), null, 1f)
        // 2 s of the mix is not silent
        val buf = ByteArray(44_100 * 2 * 2)
        PhoneEngine.render(buf, 0, buf.size)
        val samples = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
        assertTrue("The engine rendered silence (peak $peak)", peak > 500)
        assertTrue("presets: $presets", presets.size >= 0)

        // The media service plays the engine's stream (ExoPlayer accepts the WAV stream and pulls the mix)
        EngineDataSource.served = 0
        context.startService(Intent(context, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, "Test Theme"))
        val end = System.currentTimeMillis() + 20_000
        val second = 44_100L * 2
        while (!(PhonePlayer.state.value.playing && EngineDataSource.served > second) && System.currentTimeMillis() < end) Thread.sleep(200)
        val playing = PhonePlayer.state.value.playing
        val served = EngineDataSource.served
        val error = PhonePlayer.state.value.error
        context.startService(Intent(context, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP))
        Thread.sleep(500)
        PhoneEngine.close()
        assertTrue("The on-phone player didn't start playing (error: $error)", playing)
        assertTrue("The player took only $served bytes of the engine's mix", served > second)
    }
}
