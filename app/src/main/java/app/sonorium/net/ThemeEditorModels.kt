package app.sonorium.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One file of `GET /api/themes/{id}/tracks` (native-api.md Phase 3, Theme Editor). [name] is the key. */
@Serializable
data class EditorTrack(
    val name: String,
    val presence: Double = 1.0,
    val muted: Boolean = false,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("is_short_file") val isShortFile: Boolean = false,
    val volume: Double = 1.0,
    @SerialName("playback_mode") val playbackMode: String? = null,
    @SerialName("seamless_loop") val seamlessLoop: Boolean = false,
    val exclusive: Boolean = false,
    @SerialName("fade_in") val fadeIn: Double = 0.0,
    @SerialName("fade_out") val fadeOut: Double = 0.0,
    @SerialName("volume_low") val volumeLow: Double = 0.2,
    @SerialName("pause_between") val pauseBetween: Boolean = false,
    @SerialName("pause_min") val pauseMin: Double = 30.0,
    @SerialName("pause_max") val pauseMax: Double = 300.0,
    val rise: Double = 0.0,
    val group: String? = null,
    val linked: LinkedSource? = null,
) {
    /** The file name without its group folder. */
    val shortName: String get() = name.substringAfterLast('/')
    val missing: Boolean get() = linked?.missing == true
}

@Serializable
data class LinkedSource(
    @SerialName("theme_id") val themeId: String = "",
    @SerialName("theme_name") val themeName: String = "",
    val track: String = "",
    val missing: Boolean = false,
)

@Serializable
data class EditorTracks(val tracks: List<EditorTrack> = emptyList())

/** A group of `GET /api/themes/{id}/groups`; [settings] holds only the keys that are set. */
@Serializable
data class TrackGroup(
    val name: String,
    val settings: JsonObject = JsonObject(emptyMap()),
    val tracks: List<String> = emptyList(),
)

@Serializable
data class TrackGroups(val groups: List<TrackGroup> = emptyList())

@Serializable
data class GroupSaved(val name: String = "", val settings: JsonObject? = null)

@Serializable
data class MovedTrack(val track: String = "")

@Serializable
data class EditorPreset(
    val id: String,
    val name: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("has_intrusion") val hasIntrusion: Boolean = false,
)

/** A sequence as `presets.sequences` lists it (the editor keeps [transitions] as raw JSON). */
@Serializable
data class EditorSequence(
    val id: String,
    val name: String = "",
    val presets: List<String> = emptyList(),
    @SerialName("at_end") val atEnd: String = "reverse",
    @SerialName("hold_min") val holdMin: Double? = null,
    @SerialName("hold_max") val holdMax: Double? = null,
    val ramp: Double? = null,
    val transitions: JsonObject = JsonObject(emptyMap()),
    @SerialName("while_steps") val whileSteps: List<String> = emptyList(),
    @SerialName("has_intrusion") val hasIntrusion: Boolean = false,
)

@Serializable
data class EditorPresets(
    val presets: List<EditorPreset> = emptyList(),
    val sequences: List<EditorSequence> = emptyList(),
)

@Serializable
data class SequenceDefaults(
    @SerialName("hold_min") val holdMin: Double = 300.0,
    @SerialName("hold_max") val holdMax: Double = 900.0,
    val ramp: Double = 30.0,
)

@Serializable
data class PresetCreated(@SerialName("preset_id") val presetId: String = "", val name: String = "")

@Serializable
data class PresetImported(val name: String = "", val warning: String? = null)

@Serializable
data class SourceTheme(val id: String = "", val name: String = "")

@Serializable
data class SourceTrack(
    val id: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val group: String? = null,
    /** Null when the length isn't known (the server couldn't read it; on the device, audio not ready yet). */
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("preview_url") val previewUrl: String = "",
)

@Serializable
data class SourceTracks(val source: SourceTheme = SourceTheme(), val tracks: List<SourceTrack> = emptyList())
