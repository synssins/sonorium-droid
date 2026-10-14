package app.sonorium.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A typed address: a DNS name or an IP, with or without :port, a scheme changing nothing (Chris). HTTPS on the
 * port typed, else 443 then 8808; then HTTP on the port typed, else 80 then 8008 (its /api/info names HTTPS).
 */
class AddressPlanTest {
    private fun plan(s: String) = LinkViewModel.addressPlan(s)!!
    private fun p(host: String, https: List<Int>, http: List<Int>) = LinkViewModel.Companion.AddressPlan(host, https, http)
    private val defaults = p("x", listOf(443, 8808), listOf(80, 8008))

    @Test fun aBareName() {
        assertEquals(defaults.copy(host = "sonorium.local"), plan("sonorium.local"))
        assertEquals(defaults.copy(host = "demo.sonorium.app"), plan("  demo.sonorium.app  "))
    }

    @Test fun aNameWithAPort() {
        assertEquals(p("sonorium.local", listOf(18733), listOf(18733)), plan("sonorium.local:18733"))
    }

    @Test fun anIp() {
        assertEquals(defaults.copy(host = "192.0.2.5"), plan("192.0.2.5"))
        assertEquals(p("192.0.2.5", listOf(8808), listOf(8808)), plan("192.0.2.5:8808"))
    }

    @Test fun anIpv6InBrackets() {
        assertEquals(defaults.copy(host = "2001:db8::5"), plan("[2001:db8::5]"))
        assertEquals(p("2001:db8::5", listOf(8808), listOf(8808)), plan("[2001:db8::5]:8808"))
    }

    @Test fun aSchemeChangesNothing() {
        assertEquals(defaults.copy(host = "demo.sonorium.app"), plan("https://demo.sonorium.app/"))
        assertEquals(defaults.copy(host = "192.0.2.5"), plan("http://192.0.2.5"))
        assertEquals(p("192.0.2.5", listOf(19080), listOf(19080)), plan("http://192.0.2.5:19080"))
        assertEquals(p("192.0.2.5", listOf(18733), listOf(18733)), plan("https://192.0.2.5:18733/"))
    }

    @Test fun nothingUsable() {
        assertNull(LinkViewModel.addressPlan(""))
        assertNull(LinkViewModel.addressPlan("   "))
    }
}
