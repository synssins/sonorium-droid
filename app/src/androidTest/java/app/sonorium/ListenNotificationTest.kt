package app.sonorium

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.engine.MediaControls
import app.sonorium.engine.PhonePlayerService
import app.sonorium.listen.ListenController
import app.sonorium.listen.ListenService
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * A server channel heard on this device (Listen): its notification and lock screen show the theme as the
 * title, the preset and channel under it and artwork, with the same buttons as a theme played on this device
 * (Next theme, Stop); Next theme changes the channel's theme on the server.
 */
@RunWith(AndroidJUnit4::class)
class ListenNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sonorium = context.applicationContext as SonoriumApp

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(300)
        }
        return check()
    }

    private fun controller(service: Class<*>): MediaController =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, service))).buildAsync().get(10, TimeUnit.SECONDS)

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        instrumentation.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    /** Each button as the system sees it: name, icon, command, slots. */
    private fun buttons(c: MediaController): List<String> = onMain {
        c.mediaButtonPreferences.map { "${it.displayName}|${it.icon}|${it.iconResId}|${it.sessionCommand?.customAction}|${it.slots}" }
    }

    @Test
    fun aChannelHeardHereShowsItsThemeArtworkAndTheDeviceButtons() {
        val record = runBlocking { sonorium.servers.servers.first().firstOrNull { !it.device } }
        assumeNotNull("No linked server", record)
        record!!
        val api = runBlocking {
            val pinned = PinnedTls.pinnedClient(sonorium.baseClient, record.fingerprint)
            val reached = sonorium.linker.reach(pinned, record.hosts, record.httpsPort)!!
            SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), sonorium.servers.token(record.id))
        }
        val themes = runBlocking { api.themes() }.filter { it.hasAudio }
        val channel = runBlocking { api.sessions().first() }
        val before = channel.themeId
        if (before == null) runBlocking { api.updateSession(channel.id, buildJsonObject { put("theme_id", JsonPrimitive(themes.first().id)) }) }
        val slot = runBlocking { api.listen(channel.id) }
        val url = api.absolute(slot.playPath).toString()
        ListenController.listen(context, record.id, channel.id, url, channel.name, "", 100)
        val listen = controller(ListenService::class.java)
        val device = controller(PhonePlayerService::class.java)
        try {
            assertTrue("Listen didn't start: ${ListenController.state.value}", waitFor(30_000) { ListenController.state.value?.playing == true })
            val themeName = { id: String? -> themes.firstOrNull { it.id == id }?.name }
            val theme = runBlocking { api.sessions().first { it.id == channel.id } }.themeId
            assertTrue(
                "The title isn't the theme (${themeName(theme)}): ${onMain { listen.mediaMetadata.title }}",
                waitFor(20_000) { onMain { listen.mediaMetadata.title?.toString() } == themeName(theme) },
            )
            val subtitle = onMain { listen.mediaMetadata.subtitle?.toString() }.orEmpty()
            assertTrue("The subtitle doesn't name the channel: $subtitle", subtitle.contains(channel.name))
            assertNotNull("No artwork", onMain { listen.mediaMetadata.artworkData })
            // The same buttons as a theme played on this device, in the same order
            assertEquals("Not the device's buttons", buttons(device), buttons(listen))
            assertEquals(listOf("Next theme", "Stop"), onMain { listen.mediaButtonPreferences.map { it.displayName.toString() } })
            // Next theme: the channel's theme changes on the server, the title follows, it keeps playing
            onMain { listen.sendCustomCommand(SessionCommand(MediaControls.COMMAND_NEXT_THEME, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) }
            assertTrue("Next theme didn't change the channel's theme", waitFor(15_000) { runBlocking { api.sessions().first { it.id == channel.id } }.themeId != theme })
            val now = runBlocking { api.sessions().first { it.id == channel.id } }.themeId
            assertTrue("The title didn't follow the theme", waitFor(20_000) { onMain { listen.mediaMetadata.title?.toString() } == themeName(now) })
            assertTrue("It stopped playing after the title changed", waitFor(10_000) { ListenController.state.value?.playing == true })
            // Stop: listening here ends
            onMain { listen.sendCustomCommand(SessionCommand(MediaControls.COMMAND_STOP, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) }
            assertTrue("Stop didn't end listening", waitFor(10_000) { ListenController.state.value == null })
        } finally {
            onMain { listen.release(); device.release() }
            ListenController.state.value?.let { ListenController.stop(context, it.sessionId) }
            runBlocking {
                runCatching { api.stopListening(channel.id) }
                runCatching { api.updateSession(channel.id, buildJsonObject { put("theme_id", before?.let { JsonPrimitive(it) } ?: JsonPrimitive(themes.first().id)) }) }
            }
            context.startService(android.content.Intent(context, PhonePlayerService::class.java).setAction(PhonePlayerService.ACTION_STOP))
        }
    }
}
