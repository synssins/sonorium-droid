package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.ui.LinkState
import app.sonorium.ui.LinkViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Linking by a typed address (Chris): the HTTP port, a bare IP with it, or an http:// address all end on the
 * server's HTTPS address, silently; an address where nothing answers gives the one error. The test server's
 * address and ports come from the Link App code the run was given (`linkUri`: h, p = HTTP, s = HTTPS).
 */
@RunWith(AndroidJUnit4::class)
class AddressLinkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SonoriumApp

    /** host, HTTP port, HTTPS port from the run's Link App code; null without one. */
    private val server: Triple<String, Int, Int>? = InstrumentationRegistry.getArguments().getString("linkUri")?.let { b64 ->
        val link = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val q = link.substringAfter('#').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        Triple(q["h"] ?: return@let null, q["p"]?.toIntOrNull() ?: return@let null, q["s"]?.toIntOrNull() ?: return@let null)
    }

    private fun connect(address: String): LinkState {
        lateinit var model: LinkViewModel
        instrumentation.runOnMainSync { model = LinkViewModel(app) }
        instrumentation.runOnMainSync { model.connectToAddress(address) }
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            val s = model.state.value
            if (s is LinkState.ConfirmWords || s is LinkState.Failed) return s
            Thread.sleep(300)
        }
        return model.state.value
    }

    @Test
    fun theHttpPortEndsOnTheHttpsAddress() {
        org.junit.Assume.assumeNotNull("No test server given", server)
        val (host, http, https) = server!!
        for (typed in listOf("$host:$http", "http://$host:$http")) {
            val s = connect(typed)
            assertTrue("$typed: $s", s is LinkState.ConfirmWords)
            assertEquals("$typed: not the server's HTTPS port", https, (s as LinkState.ConfirmWords).httpsPort)
        }
        // The HTTPS port typed: used as it is
        val direct = connect("https://$host:$https")
        assertEquals(https, (direct as LinkState.ConfirmWords).httpsPort)
    }

    @Test
    fun nothingAnsweringGivesTheOneError() {
        val s = connect("192.0.2.1:9")
        assertTrue("$s", s is LinkState.Failed)
        assertEquals(app.getString(R.string.link_address_unreachable, "192.0.2.1"), (s as LinkState.Failed).message)
    }
}
