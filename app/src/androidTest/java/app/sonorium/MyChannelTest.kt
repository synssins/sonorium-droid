package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.sonorium.engine.OpenApp
import app.sonorium.engine.PhonePlayer
import app.sonorium.listen.ListenController
import app.sonorium.local.LocalProEntitlement
import app.sonorium.local.SampleTheme
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * My Channel on this phone (Chris): one playback at a time; switching between a server and this device stops
 * the one left when "Stop My Channel when switching" is on (not when off); the Pro line on this device's My
 * Channel only for free users with the device chosen. Runs after EndToEndTest linked the CI server.
 */
@RunWith(AndroidJUnit4::class)
class MyChannelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val sonorium = context.applicationContext as SonoriumApp
    private val prefs = context.getSharedPreferences("sonorium", android.content.Context.MODE_PRIVATE)

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    /** The CI server chosen again afterwards (the screens tests after this one start on it). */
    @org.junit.After
    fun backToTheServer() {
        server()?.let { runBlocking { sonorium.servers.select(it.id) } }
    }

    private fun server() = runBlocking { sonorium.servers.servers.first().firstOrNull { !it.device } }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(200)
        }
        return check()
    }

    /** The main screen open on [id]: its view model follows the switcher from here. */
    private fun <T> onMain(id: String, block: () -> T): T {
        runBlocking { sonorium.servers.select(id) }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ActivityScenario.launch<MainActivity>(intent).use {
            device.wait(Until.findObject(By.res("top-bar")), 20_000)
            Thread.sleep(1_500)
            block()
        }
    }

    private fun playOnDevice() {
        runBlocking { PhonePlayer.sync(context, SampleTheme.with(context, emptyList())) }
        instrumentation.runOnMainSync { PhonePlayer.play(context) }
        assertTrue("This device's player didn't start", waitFor(30_000) { PhonePlayer.state.value.playing })
    }

    private fun stopAll() {
        instrumentation.runOnMainSync { PhonePlayer.stop(context) }
        ListenController.stopAny(context)
        waitFor(5_000) { !PhonePlayer.state.value.playing && ListenController.state.value == null }
    }

    @Test
    fun switchingStopsTheOneLeftWhenOn() {
        val server = server()
        assumeNotNull("No linked server", server)
        prefs.edit().putBoolean("stopMyChannelOnSwitch", true).apply()
        try {
            onMain(SonoriumApp.DEVICE_ID) {
                playOnDevice()
                runBlocking { sonorium.servers.select(server!!.id) }
                assertTrue("Device → server: this device's My Channel kept playing", waitFor(8_000) { !PhonePlayer.state.value.playing })
            }
        } finally {
            stopAll()
        }
    }

    @Test
    fun switchingLeavesItPlayingWhenOff() {
        val server = server()
        assumeNotNull("No linked server", server)
        prefs.edit().putBoolean("stopMyChannelOnSwitch", false).apply()
        try {
            onMain(SonoriumApp.DEVICE_ID) {
                playOnDevice()
                runBlocking { sonorium.servers.select(server!!.id) }
                Thread.sleep(5_000)
                assertTrue("Setting off: this device's My Channel stopped on the switch", PhonePlayer.state.value.playing)
            }
        } finally {
            prefs.edit().putBoolean("stopMyChannelOnSwitch", true).apply()
            stopAll()
        }
    }

    /** Listening to the server's My Channel, then the switch to this device stops it (setting on). */
    @Test
    fun serverToDeviceStopsTheStream() {
        val server = server()
        assumeNotNull("No linked server", server)
        prefs.edit().putBoolean("stopMyChannelOnSwitch", true).apply()
        try {
            onMain(server!!.id) {
                assertTrue("Couldn't listen to the server's My Channel", listenToServer(server.id))
                runBlocking { sonorium.servers.select(SonoriumApp.DEVICE_ID) }
                assertTrue("Server → device: the server's stream kept playing", waitFor(8_000) { ListenController.state.value == null })
            }
        } finally {
            stopAll()
        }
    }

    /** Starting one My Channel playback stops the other, both ways (no two players). */
    @Test
    fun oneAtATime() {
        val server = server()
        assumeNotNull("No linked server", server)
        try {
            onMain(server!!.id) {
                playOnDevice()
                assertTrue("Couldn't listen to the server's My Channel", listenToServer(server.id))
                assertTrue("Listening didn't stop this device's player", waitFor(8_000) { !PhonePlayer.state.value.playing })
                playOnDevice()
                assertTrue("This device's player didn't stop the server's stream", waitFor(8_000) { ListenController.state.value == null })
            }
        } finally {
            stopAll()
        }
    }

    /** The server's My Channel streamed here, as the page's Play here does. */
    private fun listenToServer(serverId: String): Boolean = runBlocking {
        val record = sonorium.servers.find(serverId) ?: return@runBlocking false
        val token = sonorium.servers.token(serverId) ?: return@runBlocking false
        val pinned = PinnedTls.pinnedClient(sonorium.baseClient, record.fingerprint)
        val reached = sonorium.linker.reach(pinned, record.hosts, record.httpsPort) ?: return@runBlocking false
        if (reached.info.personalChannel != true) return@runBlocking false
        val api = SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), token)
        val channel = api.myChannel()
        if (channel.themeId == null) api.updateMyChannel(kotlinx.serialization.json.buildJsonObject {
            put("theme_id", kotlinx.serialization.json.JsonPrimitive(api.themes().first().id))
        })
        val slot = api.listenMyChannel()
        val port = reached.info.ports?.http ?: record.httpPort
        ListenController.listen(context, serverId, channel.id, "http://${reached.host}:$port${slot.playPath}", "My Channel", "", 100, personal = true)
        waitFor(15_000) { ListenController.state.value?.playing == true }
    }

    @Test
    fun proLineOnlyForFreeOnThisDevice() {
        val entitlement = sonorium.entitlement as LocalProEntitlement
        val wasPro = entitlement.isPro.value
        try {
            entitlement.revoke()
            assertNotNull("Free, this device: no Pro line on My Channel", myChannelNag(SonoriumApp.DEVICE_ID))
            server()?.let { assertNull("Free, a server chosen: a Pro line on My Channel", myChannelNag(it.id)) }
            runBlocking { entitlement.purchase() }
            assertNull("Pro, this device: a Pro line on My Channel", myChannelNag(SonoriumApp.DEVICE_ID))
        } finally {
            if (wasPro) runBlocking { entitlement.purchase() } else entitlement.revoke()
        }
    }

    /** My Channel opened on [id]: the Pro line, or null. */
    private fun myChannelNag(id: String): androidx.test.uiautomator.UiObject2? {
        runBlocking { sonorium.servers.select(id) }
        context.startActivity(OpenApp.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.res("top-bar")), 20_000)
        Thread.sleep(2_500)
        // At the bottom of the page: scroll to it if needed
        runCatching { androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToEnd(5) }
        val nag = device.findObject(By.res("device-nag"))
        device.pressHome()
        Thread.sleep(500)
        return nag
    }
}
