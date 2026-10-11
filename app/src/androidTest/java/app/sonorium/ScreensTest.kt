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
            menu("portrait")
            capture("portrait")
            device.setOrientationLandscape()
            Thread.sleep(2_500)
            menu("landscape")
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

    /**
     * The menu: opened on phone layouts (hamburger), always there on wide ones.
     * Also records the page's viewport and the menu's size, which showed the
     * 0-height menu bug.
     */
    private fun menu(orientation: String) {
        val info = runCatching {
            onWebView().perform(
                Atoms.script(
                    """
                    var btn = document.getElementById('mobile-nav-btn');
                    if (btn && getComputedStyle(btn).display !== 'none' && btn.offsetParent !== null) toggleSidebar();
                    var probe = document.createElement('div');
                    probe.style.cssText = 'position:fixed;height:100vh;width:1px;visibility:hidden';
                    document.body.appendChild(probe);
                    var r = document.getElementById('sidebar').getBoundingClientRect();
                    var out = 'inner=' + innerWidth + 'x' + innerHeight + ' 100vh=' + probe.offsetHeight +
                        ' sidebar=' + Math.round(r.width) + 'x' + Math.round(r.height);
                    probe.remove();
                    return out;
                    """.trimIndent(),
                ),
            ).get().value?.toString()
        }.getOrElse { "error: ${it.message}" }
        File(shots, "$orientation-0-menu.txt").writeText(info + "\n")
        Thread.sleep(1_500)
        device.takeScreenshot(File(shots, "$orientation-0-menu.png"))
        runCatching {
            onWebView().perform(Atoms.script("if (document.body.classList.contains('sidebar-open')) closeSidebar(); return true;"))
        }
        Thread.sleep(500)
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
