package app.sonorium.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/info` (APP_API.md 3). */
@Serializable
data class ServerInfo(
    val id: String,
    val name: String = "Sonorium",
    val version: String = "",
    val api: Int,
    val install: String = "",
    val ports: Ports,
    val host: String? = null,
    val hostname: String? = null,
    @SerialName("login_required") val loginRequired: Boolean = false,
    val fingerprint: String? = null,
    val plugins: List<String> = emptyList(),
) {
    val hasUsers: Boolean get() = "users" in plugins

    @Serializable
    data class Ports(val http: Int, val https: Int? = null)
}

@Serializable
data class PairRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android",
    val model: String,
)

@Serializable
data class PairLoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android",
    val model: String,
)

/** The answer of `POST /api/pair` and `POST /api/pair/login`. The token is shown once. */
@Serializable
data class PairResult(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val user: User? = null,
    @SerialName("server_id") val serverId: String,
    val words: List<String>? = null,
)

@Serializable
data class User(
    val id: String,
    val username: String,
    val role: String,
    @SerialName("role_label") val roleLabel: String = role,
)

/** `GET /api/auth/me` (APP_API.md 4.5). */
@Serializable
data class Me(
    val kind: String,
    val user: User? = null,
    val perms: List<String> = emptyList(),
    val words: List<String>? = null,
    val plugins: List<String> = emptyList(),
    val starting: Boolean = false,
)
