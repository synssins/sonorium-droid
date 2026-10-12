package app.sonorium.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The address the speaker fetches the phone's stream from: the phone's IPv4 address on its Wi-Fi (else
 * Ethernet) network, never a VPN or mobile data address, whatever Android's default network is.
 */
object CastAddress {
    /** One network the phone is on. */
    data class Net(val wifi: Boolean, val ethernet: Boolean, val vpn: Boolean, val addresses: List<InetAddress>)

    /** Pure: Wi-Fi first, then Ethernet; its first usable IPv4 address; null when there's none. */
    fun pick(nets: List<Net>): String? {
        val local = nets.filter { !it.vpn }
        return (local.filter { it.wifi } + local.filter { it.ethernet && !it.wifi })
            .firstNotNullOfOrNull { n -> n.addresses.firstOrNull(::usable)?.hostAddress }
    }

    private fun usable(a: InetAddress) = a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress

    /** The phone's networks now. */
    @Suppress("DEPRECATION") // allNetworks: the one call that lists every network on API 26+
    fun networks(context: Context): List<Net> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                val props = cm.getLinkProperties(n) ?: return@mapNotNull null
                Net(
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    addresses = props.linkAddresses.map { it.address },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun of(context: Context): String? = pick(networks(context))
}
