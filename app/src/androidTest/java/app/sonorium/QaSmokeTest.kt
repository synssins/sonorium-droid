package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The QA build (app.sonorium.qa: shrunk by R8 exactly like a release) plays the sample on this device: a fresh
 * install, first start, My Channel's Play, and the sound flows (its media session plays, no "stopped
 * unexpectedly", nothing in the log about files it can't read). Black box (UiAutomator and the shell only), so
 * what R8 removed from the app shows here as it would for a user. run_device_test.sh installs the QA APK first.
 */
@RunWith(AndroidJUnit4::class)
class QaSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "app.sonorium.qa"

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    @Test
    fun theReleaseBuildPlaysOnTheDevice() {
        // Through the shell: Android 11+ hides other apps from this one (package visibility)
        assertTrue("The QA build isn't installed", shell("pm path $pkg").contains("package:"))
        shell("logcat -c")
        shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        assertNotNull("The QA build didn't open", device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 30_000))
        // The first-run guide: through it
        repeat(4) {
            device.wait(Until.findObject(By.text("Skip")), 8_000)?.let { it.click(); Thread.sleep(800) }
        }
        // My Channel on this device: its row's Play (phones) or the card's Play
        val play = device.wait(Until.findObject(By.desc("Play My Channel")), 30_000) ?: device.findObject(By.text("Play"))
        assertNotNull("No Play for My Channel", play)
        play.click()
        // Android may ask for notifications on the first Play
        device.wait(Until.findObject(By.text("Allow")), 5_000)?.click()
        var playing = false
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && !playing) {
            Thread.sleep(1_000)
            playing = shell("dumpsys media_session").lineSequence()
                .dropWhile { !it.contains("package=$pkg") }.take(20).any { it.contains("PLAYING(3)") || it.contains("{state=3,") }
        }
        Thread.sleep(5_000)
        val log = shell("logcat -d -s python.stdout:W python.stderr:W PhonePlayer:E LoadTask:E")
        val screen = device.hasObject(By.textContains("stopped unexpectedly"))
        shell("am force-stop $pkg")
        assertFalse("The QA build can't read theme files: ${log.lineSequence().firstOrNull { "Can't read" in it }}", log.contains("Can't read"))
        assertFalse("Playback stopped unexpectedly in the QA build: $log", screen || log.contains("Playback error"))
        assertTrue("The QA build's media session never played", playing)
    }
}
