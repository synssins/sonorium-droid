package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The top bar holds the menu, the logo and the server button only: no headphones (My Channel) button and no
 * help in any state (Chris, item 11). Checked with a server chosen (EndToEndTest linked the CI server) and
 * with this device chosen; the page's help is on its title row.
 */
@RunWith(AndroidJUnit4::class)
class TopBarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val sonorium = instrumentation.targetContext.applicationContext as SonoriumApp

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    @Test
    fun noHeadphonesOrHelpInTheTopBar() {
        val before = runBlocking { sonorium.servers.currentId.first() }
        try {
            // A server chosen (if one is linked), then this device
            val server = runBlocking { sonorium.servers.servers.first().firstOrNull { !it.device } }
            if (server != null) check("server ${server.displayName}", server.id)
            check("this device", SonoriumApp.DEVICE_ID)
        } finally {
            before?.let { runBlocking { sonorium.servers.select(it) } }
        }
    }

    private fun check(what: String, id: String) {
        runBlocking { sonorium.servers.select(id) }
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val bar = device.wait(Until.findObject(By.res("top-bar")), 20_000)
            assertNotNull("No top bar ($what)", bar)
            Thread.sleep(2_000)
            val top = device.findObject(By.res("top-bar"))
            assertTrue("A My Channel button in the top bar ($what)", top.findObject(By.desc("My Channel")) == null)
            assertTrue("A help button in the top bar ($what)", top.findObject(By.descStartsWith("Help")) == null)
            // The page's help is on its title row, its right edge under the server button's (Chris: exactly)
            val help = device.findObject(By.descStartsWith("Help: "))
            assertNotNull("No help on the page ($what)", help)
            val server = top.findObject(By.descContains("Servers:")) ?: top.findObject(By.descContains(", this device"))
            assertNotNull("No server button ($what)", server)
            val gap = help.visibleBounds.right - server.visibleBounds.right
            assertTrue("Help's right edge is $gap px off the server button's ($what)", kotlin.math.abs(gap) <= 2)
        }
    }
}
