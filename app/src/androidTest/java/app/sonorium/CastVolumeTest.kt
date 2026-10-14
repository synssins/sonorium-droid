package app.sonorium

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.cast.CastVolumeSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cast's volume on the device's volume keys: the remote-playback session the app holds while casting, with a
 * stand-in speaker (no Cast device is ever used in tests). The real speaker's side (Google Home, the speaker's
 * buttons) can only be checked with real hardware.
 */
@RunWith(AndroidJUnit4::class)
class CastVolumeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)

    /** The stand-in speaker: what the session asked for. */
    private class Speaker : CastVolumeSession.Target {
        @Volatile var lastLevel = -1.0
        @Volatile var lastMuted: Boolean? = null
        @Volatile var calls = 0
        override fun setLevel(level: Double) { lastLevel = level; calls++ }
        override fun setMuted(muted: Boolean) { lastMuted = muted; calls++ }
    }

    private val speaker = Speaker()
    private var session: CastVolumeSession? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { session?.release() }
        session = null
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        assertTrue(what, check())
    }

    private fun open(level: Double, muted: Boolean = false): MediaController {
        instrumentation.runOnMainSync {
            session = CastVolumeSession(context, "Test speaker", speaker).also { it.update(level, muted) }
        }
        return MediaController(context, session!!.token)
    }

    @Test
    fun theSystemSeesTheSpeakersVolume() {
        val controller = open(0.5)
        val info = controller.playbackInfo!!
        assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE, info.playbackType)
        assertEquals(20, info.maxVolume)
        assertEquals(10, info.currentVolume)
        // Changed elsewhere (Google Home, the speaker's buttons): the panel follows
        instrumentation.runOnMainSync { session!!.update(0.3, false) }
        waitFor("Panel shows 6 of 20, not ${controller.playbackInfo?.currentVolume}") { controller.playbackInfo?.currentVolume == 6 }
        instrumentation.runOnMainSync { session!!.update(0.3, true) }
        waitFor("Muted shows 0") { controller.playbackInfo?.currentVolume == 0 }
    }

    @Test
    fun stepsAndSetsTheSpeaker() {
        val controller = open(0.5)
        controller.adjustVolume(AudioManager.ADJUST_RAISE, 0)
        waitFor("Up: 55 %, got ${speaker.lastLevel}") { Math.abs(speaker.lastLevel - 0.55) < 1e-6 }
        waitFor("Panel follows: 11") { controller.playbackInfo?.currentVolume == 11 }
        controller.adjustVolume(AudioManager.ADJUST_LOWER, 0)
        controller.adjustVolume(AudioManager.ADJUST_LOWER, 0)
        waitFor("Down twice: 45 %, got ${speaker.lastLevel}") { Math.abs(speaker.lastLevel - 0.45) < 1e-6 }
        // The panel's slider and its mute icon (which sets 0)
        controller.setVolumeTo(16, 0)
        waitFor("Slider: 80 %, got ${speaker.lastLevel}") { Math.abs(speaker.lastLevel - 0.8) < 1e-6 }
        controller.setVolumeTo(0, 0)
        waitFor("Mute") { speaker.lastMuted == true }
        assertEquals("Muting keeps the level", 0.8, speaker.lastLevel, 1e-6)
        waitFor("Muted shows 0") { controller.playbackInfo?.currentVolume == 0 }
        controller.adjustVolume(AudioManager.ADJUST_RAISE, 0)
        waitFor("Up unmutes") { speaker.lastMuted == false }
        waitFor("Back at 16") { controller.playbackInfo?.currentVolume == 16 }
    }

    @Test
    fun volumeKeysGoToTheSpeakerWhileCastingOnly() {
        val music = AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(music)
        audio.setStreamVolume(music, audio.getStreamMaxVolume(music) / 2, 0)
        try {
            val mid = audio.getStreamVolume(music)
            open(0.5)
            // What a volume key does without a window to take it: the system's default volume session
            audio.adjustVolume(AudioManager.ADJUST_RAISE, 0)
            waitFor("The key reached the speaker (got ${speaker.lastLevel})") { Math.abs(speaker.lastLevel - 0.55) < 1e-6 }
            assertEquals("The device's media volume stays", mid, audio.getStreamVolume(music))

            // Casting stops: the keys are the device's own media volume again
            instrumentation.runOnMainSync { session?.release() }
            session = null
            val calls = speaker.calls
            audio.adjustVolume(AudioManager.ADJUST_LOWER, 0)
            Thread.sleep(1_000)
            assertEquals("No key reaches the speaker after casting", calls, speaker.calls)
            // Android 9+ keeps media volume on the keys when nothing plays (older versions take the ringer)
            if (Build.VERSION.SDK_INT >= 28) {
                waitFor("Media volume lowered (still ${audio.getStreamVolume(music)})") { audio.getStreamVolume(music) < mid }
            }
            assertFalse(speaker.lastMuted == true)
        } finally {
            audio.setStreamVolume(music, before, 0)
        }
    }
}
