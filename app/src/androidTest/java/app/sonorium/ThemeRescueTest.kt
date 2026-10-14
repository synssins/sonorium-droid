package app.sonorium

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.local.AppFolderProvider
import app.sonorium.local.PhoneThemeApi
import app.sonorium.local.ThemeRescue
import app.sonorium.net.ApiException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editing a theme whose themes folder goes away (Chris, S25): the save says the folder is the problem (the
 * editor then offers a folder), and the edits are saved into the default folder as a whole theme; files that
 * can't be read any more are named.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRescueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = (context.applicationContext as SonoriumApp).themeStore
    private val picked = File(context.cacheDir, "folder-test")

    @After
    fun cleanUp() {
        store.useDefault()
        picked.deleteRecursively()
        runBlocking { store.list().filter { it.folder.startsWith("Rescue test") }.forEach { store.remove(it) } }
    }

    @Test
    fun aSaveToAGoneFolderIsRescuedIntoTheDefault() = runBlocking {
        picked.deleteRecursively()
        picked.mkdirs()
        store.setRoot(DocumentsContract.buildTreeDocumentUri(AppFolderProvider.authority(context), "${AppFolderProvider.BASE_TEST}:"))
        // A theme with one audio file, in the chosen folder
        val dir = File(picked, "Sonorium/Rescue test").apply { mkdirs() }
        File(dir, "metadata.json").writeText("""{"spec_version": 2, "id": "rescue-test", "name": "Rescue test"}""")
        File(dir, "Rain.wav").writeBytes(ByteArray(2_000) { 1 })
        val theme = store.list().first { it.folder == "Rescue test" }
        val session = PhoneThemeApi.open(context, store, theme)

        // The folder goes away while editing (moved on the device, access lost)
        File(picked, "Sonorium").renameTo(File(picked, "Gone"))
        try {
            session.api.renameTheme(session.theme.id, "Rescue test renamed")
            fail("A save to a gone folder went through")
        } catch (e: ApiException) {
            assertEquals(PhoneThemeApi.FOLDER_PROBLEM, e.detail)
        }

        // Use default, then the edits are saved there
        store.useDefault()
        val r = ThemeRescue.rescue(context, store, PhoneThemeApi.safe(theme.folder), "Rescue test renamed", "After the folder went", "", emptyList())
        val saved = File(AppFolderProvider.appStorage(context), "Sonorium/${r.folder}")
        assertTrue("Not saved in the default folder", File(saved, "metadata.json").isFile)
        val meta = JSONObject(File(saved, "metadata.json").readText())
        assertEquals("Rescue test renamed", meta.getString("name"))
        assertEquals("rescue-test", meta.getString("id"))
        // The audio was in the folder that went: named as missing
        assertEquals(listOf("Rain.wav"), r.missing)
        assertTrue(store.list().any { it.folder == r.folder })
        Unit
    }
}
