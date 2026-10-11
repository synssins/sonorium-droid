package app.sonorium.link

/**
 * The Link App code a Sonorium server shows (APP_API.md 1.1), in either form:
 *
 *  - `https://sonorium.app/link#v=1&id=...&n=...&h=...&p=...&s=...&fp=...&c=...[&u=...]`
 *  - `sonorium://pair?v=1&id=...` (older servers)
 */
data class LinkUri(
    val serverId: String,
    val name: String,
    val hosts: List<String>,
    val httpPort: Int,
    /** Null when the server has HTTPS off; such a server can't be linked. */
    val httpsPort: Int?,
    /** Lowercase hex SHA-256 of the server certificate's DER bytes. */
    val fingerprint: String,
    val code: String,
    /** The user the phone will act as, when someone was signed in. */
    val username: String?,
) {
    sealed class Result {
        data class Ok(val link: LinkUri) : Result()
        /** A link of a newer format than this app understands. */
        data class UnsupportedVersion(val version: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    companion object {
        const val SUPPORTED_VERSION = "1"
        private const val WEB_PREFIX = "https://sonorium.app/link"
        private const val LEGACY_PREFIX = "sonorium://pair?"
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

        fun parse(raw: String): Result {
            val text = raw.trim()
            val query = when {
                text.startsWith("$WEB_PREFIX#") || text.startsWith("$WEB_PREFIX/#") ->
                    text.substringAfter('#')
                text.startsWith(LEGACY_PREFIX) -> text.removePrefix(LEGACY_PREFIX)
                else -> return Result.Invalid("Not a Sonorium link")
            }
            if (query.isEmpty()) return Result.Invalid("The link has no details")

            val params = parseQuery(query)
            val version = params["v"] ?: return Result.Invalid("Missing v")
            if (version != SUPPORTED_VERSION) return Result.UnsupportedVersion(version)

            fun required(key: String) = params[key]?.takeIf { it.isNotEmpty() }
            val id = required("id") ?: return Result.Invalid("Missing id")
            val hosts = required("h")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: return Result.Invalid("Missing h")
            if (hosts.isEmpty()) return Result.Invalid("Missing h")
            val httpPort = required("p")?.toPortOrNull() ?: return Result.Invalid("Missing or bad p")
            val httpsPort = params["s"]?.takeIf { it.isNotEmpty() }?.let {
                it.toPortOrNull() ?: return Result.Invalid("Bad s")
            }
            val fingerprint = required("fp")?.lowercase()
                ?.takeIf { FINGERPRINT.matches(it) } ?: return Result.Invalid("Missing or bad fp")
            val code = required("c") ?: return Result.Invalid("Missing c")

            return Result.Ok(
                LinkUri(
                    serverId = id,
                    name = required("n") ?: "Sonorium",
                    hosts = hosts,
                    httpPort = httpPort,
                    httpsPort = httpsPort,
                    fingerprint = fingerprint,
                    code = code,
                    username = required("u"),
                ),
            )
        }

        /** Split on `&` then the first `=`, decoding each part as Python's `parse_qsl` does. */
        internal fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = if ('=' in pair) pair.substringAfter('=') else ""
                percentDecode(key) to percentDecode(value)
            }

        internal fun percentDecode(s: String): String {
            if ('%' !in s && '+' !in s) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 <= s.lastIndex) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                out.write((if (c == '+') " " else c.toString()).toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun String.toPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
