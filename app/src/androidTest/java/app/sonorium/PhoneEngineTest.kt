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
import org.junit.Assert.assertEquals
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
        // A sequence over the test theme's two presets (presets.json, as the server stores it)
        File(theme, "presets.json").let { f ->
            val doc = org.json.JSONObject(f.readText())
            doc.put("sequences", org.json.JSONObject().put("walk", org.json.JSONObject().put("name", "Walk")
                .put("presets", org.json.JSONArray().put("calm").put("busy")).put("at_end", "restart").put("hold_min", 5).put("hold_max", 5).put("ramp", 2)))
            f.writeText(doc.toString())
            assertTrue("presets.json sequences not read", PhoneEngine.sequencesFromJson(f.readText()).any { it.id == "walk" })
        }
        // Decode every audio file the way ThemePrep does (mirror layout, .wav)
        val pcm = File(root, "pcm")
        theme.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.forEach { f ->
            PcmDecoder.decodeToWav(f, File(pcm, f.relativeTo(theme).path.substringBeforeLast('.') + ".wav"))
        }
        val presets = PhoneEngine.open(context, ThemePrep.Prepared(theme, pcm), null, 1f)
        // 2 s of the mix is not silent
        val buf = ByteArray(PhoneEngine.BYTES_PER_SECOND * 2)
        PhoneEngine.render(buf, 0, buf.size)
        val samples = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
        assertTrue("The engine rendered silence (peak $peak)", peak > 500)
        assertTrue("presets: $presets", presets.size >= 0)

        // The sequence runs: its step shows, Next moves on
        assertTrue("No sequences from the engine", PhoneEngine.sequences().any { it.id == "walk" })
        PhoneEngine.startSequence("walk")
        PhoneEngine.render(ByteArray(PhoneEngine.BYTES_PER_SECOND), 0, PhoneEngine.BYTES_PER_SECOND)
        val first = PhoneEngine.step()
        assertTrue("No sequence step: $first", first != null && first.step == 1 && first.steps == 2)
        assertEquals("sequence", PhoneEngine.next())
        PhoneEngine.render(ByteArray(PhoneEngine.BYTES_PER_SECOND / 10), 0, PhoneEngine.BYTES_PER_SECOND / 10)
        assertEquals(2, PhoneEngine.step()?.step)

        // Another theme while it plays: the engine crossfades to it (Chris's bug: the theme didn't switch)
        val other = File(Python.getInstance().getModule("lab").callAttr("make_test_theme", File(root, "other").apply { mkdirs() }.path).toString())
        File(other, "metadata.json").let { f -> f.writeText(org.json.JSONObject(f.readText()).put("id", "other-theme").put("name", "Other").toString()) }
        val otherPcm = File(root, "other-pcm")
        other.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.forEach { f ->
            PcmDecoder.decodeToWav(f, File(otherPcm, f.relativeTo(other).path.substringBeforeLast('.') + ".wav"))
        }
        PhoneEngine.setTheme(ThemePrep.Prepared(other, otherPcm), null)
        val after = ByteArray(PhoneEngine.BYTES_PER_SECOND * 6)
        PhoneEngine.render(after, 0, after.size)
        val tail = ByteBuffer.wrap(after, after.size - PhoneEngine.BYTES_PER_SECOND, PhoneEngine.BYTES_PER_SECOND).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peakAfter = 0
        while (tail.hasRemaining()) peakAfter = maxOf(peakAfter, kotlin.math.abs(tail.get().toInt()))
        assertTrue("Silence after switching themes (peak $peakAfter)", peakAfter > 500)

        // The media service plays the engine's stream (ExoPlayer accepts the WAV stream and pulls the mix)
        EngineDataSource.served = 0
        // Play is pressed with the app on screen (Android 15 refuses audio focus to a player started
        // from the background, as a bare service start from a test is)
        val app = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario = androidx.test.core.app.ActivityScenario.launch<MainActivity>(app)
        Thread.sleep(3_000)
        context.startService(Intent(context, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_PLAY).putExtra(PhonePlayerService.EXTRA_TITLE, "Test Theme"))
        val end = System.currentTimeMillis() + 20_000
        // One second of the engine's stream (44.1 kHz 16-bit stereo)
        val second = PhoneEngine.BYTES_PER_SECOND.toLong()
        while (!(PhonePlayer.state.value.playing && EngineDataSource.served > second) && System.currentTimeMillis() < end) Thread.sleep(200)
        val playing = PhonePlayer.state.value.playing
        val served = EngineDataSource.served
        // The mix keeps to real time (the engine's clock times sequences and playlists), even where the
        // output drains faster (the emulators have no sound): 6 s later, at most 6 s more plus the allowed lead
        val before = EngineDataSource.served
        Thread.sleep(6_000)
        val inSix = EngineDataSource.served - before
        // What the lock screen sees: a live stream (no seek bar, no length), the title, Next theme and Stop
        val token = androidx.media3.session.SessionToken(context, android.content.ComponentName(context, PhonePlayerService::class.java))
        val controller = androidx.media3.session.MediaController.Builder(context, token).buildAsync().get(10, java.util.concurrent.TimeUnit.SECONDS)
        val live = java.util.concurrent.atomic.AtomicReference<String>()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            live.set(
                listOf(
                    "duration=${controller.duration == androidx.media3.common.C.TIME_UNSET}",
                    "live=${controller.isCurrentMediaItemLive}",
                    "seekable=${controller.isCurrentMediaItemSeekable}",
                    "next=${controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)}",
                    "previous=${controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)}",
                    "title=${controller.mediaMetadata.title}",
                    "artwork=${controller.mediaMetadata.artworkData != null}",
                    "buttons=${controller.mediaButtonPreferences.mapNotNull { it.displayName?.toString() }}",
                ).joinToString(" "),
            )
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.release() }
        val error = PhonePlayer.state.value.error
        context.startService(Intent(context, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP))
        Thread.sleep(500)
        runCatching { scenario.close() }
        PhoneEngine.close()
        assertTrue("The on-phone player didn't start playing (error: $error)", playing)
        assertTrue("The player took only $served bytes of the engine's mix", served > second)
        assertTrue("The mix ran ahead of real time: ${inSix / second} s of audio in 6 s", inSix <= second * (6 + 4 + 1))
        val seen = live.get()
        // The title is the theme chosen on this device (the sample, opened with the main screen), else the one given
        val title = PhonePlayer.title() ?: "Test Theme"
        listOf("duration=true", "live=true", "seekable=false", "next=true", "previous=true", "title=$title", "artwork=true", "Next theme", "Stop").forEach {
            assertTrue("Lock-screen control: expected $it in [$seen]", seen.contains(it))
        }
    }
}
