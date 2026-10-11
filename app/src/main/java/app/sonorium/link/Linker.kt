package app.sonorium.link

import app.sonorium.data.ServerRecord
import app.sonorium.net.ApiException
import app.sonorium.net.PairLoginRequest
import app.sonorium.net.PairRequest
import app.sonorium.net.PinnedTls
import app.sonorium.net.ServerInfo
import app.sonorium.net.SonoriumApi
import java.io.IOException
import okhttp3.OkHttpClient

/** A server reached over pinned HTTPS at [host]. */
data class Reached(val host: String, val info: ServerInfo)

sealed class LinkOutcome {
    data class Linked(val record: ServerRecord, val token: String) : LinkOutcome()
    /** The server has HTTPS off; the app never links over plain HTTP. */
    data object HttpsOff : LinkOutcome()
    data object Unreachable : LinkOutcome()
    /** A server answered at the address but it isn't the one in the code. */
    data object WrongServer : LinkOutcome()
    data class TooOld(val message: String) : LinkOutcome()
    data class TooNew(val serverApi: Int) : LinkOutcome()
    data class Refused(val error: ApiException) : LinkOutcome()
}

/** Linking (APP_API.md 4): by Link App code, or by typed address and password. */
class Linker(private val base: OkHttpClient, private val device: DeviceInfo) {
    data class DeviceInfo(val name: String, val model: String)

    suspend fun link(code: LinkUri): LinkOutcome {
        val httpsPort = code.httpsPort ?: return LinkOutcome.HttpsOff
        val client = PinnedTls.pinnedClient(base, code.fingerprint)
        val reached = reach(client, code.hosts, httpsPort) ?: return LinkOutcome.Unreachable
        if (reached.info.id != code.serverId) return LinkOutcome.WrongServer
        checkApi(reached.info)?.let { return it }

        val api = SonoriumApi(client, SonoriumApi.baseUrl(reached.host, httpsPort))
        val result = try {
            api.pair(PairRequest(code = code.code, deviceName = device.name, model = device.model))
        } catch (e: ApiException) {
            return LinkOutcome.Refused(e)
        } catch (_: IOException) {
            return LinkOutcome.Unreachable
        }
        if (result.serverId != code.serverId) return LinkOutcome.WrongServer
        val hosts = (code.hosts + listOfNotNull(reached.info.host, reached.info.hostname)).distinct()
        return LinkOutcome.Linked(
            record(reached, code.fingerprint, hosts, httpsPort, result.deviceId, result.user?.username),
            result.token,
        )
    }

    /**
     * Signs in with a password on a server first reached by typed address. The
     * caller has shown the security words of [fingerprint] and the user agreed.
     */
    suspend fun linkWithPassword(
        reached: Reached,
        httpsPort: Int,
        fingerprint: String,
        username: String,
        password: String,
    ): LinkOutcome {
        checkApi(reached.info)?.let { return it }
        val client = PinnedTls.pinnedClient(base, fingerprint)
        val api = SonoriumApi(client, SonoriumApi.baseUrl(reached.host, httpsPort))
        val result = try {
            api.pairLogin(PairLoginRequest(username, password, device.name, model = device.model))
        } catch (e: ApiException) {
            return LinkOutcome.Refused(e)
        } catch (_: IOException) {
            return LinkOutcome.Unreachable
        }
        if (result.serverId != reached.info.id) return LinkOutcome.WrongServer
        val hosts = listOfNotNull(reached.host, reached.info.host, reached.info.hostname).distinct()
        return LinkOutcome.Linked(
            record(reached, fingerprint, hosts, httpsPort, result.deviceId, result.user?.username),
            result.token,
        )
    }

    /** Tries each host in order; the first that answers `/api/info` over [client] wins. */
    suspend fun reach(client: OkHttpClient, hosts: List<String>, httpsPort: Int): Reached? {
        for (host in hosts) {
            val info = runCatching { SonoriumApi(client, SonoriumApi.baseUrl(host, httpsPort)).info() }.getOrNull()
            if (info != null) return Reached(host, info)
        }
        return null
    }

    private fun checkApi(info: ServerInfo): LinkOutcome? = when {
        info.api > SonoriumApi.API_VERSION -> LinkOutcome.TooNew(info.api)
        info.api < 1 -> LinkOutcome.TooOld("This Sonorium is too old for the app. Update it to 1.6.2 or later.")
        else -> null
    }

    private fun record(
        reached: Reached,
        fingerprint: String,
        hosts: List<String>,
        httpsPort: Int,
        deviceId: String,
        username: String?,
    ) = ServerRecord(
        id = reached.info.id,
        label = reached.info.name,
        fingerprint = fingerprint,
        hosts = hosts,
        httpPort = reached.info.ports.http,
        httpsPort = httpsPort,
        hostname = reached.info.hostname,
        deviceId = deviceId,
        username = username,
        install = reached.info.install,
        version = reached.info.version,
    ).preferring(reached.host)
}
