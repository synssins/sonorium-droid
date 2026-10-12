package app.sonorium.cast

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAddressTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private val mobile = CastAddress.Net(wifi = false, ethernet = false, vpn = false, addresses = listOf(ip("10.120.4.7")))
    private val vpn = CastAddress.Net(wifi = false, ethernet = false, vpn = true, addresses = listOf(ip("10.8.0.2")))
    private val wifi = CastAddress.Net(wifi = true, ethernet = false, vpn = false, addresses = listOf(ip("fe80::1"), ip("192.168.1.20")))
    private val ethernet = CastAddress.Net(wifi = false, ethernet = true, vpn = false, addresses = listOf(ip("192.168.2.5")))

    @Test fun wifiWinsWhateverTheDefaultNetworkIs() {
        assertEquals("192.168.1.20", CastAddress.pick(listOf(vpn, mobile, wifi)))
        assertEquals("192.168.1.20", CastAddress.pick(listOf(ethernet, wifi)))
    }

    @Test fun ethernetWhenThereIsNoWifi() {
        assertEquals("192.168.2.5", CastAddress.pick(listOf(mobile, ethernet)))
    }

    @Test fun noLocalNetworkNoAddress() {
        assertNull(CastAddress.pick(listOf(mobile, vpn)))
        assertNull(CastAddress.pick(emptyList()))
        // A VPN over Wi-Fi isn't the speaker's network
        assertNull(CastAddress.pick(listOf(CastAddress.Net(wifi = true, ethernet = false, vpn = true, addresses = listOf(ip("10.8.0.2"))))))
    }

    @Test fun onlyUsableIpv4() {
        val odd = CastAddress.Net(wifi = true, ethernet = false, vpn = false, addresses = listOf(ip("127.0.0.1"), ip("169.254.3.3"), ip("::1"), ip("0.0.0.0")))
        assertNull(CastAddress.pick(listOf(odd)))
    }
}
