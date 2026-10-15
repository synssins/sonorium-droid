package app.sonorium

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.sonorium.ui.coach.CoachState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guides are off for now (Chris, 2026-10-14): on a new install no first-run or first-visit guide shows,
 * and a page's help has no "Show the guide".
 */
@RunWith(AndroidJUnit4::class)
class GuidesOffTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val sonorium = instrumentation.targetContext.applicationContext as SonoriumApp

    @Test
    fun noGuideAnywhere() {
        assertFalse("The guides are on by default", CoachState.ENABLED)
        // As on a new install: no guide seen yet
        instrumentation.targetContext.getSharedPreferences("coach", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val before = runBlocking { sonorium.servers.currentId.first() }
        try {
            runBlocking { sonorium.servers.select(SonoriumApp.DEVICE_ID) }
            val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                assertNotNull("No main screen", device.wait(Until.findObject(By.res("top-bar")), 20_000))
                assertFalse("A guide showed", device.wait(Until.hasObject(By.text("Skip")), 5_000) == true)
                device.findObject(By.descStartsWith("Help: "))!!.click()
                Thread.sleep(1_500)
                val show = instrumentation.targetContext.getString(R.string.coach_show)
                assertFalse("The help offers the guide", device.hasObject(By.text(show)))
                device.pressBack()
            }
        } finally {
            before?.let { runBlocking { sonorium.servers.select(it) } }
        }
    }
}
