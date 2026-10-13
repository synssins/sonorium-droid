package app.sonorium.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.ui.Palette

enum class HelpKind { H4, H5, H6, P1, P2, P3 }

/** One line of a help panel: a heading, or a paragraph with its bold lead. */
data class HelpLine(val kind: HelpKind, val bold: String, val text: String)

/** This device's own entry is the one shown: help and labels describe the device, not a server's speakers. */
val LocalOnDevice = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The help panels' texts, from the web UI's `index.html` (`#te-help`, `#tx-help`), worded for a touch screen. */
object HelpTexts {
    /** Lines about what only a Sonorium has (speakers, making channels, Save to phone): not shown on the device. */
    private val SERVER_ONLY = setOf(
        "Speakers:", "Volume:", "Speaker button:", "Headphones button:", "Play / Stop:", "Edit:", "Delete:",
        "New Channel:", "Speaker group:", "Playing count:", "⋯ › Save to phone:", "Save to phone:",
    )

    /** [lines] as the device shows them: the server-only lines left out, and no sentences about speakers. */
    fun forDevice(lines: List<HelpLine>): List<HelpLine> = lines
        .filterNot { it.bold in SERVER_ONLY || (it.kind == HelpKind.H4 && it.text == "New channel") || (it.bold.isEmpty() && "speakers" in it.text) }
        .map { it.copy(text = it.text.replace(" The speakers are not changed.", "")) }

    val THEME_EDITOR = listOf(
        HelpLine(HelpKind.P1, "", "A theme is a set of tracks (sound files) mixed together. This help follows the editor from top to bottom."),
        HelpLine(HelpKind.H4, "", "Tracks"),
        HelpLine(HelpKind.P1, "Sequences:", "Lists this theme's sequences. Pick one to edit it, or Add sequence."),
        HelpLine(HelpKind.P1, "Preview mix:", "Plays the mix on this phone, with your changes. The speakers are not changed."),
        HelpLine(HelpKind.P1, "Upload:", "Uploads to Unassigned, or to the group you pick."),
        HelpLine(HelpKind.P1, "Add from another theme:", "Links files from another theme."),
        HelpLine(HelpKind.P1, "Reset all:", "Resets every track and group to defaults."),
        HelpLine(HelpKind.H4, "", "Sections"),
        HelpLine(HelpKind.P1, "Sections:", "tracks sit in sections by how they play: Background, Intermittent and Ebb & Flow. Every section is always there, even empty. Change a track's Mode, or move it into a group with its ⋯ menu, and it moves there and plays that way. A track in a group plays its group's way: move it out of the group to change it."),
        HelpLine(HelpKind.P1, "Groups:", "a group belongs to its section and plays that section's way: Background groups are Merry-go-round, Intermittent groups take turns, Ebb & Flow groups play every file at once. Make one with the section's New group."),
        HelpLine(HelpKind.P1, "Moving tracks:", "use the track's ⋯ menu › Move to, or change its Mode."),
        HelpLine(HelpKind.H4, "", "Unassigned"),
        HelpLine(HelpKind.P1, "Unassigned:", "uploads and files added from another theme start here. Nothing here plays. Pick each file's Mode, or move it into a group with its ⋯ menu."),
        HelpLine(HelpKind.H4, "", "Background"),
        HelpLine(HelpKind.P1, "Background:", "plays all the time, looping smoothly. Rain, wind, a fire."),
        HelpLine(HelpKind.H5, "", "Track"),
        HelpLine(HelpKind.P2, "Gapless:", "in the track's ⋯ menu, outside groups. Loops without a crossfade."),
        HelpLine(HelpKind.H5, "", "Merry-go-round group"),
        HelpLine(HelpKind.P2, "Merry-go-round:", "one file plays, then crossfades into another picked at random, never the same one twice in a row. One file crossfades into itself. Use 3 or more files of the same scene for a bed that never repeats the same way."),
        HelpLine(HelpKind.P2, "Crossfade:", "under the group's name. Overlap between files (seconds)."),
        HelpLine(HelpKind.P2, "Volume, Mute:", "apply to every track in the group."),
        HelpLine(HelpKind.H4, "", "Intermittent"),
        HelpLine(HelpKind.P1, "Intermittent:", "plays once, then goes quiet before playing again. In a group, the group decides when it plays. Bird calls, a thunder crack, a door."),
        HelpLine(HelpKind.H5, "", "Track"),
        HelpLine(HelpKind.P2, "Interval:", "the wait between plays. In a group, how fast its weight wears off. Higher comes back sooner; lower plays less often."),
        HelpLine(HelpKind.H5, "", "Intermittent group"),
        HelpLine(HelpKind.P2, "Take turns:", "only one track plays at a time, never overlapping. On its turn, a track plays its whole file once, start to finish, then hands over. Use it for sounds that shouldn't pile up, like thunder cracks or songs from one musician."),
        HelpLine(HelpKind.P2, "Gap:", "under the group's name: after a track finishes, the group waits a random time in this range (minutes) before the next one starts. Different groups don't wait for each other. Presets keep their own Gap."),
        HelpLine(HelpKind.P2, "Weight:", "each time a track plays, it gets heavier and is less likely to be picked next. The weight wears off as other tracks play. The same track never plays twice in a row."),
        HelpLine(HelpKind.P2, "Volume, Interval, Mute:", "apply to every track in the group."),
        HelpLine(HelpKind.H4, "", "Ebb & Flow"),
        HelpLine(HelpKind.P1, "Ebb & Flow:", "Tracks can be configured with a minimum and maximum volume, and will automatically move to any value between those two limits randomly for a period of time. Wind, surf, rain that gusts."),
        HelpLine(HelpKind.H5, "", "Track"),
        HelpLine(HelpKind.P2, "Volume:", "minimum and maximum volume."),
        HelpLine(HelpKind.P2, "Duration:", "increases or decreases the amount of time the track is at a value."),
        HelpLine(HelpKind.H6, "", "Its ⋯ menu"),
        HelpLine(HelpKind.P3, "Rise:", "increases or decreases the speed at which the track reaches that value."),
        HelpLine(HelpKind.P3, "Pause between plays:", "off, the file loops and is always heard. On, it plays once from start to finish, fades out at its end, then pauses before it comes back. Rain on a tin roof that comes and goes."),
        HelpLine(HelpKind.P3, "Pause:", "how long it stays quiet, in seconds, picked at random between the two numbers each time (600 is 10 minutes). It also staggers when tracks first start, so they don't all begin together."),
        HelpLine(HelpKind.H5, "", "Ebb & Flow group"),
        HelpLine(HelpKind.P2, "Ebb & Flow group:", "every file plays at once, each between its own minimum and maximum volume. Each file keeps its own settings; the group's Volume and Duration scale them down."),
        HelpLine(HelpKind.P2, "Pause between plays:", "under the group's name. On, every file pauses between plays, at staggered times, so at least one is always playing. Off, each file follows its own Pause between plays."),
        HelpLine(HelpKind.P2, "Volume, Duration, Mute:", "apply to every track in the group."),
        HelpLine(HelpKind.H6, "", "Its ⋯ menu"),
        HelpLine(HelpKind.P3, "Same Rise for every file:", "one Rise for the whole group instead of each file's own."),
        HelpLine(HelpKind.H4, "", "A track's ⋯ menu"),
        HelpLine(HelpKind.P1, "Fade in / Fade out:", "for a file that starts or ends abruptly. Set how many seconds it fades in each time it starts, and fades out each time it ends, including each loop. Empty is off. Sequences and advanced transitions use them too."),
        HelpLine(HelpKind.P1, "Move to:", "Unassigned, or a group."),
        HelpLine(HelpKind.P1, "New group:", "Creates a group in this section with the track in it."),
        HelpLine(HelpKind.P1, "Reset track:", "Resets the track to defaults."),
        HelpLine(HelpKind.H4, "", "A group's ⋯ menu"),
        HelpLine(HelpKind.P1, "Rename:", "also by tapping and holding the group name."),
        HelpLine(HelpKind.P1, "Delete group:", "keeps its files; they move back into its section."),
        HelpLine(HelpKind.H4, "", "Files from another theme"),
        HelpLine(HelpKind.P1, "Linked file:", "plays here but stays in its own theme. Add one with Add from another theme above the list; it starts in Unassigned, and can go anywhere your own files can. If the file is gone, it shows Missing."),
        HelpLine(HelpKind.P1, "Presets:", "turn linked files on and other tracks off, like rain that mutes the crickets. A preset that turns a linked file on shows ⚭."),
        HelpLine(HelpKind.P1, "Remove link:", "in a linked file's ⋯ menu. The file stays in its own theme."),
        HelpLine(HelpKind.H4, "", "Sequences"),
        HelpLine(HelpKind.P1, "Sequence:", "plays presets of this theme in the order you choose, gliding from one to the next. Marked (sequence icon)."),
        HelpLine(HelpKind.P1, "Picking one:", "preset lists show the sequences after the presets. Picking a preset by hand stops a running sequence."),
        HelpLine(HelpKind.P1, "Edit:", "pick a sequence in the preset field below, then Edit."),
        HelpLine(HelpKind.H5, "", "Edit sequence"),
        HelpLine(HelpKind.P2, "Time on each preset:", "each preset plays for a random time in this range (minutes), drawn fresh each time."),
        HelpLine(HelpKind.P2, "Ramp:", "how long the glide to the next preset takes (seconds)."),
        HelpLine(HelpKind.P2, "At the end:", "Reverse plays the list backwards, Start again goes back to the first preset, Stop stays on the last one."),
        HelpLine(HelpKind.P2, "Order:", "use a preset's ⋯ menu to move it up or down."),
        HelpLine(HelpKind.P2, "Theme defaults:", "empty fields use the theme's time and ramp, set at the bottom of the Sequences menu."),
        HelpLine(HelpKind.P2, "Save:", "saves the sequence at once. Every channel playing this theme can pick it."),
        HelpLine(HelpKind.H5, "", "A step: ⋯ › Advanced"),
        HelpLine(HelpKind.P2, "While playing:", "changes how tracks play while this step plays, for this sequence only. The same controls as the Theme Editor. A value you change shows in green; anything you don't change follows the preset."),
        HelpLine(HelpKind.P2, "Revert changes:", "puts back the settings as they were when Save was last pressed."),
        HelpLine(HelpKind.P2, "Coming in / Leaving:", "tracks this step turns on or makes louder, and tracks the next step turns off or makes quieter."),
        HelpLine(HelpKind.H6, "", "Coming in and Leaving"),
        HelpLine(HelpKind.P3, "Smooth:", "no button on. The track follows the sequence's ramp."),
        HelpLine(HelpKind.P3, "Buttons:", "tap to turn a style on, tap again to turn it off. Each style that's on shows its own setting under Setting."),
        HelpLine(HelpKind.P3, "Pre-fade:", "starts the change this many seconds before the step."),
        HelpLine(HelpKind.P3, "Gusts / Gusts dying down:", "rises or falls in uneven surges. Intensity sets how rough. Max volume caps the peaks at the preset volume."),
        HelpLine(HelpKind.P3, "Swell / Smooth fade:", "one curved fade. Speed sets how much of the fade time (pre-fade plus ramp) it uses."),
        HelpLine(HelpKind.P3, "Build / Thin out:", "Intermittent tracks play more often and louder over the fade, or less often and quieter. Amount sets how much."),
        HelpLine(HelpKind.P3, "Distance / Recede:", "starts faint and muffled, like far-off thunder, and ends clear at the preset volume; or drifts away. How far sets the distance."),
        HelpLine(HelpKind.P3, "Start fresh / Finish then stop:", "plays from the start of the file with a short fade, for music, voices and rhythm; or lets the current play finish, then stops."),
        HelpLine(HelpKind.P3, "Preview:", "▶ plays that track's change on this phone and follows your edits."),
        HelpLine(HelpKind.P3, "File fades:", "a file's own Fade in and Fade out apply on top of every style."),
        HelpLine(HelpKind.P3, "Combining styles:", "several styles can be on together. They all work over the same fade time (pre-fade plus ramp). For example:"),
        HelpLine(HelpKind.P3, "Gusts + Distance:", "wind starts far off and muffled, and surges unevenly as it comes closer."),
        HelpLine(HelpKind.P3, "Gusts + Swell:", "the surges ride on one curved rise instead of a steady one."),
        HelpLine(HelpKind.P3, "Gusts + Start fresh:", "the file plays from its start and rises in uneven surges."),
        HelpLine(HelpKind.P3, "Build + Distance:", "thunder comes more often and louder as it moves from far off to close."),
        HelpLine(HelpKind.P3, "Thin out + Recede:", "thunder comes less often and quieter as it drifts away."),
        HelpLine(HelpKind.P3, "Smooth fade + Finish then stop:", "fades out over the fade time; if the current play ends first, it stops there with a short fade."),
        HelpLine(HelpKind.H4, "", "Presets and saving"),
        HelpLine(HelpKind.P1, "Live:", "changes are heard at once with Preview mix, and on channels playing this theme with no preset. A channel playing a preset hears them after Save preset."),
        HelpLine(HelpKind.P1, "Preset:", "the loaded preset. Current settings is the theme without one."),
        HelpLine(HelpKind.P1, "New:", "makes a new preset from the current mix."),
        HelpLine(HelpKind.P1, "Save preset:", "saves the loaded preset."),
        HelpLine(HelpKind.P1, "Save theme:", "saves the theme with all its changes (presets, name and more). Needed after saving a preset."),
        HelpLine(HelpKind.P1, "Cancel:", "puts back the changes that aren't saved."),
    )

    val ADVANCED = listOf(
        HelpLine(HelpKind.H4, "", "In a sequence step"),
        HelpLine(HelpKind.P1, "While playing:", "changes how tracks play while this step plays, for this sequence only. The same controls as the Theme Editor. A value you change shows in green; anything you don't change follows the preset."),
        HelpLine(HelpKind.P1, "Revert changes:", "puts back the settings as they were when Save was last pressed: for one track or group in its ⋯ menu, or for everything at the top of the list. A value set back to the preset's follows the preset again."),
        HelpLine(HelpKind.P1, "Coming in / Leaving:", "how tracks change as the step starts and ends (see Advanced transitions)."),
        HelpLine(HelpKind.P1, "Rise:", "in an Ebb & Flow track's ⋯ menu. Increases or decreases the speed at which the track reaches a value. Duration increases or decreases the amount of time the track is at a value. A group can set the same Rise for every file."),
        HelpLine(HelpKind.H4, "", "Advanced transitions"),
        HelpLine(HelpKind.P1, "Advanced:", "in a sequence, a preset's ⋯ › Advanced sets how each track comes in, and how it leaves."),
        HelpLine(HelpKind.P1, "Coming in / Leaving:", "tracks this step turns on or makes louder, and tracks the next step turns off or makes quieter."),
        HelpLine(HelpKind.P1, "Smooth:", "no button on. The track follows the sequence's ramp."),
        HelpLine(HelpKind.P1, "Buttons:", "tap to turn one on, tap it again to show its setting on the slider, tap once more to turn it off. Several can be on together."),
        HelpLine(HelpKind.P1, "Pre-fade:", "starts the change this many seconds before the step."),
        HelpLine(HelpKind.P1, "Gusts / Gusts dying down:", "rises or falls in uneven surges. Intensity sets how rough. Max volume caps the peaks at the preset volume."),
        HelpLine(HelpKind.P1, "Swell / Smooth fade:", "one curved fade. Speed sets how much of the fade time (pre-fade plus ramp) it uses."),
        HelpLine(HelpKind.P1, "Build / Thin out:", "Intermittent tracks play more often and louder over the fade, or less often and quieter. Amount sets how much."),
        HelpLine(HelpKind.P1, "Distance / Recede:", "starts faint and muffled, like far-off thunder, and ends clear at the preset volume; or drifts away. How far sets the distance."),
        HelpLine(HelpKind.P1, "Start fresh / Finish then stop:", "plays from the start of the file with a short fade, for music, voices and rhythm; or lets the current play finish, then stops."),
        HelpLine(HelpKind.P1, "Preview:", "▶ plays that track's change on this phone and follows your edits."),
    )

    /** `PAGE_HELP` (app.js): page view id → title and lines. */
    val PAGES: Map<String, Pair<String, List<HelpLine>>> = mapOf(
        "sessions" to ("Channels" to listOf(
            HelpLine(HelpKind.P1, "", "A channel plays one theme on a set of speakers. Each card is one channel."),
            HelpLine(HelpKind.H4, "", "Each channel"),
            HelpLine(HelpKind.P1, "Theme:", "the sound the channel plays. Picking another theme also picks that theme's default preset, if it has one."),
            HelpLine(HelpKind.P1, "Preset:", "a saved mix of the theme's tracks. Default settings plays the theme without a preset."),
            HelpLine(HelpKind.P1, "Sequences:", "listed in the Preset menu after the presets, marked with the sequence icon. Picking one plays its presets in turn; the line under the field shows the preset playing now and when it changes. Picking a preset stops it."),
            HelpLine(HelpKind.P1, "Playlist:", "a channel can play a playlist instead of one theme: tap the playlist button right of the fields. The fields then show the playlist and the entry playing now."),
            HelpLine(HelpKind.P1, "Theme or playlist:", "the two buttons right of the fields. The palette plays a theme (Theme and Preset); the playlist icon plays a playlist (Playlist and Playing)."),
            HelpLine(HelpKind.P1, "Now:", "the sequence step or playlist entry playing, and when the next one starts."),
            HelpLine(HelpKind.P1, "Next:", "skips to the next step or entry."),
            HelpLine(HelpKind.P1, "Speakers:", "where the channel plays. Use Edit to change them."),
            HelpLine(HelpKind.P1, "Volume:", "the speakers. Saved when you let go of the slider."),
            HelpLine(HelpKind.P1, "Speaker button:", "mutes the speakers. Red while muted. Speakers may take a few seconds to go quiet."),
            HelpLine(HelpKind.P1, "Headphones button:", "plays the channel on this phone, even while the speakers are stopped. Its slider is this phone's volume only."),
            HelpLine(HelpKind.P1, "Play / Stop:", "starts or stops the speakers. This phone keeps playing if it listens."),
            HelpLine(HelpKind.P1, "Edit:", "changes the channel's name, theme or playlist, preset, volume and speakers."),
            HelpLine(HelpKind.P1, "Delete:", "removes the channel. You confirm first."),
            HelpLine(HelpKind.P1, "Recent changes:", "the channel's latest changes with their times: started, stopped, muted, theme, preset, sequence step, playlist entry."),
            HelpLine(HelpKind.H4, "", "New channel"),
            HelpLine(HelpKind.P1, "New Channel:", "makes a channel. Leave the name empty and Sonorium names it Channel and a number, for example Channel 1."),
            HelpLine(HelpKind.P1, "Speakers:", "pick floors, areas or single speakers. Only speakers turned on under Settings › Speakers are listed."),
            HelpLine(HelpKind.P1, "Speaker group:", "picks a saved group instead of single speakers. Shown when you have speaker groups."),
            HelpLine(HelpKind.P1, "Playing count:", "the number next to Channels in the menu is how many channels are playing."),
        )),
        "playlists" to ("Playlists" to listOf(
            HelpLine(HelpKind.P1, "", "A playlist plays themes one after another on a channel."),
            HelpLine(HelpKind.H4, "", "Each playlist"),
            HelpLine(HelpKind.P1, "Entry:", "a theme, plus a preset or sequence of that theme if you want one. Without one, the theme plays its default settings."),
            HelpLine(HelpKind.P1, "Arrow:", "shows the entries."),
            HelpLine(HelpKind.P1, "Green dot:", "a channel is playing the playlist now."),
            HelpLine(HelpKind.P1, "Play each:", "every entry plays for a random time in this range (minutes), drawn fresh each time. Then the next entry starts."),
            HelpLine(HelpKind.P1, "In order, Shuffle:", "Shuffle mixes the order on every pass."),
            HelpLine(HelpKind.P1, "Repeat:", "starts over after the last entry. Without it the channel stops after the last entry."),
            HelpLine(HelpKind.P1, "⋯:", "Duplicate and Delete. You confirm a delete first."),
            HelpLine(HelpKind.H4, "", "Editing"),
            HelpLine(HelpKind.P1, "Sequence entry:", "plays the whole sequence once, then the playlist moves on. Sequences are listed after the presets, marked with the sequence icon."),
            HelpLine(HelpKind.P1, "Fit sequences into the playlist time:", "a sequence entry plays for the playlist's time like any other entry, with its steps shortened or lengthened to fit."),
            HelpLine(HelpKind.P1, "Reorder:", "use an entry's ⋯ menu to move it up or down."),
            HelpLine(HelpKind.H4, "", "Playing one"),
            HelpLine(HelpKind.P1, "Channel:", "on a channel's card, tap the playlist button right of the fields and pick the playlist. Next on the card skips to the next entry."),
        )),
        "themes" to ("Themes" to listOf(
            HelpLine(HelpKind.P1, "", "A theme is a set of tracks (sound files) mixed together. Every theme is listed here."),
            HelpLine(HelpKind.H4, "", "Finding themes"),
            HelpLine(HelpKind.P1, "Search:", "looks in theme names and descriptions."),
            HelpLine(HelpKind.P1, "Categories:", "a chip shows only the themes in that category. Favorites shows the themes you starred. The number is how many themes match."),
            HelpLine(HelpKind.P1, "Sort:", "by name, or with the most tracks first."),
            HelpLine(HelpKind.H4, "", "Each theme"),
            HelpLine(HelpKind.P1, "★:", "adds the theme to Favorites or removes it."),
            HelpLine(HelpKind.P1, "Preview:", "plays the theme on this phone only. The speakers are not changed. Tap it again to stop."),
            HelpLine(HelpKind.P1, "Edit:", "opens the theme editor: tracks, modes, presets and more."),
            HelpLine(HelpKind.P1, "Green badge:", "the channel playing this theme now."),
            HelpLine(HelpKind.P1, "⋯ › Save to phone:", "copies the theme to this phone, so it plays here without the server: on the On this phone page. Shows Update when the server has a newer version."),
            HelpLine(HelpKind.P1, "⋯ › Export:", "saves the theme as a .zip file, wherever you choose on this phone."),
            HelpLine(HelpKind.P1, "⋯ › Delete:", "removes the theme. You confirm first."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Create theme:", "asks for a name, makes an empty theme and opens it in the editor. With a category selected, the new theme goes in that category."),
            HelpLine(HelpKind.P1, "⋯ › Import theme:", "adds a theme from a .zip file."),
            HelpLine(HelpKind.P1, "⋯ › Manage categories:", "adds or deletes categories."),
            HelpLine(HelpKind.P1, "⋯ › Refresh:", "scans the theme folders again for new or changed themes."),
        )),
        "phone-themes" to ("On this phone" to listOf(
            HelpLine(HelpKind.P1, "", "Themes kept on this phone. They play here with the same engine as your server, with no connection needed."),
            HelpLine(HelpKind.H4, "", "Getting themes"),
            HelpLine(HelpKind.P1, "Save to phone:", "on your server's Themes page, a theme's ⋯ › Save to phone copies it here."),
            HelpLine(HelpKind.P1, "Import theme:", "adds a theme from a .zip file, for example one exported from your server."),
            HelpLine(HelpKind.P1, "⋯ › Change themes folder:", "picks the folder on this phone where the themes are kept."),
            HelpLine(HelpKind.H4, "", "Each theme"),
            HelpLine(HelpKind.P1, "Play:", "tap the headphone button to play the theme on this phone. Tap it again to stop."),
            HelpLine(HelpKind.P1, "⋯ › Remove from phone:", "deletes the copy on this phone. The theme stays on your server. You confirm first."),
        )),
        "settings" to ("Settings" to listOf(
            HelpLine(HelpKind.P1, "", "Settings are split into pages. Pick one from the Settings menu."),
            HelpLine(HelpKind.H4, "", "Pages"),
            HelpLine(HelpKind.P1, "Connection:", "Home Assistant, MQTT and the stream address. Shown when Sonorium runs outside Home Assistant."),
            HelpLine(HelpKind.P1, "Audio Settings:", "crossfade, default volume and output gain."),
            HelpLine(HelpKind.P1, "Floors & Areas:", "where your speakers are."),
            HelpLine(HelpKind.P1, "Speakers:", "every speaker, with its area, volume offset and on/off switch."),
            HelpLine(HelpKind.P1, "Speaker Groups:", "saved sets of speakers for channels."),
            HelpLine(HelpKind.P1, "Plugins:", "extras that add features."),
            HelpLine(HelpKind.P1, "Logs:", "recent messages from Sonorium."),
            HelpLine(HelpKind.P1, "Users & devices:", "who can sign in, and their phones and other devices. Shown to admins while the Users plugin is on."),
            HelpLine(HelpKind.P1, "Keys:", "keys that let devices and programs use Sonorium. Shown to admins while the Integration plugin is on."),
            HelpLine(HelpKind.P1, "Advanced:", "features that are off by default. Shown on installs that have them."),
        )),
        "settings-connection" to ("Connection" to listOf(
            HelpLine(HelpKind.P1, "", "Every system Sonorium connects to: Home Assistant and your MQTT broker, and the ones plugins add (marked Plugin)."),
            HelpLine(HelpKind.P1, "Status:", "Connected, Not connected or Not configured."),
            HelpLine(HelpKind.P1, "Points at:", "the address it uses."),
            HelpLine(HelpKind.P1, "Test:", "tries the saved values."),
            HelpLine(HelpKind.P1, "Edit:", "opens its values. Test there tries what you typed before it is saved. A saved token or password stays hidden: leave the field blank to keep it."),
            HelpLine(HelpKind.P1, "Save:", "Home Assistant and MQTT restart Sonorium to use the new values (you confirm first; playing channels stop for a few seconds). A plugin connection is saved at once."),
            HelpLine(HelpKind.P1, "⋯ › Remove:", "removes the connection and its saved values. Removing Home Assistant takes its floors, areas and speakers out of every list."),
        )),
        "settings-audio" to ("Audio Settings" to listOf(
            HelpLine(HelpKind.P1, "", "Sound settings for all of Sonorium. Move a slider, then Save settings."),
            HelpLine(HelpKind.H4, "", "Settings"),
            HelpLine(HelpKind.P1, "Crossfade:", "how long the fade lasts, in seconds, when a channel changes theme."),
            HelpLine(HelpKind.P1, "Default volume:", "the starting volume of a new channel."),
            HelpLine(HelpKind.P1, "Master output gain:", "scales the volume of every stream."),
            HelpLine(HelpKind.P1, "Default:", "the value out of the box. A dot marks a change that is not saved yet."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Save settings:", "saves the changes."),
            HelpLine(HelpKind.P1, "Cancel:", "puts back the saved values."),
            HelpLine(HelpKind.P1, "⋯ › Reset to defaults:", "moves every slider back to its default. Save settings keeps it."),
        )),
        "settings-spaces" to ("Floors & Areas" to listOf(
            HelpLine(HelpKind.P1, "", "Floors and areas say where your speakers are. Speaker lists across Sonorium are grouped by them."),
            HelpLine(HelpKind.H4, "", "The list"),
            HelpLine(HelpKind.P1, "Floors:", "each floor lists its areas. No floor holds the areas without one."),
            HelpLine(HelpKind.P1, "Badges:", "Home Assistant or Sonorium shows where a floor or area comes from. A floor or area with the same name in both becomes one."),
            HelpLine(HelpKind.P1, "Speakers:", "how many speakers are in the area."),
            HelpLine(HelpKind.P1, "From Home Assistant:", "change these in Home Assistant. They are read-only here."),
            HelpLine(HelpKind.H4, "", "Your own floors and areas"),
            HelpLine(HelpKind.P1, "", "On installs that allow it, Add floor and Add area show in the top bar."),
            HelpLine(HelpKind.P1, "Add floor, Add area:", "makes a new one. An area can go on a floor."),
            HelpLine(HelpKind.P1, "Area name:", "type a new name for an area you added."),
            HelpLine(HelpKind.P1, "Floor:", "moves the area to another floor, or to No floor."),
            HelpLine(HelpKind.P1, "Pencil:", "renames a floor you added."),
            HelpLine(HelpKind.P1, "Trash:", "deletes it. The areas of a deleted floor stay, without a floor. The speakers of a deleted area move to No area."),
        )),
        "settings-speakers" to ("Speakers" to listOf(
            HelpLine(HelpKind.P1, "", "Every speaker Sonorium has found, grouped by floor and area."),
            HelpLine(HelpKind.H4, "", "Toolbar"),
            HelpLine(HelpKind.P1, "Search:", "looks in speaker names and addresses."),
            HelpLine(HelpKind.P1, "All, Home Assistant, Discovered, Manual:", "shows the speakers from one source. Shown when speakers come from more than one place."),
            HelpLine(HelpKind.P1, "Hide offline:", "hides speakers that are not reachable. It changes this list only."),
            HelpLine(HelpKind.P1, "Rescan network:", "looks for speakers again. Without network speakers the button is Refresh from HA. The text next to it shows the last scan and how many speakers it found."),
            HelpLine(HelpKind.H4, "", "Each speaker"),
            HelpLine(HelpKind.P1, "Dot:", "green when the speaker is online."),
            HelpLine(HelpKind.P1, "Name:", "tap to rename. An empty name goes back to the speaker's own name."),
            HelpLine(HelpKind.P1, "Area:", "the area the speaker belongs to in Sonorium."),
            HelpLine(HelpKind.P1, "Offset:", "plays this speaker louder or quieter than the channel volume, from -20% to +20%."),
            HelpLine(HelpKind.P1, "Test:", "plays a short test sound on the speaker."),
            HelpLine(HelpKind.P1, "Use:", "turns the speaker on or off in Sonorium. Only speakers in use can be picked for a channel."),
            HelpLine(HelpKind.P1, "⋯:", "Rename, Reset name, Play via and Remove. A dot on ⋯ means the name or Play via was changed."),
            HelpLine(HelpKind.P1, "Play via:", "for a speaker Sonorium reaches in more than one way, picks Home Assistant or the direct connection."),
            HelpLine(HelpKind.P1, "Remove:", "removes a speaker added by its address. You confirm first."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Count:", "speakers in use / all speakers."),
            HelpLine(HelpKind.P1, "Add speaker:", "adds a speaker by its IP address or network name. Check connection tests it first. Shown when network speakers are on."),
        )),
        "settings-groups" to ("Speaker Groups" to listOf(
            HelpLine(HelpKind.P1, "", "A speaker group is a saved set of speakers. Pick it for a channel instead of picking speakers one by one."),
            HelpLine(HelpKind.H4, "", "Each group"),
            HelpLine(HelpKind.P1, "Includes:", "the floors, areas and speakers in the group. A crossed-out chip is left out."),
            HelpLine(HelpKind.P1, "Speakers:", "how many speakers the group plays on now."),
            HelpLine(HelpKind.P1, "Arrow:", "shows each speaker in the group and where it comes from."),
            HelpLine(HelpKind.P1, "Edit:", "changes the name and what the group includes."),
            HelpLine(HelpKind.P1, "⋯ › Delete:", "removes the group. You confirm first."),
            HelpLine(HelpKind.H4, "", "Adding a group"),
            HelpLine(HelpKind.P1, "Add group:", "opens the editor. Give the group a name and tick floors, areas or speakers."),
            HelpLine(HelpKind.P1, "Floors and areas:", "a ticked floor or area includes every speaker in it, also speakers added there later."),
        )),
        "settings-plugins" to ("Plugins" to listOf(
            HelpLine(HelpKind.P1, "", "Plugins add features to Sonorium."),
            HelpLine(HelpKind.H4, "", "Installed"),
            HelpLine(HelpKind.P1, "On:", "turns a plugin on or off."),
            HelpLine(HelpKind.P1, "Arrow:", "shows the plugin's fields and buttons. Only for a plugin that is on and has options."),
            HelpLine(HelpKind.P1, "Built-in:", "comes with Sonorium. If you uninstall it, the Catalog can install it again."),
            HelpLine(HelpKind.P1, "⋯ › Uninstall:", "removes the plugin files. You confirm first."),
            HelpLine(HelpKind.H4, "", "Catalog"),
            HelpLine(HelpKind.P1, "Install:", "downloads the plugin from the catalog and installs it."),
            HelpLine(HelpKind.P1, "Update:", "installs the newer version of a plugin you have."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Upload plugin:", "installs a plugin from a .zip file."),
            HelpLine(HelpKind.P1, "⋯ › Plugin requirements:", "what a plugin .zip has to contain."),
        )),
        "settings-logs" to ("Logs" to listOf(
            HelpLine(HelpKind.P1, "", "Recent messages from Sonorium. New lines show up every few seconds."),
            HelpLine(HelpKind.H4, "", "Finding lines"),
            HelpLine(HelpKind.P1, "Search:", "shows the lines that contain the text."),
            HelpLine(HelpKind.P1, "All, Info, Warnings, Errors:", "the lowest level shown. Warnings shows warnings and errors. All adds debug lines."),
            HelpLine(HelpKind.P1, "Playback:", "only what the channels play: sequence steps, transitions, tracks fading in and out, the mix level."),
            HelpLine(HelpKind.P1, "Version line:", "the Sonorium version, install type and log level."),
            HelpLine(HelpKind.P1, "Scrolling:", "the list follows new lines until you scroll up."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Pause:", "stops the list moving so you can read it. New lines wait and show up on Resume."),
            HelpLine(HelpKind.P1, "Copy:", "copies the lines shown to the clipboard."),
            HelpLine(HelpKind.P1, "Download:", "saves recent log messages as a text file."),
        )),
        "settings-advanced" to ("Advanced" to listOf(
            HelpLine(HelpKind.P1, "", "Features this install has off by default, and the values playback uses."),
            HelpLine(HelpKind.H4, "", "Features"),
            HelpLine(HelpKind.P1, "On:", "turns the feature on or off. A change needs a restart."),
            HelpLine(HelpKind.P1, "Default:", "the setting out of the box."),
            HelpLine(HelpKind.P1, "Restart to apply:", "marks a feature changed since the last restart."),
            HelpLine(HelpKind.P1, "Network speakers:", "adds speakers by IP address, outside Home Assistant."),
            HelpLine(HelpKind.P1, "Plugin packages:", "lets plugins install the Python packages they ask for. Packages come from the internet and run with the same rights as Sonorium."),
            HelpLine(HelpKind.H4, "", "Playback values"),
            HelpLine(HelpKind.P1, "Value:", "used by every theme, unless a track or group sets its own. Saved as you change it; playing channels follow."),
            HelpLine(HelpKind.P1, "Default:", "the value out of the box. The arrow puts it back."),
            HelpLine(HelpKind.P1, "Green:", "changed from the default."),
            HelpLine(HelpKind.P1, "Amber:", "far outside the usual range. Allowed, but it may not sound right."),
            HelpLine(HelpKind.P1, "Announcements and sounds:", "how announcements, alerts and sound effects play over a channel: the channel gets quieter (ducks), the sound plays, the channel comes back. A stopped channel is started for an announcement and stopped after it."),
            HelpLine(HelpKind.H4, "", "Network"),
            HelpLine(HelpKind.P1, "Stream URL:", "the address speakers are given to reach Sonorium (port 8008). Automatic works out the address itself. A change restarts Sonorium; you confirm first."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Badge:", "the install type."),
            HelpLine(HelpKind.P1, "Restart now:", "restarts Sonorium to apply the changes. Shown after a change. You confirm first."),
        )),
        "status" to ("Status" to listOf(
            HelpLine(HelpKind.P1, "", "What Sonorium is doing now."),
            HelpLine(HelpKind.H4, "", "Overview"),
            HelpLine(HelpKind.P1, "Active Channels:", "channels playing now."),
            HelpLine(HelpKind.P1, "Playing:", "one card per playing channel: its theme, preset or sequence step, where it plays, and its tracks by section. Idle channels are hidden."),
            HelpLine(HelpKind.H4, "", "Tracks"),
            HelpLine(HelpKind.P1, "Light:", "Playing, Fading, Waiting, Paused or Muted."),
            HelpLine(HelpKind.P1, "Mode:", "how the track plays. A group shows how its files play: One at a time, Every file at once or Merry-go-round."),
            HelpLine(HelpKind.P1, "Now:", "what the track is doing, and the time until its next change: the wait, pause or gap the mixer picked."),
            HelpLine(HelpKind.P1, "Level:", "how loud the track plays now. For Ebb & Flow, the shaded band is the range it moves in."),
            HelpLine(HelpKind.P1, "Group:", "counts down to the group's next turn. Which file plays is picked when the gap ends."),
            HelpLine(HelpKind.H4, "", "Top bar"),
            HelpLine(HelpKind.P1, "Server:", "the mix on the server right this second."),
            HelpLine(HelpKind.P1, "As heard:", "an estimate of what you are hearing right now: the mix delayed by the speakers' buffer, the longest one when a channel plays on several."),
        )),
    )
}

/** A help panel (`?`): full screen on a phone, with ×. */
@Composable
fun HelpPanel(title: String, lines: List<HelpLine>, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconBox(SIcons.Close, "Close help", onClose, size = app.sonorium.ui.UiSize.STANDARD)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    lines.forEach { line ->
                        when (line.kind) {
                            HelpKind.H4, HelpKind.H5, HelpKind.H6 -> {
                                Spacer(Modifier.height(if (line.kind == HelpKind.H4) 16.dp else 8.dp))
                                Text(
                                    line.text,
                                    color = if (line.kind == HelpKind.H4) Palette.accent else Palette.text,
                                    fontSize = if (line.kind == HelpKind.H4) 16.sp else 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = if (line.kind == HelpKind.H6) 16.dp else if (line.kind == HelpKind.H5) 8.dp else 0.dp),
                                )
                            }
                            else -> Text(
                                buildAnnotatedString {
                                    if (line.bold.isNotEmpty()) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.text)) { append(line.bold) }
                                        append(" ")
                                    }
                                    append(line.text)
                                },
                                color = Palette.textSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(
                                    start = when (line.kind) { HelpKind.P2 -> 8.dp; HelpKind.P3 -> 16.dp; else -> 0.dp },
                                    top = 4.dp,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
