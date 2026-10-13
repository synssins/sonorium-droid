package app.sonorium.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sonorium servers announcing themselves on this network (mDNS `_sonorium._tcp`, TXT id, name, https,
 * version, host; the server's auth/announce.py). Only a hint: a server is trusted after linking, as with
 * an address typed in (the security words). [start] while the Servers list shows, [stop] when it goes.
 */
class ServerDiscovery(context: Context) {
    /** One server found: its id, its name, an address and its HTTPS port (null: not given). */
    data class Found(val id: String, val name: String, val host: String, val httpsPort: Int?, val version: String) {
        /** What "Enter address" would take for it. */
        val address: String get() = if (httpsPort != null) "$host:$httpsPort" else host
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private var scope: CoroutineScope? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    /** Service names seen, to their server id (to drop one that goes away). */
    private val names: MutableMap<String, String> = java.util.Collections.synchronizedMap(mutableMapOf())

    fun start() {
        if (listener != null || nsd == null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        // Older Android resolves one service at a time: one after the other
        s.launch { for (info in toResolve) resolve(info)?.let { add(info.serviceName, it) } }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery didn't start ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val id = names.remove(info.serviceName) ?: return
                if (id !in names.values) _found.update { list -> list.filter { it.id != id } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "Discovery didn't start", it); listener = null }
    }

    fun stop() {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        scope?.cancel()
        scope = null
        names.clear()
        _found.value = emptyList()
    }

    private fun add(serviceName: String, found: Found) {
        names[serviceName] = found.id
        _found.update { list -> (list.filter { it.id != found.id } + found).sortedBy { it.name.lowercase() } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): Found? = withTimeoutOrNull(RESOLVE_MS) {
        val manager = nsd ?: return@withTimeoutOrNull null
        val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            runCatching {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        } ?: return@withTimeoutOrNull null
        fun txt(key: String) = resolved.attributes[key]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
        val id = txt("id") ?: return@withTimeoutOrNull null
        // The name it answers to (sonorium.local) when it has one, else its address
        val address = resolved.host?.hostAddress?.let { a -> if (':' in a) "[${a.substringBefore('%')}]" else a }
        val host = txt("host")?.removeSuffix(".") ?: address ?: return@withTimeoutOrNull null
        Found(id, txt("name") ?: "Sonorium", host, txt("https")?.toIntOrNull(), txt("version").orEmpty())
    }

    private companion object {
        const val TAG = "ServerDiscovery"
        const val SERVICE_TYPE = "_sonorium._tcp."
        const val RESOLVE_MS = 5_000L
    }
}
