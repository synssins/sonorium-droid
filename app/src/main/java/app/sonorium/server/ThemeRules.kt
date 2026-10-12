package app.sonorium.server

import app.sonorium.net.Session
import app.sonorium.net.ThemeSummary
import java.text.Collator

/** Configuration › Themes: search, chips, sort and the on-air badge (native-api.md Phase 2). */
object ThemeRules {
    const val ALL = ""
    const val FAVORITES = "\u0000fav"

    /** `tpMatchesQuery`. */
    fun matches(t: ThemeSummary, text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return true
        val name = t.name.lowercase()
        return name.replace(Regex("_+"), " ").contains(q) || name.contains(q) || t.description.lowercase().contains(q)
    }

    /** `tpInCat`. */
    fun inChip(t: ThemeSummary, chip: String): Boolean = when (chip) {
        ALL -> true
        FAVORITES -> t.isFavorite
        else -> chip in t.categories
    }

    /** Chips after All and Favorites: the category list, then categories found only on themes. */
    fun categoryChips(categories: List<String>, themes: List<ThemeSummary>): List<String> =
        (categories + themes.flatMap { it.categories }).distinct()

    private val collator: Collator = Collator.getInstance()

    private fun sortName(t: ThemeSummary) = t.name.lowercase().replace('_', ' ')

    /** Name (locale order of the normalised names) or Most tracks (ties by Name). */
    fun sorted(themes: List<ThemeSummary>, byTracks: Boolean): List<ThemeSummary> {
        val byName = Comparator<ThemeSummary> { a, b -> collator.compare(sortName(a), sortName(b)) }
        return themes.sortedWith(if (byTracks) compareByDescending<ThemeSummary> { it.totalTracks }.then(byName) else byName)
    }

    fun count(n: Int) = "$n ${if (n == 1) "theme" else "themes"}"

    fun tracks(n: Int) = if (n == 0) "No tracks" else "$n ${if (n == 1) "track" else "tracks"}"

    /** Channels playing this theme now. */
    fun onAir(themeId: String, sessions: List<Session>) = sessions.filter { it.isPlaying && it.themeId == themeId }

    /** The on-air badge text: first channel, `+<n-1>` when more. */
    fun onAirLabel(on: List<Session>): String = on.first().name + if (on.size > 1) " +${on.size - 1}" else ""

    fun canPreview(t: ThemeSummary) = t.hasAudio && t.totalTracks > 0

    /** Manage categories › Add: disabled when empty or a case-insensitive duplicate. */
    fun canAddCategory(text: String, categories: List<String>): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && categories.none { it.equals(t, ignoreCase = true) }
    }

    fun collapseSpaces(text: String) = text.trim().replace(Regex("\\s+"), " ")
}
