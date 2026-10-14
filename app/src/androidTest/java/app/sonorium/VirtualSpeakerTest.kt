package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.listen.ListenController
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A demo Sonorium (plugin `virtual_speakers`): a channel playing on the account's virtual speaker plays on this
 * device while it holds the speaker, and stops here when another client claims it. Skipped on other servers.
 */
@RunWith(AndroidJUnit4::class)
class VirtualSpeakerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sonorium = context.applicationContext as SonoriumApp

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(300)
        }
        return check()
    }

    @Test
    fun theVirtualSpeakerPlaysHereUntilAnotherClientTakesIt() {
        val record = runBlocking { sonorium.servers.servers.first().firstOrNull { !it.device } }
        assumeNotNull("No linked server", record)
        record!!
        val api = runBlocking {
            val pinned = PinnedTls.pinnedClient(sonorium.baseClient, record.fingerprint)
            val reached = sonorium.linker.reach(pinned, record.hosts, record.httpsPort)!!
            assumeTrue("Not a demo Sonorium", "virtual_speakers" in reached.info.plugins)
            SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), sonorium.servers.token(record.id))
        }
        val speaker = runBlocking { api.virtualSpeaker("test-other-client") }
        // A channel of its own with the speaker, playing (the server's other channels stay as they are)
        val channel = runBlocking {
            val made = api.createSession(buildJsonObject {
                put("theme_id", api.themes().first().id)
                put("custom_name", "Virtual speaker test")
                putJsonObject("adhoc_selection") { put("include_speakers", buildJsonArray { add(JsonPrimitive(speaker.id)) }) }
            })
            api.play(made.id)
            made
        }
        try {
            runBlocking { sonorium.servers.select(record.id) }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                assertTrue("The virtual speaker didn't play here", waitFor(30_000) { ListenController.state.value?.let { it.sessionId == speaker.id && it.playing } == true })
                // The notification shows the channel's theme (not the speaker's name) and artwork
                val theme = runBlocking { api.themes() }.first { it.id == channel.themeId }.name
                val token = androidx.media3.session.SessionToken(context, android.content.ComponentName(context, app.sonorium.listen.ListenService::class.java))
                val controller = androidx.media3.session.MediaController.Builder(context, token).buildAsync().get(10, java.util.concurrent.TimeUnit.SECONDS)
                var title: String? = null
                var art = false
                val shown = waitFor(20_000) {
                    instrumentation.runOnMainSync { title = controller.mediaMetadata.title?.toString(); art = controller.mediaMetadata.artworkData != null }
                    title == theme && art
                }
                instrumentation.runOnMainSync { controller.release() }
                assertTrue("The notification shows '$title' (artwork: $art), not the theme '$theme'", shown)
                // Another client of the account claims it: it stops here
                runBlocking { api.claimVirtualSpeaker("test-other-client") }
                assertTrue("It kept playing here after another client took it", waitFor(15_000) { ListenController.state.value?.sessionId != speaker.id })
            }
        } finally {
            runBlocking {
                runCatching { api.releaseVirtualSpeaker("test-other-client") }
                runCatching { api.stop(channel.id) }
                runCatching { api.deleteSession(channel.id) }
            }
        }
    }
}
