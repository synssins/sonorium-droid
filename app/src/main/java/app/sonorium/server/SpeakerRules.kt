package app.sonorium.server

import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerHierarchy
import java.time.Instant

/** Settings › Speakers: the web UI's client-side logic (native-api.md Phase 2). */
object SpeakerRules {

    data class Section(val key: String, val title: String, val speakers: List<Speaker>)

    /** `settingsSpeakerGroups`: floor · area, then unassigned areas, then `No area`; empty ones dropped. */
    fun sections(h: SpeakerHierarchy, keep: (Speaker) -> Boolean): List<Section> = buildList {
        h.floors.forEach { f -> f.areas.forEach { a -> add(Section(a.areaId, "${f.name} · ${a.name}", a.speakers.filter(keep))) } }
        h.unassignedAreas.forEach { a -> add(Section(a.areaId, a.name, a.speakers.filter(keep))) }
        add(Section(NO_AREA, "No area", h.unassignedSpeakers.filter(keep)))
    }.filter { it.speakers.isNotEmpty() }

    const val NO_AREA = "__none__"

    fun all(h: SpeakerHierarchy): List<Speaker> =
        h.floors.flatMap { f -> f.areas.flatMap { it.speakers } } + h.unassignedAreas.flatMap { it.speakers } + h.unassignedSpeakers

    /** Search (name, original name, address) + source filter + Hide offline. */
    fun matches(s: Speaker, query: String, source: String?, hideOffline: Boolean): Boolean {
        val q = query.trim().lowercase()
        if (q.isNotEmpty() && listOfNotNull(s.name, s.originalName, s.address).none { it.lowercase().contains(q) }) return false
        if (source != null && source !in s.source) return false
        if (hideOffline && !s.online) return false
        return true
    }

    fun renamed(s: Speaker) = s.originalName != null && s.originalName != s.name

    /** The PUT body's `name` after an inline rename; null = nothing to send. */
    sealed interface Rename {
        data object None : Rename
        data class Reset(val original: String) : Rename
        data class To(val name: String) : Rename
    }

    fun rename(s: Speaker, typed: String): Rename = when {
        typed == s.name -> Rename.None
        typed.isEmpty() || typed == s.originalName -> Rename.Reset(s.originalName ?: s.name)
        else -> Rename.To(typed)
    }

    /** Area change: back to the speaker's own area sends null. */
    fun roomValue(s: Speaker, picked: String): String? = if (picked == (s.defaultAreaId ?: "")) null else picked

    fun offsetOptions(current: Int): List<Int> = (listOf(-20, -10, 0, 10, 20) + current).distinct().sorted()

    fun offsetText(v: Int) = if (v > 0) "+$v%" else "$v%"

    fun playViaLabel(type: String?): String = when (type) {
        "cast" -> "Google Cast (direct)"
        "sonos" -> "Sonos (direct)"
        "dlna" -> "DLNA (direct)"
        "airplay" -> "AirPlay (direct)"
        "linkplay" -> "LinkPlay (direct)"
        "heos" -> "HEOS (direct)"
        else -> "$type (direct)"
    }

    /** The small dot on ⋯: renamed, or playing via a merged way. */
    fun menuDot(s: Speaker) = renamed(s) || (s.merged.isNotEmpty() && (s.playVia ?: "ha") != "ha")

    fun isManual(s: Speaker) = s.entityId.startsWith("net:") && "manual" in s.source

    /** `timeAgo(iso)`. */
    fun timeAgo(iso: String, now: Instant = Instant.now()): String {
        val t = runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: return "just now"
        val m = (now.toEpochMilli() - t.toEpochMilli()) / 60_000
        if (m < 1) return "just now"
        if (m < 60) return "$m minute${if (m == 1L) "" else "s"} ago"
        val h = m / 60
        if (h < 24) return "$h hour${if (h == 1L) "" else "s"} ago"
        val d = h / 24
        return "$d day${if (d == 1L) "" else "s"} ago"
    }

    /** Add speaker: the client checks; the toast, or null. */
    fun checkAddress(address: String, port: String): String? {
        val a = address.trim()
        if (a.isEmpty()) return "Enter the speaker's IP address or network name"
        if (a.contains("://") || a.contains('/')) return "Enter just the address, e.g. 192.168.1.50 or speaker.local"
        if (port.isNotBlank()) {
            val p = port.trim().toIntOrNull()
            if (p == null || p !in 1..65535) return "Port must be a number from 1 to 65535"
        }
        return null
    }

    val ADD_TYPES = listOf(
        "auto" to "Detect automatically",
        "cast" to "Google Cast",
        "sonos" to "Sonos",
        "dlna" to "DLNA / UPnP",
        "airplay" to "AirPlay",
        "linkplay" to "LinkPlay / Arylic",
        "heos" to "HEOS (Denon / Marantz)",
    )
}
