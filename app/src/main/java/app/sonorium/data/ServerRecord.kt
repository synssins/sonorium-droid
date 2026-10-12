package app.sonorium.data

import kotlinx.serialization.Serializable

/** What the app keeps per linked server (APP_API.md 1.4). The token is stored apart, encrypted. */
@Serializable
data class ServerRecord(
    val id: String,
    /** The name the server reports (`Sonorium` unless it has its own). */
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
    /** The name the user gave this server in the app; null when not named. */
    val customName: String? = null,
) {
    /** The user's name for it, else the server's own name, else null. */
    val name: String?
        get() = customName?.takeIf { it.isNotBlank() } ?: label.takeIf { it.isNotBlank() && it != DEFAULT_NAME }

    /** What to show: its name, or its address when it has none. */
    val displayName: String get() = name ?: hosts.firstOrNull() ?: label

    companion object {
        const val DEFAULT_NAME = "Sonorium"
    }

    /** The same record with [host] moved to the front of [hosts]. */
    fun preferring(host: String): ServerRecord =
        copy(hosts = listOf(host) + hosts.filter { it != host })
}
