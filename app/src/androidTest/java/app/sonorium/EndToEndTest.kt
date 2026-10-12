package app.sonorium

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.sonorium.web.WebViewSupport
import java.io.File
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a real Sonorium server (CI starts one in Docker; see
 * .github/workflows/android.yml). Instrumentation arguments:
 *  - `linkUri`: a Link App code, base64 (it holds `&` and `#`)
 *  - `channelName`: a channel the server has, to find in the page
 *
 * Screenshots go to the app's external files folder for CI to collect.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val shots = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    @Test
    fun linksByCodeAndControlsTheServerThroughItsWebUi() {
        val link = String(Base64.decode(args.getString("linkUri")!!, Base64.DEFAULT))
        val channelName = args.getString("channelName") ?: "CI channel"

        // 1. The Link App code arrives as it would from the camera (App Link).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setClassName(instrumentation.targetContext, MainActivity::class.java.name)
        ActivityScenario.launch<MainActivity>(intent).use {
            val open = device.wait(Until.findObject(By.text("Open")), 30_000)
            screenshot("01-linked")
            assertNotNull("Linking didn't finish; see 01-linked.png", open)
            open.click()

            // A WebView too old for the web UI (an Android 8 image that was never
            // updated): the app must say so and offer the update instead.
            WebViewSupport.outdated(instrumentation.targetContext)?.let { old ->
                val notice = device.wait(Until.findObject(By.textContains("web view is too old")), 30_000)
                screenshot("02-old-webview-${old.major}")
                assertNotNull("No update notice for WebView ${old.versionName}", notice)
                assertNotNull(device.findObject(By.text("Update")))
                return
            }

            // 2. The server's web UI loads through the proxy and lists the server's channel.
            waitForWeb(90_000) {
                onWebView().withElement(findElement(Locator.ID, "sessions-container"))
                    .check(webMatches(getText(), containsString(channelName)))
            }
            screenshot("02-channels-portrait")

            // 3. Landscape gets the wide layout.
            device.setOrientationLandscape()
            Thread.sleep(3_000)
            screenshot("03-channels-landscape")
            device.setOrientationNatural()
            Thread.sleep(2_000)

            // 4. The menu's Servers item returns to the server list.
            onWebView().perform(
                Atoms.script("toggleSidebar(); document.querySelector('[data-host=\"servers\"]').click(); return true;"),
            )
            val list = device.wait(Until.findObject(By.text("Scan Link App code")), 15_000)
            screenshot("04-servers")
            assertNotNull("The Servers menu item didn't return to the server list", list)
        }
    }

    private fun waitForWeb(timeoutMs: Long, check: () -> Unit) {
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                check()
                return
            } catch (e: Throwable) {
                last = e
                Thread.sleep(1_000)
            }
        }
        screenshot("99-web-timeout")
        throw AssertionError("The web UI didn't show the channel in time", last)
    }

    private fun screenshot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }
}
