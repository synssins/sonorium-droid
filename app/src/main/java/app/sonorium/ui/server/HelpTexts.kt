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

/** The help panels' texts, verbatim from the web UI's `index.html` (`#te-help`, `#tx-help`). */
object HelpTexts {
    val THEME_EDITOR = listOf(
        HelpLine(HelpKind.P1, "", "A theme is a set of tracks (sound files) mixed together. This help follows the editor from top to bottom."),
        HelpLine(HelpKind.H4, "", "Tracks"),
        HelpLine(HelpKind.P1, "Sequences:", "Lists this theme's sequences. Pick one to edit it, or Add sequence."),
        HelpLine(HelpKind.P1, "Preview mix:", "Plays the mix in this browser."),
        HelpLine(HelpKind.P1, "Upload:", "Uploads to Unassigned, or to the group you pick."),
        HelpLine(HelpKind.P1, "Add from another theme:", "Links files from another theme."),
        HelpLine(HelpKind.P1, "Reset all:", "Resets every track and group to defaults."),
        HelpLine(HelpKind.H4, "", "Sections"),
        HelpLine(HelpKind.P1, "Sections:", "tracks sit in sections by how they play: Background, Intermittent and Ebb & Flow. Every section is always there, even empty. Change a track's Mode, or drag it to another section or into a group in one, and it moves there and plays that way. A track in a group plays its group's way: drag it out to change it."),
        HelpLine(HelpKind.P1, "Groups:", "a group belongs to its section and plays that section's way: Background groups are Merry-go-round, Intermittent groups take turns, Ebb & Flow groups play every file at once. Make one with the section's New group."),
        HelpLine(HelpKind.P1, "Moving tracks:", "drag a track onto a group, or use its ⋯ menu."),
        HelpLine(HelpKind.H4, "", "Unassigned"),
        HelpLine(HelpKind.P1, "Unassigned:", "uploads and files added from another theme start here. Nothing here plays. Pick each file's Mode, or drag it into a section or a group."),
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
        HelpLine(HelpKind.P1, "Rename:", "also by double-clicking the group name."),
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
        HelpLine(HelpKind.P2, "Order:", "drag a preset, or use its ⋯ menu."),
        HelpLine(HelpKind.P2, "Theme defaults:", "empty fields use the theme's time and ramp, set at the bottom of the Sequences menu."),
        HelpLine(HelpKind.P2, "Save:", "saves the sequence at once. Every channel playing this theme can pick it."),
        HelpLine(HelpKind.H5, "", "A step: ⋯ › Advanced"),
        HelpLine(HelpKind.P2, "While playing:", "changes how tracks play while this step plays, for this sequence only. The same controls as the Theme Editor. A value you change shows in green; anything you don't change follows the preset."),
        HelpLine(HelpKind.P2, "Revert changes:", "puts back the settings as they were when Save was last pressed."),
        HelpLine(HelpKind.P2, "Coming in / Leaving:", "tracks this step turns on or makes louder, and tracks the next step turns off or makes quieter."),
        HelpLine(HelpKind.H6, "", "Coming in and Leaving"),
        HelpLine(HelpKind.P3, "Smooth:", "no button on. The track follows the sequence's ramp."),
        HelpLine(HelpKind.P3, "Buttons:", "click to turn a style on, click again to turn it off. Each style that's on shows its own setting under Setting."),
        HelpLine(HelpKind.P3, "Pre-fade:", "starts the change this many seconds before the step."),
        HelpLine(HelpKind.P3, "Gusts / Gusts dying down:", "rises or falls in uneven surges. Intensity sets how rough. Max volume caps the peaks at the preset volume."),
        HelpLine(HelpKind.P3, "Swell / Smooth fade:", "one curved fade. Speed sets how much of the fade time (pre-fade plus ramp) it uses."),
        HelpLine(HelpKind.P3, "Build / Thin out:", "Intermittent tracks play more often and louder over the fade, or less often and quieter. Amount sets how much."),
        HelpLine(HelpKind.P3, "Distance / Recede:", "starts faint and muffled, like far-off thunder, and ends clear at the preset volume; or drifts away. How far sets the distance."),
        HelpLine(HelpKind.P3, "Start fresh / Finish then stop:", "plays from the start of the file with a short fade, for music, voices and rhythm; or lets the current play finish, then stops."),
        HelpLine(HelpKind.P3, "Preview:", "▶ plays that track's change on this device and follows your edits."),
        HelpLine(HelpKind.P3, "File fades:", "a file's own Fade in and Fade out apply on top of every style."),
        HelpLine(HelpKind.P3, "Combining styles:", "several styles can be on together. They all work over the same fade time (pre-fade plus ramp). For example:"),
        HelpLine(HelpKind.P3, "Gusts + Distance:", "wind starts far off and muffled, and surges unevenly as it comes closer."),
        HelpLine(HelpKind.P3, "Gusts + Swell:", "the surges ride on one curved rise instead of a steady one."),
        HelpLine(HelpKind.P3, "Gusts + Start fresh:", "the file plays from its start and rises in uneven surges."),
        HelpLine(HelpKind.P3, "Build + Distance:", "thunder comes more often and louder as it moves from far off to close."),
        HelpLine(HelpKind.P3, "Thin out + Recede:", "thunder comes less often and quieter as it drifts away."),
        HelpLine(HelpKind.P3, "Smooth fade + Finish then stop:", "fades out over the fade time; if the current play ends first, it stops there with a short fade."),
        HelpLine(HelpKind.H4, "", "Presets and saving"),
        HelpLine(HelpKind.P1, "Live:", "changes are heard at once with Preview mix, and on channels playing this theme with no preset. A channel playing a preset hears them after Save Preset."),
        HelpLine(HelpKind.P1, "Preset:", "the loaded preset. Current settings is the theme without one."),
        HelpLine(HelpKind.P1, "New:", "makes a new preset from the current mix."),
        HelpLine(HelpKind.P1, "Save Preset:", "Saves loaded preset"),
        HelpLine(HelpKind.P1, "Save Theme:", "Saves theme with all changes (presets, name, etc) Required after saving a preset."),
        HelpLine(HelpKind.P1, "Cancel:", "Reverts unsaved changes."),
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
        HelpLine(HelpKind.P1, "Buttons:", "click to turn one on, click it again to show its setting on the slider, click once more to turn it off. Several can be on together."),
        HelpLine(HelpKind.P1, "Pre-fade:", "starts the change this many seconds before the step."),
        HelpLine(HelpKind.P1, "Gusts / Gusts dying down:", "rises or falls in uneven surges. Intensity sets how rough. Max volume caps the peaks at the preset volume."),
        HelpLine(HelpKind.P1, "Swell / Smooth fade:", "one curved fade. Speed sets how much of the fade time (pre-fade plus ramp) it uses."),
        HelpLine(HelpKind.P1, "Build / Thin out:", "Intermittent tracks play more often and louder over the fade, or less often and quieter. Amount sets how much."),
        HelpLine(HelpKind.P1, "Distance / Recede:", "starts faint and muffled, like far-off thunder, and ends clear at the preset volume; or drifts away. How far sets the distance."),
        HelpLine(HelpKind.P1, "Start fresh / Finish then stop:", "plays from the start of the file with a short fade, for music, voices and rhythm; or lets the current play finish, then stops."),
        HelpLine(HelpKind.P1, "Preview:", "▶ plays that track's change on this device and follows your edits."),
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
                    IconBox(SIcons.Close, "Close help", onClose)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    lines.forEach { line ->
                        when (line.kind) {
                            HelpKind.H4, HelpKind.H5, HelpKind.H6 -> {
                                Spacer(Modifier.height(if (line.kind == HelpKind.H4) 14.dp else 8.dp))
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
