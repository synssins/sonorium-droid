package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server renamed (its Server name setting): the app's entry follows it on the next connection.
 * Skipped on a server without the setting. Runs after EndToEndTest linked the CI server.
 */
@RunWith(AndroidJUnit4::class)
class ServerNameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sonorium = context.applicationContext as SonoriumApp

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    @Test
    fun theEntryFollowsTheServersName() {
        val record = runBlocking { sonorium.servers.servers.first().firstOrNull { !it.device } }
        assumeNotNull("No linked server", record)
        record!!
        val api = runBlocking {
            val token = sonorium.servers.token(record.id)!!
            val pinned = PinnedTls.pinnedClient(sonorium.baseClient, record.fingerprint)
            val reached = sonorium.linker.reach(pinned, record.hosts, record.httpsPort)!!
            SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), token)
        }
        val settings = runBlocking { api.json("GET", "settings") }
        assumeTrue("This server has no Server name setting", settings.containsKey("server_name"))
        val before = (settings["server_name"] as? JsonPrimitive)?.content.orEmpty()
        try {
            runBlocking { api.json("PUT", "settings", buildJsonObject { put("server_name", "CI Living Room") }) }
            assertEquals("CI Living Room", runBlocking { api.info().name })
            runBlocking { sonorium.servers.select(record.id) }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                val end = System.currentTimeMillis() + 30_000
                var shown = ""
                while (System.currentTimeMillis() < end) {
                    shown = runBlocking { sonorium.servers.find(record.id)?.displayName.orEmpty() }
                    if (shown == "CI Living Room") break
                    Thread.sleep(500)
                }
                assertEquals("The entry didn't follow the server's name", "CI Living Room", shown)
            }
        } finally {
            runBlocking { runCatching { api.json("PUT", "settings", buildJsonObject { put("server_name", before) }) } }
        }
    }
}
