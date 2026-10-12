package app.sonorium.server

import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerGroup
import app.sonorium.net.SpeakerHierarchy

/** Settings › Speaker Groups: the web UI's client-side logic (native-api.md Phase 2). */
object GroupRules {

    /** The type badge (`cast` → `Cast`, …); unknown or none → null. */
    fun typeLabel(type: String?): String? = when (type) {
        "cast" -> "Cast"
        "sonos" -> "Sonos"
        "dlna" -> "DLNA"
        "airplay" -> "AirPlay"
        "linkplay" -> "LinkPlay"
        "heos" -> "HEOS"
        "esphome" -> "ESPHome"
        "denon" -> "Denon / Marantz"
        else -> null
    }

    /** Where a speaker sits in the hierarchy. */
    data class Placed(val speaker: Speaker, val areaId: String?, val areaName: String?, val floorId: String?, val floorName: String?)

    /** Every hierarchy speaker: floors' areas, unassigned areas, unassigned speakers, in that order. */
    fun placed(h: SpeakerHierarchy): List<Placed> = buildList {
        h.floors.forEach { f -> f.areas.forEach { a -> a.speakers.forEach { add(Placed(it, a.areaId, a.name, f.floorId, f.name)) } } }
        h.unassignedAreas.forEach { a -> a.speakers.forEach { add(Placed(it, a.areaId, a.name, null, null)) } }
        h.unassignedSpeakers.forEach { add(Placed(it, null, null, null, null)) }
    }

    enum class Via { Speaker, Area, Floor }

    data class Member(val placed: Placed, val via: Via, val excluded: Boolean) {
        /** `<floor> · <area>` / `<area>` / `No area`, then `· added` / `· via area` / `· via floor`. */
        val location: String
            get() {
                val where = when {
                    placed.areaName != null && placed.floorName != null -> "${placed.floorName} · ${placed.areaName}"
                    placed.areaName != null -> placed.areaName
                    else -> "No area"
                }
                val how = when (via) {
                    Via.Speaker -> "added"
                    Via.Area -> "via area"
                    Via.Floor -> "via floor"
                }
                return "$where · $how"
            }
    }

    data class Selection(
        val floors: List<String>,
        val areas: List<String>,
        val speakers: List<String>,
        val excludeAreas: List<String> = emptyList(),
        val excludeSpeakers: List<String> = emptyList(),
    ) {
        constructor(g: SpeakerGroup) : this(g.includeFloors, g.includeAreas, g.includeSpeakers, g.excludeAreas, g.excludeSpeakers)
        val empty: Boolean get() = floors.isEmpty() && areas.isEmpty() && speakers.isEmpty()
    }

    /** `resolveGroupMembers`. */
    fun members(sel: Selection, h: SpeakerHierarchy?): List<Member> = placed(h ?: return emptyList()).mapNotNull { p ->
        val id = p.speaker.entityId
        val via = when {
            id in sel.speakers -> Via.Speaker
            p.areaId != null && p.areaId in sel.areas -> Via.Area
            p.floorId != null && p.floorId in sel.floors -> Via.Floor
            else -> return@mapNotNull null
        }
        Member(p, via, id in sel.excludeSpeakers || (p.areaId != null && p.areaId in sel.excludeAreas))
    }

    fun count(sel: Selection, h: SpeakerHierarchy?): Int = members(sel, h).count { !it.excluded }

    fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    /** The chip strip's tooltip and the editor summary parts (stored list lengths, stale ids included). */
    fun parts(sel: Selection): List<String> = buildList {
        if (sel.floors.isNotEmpty()) add(plural(sel.floors.size, "floor"))
        if (sel.areas.isNotEmpty()) add(plural(sel.areas.size, "area"))
        if (sel.speakers.isNotEmpty()) add(plural(sel.speakers.size, "speaker"))
    }

    enum class ChipKind { Floor, Area, Speaker, ExcludedArea, ExcludedSpeaker }

    data class Chip(val kind: ChipKind, val label: String)

    /** The Includes chips, only for ids still in the hierarchy, in the web UI's order. */
    fun chips(sel: Selection, h: SpeakerHierarchy?): List<Chip> {
        h ?: return emptyList()
        val floors = h.floors.associate { it.floorId to it.name }
        val areas = (h.floors.flatMap { it.areas } + h.unassignedAreas).associate { it.areaId to it.name }
        val speakers = placed(h).associate { it.speaker.entityId to it.speaker.name }
        return sel.floors.mapNotNull { floors[it]?.let { n -> Chip(ChipKind.Floor, n) } } +
            sel.areas.mapNotNull { areas[it]?.let { n -> Chip(ChipKind.Area, n) } } +
            sel.speakers.mapNotNull { speakers[it]?.let { n -> Chip(ChipKind.Speaker, n) } } +
            sel.excludeAreas.mapNotNull { areas[it]?.let { n -> Chip(ChipKind.ExcludedArea, n) } } +
            sel.excludeSpeakers.mapNotNull { speakers[it]?.let { n -> Chip(ChipKind.ExcludedSpeaker, n) } }
    }

    /** Save checks; the toast, or null. */
    fun validate(name: String, sel: Selection): String? = when {
        name.trim().isEmpty() -> "Enter a group name"
        sel.empty -> "Select at least one speaker"
        else -> null
    }
}
