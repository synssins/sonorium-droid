package app.sonorium.server

import app.sonorium.net.AdhocSelection
import app.sonorium.net.Area
import app.sonorium.net.SpeakerHierarchy
import app.sonorium.net.ThemeSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The web UI's client-side rules, shared/native-api.md 1.5. */
object ChannelRules {
    private val MDI = mapOf(
        "music" to "🎵", "music-note" to "🎵", "music-circle" to "🎵", "weather-rainy" to "🌧️",
        "pine-tree" to "🌲", "tree" to "🌲", "waves" to "🌊", "fire" to "🔥", "weather-lightning" to "⛈️",
        "weather-windy" to "💨", "bird" to "🐦", "moon-waning-crescent" to "🌙", "weather-night" to "🌙",
        "coffee" to "☕", "city" to "🏙️", "snowflake" to "❄️", "beach" to "🏖️", "castle" to "🏰",
        "sword" to "⚔️", "book" to "📚", "flower" to "🌸", "white-balance-sunny" to "☀️", "leaf" to "🍂",
        "pumpkin" to "🎃", "ghost" to "👻", "train" to "🚂", "saxophone" to "🎷", "piano" to "🎹",
        "meditation" to "🧘", "spa" to "💆", "water" to "💧", "image-filter-hdr" to "🏔️", "cactus" to "🏜️",
        "palm-tree" to "🌴", "glass-mug-variant" to "🍺", "beer" to "🍺", "dragon" to "🐉", "pine-tree-box" to "🎄",
    )

    private val KEYWORDS = listOf(
        listOf("rain") to "🌧️", listOf("forest") to "🌲", listOf("ocean") to "🌊", listOf("fire") to "🔥",
        listOf("thunder") to "⛈️", listOf("wind") to "💨", listOf("birds") to "🐦", listOf("night") to "🌙",
        listOf("cafe") to "☕", listOf("city") to "🏙️", listOf("christmas") to "🎄", listOf("fantasy") to "🐉",
        listOf("tavern", "inn", "pub") to "🍺", listOf("winter", "snow") to "❄️", listOf("beach") to "🏖️",
        listOf("space") to "🚀", listOf("medieval", "castle") to "🏰", listOf("dungeon", "battle") to "⚔️",
        listOf("library", "study") to "📚", listOf("garden", "spring") to "🌸", listOf("summer") to "☀️",
        listOf("autumn", "fall") to "🍂", listOf("halloween") to "🎃", listOf("spooky", "horror") to "👻",
        listOf("train") to "🚂", listOf("jazz") to "🎷", listOf("piano") to "🎹", listOf("meditation", "zen") to "🧘",
        listOf("spa") to "💆", listOf("waterfall") to "💧", listOf("river", "stream") to "🏞️",
        listOf("mountain") to "🏔️", listOf("desert") to "🏜️", listOf("jungle", "tropical") to "🌴",
    )

    /** `resolveThemeIcon`: the theme's emoji, an mdi: name mapped, else a keyword of the theme id, else 🎵. */
    fun themeIcon(themeId: String?, theme: ThemeSummary?): String {
        val icon = theme?.icon.orEmpty()
        if (icon.startsWith("mdi:")) MDI[icon.removePrefix("mdi:")]?.let { return it }
        else if (icon.isNotBlank()) return icon
        val id = themeId.orEmpty().lowercase(Locale.ROOT)
        // First keyword of the list (in list order) found in the id
        KEYWORDS.firstOrNull { (words, _) -> words.any { it in id } }?.let { return it.second }
        return "🎵"
    }

    /** Seconds as m:ss, or h:mm:ss from an hour; never negative. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    fun parseInstant(iso: String?): Instant? = iso?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(it).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    /** A Recent changes time: HH:MM today, "Yesterday HH:MM", else a short date (12 Oct). */
    fun recentTime(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = parseInstant(iso)?.atZone(zone) ?: return ""
        val hm = DateTimeFormatter.ofPattern("HH:mm").format(at)
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.atZone(zone).toLocalDate())
        return when {
            days <= 0 -> hm
            days == 1L -> "Yesterday $hm"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(at)
        }
    }

    // ---------- Speaker picker (1.5.6) ----------

    class Picker(private val hierarchy: SpeakerHierarchy, private val enabled: Set<String>) {
        fun visibleSpeakers(area: Area) = area.speakers.filter { it.entityId in enabled && it.online }
        fun areaIds(area: Area) = visibleSpeakers(area).map { it.entityId }
        fun visibleAreas(areas: List<Area>) = areas.filter { visibleSpeakers(it).isNotEmpty() }
        fun floorIds(floorAreas: List<Area>) = visibleAreas(floorAreas).flatMap { areaIds(it) }
        private val allAreas get() = hierarchy.floors.flatMap { it.areas } + hierarchy.unassignedAreas

        /** The set of speaker ids a stored selection means. */
        fun effective(selection: AdhocSelection?): Set<String> {
            val s = selection ?: return emptySet()
            val out = linkedSetOf<String>()
            hierarchy.floors.filter { it.floorId in s.includeFloors }.forEach { out += floorIds(it.areas) }
            allAreas.filter { it.areaId in s.includeAreas }.forEach { out += areaIds(it) }
            out += s.includeSpeakers
            allAreas.filter { it.areaId in s.excludeAreas }.forEach { out -= areaIds(it).toSet() }
            out -= s.excludeSpeakers.toSet()
            return out.filterTo(linkedSetOf()) { it in enabled }
        }

        /** Saving compacts the set: full floors, then full areas, then single speakers; excludes empty. */
        fun compact(selected: Set<String>): AdhocSelection {
            val remaining = selected.toMutableSet()
            val floors = mutableListOf<String>()
            val areas = mutableListOf<String>()
            fun takeArea(area: Area) {
                val ids = areaIds(area)
                if (ids.isNotEmpty() && remaining.containsAll(ids)) {
                    areas += area.areaId
                    remaining -= ids.toSet()
                }
            }
            for (floor in hierarchy.floors) {
                val ids = floorIds(floor.areas)
                if (ids.isNotEmpty() && remaining.containsAll(ids)) {
                    floors += floor.floorId
                    remaining -= ids.toSet()
                } else {
                    visibleAreas(floor.areas).forEach(::takeArea)
                }
            }
            hierarchy.unassignedAreas.forEach(::takeArea)
            return AdhocSelection(includeFloors = floors, includeAreas = areas, includeSpeakers = remaining.toList())
        }
    }
}
