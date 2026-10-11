package app.sonorium.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The server's HTTPS API. [client] must be pinned to the server's certificate
 * (or be a recording client for the first, token-less contact).
 */
class SonoriumApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String? = null,
) {
    suspend fun info(): ServerInfo = get("info")

    suspend fun pair(request: PairRequest): PairResult = post("pair", json.encodeToString(request))

    suspend fun pairLogin(request: PairLoginRequest): PairResult =
        post("pair/login", json.encodeToString(request))

    suspend fun me(): Me = get("auth/me")

    private suspend inline fun <reified T> get(path: String): T =
        decode(execute(request(path).get().build()))

    private suspend inline fun <reified T> post(path: String, body: String): T =
        decode(execute(request(path).post(body.toRequestBody(JSON)).build()))

    private fun request(path: String): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegment("api").addPathSegments(path).build()
        return Request.Builder().url(url).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw ApiException.from(response.code, body, response.header("Retry-After"))
            body
        }
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    companion object {
        /** The newest `api` version this app understands (APP_API.md 11.3). */
        const val API_VERSION = 1
        private val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun baseUrl(host: String, port: Int): HttpUrl =
            HttpUrl.Builder().scheme("https").host(host).port(port).build()
    }
}

/**
 * An error answer. [detail] is the server's message for the user.
 * [unlinked] is the 401 `"pair": true` rule (APP_API.md 4.6): forget the token.
 */
class ApiException(
    val status: Int,
    val detail: String,
    val unlinked: Boolean,
    val starting: Boolean,
    val retryAfterSeconds: Int?,
) : Exception("HTTP $status: $detail") {
    companion object {
        fun from(status: Int, body: String, retryAfter: String?): ApiException {
            val obj = runCatching { SonoriumApi.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val detail = (obj?.get("detail") as? JsonPrimitive)?.content ?: body.take(200)
            fun flag(name: String) = (obj?.get(name) as? JsonPrimitive)?.booleanOrNull == true
            return ApiException(
                status = status,
                detail = detail,
                unlinked = status == 401 && flag("pair"),
                starting = status == 503 && flag("starting"),
                retryAfterSeconds = retryAfter?.toIntOrNull(),
            )
        }
    }
}
