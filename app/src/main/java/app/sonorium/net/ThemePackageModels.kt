package app.sonorium.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /api/themes/packages` (APP_API.md 10). */
@Serializable
data class ThemePackages(val packages: List<PackageSummary> = emptyList())

@Serializable
data class PackageSummary(
    @SerialName("theme_id") val themeId: String,
    val name: String = "",
    @SerialName("content_hash") val contentHash: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("track_count") val trackCount: Int = 0,
    /** false: the licence doesn't allow copies; null: unknown (allowed, the user's own theme). */
    val redistributable: Boolean? = null,
)

/** `GET /api/themes/{id}/package`: every file with its size and sha256, and the server's playback values. */
@Serializable
data class ThemePackage(
    @SerialName("theme_id") val themeId: String,
    val name: String = "",
    @SerialName("content_hash") val contentHash: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val files: List<PackageFile> = emptyList(),
    @SerialName("playback_values") val playbackValues: JsonObject? = null,
)

@Serializable
data class PackageFile(val path: String, val size: Long, val sha256: String)
