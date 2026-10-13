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
    /**
     * This device itself (with Pro): its own themes, presets, sequences and playlists, played on it. Never
     * stored; [ServerStore] lists it while it's available.
     */
    val device: Boolean = false,
) {
    /** The user's name for it, else the server's own name, else null. */
    val name: String?
        get() = customName?.takeIf { it.isNotBlank() } ?: label.takeIf { it.isNotBlank() && it != DEFAULT_NAME }

    /** What to show: its name, or its address when it has none. */
    val displayName: String get() = name ?: hosts.firstOrNull() ?: label

    companion object {
        const val DEFAULT_NAME = "Sonorium"
    }

    /**
     * The record after a connection: [host] first, and the server's own name and version as it reports them
     * now (its "Server name" setting), so the entry follows a rename on the server. A name given in the app
     * ([customName]) still wins in [displayName].
     */
    fun reachedAs(host: String, serverName: String, serverVersion: String): ServerRecord =
        preferring(host).copy(label = serverName.ifBlank { label }, version = serverVersion)

    /** The same record with [host] moved to the front of [hosts]. */
    fun preferring(host: String): ServerRecord =
        copy(hosts = listOf(host) + hosts.filter { it != host })
}
