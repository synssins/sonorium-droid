package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.local.PackageRules
import app.sonorium.net.PackageFile
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backups to the linked server (`/api/me/backups`): a mix, a whole theme sent in two parts (resumed with
 * Content-Range) and read back, playlists, the listing, then this phone's backups deleted again.
 * Runs after EndToEndTest has linked the CI server; skipped on a server without backups.
 */
@RunWith(AndroidJUnit4::class)
class BackupApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SonoriumApp

    @Test
    fun backsUpAndReadsBack() = runBlocking {
        val record = app.servers.servers.first().firstOrNull { !it.device }
        assumeNotNull("No linked server (EndToEndTest links one)", record)
        record!!
        val token = app.servers.token(record.id)
        assertNotNull("No token for ${record.displayName}", token)
        val pinned = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
        val reached = app.linker.reach(pinned, record.hosts, record.httpsPort)
        assertNotNull("Can't reach the server", reached)
        assumeTrue("This server keeps no backups", reached!!.info.capabilities?.contains("theme_backup") == true)
        val api = SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), token)

        // A mix of a server theme
        val mix = buildJsonObject {
            put("name", "Test mix")
            put("presets", buildJsonObject { put("calm", buildJsonObject { put("name", "Calm"); put("tracks", buildJsonObject { }) }) })
        }
        api.putBackupMix("backup-test-mix", mix)
        assertEquals("mix", (api.backupTheme("self", "backup-test-mix")["kind"] as JsonPrimitive).content)

        // A whole theme: the audio file in two parts, the second resumed where the first stopped
        val audio = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val meta = """{"name": "Backup test"}""".toByteArray()
        fun sha(b: ByteArray) = PackageRules.hex(MessageDigest.getInstance("SHA-256").digest(b))
        val files = listOf(PackageFile("Birds/tone.wav", audio.size.toLong(), sha(audio)), PackageFile("metadata.json", meta.size.toLong(), sha(meta)))
        val manifest = buildJsonObject {
            put("name", "Backup test")
            put("files", buildJsonArray { files.forEach { f -> add(buildJsonObject { put("path", f.path); put("size", f.size); put("sha256", f.sha256) }) } })
        }
        val started = api.putBackupPackage("backup-test-theme", manifest)
        assertEquals(2, (started["missing"] as JsonArray).size)
        // The first 50 000 bytes only (a dropped connection), then the rest from there
        val part = api.uploadBackupFile("backup-test-theme", "Birds/tone.wav", 50_000, 0) { audio.copyOf(50_000).inputStream() }
        assertTrue("A part counted as the whole file: $part", (part["complete"] as JsonPrimitive).booleanOrNull != true)
        val resume = (api.putBackupPackage("backup-test-theme", manifest)["missing"] as JsonArray).map { it as JsonObject }
            .first { (it["path"] as JsonPrimitive).content == "Birds/tone.wav" }
        val received = (resume["received"] as JsonPrimitive).content.toLong()
        assertTrue("Nothing kept of the first part ($received)", received > 0)
        api.uploadBackupFile("backup-test-theme", "Birds/tone.wav", audio.size.toLong(), received) { audio.inputStream() }
        val done = api.uploadBackupFile("backup-test-theme", "metadata.json", meta.size.toLong(), 0) { meta.inputStream() }
        assertEquals(true, (done["complete"] as JsonPrimitive).booleanOrNull)

        // Read back, as a restore does
        val pkg = api.backupPackage("self", "backup-test-theme")
        assertEquals(PackageRules.contentHash(files), pkg.contentHash)
        val dest = File(context.cacheDir, "backup-test.wav").apply { delete() }
        api.downloadBackupFile("self", "backup-test-theme", pkg.files.first { it.path == "Birds/tone.wav" }, dest) {}
        assertEquals(sha(audio), sha(dest.readBytes()))
        dest.delete()

        // Playlists, then the listing names this phone with all three
        api.putBackupPlaylists(buildJsonObject { put("playlists", buildJsonObject { put("p1", buildJsonObject { put("name", "Evening") }) }) })
        val mine = (api.backups()["devices"] as JsonArray).map { it as JsonObject }
            .first { (it["this_device"] as JsonPrimitive).booleanOrNull == true }
        val keys = (mine["themes"] as JsonArray).map { ((it as JsonObject)["key"] as JsonPrimitive).content }.toSet()
        assertTrue("Listing: $keys", keys.containsAll(setOf("backup-test-mix", "backup-test-theme")))
        assertEquals("1", (mine["playlists"] as JsonPrimitive).content)

        // Clean up: this phone's backups go
        api.deleteBackupDevice("self")
        val after = (api.backups()["devices"] as JsonArray).map { it as JsonObject }.none { (it["this_device"] as JsonPrimitive).booleanOrNull == true }
        assertTrue("This phone's backups are still listed", after)
    }
}
