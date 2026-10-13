package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA screen tour: every screen in each connection state, as a screenshot plus the UI tree (text and
 * descriptions), for review. CI runs one method per state at the right point of the run (see
 * e2e/run_device_test.sh): [offline] on the fresh install, [linked] after EndToEndTest linked the CI
 * server, [unreachable] with the network off, [removed] after removing the server in the app.
 * Files: e2e/tour/<state>/<NN-name>.png and .xml. It doesn't assert: it records what shows.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val root = File(context.getExternalFilesDir(null), "e2e/tour")
    private var dir = root
    private var n = 0

    @org.junit.Before
    fun hideSystemErrorDialogs() {
        shell("settings put global hide_error_dialogs 1")
        shell("settings put secure anr_show_background 0")
    }

    /** Fresh install: no server, nothing unlocked, no themes on the phone. */
    @Test
    fun offline() {
        state("1-offline")
        launch {
            settle(4_000)
            shot("main")
            tourPages()
            // Top bar: the server button (Connect) → Servers, then Back
            device.findObject(By.descStartsWith("Servers:"))?.let { it.click(); settle(); shot("servers-from-top-bar"); back() }
            // Headphone button
            device.findObject(By.desc("My channel"))?.let { it.click(); settle(); shot("headphone"); back() }
            // Themes › Import theme: the unlock screen
            openPage("Themes")
            device.findObject(By.text("Import theme"))?.let { it.click(); settle(); shot("import-asks-unlock"); back() }
            // Unlocked (test build's local yes), still no themes
            runBlocking { (context.applicationContext as SonoriumApp).entitlement.purchase() }
            device.findObject(By.desc("My channel"))?.let { it.click(); settle(); shot("headphone-unlocked-no-themes"); back() }
            openPage("Themes")
            shot("themes-unlocked-no-themes")
        }
    }

    /** A server linked and reachable (EndToEndTest linked the CI server). */
    @Test
    fun linked() {
        state("2-linked")
        launch {
            device.wait(Until.findObject(By.text("New Channel")), 60_000)
            settle(2_000)
            shot("main")
            tourPages()
            device.findObject(By.desc("My channel"))?.let { it.click(); settle(); shot("headphone"); back() }
            openPage("Themes")
            device.findObject(By.text("On this phone"))?.let { it.click(); settle(); shot("themes-on-this-phone") }
            device.findObject(By.descStartsWith("More for "))?.let { it.click(); settle(800); shot("theme-card-menu"); back() }
            device.findObject(By.descStartsWith("Servers:"))?.let { it.click(); settle(); shot("servers"); back(); settle(); shot("after-servers-back") }
        }
    }

    /** The linked server can't be reached: at a cold start, then on the pages, then back online. */
    @Test
    fun unreachable() {
        state("3-unreachable")
        network(false)
        try {
            launch {
                settle(8_000)
                shot("cold-start")
                tourPages()
                device.findObject(By.desc("My channel"))?.let { it.click(); settle(); shot("headphone"); back() }
                device.findObject(By.descStartsWith("Servers:"))?.let { it.click(); settle(); shot("servers"); back() }
                network(true)
                settle(30_000)
                shot("back-online-30s")
            }
        } finally {
            network(true)
        }
    }

    /** The server removed in the app (Servers › Remove): what's left on screen, then after a restart. */
    @Test
    fun removed() {
        state("4-removed")
        launch {
            device.wait(Until.findObject(By.descStartsWith("Servers:")), 30_000)
            settle(2_000)
            device.findObject(By.descStartsWith("Servers:"))?.click()
            settle()
            device.findObject(By.text("Remove"))?.let {
                it.click()
                settle(800)
                shot("remove-confirm")
                // The dialog's own Remove button (the last "Remove" on screen)
                device.findObjects(By.text("Remove")).lastOrNull()?.click()
                settle()
                shot("servers-after-remove")
            }
            back()
            settle()
            shot("main-after-back")
            tourPages()
        }
        // A cold start after the removal
        launch {
            settle(4_000)
            shot("main-after-restart")
            device.findObject(By.desc("My channel"))?.let { it.click(); settle(); shot("headphone-after-restart"); back() }
        }
    }

    // ---------- helpers ----------

    private fun state(name: String) {
        dir = File(root, name).apply { deleteRecursively(); mkdirs() }
        n = 0
    }

    private fun launch(block: () -> Unit) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { block() }
    }

    /** Every page in the menu (Settings opened), each from a fresh menu. */
    private fun tourPages() {
        openMenu()
        shot("menu")
        device.findObject(By.res("menu:Settings"))?.let { it.click(); settle(800) }
        shot("menu-settings-open")
        val titles = device.findObjects(By.res(java.util.regex.Pattern.compile("menu:.*")))
            .mapNotNull { it.resourceName?.removePrefix("menu:") }
            // Servers opens its own screen (shot separately from the top bar)
            .filter { it != "Settings" && it != "Servers" }
            .distinct()
        back()
        titles.forEach { title ->
            openPage(title)
            shot("page-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
        }
    }

    private fun openMenu() {
        device.findObject(By.desc("Menu"))?.click()
        settle(1_200)
    }

    private fun openPage(title: String) {
        openMenu()
        if (!device.hasObject(By.res("menu:$title"))) {
            device.findObject(By.res("menu:Settings"))?.click()
            settle(800)
        }
        var item = device.findObject(By.res("menu:$title"))
        if (item == null) {
            // Below the fold (Status under an open Settings group): scroll the menu
            val w = device.displayWidth / 4
            device.swipe(w, device.displayHeight * 3 / 4, w, device.displayHeight / 4, 20)
            settle(800)
            item = device.findObject(By.res("menu:$title"))
        }
        if (item == null) {
            shot("missing-$title")
            back()
            return
        }
        item.click()
        settle(2_000)
    }

    private fun back() {
        device.pressBack()
        settle(800)
    }

    private fun settle(ms: Long = 1_500) {
        Thread.sleep(ms)
        if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click()
    }

    private fun shot(name: String) {
        n++
        val base = "%02d-%s".format(n, name)
        device.takeScreenshot(File(dir, "$base.png"))
        runCatching { File(dir, "$base.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
    }

    /** Network on/off for the app (Wi-Fi, mobile data and airplane mode, whichever the image honours). */
    private fun network(on: Boolean) {
        if (on) {
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
        } else {
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
        }
        Thread.sleep(3_000)
    }

    private fun shell(cmd: String) {
        runCatching { instrumentation.uiAutomation.executeShellCommand(cmd).close() }
    }
}
