package app.sonorium.data

import kotlinx.serialization.Serializable

/** What the app keeps per linked server (APP_API.md 1.4). The token is stored apart, encrypted. */
@Serializable
data class ServerRecord(
    val id: String,
    /** The user's own label; defaults to the server's name. */
    val label: String,
    val fingerprint: String,
    /** Addresses to try, the last one that worked first. */
    val hosts: List<String>,
    val httpPort: Int,
    val httpsPort: Int,
    val hostname: String? = null,
    val deviceId: String,
    val username: String? = null,
    val install: String = "",
    val version: String = "",
) {
    /** The same record with [host] moved to the front of [hosts]. */
    fun preferring(host: String): ServerRecord =
        copy(hosts = listOf(host) + hosts.filter { it != host })
}
