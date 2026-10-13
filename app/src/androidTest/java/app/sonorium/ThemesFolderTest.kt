package app.sonorium

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.local.AppFolderProvider
import app.sonorium.local.ThemeStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The themes folder: the default (the app's own "Sonorium" folder, made without asking), a folder chosen
 * instead (its "Sonorium" folder made inside it; the system picker stood in for by a tree of the app's own
 * provider), moving the themes over, and the fallback when a chosen folder is gone.
 */
@RunWith(AndroidJUnit4::class)
class ThemesFolderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = (context.applicationContext as SonoriumApp).themeStore
    private val picked = File(context.cacheDir, "folder-test")

    @After
    fun backToDefault() {
        store.useDefault()
        picked.deleteRecursively()
    }

    private fun theme(name: String) = runBlocking {
        store.createTheme(name, """{"spec_version": 2, "id": "$name-id", "name": "$name"}""")
    }

    @Test
    fun theDefaultNeedsNoQuestion() = runBlocking {
        store.useDefault()
        assertTrue(store.isDefault)
        assertEquals(ThemeStore.FolderState.OK, store.folderState())
        assertEquals("App storage/Sonorium", store.location())
        assertTrue("No default folder", File(AppFolderProvider.appStorage(context), "Sonorium").isDirectory)
        val name = "Folder test default"
        store.list().firstOrNull { it.folder == name }?.let { store.remove(it) }
        theme(name)
        assertTrue("The theme isn't listed", store.list().any { it.folder == name })
        store.list().first { it.folder == name }.let { store.remove(it) }
        Unit
    }

    @Test
    fun aChosenFolderGetsASonoriumFolderAndTheThemesMove() = runBlocking {
        store.useDefault()
        val name = "Folder test move"
        store.list().firstOrNull { it.folder == name }?.let { store.remove(it) }
        theme(name)
        val before = store.current()!!
        // The picker's answer: a folder (here one of the app's own, standing in for e.g. Music)
        picked.deleteRecursively()
        picked.mkdirs()
        val tree = DocumentsContract.buildTreeDocumentUri(AppFolderProvider.authority(context), "${AppFolderProvider.BASE_TEST}:")
        assertTrue(store.setRoot(tree))
        assertFalse(store.isDefault)
        assertTrue("No Sonorium folder made in the chosen one", File(picked, "Sonorium").isDirectory)
        assertEquals("Sonorium", store.location())
        assertEquals(ThemeStore.FolderState.OK, store.folderState())
        // Choosing it again reuses that folder
        assertFalse(store.setRoot(tree))
        // Move: copied, checked, removed from the old folder
        var last = 0 to 0
        val moved = store.moveThemes(before) { d, t -> last = d to t }
        assertTrue("Nothing moved", moved >= 1)
        assertEquals(last.second, last.first)
        assertTrue("Not in the new folder", File(picked, "Sonorium/$name/metadata.json").isFile)
        assertFalse("Still in the old folder", File(AppFolderProvider.appStorage(context), "Sonorium/$name").exists())
        assertTrue(store.list().any { it.folder == name })
        Unit
    }

    @Test
    fun aFolderThatIsGoneFallsBackToTheDefault() = runBlocking {
        picked.deleteRecursively()
        picked.mkdirs()
        val tree = DocumentsContract.buildTreeDocumentUri(AppFolderProvider.authority(context), "${AppFolderProvider.BASE_TEST}:")
        store.setRoot(tree)
        // The folder goes (an SD card out, the folder deleted)
        File(picked, "Sonorium").deleteRecursively()
        assertEquals(ThemeStore.FolderState.MISSING, store.folderState())
        // Use default: works again at once
        assertTrue(store.useDefault())
        assertEquals(ThemeStore.FolderState.OK, store.folderState())
        Unit
    }
}
