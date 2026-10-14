package app.sonorium

import android.content.Intent
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.sonorium.local.AppFolderProvider
import app.sonorium.server.ServerViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The themes folder flows as a person goes through them on the main screen (validation alpha33): Move after a
 * folder change moves the themes (R1); an edited theme whose folder went away is saved into a chosen folder
 * with its edits (R2), the editor open on it again; the setting says a folder that's gone (R3).
 */
@RunWith(AndroidJUnit4::class)
class FolderFlowsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val sonorium = context.applicationContext as SonoriumApp
    private val store = sonorium.themeStore
    private val picked = File(context.cacheDir, "folder-test")

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    @After
    fun cleanUp() {
        store.useDefault()
        store.releaseUnused()
        picked.deleteRecursively()
        runBlocking { store.list().filter { it.folder.startsWith("Flow test") }.forEach { store.remove(it) } }
    }

    private fun tree(sub: String) = DocumentsContract.buildTreeDocumentUri(AppFolderProvider.authority(context), "${AppFolderProvider.BASE_TEST}:$sub")

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(300)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        instrumentation.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    /** The main screen on this device's entry, and its view model. */
    private fun open(): Pair<ActivityScenario<MainActivity>, ServerViewModel> {
        runBlocking {
            if (!sonorium.entitlement.isPro.value) sonorium.entitlement.purchase()
            sonorium.servers.select(SonoriumApp.DEVICE_ID)
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        // The main screen, or the dialog over it (a folder that's gone is said at once)
        assertTrue("No main screen", waitFor(20_000) { device.hasObject(By.res("top-bar")) || device.hasObject(By.text("Not now")) })
        val model = onMain {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            ViewModelProvider(activity as ViewModelStoreOwner)[ServerViewModel::class.java]
        }
        return scenario to model
    }

    @Test
    fun moveAfterAFolderChangeMovesTheThemes() {
        store.useDefault()
        val name = "Flow test move"
        runBlocking { store.createTheme(name, """{"spec_version": 2, "id": "flow-move", "name": "$name"}""") }
        picked.deleteRecursively()
        File(picked, "A").mkdirs()
        val (scenario, model) = open()
        scenario.use {
            onMain { model.refreshLocalThemes() }
            assertTrue("The theme isn't listed", waitFor(10_000) { model.localThemes.value.orEmpty().any { it.folder == name } })
            onMain { model.useThemesFolder(tree("A"), changing = true) }
            val move = device.wait(Until.findObject(By.text("Move")), 10_000)
            assertNotNull("No move offer", move)
            move.click()
            assertTrue("Not moved to the new folder", waitFor(15_000) { File(picked, "A/Sonorium/$name/metadata.json").isFile })
            assertTrue("Still in the old folder", waitFor(10_000) { !File(AppFolderProvider.appStorage(context), "Sonorium/$name").exists() })
            assertTrue("Not listed after the move", waitFor(10_000) { model.localThemes.value.orEmpty().any { it.folder == name } })
        }
    }

    @Test
    fun anEditedThemeWhoseFolderWentIsSavedIntoAChosenFolder() {
        picked.deleteRecursively()
        File(picked, "A").mkdirs()
        File(picked, "B").mkdirs()
        store.setRoot(tree("A"))
        val name = "Flow test rescue"
        val dir = File(picked, "A/Sonorium/$name").apply { mkdirs() }
        File(dir, "metadata.json").writeText("""{"spec_version": 2, "id": "flow-rescue", "name": "$name"}""")
        File(dir, "Rain.wav").writeBytes(ByteArray(2_000) { 1 })
        val (scenario, model) = open()
        scenario.use {
            onMain { model.refreshLocalThemes() }
            assertTrue("The theme isn't listed", waitFor(15_000) { model.state.value.themes.any { it.name == name } })
            val theme = model.state.value.themes.first { it.name == name }
            onMain { model.openThemeEditor(theme) }
            assertTrue("The editor didn't open", waitFor(15_000) { model.editor.value?.ui?.value?.loading == false })
            val editor = model.editor.value!!
            onMain { editor.setDescription("Edited before the folder went") }
            // The folder goes away; Save says so and keeps the edits
            File(picked, "A/Sonorium").renameTo(File(picked, "A/Gone"))
            val saved = runBlocking { editor.saveTheme(quiet = true) }
            assertFalse("A save to a gone folder went through", saved)
            assertTrue("No folder offered", waitFor(10_000) { editor.folderProblem.value })
            // A folder chosen: the theme is rebuilt there with the edits (not lost to the folder change)
            onMain { editor.folderProblemSeen(); model.rescueEditedTheme(tree("B")) }
            val meta = { File(picked, "B/Sonorium").listFiles()?.map { File(it, "metadata.json") }?.firstOrNull { it.isFile } }
            assertTrue("Not saved in the chosen folder", waitFor(20_000) { meta() != null })
            assertTrue("The edits were lost", JSONObject(meta()!!.readText()).optString("description") == "Edited before the folder went")
            assertTrue("The editor didn't open again on it", waitFor(15_000) { model.editor.value?.ui?.value?.loading == false })
            // Saving again works (not "Theme not found")
            val again = model.editor.value!!
            onMain { again.setDescription("Saved again") }
            assertTrue("Saving again failed", runBlocking { again.saveTheme(quiet = true) })
            onMain { model.closeThemeEditor() }
        }
    }

    @Test
    fun aFolderThatWentIsShownAsGoneInTheSetting() {
        picked.deleteRecursively()
        File(picked, "A").mkdirs()
        store.setRoot(tree("A"))
        File(picked, "A/Sonorium").deleteRecursively()
        val (scenario, model) = open()
        scenario.use {
            onMain { model.refreshLocalThemes() }
            assertTrue("Not seen as gone", waitFor(10_000) { model.localFolder.value == app.sonorium.local.ThemeStore.FolderState.MISSING })
            // The main screen offers a folder (dialog): Not now, then the setting
            device.wait(Until.findObject(By.text("Not now")), 5_000)?.click()
            Thread.sleep(800)
            device.findObject(By.desc("Menu"))?.let { it.click(); Thread.sleep(1_000) }
            if (device.wait(Until.findObject(By.text("Audio Settings")), 2_000) == null) device.findObject(By.text("Settings"))?.let { it.click(); Thread.sleep(800) }
            device.wait(Until.findObject(By.text("Audio Settings")), 3_000)!!.click()
            Thread.sleep(1_500)
            var row = device.findObject(By.res("themes-folder"))
            repeat(8) {
                if (row != null) return@repeat
                device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
                Thread.sleep(500)
                row = device.findObject(By.res("themes-folder"))
            }
            assertNotNull("No themes folder setting", row)
            assertNotNull("The setting doesn't say the folder is gone", row!!.findObject(By.text(context.getString(R.string.folder_gone_title))))
        }
    }
}
