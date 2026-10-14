package app.sonorium.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A typed address is used as typed (Chris): the server's own ports only for a bare host. */
class AddressPlanTest {
    private fun plan(s: String) = LinkViewModel.addressPlan(s)!!

    @Test
    fun aUrlIsUsedExactly() {
        // Behind a reverse proxy on 443
        assertEquals(LinkViewModel.Companion.AddressPlan("demo.sonorium.app", listOf(443), null), plan("https://demo.sonorium.app"))
        assertEquals(LinkViewModel.Companion.AddressPlan("demo.sonorium.app", listOf(443), null), plan("https://demo.sonorium.app/"))
        assertEquals(LinkViewModel.Companion.AddressPlan("192.0.2.5", listOf(18733), null), plan("https://192.0.2.5:18733/"))
        // http: only to ask where HTTPS is
        assertEquals(LinkViewModel.Companion.AddressPlan("192.0.2.5", emptyList(), 18732), plan("http://192.0.2.5:18732"))
    }

    @Test
    fun aBareHostTriesWhatWasTypedFirst() {
        assertEquals(LinkViewModel.Companion.AddressPlan("192.0.2.5", listOf(18733), 8008), plan("192.0.2.5:18733"))
        assertEquals(LinkViewModel.Companion.AddressPlan("sonorium.local", listOf(443, 8808), 8008), plan("sonorium.local"))
    }
}
