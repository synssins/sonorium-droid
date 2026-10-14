package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.sonorium.local.LocalProEntitlement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The menu's last line: "Sonorium <version>" (the build's own version name), and PRO only with Pro (Chris). */
@RunWith(AndroidJUnit4::class)
class MenuBuildTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val sonorium = instrumentation.targetContext.applicationContext as SonoriumApp

    init {
        app.sonorium.ui.coach.CoachState.ENABLED = false
    }

    /** The menu opened and scrolled to its end; the build line. */
    private fun buildLine(): UiObject2 {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent)
        assertNotNull("No main screen", device.wait(Until.findObject(By.res("top-bar")), 20_000))
        Thread.sleep(1_500)
        device.findObject(By.desc("Menu"))?.let { it.click(); Thread.sleep(1_200) }
        var line = device.findObject(By.res("app-build"))
        repeat(6) {
            if (line != null) return@repeat
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.9f)
            Thread.sleep(400)
            line = device.findObject(By.res("app-build"))
        }
        return line ?: throw AssertionError("No build line at the bottom of the menu")
    }

    @Test
    fun theVersionAndPROOnlyWithPro() {
        val entitlement = sonorium.entitlement as LocalProEntitlement
        val wasPro = entitlement.isPro.value
        val before = runBlocking { sonorium.servers.currentId.first() }
        try {
            runBlocking { sonorium.servers.select(SonoriumApp.DEVICE_ID) }
            entitlement.revoke()
            var line = buildLine()
            assertNotNull(line.findObject(By.text("Sonorium ${BuildConfig.VERSION_NAME}")))
            assertNull("PRO without Pro", line.findObject(By.res("pro-badge")))
            runBlocking { entitlement.purchase() }
            line = buildLine()
            assertEquals("PRO", line.findObject(By.res("pro-badge"))?.findObject(By.textContains("PRO"))?.text)
        } finally {
            if (wasPro) runBlocking { entitlement.purchase() } else entitlement.revoke()
            before?.let { runBlocking { sonorium.servers.select(it) } }
        }
    }
}
