package app.sonorium.listen

import app.sonorium.net.PresetInfo
import app.sonorium.net.Session
import app.sonorium.net.ThemeSummary

/**
 * What the notification and lock screen show for a server channel heard on this device, worded as for a theme
 * played on the device (PhonePlayer.title / subtitle): the theme as the title; the playlist entry, sequence
 * step or preset, then the channel (and the server when it fits) under it.
 */
object NowHeard {
    /** Longest subtitle that still takes the server's name. */
    const val FITS = 42

    /** The playlist entry's theme while a playlist plays, else the channel's theme; [fallback] without one. */
    fun title(session: Session?, themes: List<ThemeSummary>, fallback: String): String {
        val playlistTheme = session?.playlist?.takeIf { it.running }?.themeName?.takeIf { it.isNotBlank() }
        val theme = themes.firstOrNull { it.id == session?.themeId }?.name?.takeIf { it.isNotBlank() }
        return playlistTheme ?: theme ?: fallback.ifBlank { "Sonorium" }
    }

    /** The playlist entry, sequence step, else the preset; then the channel, and the server when it fits. */
    fun subtitle(session: Session?, presets: List<PresetInfo>, channel: String, server: String?): String {
        val playlist = session?.playlist?.takeIf { it.running }
        val sequence = session?.sequence?.takeIf { it.running }
        val step = when {
            playlist != null && (playlist.entries ?: 0) > 0 -> "Entry ${(playlist.position ?: 0) + 1} of ${playlist.entries}"
            sequence != null && sequence.step != null && sequence.steps != null ->
                "Step ${sequence.step} of ${sequence.steps}" + (sequence.currentPresetName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            sequence != null -> sequence.name?.takeIf { it.isNotBlank() } ?: "Sequence"
            session?.presetId != null && session.presetId.isNotBlank() -> presets.firstOrNull { it.id == session.presetId }?.name?.takeIf { it.isNotBlank() } ?: "Preset"
            else -> "Default settings"
        }
        val withChannel = if (channel.isBlank()) step else "$step · $channel"
        val withServer = server?.takeIf { it.isNotBlank() }?.let { "$withChannel · $it" }
        return if (withServer != null && withServer.length <= FITS) withServer else withChannel
    }

    /** The theme to play after the one playing (in the app's theme order, those with audio), or null. */
    fun nextTheme(themeId: String?, themes: List<ThemeSummary>): ThemeSummary? {
        val playable = app.sonorium.server.ThemeRules.sorted(themes.filter { it.hasAudio }, false)
        if (playable.isEmpty()) return null
        val i = playable.indexOfFirst { it.id == themeId }
        val next = playable[(i + 1).mod(playable.size)]
        return next.takeIf { it.id != themeId }
    }

    /** The preset after (or before) the one playing, the theme's default settings included: "" for those. */
    fun nextPreset(presetId: String?, presets: List<PresetInfo>, forward: Boolean): String? {
        val ids = listOf("") + presets.map { it.id }
        if (ids.size < 2) return null
        val i = ids.indexOf(presetId.orEmpty()).coerceAtLeast(0)
        return ids[(i + (if (forward) 1 else -1)).mod(ids.size)]
    }
}
