package app.sonorium

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.sonorium.web.ServerActivity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots for one screen profile (CI changes the emulator's size and density between
 * runs), portrait and landscape: the native Channels screen, its menu and the channel editor,
 * then the Phase 2 native pages and a page still shown by the web view. Needs a server linked already (EndToEndTest runs
 * first, same install). Argument `profile`: a name for the files.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "default"
    private val shots = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e/$profile").apply { mkdirs() }

    @Test
    fun mainScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (orientation in listOf("portrait", "landscape")) {
                if (orientation == "landscape") device.setOrientationLandscape() else device.setOrientationNatural()
                Thread.sleep(2_500)
                device.wait(Until.findObject(By.text("New Channel")), 60_000)
                Thread.sleep(1_000)
                shot("$orientation-1-channels")
                // A collapsed row opened (phones collapse by default; wide screens may already be open)
                device.findObject(By.descStartsWith("Open "))?.let {
                    it.click()
                    Thread.sleep(1_000)
                    shot("$orientation-1b-channel-open")
                }
                // The menu (a side panel; a permanent sidebar on large screens)
                device.findObject(By.desc("Menu"))?.let {
                    it.click()
                    Thread.sleep(1_200)
                    shot("$orientation-2-menu")
                    device.pressBack()
                    Thread.sleep(800)
                }
                // The channel editor
                device.findObject(By.desc("Edit"))?.let {
                    it.click()
                    device.wait(Until.findObject(By.text("Edit Channel")), 10_000)
                    Thread.sleep(800)
                    shot("$orientation-3-editor")
                    device.findObject(By.text("Cancel"))?.click()
                    Thread.sleep(800)
                }
            }
            device.setOrientationNatural()
            Thread.sleep(1_500)
        }

        // The Phase 2 native pages, opened from the menu
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.text("New Channel")), 60_000)
            for ((i, page) in NATIVE_PAGES.withIndex()) {
                device.findObject(By.desc("Menu"))?.click()
                Thread.sleep(1_200)
                if (page in SETTINGS_PAGES && !device.hasObject(By.text(page))) {
                    device.findObject(By.text("Settings"))?.click()
                    Thread.sleep(600)
                }
                val item = device.findObject(By.text(page)) ?: continue
                item.click()
                Thread.sleep(2_500)
                shot("portrait-4-${i + 1}-${page.lowercase().replace(Regex("[^a-z]+"), "-")}")
                // The Theme Editor (Phase 3), from a theme's Edit
                if (page == "Themes") device.findObject(By.text("Edit"))?.let {
                    it.click()
                    device.wait(Until.findObject(By.text("Edit theme")), 15_000)
                    Thread.sleep(2_000)
                    shot("portrait-5-theme-editor")
                    device.findObject(By.desc("Sequences"))?.let { s ->
                        s.click()
                        Thread.sleep(800)
                        shot("portrait-5-theme-editor-sequences")
                        device.pressBack()
                        Thread.sleep(600)
                    }
                    device.findObject(By.text("Cancel"))?.click()
                    Thread.sleep(1_500)
                }
            }
        }

        // A page still in the web view, as the native menu opens it
        val app = instrumentation.targetContext.applicationContext as SonoriumApp
        val serverId = runBlocking { app.servers.currentId.first() } ?: return
        for ((i, view) in WEB_VIEWS.withIndex()) {
            val intent = ServerActivity.intent(instrumentation.targetContext, serverId, view)
            ActivityScenario.launch<ServerActivity>(intent).use {
                Thread.sleep(7_000)
                shot("portrait-web-${i + 1}-$view")
            }
        }
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private companion object {
        val WEB_VIEWS = listOf("plugin-users")
        val NATIVE_PAGES = listOf("Playlists", "Themes", "Speakers", "Speaker Groups", "Floors & Areas", "Status", "Connection", "Audio Settings", "Plugins", "Logs", "Advanced")
        val SETTINGS_PAGES = setOf("Speakers", "Speaker Groups", "Floors & Areas", "Connection", "Audio Settings", "Plugins", "Logs", "Advanced")
    }
}
