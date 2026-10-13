package app.sonorium.ui.coach

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sonorium.R

/** The guides and the controls they point at ([Targets]). Texts are in strings.xml. */
object Guides {
    /** First run, over the main screen (Chris's steps and wording). */
    const val FIRST_RUN = "first-run"

    @Composable
    private fun step(target: String?, title: Int, text: Int) = CoachStep(target, stringResource(title), stringResource(text))

    @Composable
    fun firstRun() = CoachGuide(
        FIRST_RUN,
        listOf(
            step(Targets.PLAY, R.string.coach_play_title, R.string.coach_play_text),
            step(Targets.SERVERS, R.string.coach_connections_title, R.string.coach_connections_text),
            step(Targets.SERVERS, R.string.coach_add_title, R.string.coach_add_text),
        ),
    )

    /** First visit of Themes. */
    @Composable
    fun themes() = CoachGuide(
        "themes",
        listOf(
            step(Targets.THEME_PREVIEW, R.string.coach_themes_preview_title, R.string.coach_themes_preview_text),
            step(Targets.THEME_EDIT, R.string.coach_themes_edit_title, R.string.coach_themes_edit_text),
            step(Targets.THEME_MORE, R.string.coach_themes_more_title, R.string.coach_themes_more_text),
            step(Targets.THEME_CREATE, R.string.coach_themes_create_title, R.string.coach_themes_create_text),
        ),
    )

    /** First visit of Playlists. */
    @Composable
    fun playlists() = CoachGuide(
        "playlists",
        listOf(
            step(Targets.PLAYLIST_ADD, R.string.coach_playlists_add_title, R.string.coach_playlists_add_text),
            step(Targets.PLAYLIST_ENTRIES, R.string.coach_playlists_entries_title, R.string.coach_playlists_entries_text),
            step(Targets.PLAYLIST_EDIT, R.string.coach_playlists_edit_title, R.string.coach_playlists_edit_text),
        ),
    )

    /** The first channel card opened. */
    @Composable
    fun channel() = CoachGuide(
        "channel",
        listOf(
            step(Targets.CARD_SOURCE, R.string.coach_channel_source_title, R.string.coach_channel_source_text),
            step(Targets.CARD_MODE, R.string.coach_channel_mode_title, R.string.coach_channel_mode_text),
            step(Targets.CARD_MIX, R.string.coach_channel_mix_title, R.string.coach_channel_mix_text),
            step(Targets.CARD_VOLUME, R.string.coach_channel_volume_title, R.string.coach_channel_volume_text),
        ),
    )

    /** First visit of the Theme Editor. */
    @Composable
    fun editor() = CoachGuide(
        "editor",
        listOf(
            step(Targets.EDITOR_TRACKS, R.string.coach_editor_tracks_title, R.string.coach_editor_tracks_text),
            step(Targets.EDITOR_PREVIEW, R.string.coach_editor_preview_title, R.string.coach_editor_preview_text),
            step(Targets.EDITOR_PRESETS, R.string.coach_editor_presets_title, R.string.coach_editor_presets_text),
            step(Targets.EDITOR_SAVE, R.string.coach_editor_save_title, R.string.coach_editor_save_text),
        ),
    )

    /** The guide a page's help offers again ([view]: the help's page), or null. */
    @Composable
    fun forHelp(view: String): CoachGuide? = when (view) {
        "sessions" -> firstRun()
        "themes", "phone-themes" -> themes()
        "playlists" -> playlists()
        else -> null
    }
}

/** Names of the controls guides point at ([coachTarget]). */
object Targets {
    const val PLAY = "play"
    const val SERVERS = "servers"
    const val THEME_PREVIEW = "theme-preview"
    const val THEME_EDIT = "theme-edit"
    const val THEME_MORE = "theme-more"
    const val THEME_CREATE = "theme-create"
    const val PLAYLIST_ADD = "playlist-add"
    const val PLAYLIST_ENTRIES = "playlist-entries"
    const val PLAYLIST_EDIT = "playlist-edit"
    const val CARD_SOURCE = "card-source"
    const val CARD_MODE = "card-mode"
    const val CARD_MIX = "card-mix"
    const val CARD_VOLUME = "card-volume"
    const val EDITOR_TRACKS = "editor-tracks"
    const val EDITOR_PREVIEW = "editor-preview"
    const val EDITOR_PRESETS = "editor-presets"
    const val EDITOR_SAVE = "editor-save"
}
