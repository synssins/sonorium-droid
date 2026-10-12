package app.sonorium

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.sonorium.listen.ListenController
import app.sonorium.web.WebViewSupport
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a real Sonorium server (CI starts one in Docker; see
 * .github/workflows/android.yml). Instrumentation arguments:
 *  - `linkUri`: a Link App code, base64 (it holds `&` and `#`)
 *  - `channelName`: a channel the server has
 *
 * Drives the native screens the way a person would. Screenshots go to the app's
 * external files folder for CI to collect.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val shots = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    @Test
    fun linksAndControlsTheServerNatively() {
        val link = String(Base64.decode(args.getString("linkUri")!!, Base64.DEFAULT))
        val channelName = args.getString("channelName") ?: "CI channel"

        // 1. The Link App code arrives as it would from the camera (App Link).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setClassName(instrumentation.targetContext, MainActivity::class.java.name)
        ActivityScenario.launch<MainActivity>(intent).use {
            val open = device.wait(Until.findObject(By.text("Open")), 30_000)
            screenshot("01-linked")
            assertNotNull("Linking didn't finish; see 01-linked.png", open)

            // A WebView too old for the server's pages (an Android 8 image never updated) only
            // matters for pages not native yet; the native screens don't need it.
            val oldWebView = WebViewSupport.outdated(instrumentation.targetContext)
            open.click()

            // 2. The native Channels screen shows the server's channel.
            need(By.text(channelName), 60_000, "02-channels", "The channel didn't appear")
            Thread.sleep(1_500)
            screenshot("02-channels-portrait")

            // 3. Preset: pick Default settings → the web UI's toast text.
            find(By.desc("Preset"), "No preset field").click()
            need(By.text("Default settings"), 10_000, "03-preset-menu", "No preset menu").click()
            need(By.text("Using default settings"), 10_000, "03-preset-toast", "No toast after choosing a preset")
            screenshot("03-preset")

            // 4. Play with no speakers: the server's refusal shows as a toast, verbatim.
            find(By.text("Play"), "No Play button").click()
            need(By.text("No speakers selected"), 10_000, "04-play-refused", "No error toast for Play without speakers")

            // 5. Listen here plays natively, with a playback notification; the same button stops it.
            find(By.desc("Listen on this device"), "No listen button").click()
            assertTrue("The native player didn't start: ${ListenController.state.value}", waitFor(30_000) { ListenController.state.value?.playing == true })
            screenshot("05-listening")
            device.openNotification()
            val notification = device.wait(Until.findObject(By.textContains(channelName)), 10_000)
            screenshot("06-notification")
            device.pressBack()
            Thread.sleep(1_000)
            assertNotNull("No playback notification for $channelName", notification)
            find(By.desc("Listen on this device"), "No listen button").click()
            assertTrue("The native player didn't stop", waitFor(15_000) { ListenController.state.value?.playing != true })

            // 6. The editor opens with the channel and closes with Cancel.
            find(By.desc("Edit"), "No Edit button").click()
            need(By.text("Edit Channel"), 10_000, "07-editor", "The editor didn't open")
            screenshot("07-editor")
            find(By.text("Cancel"), "No Cancel").click()

            // 7. A new channel: pick a theme, create → toast and a second card.
            find(By.text("New Channel"), "No New Channel button").click()
            need(By.text("New Channel"), 10_000, "08-new", "The new channel editor didn't open")
            device.wait(Until.findObject(By.text("A Rainy Day")), 10_000)?.click()
            Thread.sleep(500)
            screenshot("08-new-channel")
            find(By.text("Create Channel"), "No Create Channel button").click()
            need(By.text("Channel created"), 15_000, "09-created", "No toast after creating a channel")
            screenshot("09-created")

            // 8. Landscape.
            device.setOrientationLandscape()
            Thread.sleep(2_500)
            screenshot("10-channels-landscape")
            device.setOrientationNatural()
            Thread.sleep(2_000)

            // 9. The server button returns to the server list.
            find(By.descStartsWith("Servers:"), "No server button").click()
            need(By.text("Scan Link App code"), 15_000, "11-servers", "The server button didn't return to the list")
            screenshot("11-servers")
            oldWebView?.let { File(shots, "old-webview.txt").writeText("WebView ${it.versionName}\n") }
        }
    }

    private fun find(selector: BySelector, message: String): UiObject2 =
        device.wait(Until.findObject(selector), 10_000) ?: run {
            screenshot("99-missing")
            throw AssertionError(message)
        }

    private fun need(selector: BySelector, timeoutMs: Long, shot: String, message: String): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: run {
            screenshot("99-$shot")
            throw AssertionError("$message; see 99-$shot.png")
        }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }

    private fun screenshot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }
}
