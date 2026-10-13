package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.local.DeviceApi
import app.sonorium.local.PhoneThemeApi
import app.sonorium.net.ApiException
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This device's own entry: the app's usual API calls answered in the app ([DeviceApi]), in the server's
 * shapes, with only what a device offers.
 */
@RunWith(AndroidJUnit4::class)
class DeviceApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val api = SonoriumApi(DeviceApi(context).client(), PhoneThemeApi.BASE_URL, "device")

    @Test
    fun answersAsAServerWithoutTheServerParts() = runBlocking {
        val info = api.info()
        assertEquals(SonoriumApp.DEVICE_ID, info.id)
        assertTrue(info.personalChannel)
        assertEquals(listOf("audio_settings"), info.capabilities)
        assertTrue("ch.play" in api.me().perms)

        // Its one channel: what this device plays, also as My channel
        val sessions = api.sessions()
        assertEquals(listOf(DeviceApi.CHANNEL_ID), sessions.map { it.id })
        assertEquals(DeviceApi.CHANNEL_ID, api.myChannel().id)
        assertEquals(emptyList<Any>(), api.groups())
        // The sample theme comes with the app: there with nothing unlocked and no themes folder
        assertTrue("No sample theme", api.themes().any { it.name.isNotBlank() })

        // Playlists: Core's checks (an unknown theme is refused as on a server)
        api.playlists()
        try {
            api.createPlaylist(kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("Test"))
                put("entries", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("theme_id", kotlinx.serialization.json.JsonPrimitive("no-such-theme")) })
                })
            })
            fail("A playlist of an unknown theme should be refused")
        } catch (e: ApiException) {
            // 400 (Core's check) with Pro unlocked by an earlier test; 403 (Pro needed) without
            val pro = (context.applicationContext as SonoriumApp).entitlement.isPro.value
            assertEquals(if (pro) 400 else 403, e.status)
        }

        // What a device doesn't have answers 404, as a server without it
        try {
            api.zones()
            fail("Zones should not be on a device")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
        }
    }
}
