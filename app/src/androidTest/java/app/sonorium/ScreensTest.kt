package app.sonorium

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of the web UI's main pages in portrait and landscape, for one
 * screen profile (CI changes the emulator's size and density between runs).
 * Needs a server linked already (EndToEndTest runs first, same install).
 * Argument `profile`: a name for the files, e.g. `tab-s10-ultra`.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "default"
    private val shots = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e/$profile").apply { mkdirs() }

    @Test
    fun mainPages() {
        // A fresh start opens the server used last.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForPage()
            capture("portrait")
            device.setOrientationLandscape()
            Thread.sleep(2_500)
            capture("landscape")
            device.setOrientationNatural()
            Thread.sleep(1_500)
        }
    }

    private fun capture(orientation: String) {
        VIEWS.forEachIndexed { i, view ->
            runCatching {
                onWebView().perform(Atoms.script("showView('$view'); window.scrollTo(0, 0); return true;"))
            }
            Thread.sleep(2_000)
            device.takeScreenshot(File(shots, "$orientation-${i + 1}-$view.png"))
        }
        runCatching { onWebView().perform(Atoms.script("showView('sessions'); return true;")) }
    }

    private fun waitForPage() {
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            if (runCatching { onWebView().withElement(findElement(Locator.ID, "sessions-container")) }.isSuccess) {
                Thread.sleep(1_500)
                return
            }
            Thread.sleep(1_000)
        }
        device.takeScreenshot(File(shots, "timeout.png"))
        throw AssertionError("The web UI didn't load")
    }

    private companion object {
        val VIEWS = listOf(
            "sessions", "themes", "playlists", "settings-speakers", "settings-groups",
            "settings-spaces", "settings-audio", "settings-advanced", "status",
        )
    }
}
