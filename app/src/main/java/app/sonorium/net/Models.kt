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
    /** User accounts are loaded (username + password sign-in works); newer servers send it (to-app/21). */
    val accounts: Boolean? = null,
    @SerialName("personal_channel") val personalChannel: Boolean = false,
    /**
     * What this Sonorium offers of the optional parts (speakers, plugins, logs, …); null from a server that
     * doesn't say (everything). This device's own entry lists only what a device has.
     */
    val capabilities: List<String>? = null,
    @SerialName("boot_id") val bootId: String? = null,
) {
    val hasUsers: Boolean get() = accounts ?: ("users" in plugins)

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

/** `POST /api/sessions/{id}/listen`. */
@Serializable
data class ListenSlot(
    @SerialName("channel_id") val channelId: Int? = null,
    @SerialName("stream_path") val streamPath: String,
    /** This device's own stream (`...?local=1&listener=<tag>`); newer servers send it. */
    @SerialName("listen_path") val listenPath: String? = null,
) {
    /** The path to play: the device's tagged path when given, else the stream with ?local=1. */
    val playPath: String get() = listenPath ?: "$streamPath?local=1"
}

// ---------- Channels (sessions), APP_API.md 7.1 ----------

@Serializable
data class AdhocSelection(
    @SerialName("include_floors") val includeFloors: List<String> = emptyList(),
    @SerialName("include_areas") val includeAreas: List<String> = emptyList(),
    @SerialName("include_speakers") val includeSpeakers: List<String> = emptyList(),
    @SerialName("exclude_areas") val excludeAreas: List<String> = emptyList(),
    @SerialName("exclude_speakers") val excludeSpeakers: List<String> = emptyList(),
)

@Serializable
data class SequenceStatus(
    val id: String? = null,
    val name: String? = null,
    val running: Boolean = false,
    val step: Int? = null,
    val steps: Int? = null,
    @SerialName("current_preset_id") val currentPresetId: String? = null,
    @SerialName("current_preset_name") val currentPresetName: String? = null,
    @SerialName("next_change_at") val nextChangeAt: String? = null,
)

@Serializable
data class PlaylistStatus(
    val id: String? = null,
    val name: String? = null,
    val running: Boolean = false,
    @SerialName("entry_index") val entryIndex: Int? = null,
    val position: Int? = null,
    val entries: Int? = null,
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("theme_name") val themeName: String? = null,
    @SerialName("next_change_at") val nextChangeAt: String? = null,
)

@Serializable
data class RecentChange(val at: String = "", val text: String = "")

@Serializable
data class Session(
    val id: String,
    val name: String = "",
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("preset_id") val presetId: String? = null,
    @SerialName("speaker_group_id") val speakerGroupId: String? = null,
    @SerialName("adhoc_selection") val adhocSelection: AdhocSelection? = null,
    val volume: Int = 0,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val speakers: List<String> = emptyList(),
    @SerialName("speaker_summary") val speakerSummary: String? = null,
    @SerialName("channel_id") val channelId: Int? = null,
    @SerialName("sequence_id") val sequenceId: String? = null,
    @SerialName("preset_choice") val presetChoice: String? = null,
    @SerialName("playlist_id") val playlistId: String? = null,
    val sequence: SequenceStatus? = null,
    val playlist: PlaylistStatus? = null,
    @SerialName("speakers_muted") val speakersMuted: Boolean = false,
    val listening: Boolean = false,
    val recent: List<RecentChange> = emptyList(),
    val announcements: Boolean = true,
    val personal: Boolean = false,
    val outputs: List<CastOutput> = emptyList(),
    /** Personal channel: how many of the account's devices listen. */
    val devices: Int = 0,
) {
    /** What the preset field shows: "seq:<id>" while a sequence is set, else the preset; "" = default settings. */
    val choice: String get() = presetChoice ?: presetId ?: ""
}

// ---------- Themes, presets, playlists ----------

@Serializable
data class ThemeSummary(
    val id: String,
    val name: String = "",
    val icon: String = "",
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("has_audio") val hasAudio: Boolean = true,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    val categories: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val description: String = "",
)

@Serializable
data class Categories(val categories: List<String> = emptyList())

@Serializable
data class FavoriteResult(@SerialName("is_favorite") val isFavorite: Boolean = false)

@Serializable
data class CreatedTheme(@SerialName("theme_id") val themeId: String? = null)

@Serializable
data class ImportedTheme(
    @SerialName("theme_folder") val themeFolder: String = "",
    @SerialName("files_extracted") val filesExtracted: Int = 0,
)

@Serializable
data class PresetInfo(
    val id: String,
    val name: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("has_intrusion") val hasIntrusion: Boolean = false,
)

@Serializable
data class SequenceInfo(
    val id: String,
    val name: String = "",
    @SerialName("has_intrusion") val hasIntrusion: Boolean = false,
    @SerialName("pass_min") val passMin: Double? = null,
    @SerialName("pass_max") val passMax: Double? = null,
)

@Serializable
data class ThemePresets(
    val presets: List<PresetInfo> = emptyList(),
    val sequences: List<SequenceInfo> = emptyList(),
    @SerialName("default_has_intrusion") val defaultHasIntrusion: Boolean = false,
)

@Serializable
data class PlaylistEntry(
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("preset_id") val presetId: String? = null,
    @SerialName("sequence_id") val sequenceId: String? = null,
)

/** A playlist (native-api.md Phase 2, Playlists); [playMin]/[playMax] are seconds. */
@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val entries: List<PlaylistEntry> = emptyList(),
    val order: String = "in_order",
    val repeat: Boolean = true,
    @SerialName("play_min") val playMin: Double = 1800.0,
    @SerialName("play_max") val playMax: Double = 3600.0,
    @SerialName("fit_sequences") val fitSequences: Boolean = false,
)

@Serializable
data class Playlists(val playlists: List<Playlist> = emptyList())

// ---------- Speakers ----------

@Serializable
data class Speaker(
    @SerialName("entity_id") val entityId: String,
    val name: String = "",
    val online: Boolean = true,
    val type: String? = null,
    val source: List<String> = emptyList(),
    @SerialName("area_id") val areaId: String? = null,
    val address: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("default_area_id") val defaultAreaId: String? = null,
    @SerialName("volume_offset") val volumeOffset: Int = 0,
    val merged: List<MergedSpeaker> = emptyList(),
    @SerialName("play_via") val playVia: String? = null,
)

/** Another way to reach the same device (`merged[]`). */
@Serializable
data class MergedSpeaker(val id: String, val type: String? = null, val source: String? = null)

/** `GET /network-speakers` (standalone only; 404 on the add-on). */
@Serializable
data class NetworkSpeakers(@SerialName("last_scan") val lastScan: String? = null, val found: Int = 0)

@Serializable
data class RefreshResult(@SerialName("total_speakers") val totalSpeakers: Int = 0)

@Serializable
data class TestResult(val seconds: Double? = null)

@Serializable
data class ConnectionInfo(@SerialName("ha_url") val haUrl: String? = null)

/** `POST /speakers/manual/check` and `POST /speakers/manual`. */
@Serializable
data class ManualResult(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val found: Boolean = true,
    val message: String? = null,
    @SerialName("merged_into") val mergedInto: String? = null,
)

@Serializable
data class Area(
    @SerialName("area_id") val areaId: String,
    val name: String = "",
    @SerialName("floor_id") val floorId: String? = null,
    val speakers: List<Speaker> = emptyList(),
)

@Serializable
data class Floor(
    @SerialName("floor_id") val floorId: String,
    val name: String = "",
    val level: Int? = null,
    val areas: List<Area> = emptyList(),
)

@Serializable
data class SpeakerHierarchy(
    val floors: List<Floor> = emptyList(),
    @SerialName("unassigned_areas") val unassignedAreas: List<Area> = emptyList(),
    @SerialName("unassigned_speakers") val unassignedSpeakers: List<Speaker> = emptyList(),
)

@Serializable
data class EnabledSpeakers(@SerialName("enabled_speakers") val enabledSpeakers: List<String> = emptyList())

@Serializable
data class SpeakerGroup(
    val id: String,
    val name: String = "",
    val icon: String? = null,
    @SerialName("speaker_count") val speakerCount: Int = 0,
    val summary: String? = null,
    @SerialName("include_floors") val includeFloors: List<String> = emptyList(),
    @SerialName("include_areas") val includeAreas: List<String> = emptyList(),
    @SerialName("include_speakers") val includeSpeakers: List<String> = emptyList(),
    @SerialName("exclude_areas") val excludeAreas: List<String> = emptyList(),
    @SerialName("exclude_speakers") val excludeSpeakers: List<String> = emptyList(),
)

// ---------- Floors & Areas (GET /spaces) ----------

@Serializable
data class SpaceArea(
    val id: String,
    val name: String = "",
    val source: List<String> = emptyList(),
    @SerialName("local_id") val localId: String? = null,
    val speakers: Int = 0,
)

@Serializable
data class SpaceFloor(
    val id: String,
    val name: String = "",
    val source: List<String> = emptyList(),
    @SerialName("local_id") val localId: String? = null,
    val speakers: Int = 0,
    val areas: List<SpaceArea> = emptyList(),
)

@Serializable
data class Spaces(
    val editable: Boolean = false,
    val floors: List<SpaceFloor> = emptyList(),
    @SerialName("unassigned_areas") val unassignedAreas: List<SpaceArea> = emptyList(),
)

/** A Cast device playing the channel, reported by a phone (G2). */
@Serializable
data class CastOutput(
    @SerialName("output_id") val outputId: String,
    val kind: String = "",
    val name: String = "",
    @SerialName("by_device_name") val byDeviceName: String? = null,
)

@Serializable
data class ServerStatus(val version: String = "")

@Serializable
data class InstallInfo(val label: String = "", val version: String = "")

// ---------- Zones (Users plugin; native-api.md 1.6) ----------

@Serializable
data class ZoneSchedule(val start: String = "", val end: String = "")

@Serializable
data class Zone(
    val owner: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("kid_mode") val kidMode: Boolean = false,
    val schedule: ZoneSchedule? = null,
)

@Serializable
data class ChannelLink(
    val id: String,
    val name: String = "",
    val channels: List<String> = emptyList(),
    val left: List<String> = emptyList(),
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("preset_id") val presetId: String? = null,
    @SerialName("playlist_id") val playlistId: String? = null,
)

@Serializable
data class ZoneUser(
    val id: String,
    val username: String = "",
    val role: String = "",
    @SerialName("can_leave_link") val canLeaveLink: Boolean = false,
)

@Serializable
data class ZonesView(
    val on: Boolean = false,
    val admin: Boolean = false,
    val me: String? = null,
    @SerialName("can_leave_link") val canLeaveLink: Boolean = false,
    val zones: Map<String, Zone> = emptyMap(),
    val links: List<ChannelLink> = emptyList(),
    val users: List<ZoneUser> = emptyList(),
) {
    fun linkOf(channelId: String) = links.firstOrNull { channelId in it.channels || channelId in it.left }

    /** Leave/Rejoin: an admin, or the channel's owner when they may leave links. */
    fun mayLeave(channelId: String) = admin || (zones[channelId]?.owner != null && zones[channelId]?.owner == me && canLeaveLink)
}

// ---------- Status (GET /now-playing) ----------

@Serializable
data class NowPlaying(val at: Double = 0.0, val channels: List<NowChannel> = emptyList())

@Serializable
data class NowSequence(val name: String = "", val step: Int? = null, val steps: Int? = null)

@Serializable
data class NowChannel(
    val id: String,
    val name: String = "",
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("theme_name") val themeName: String? = null,
    @SerialName("preset_name") val presetName: String? = null,
    val sequence: NowSequence? = null,
    @SerialName("speakers_playing") val speakersPlaying: Boolean = false,
    val speakers: String? = null,
    @SerialName("speakers_muted") val speakersMuted: Boolean = false,
    val listening: Boolean = false,
    @SerialName("buffer_seconds") val bufferSeconds: Double = 0.0,
    val sections: List<NowSection> = emptyList(),
)

@Serializable
data class NowSection(val title: String = "", val rows: List<NowRow> = emptyList())

@Serializable
data class NowRow(
    val type: String = "track",
    val key: String = "",
    val name: String = "",
    val state: String = "",
    val mode: String = "",
    val now: String = "",
    val seconds: Double? = null,
    val level: Double? = null,
    val band: List<Double>? = null,
    val tracks: List<NowRow> = emptyList(),
)
