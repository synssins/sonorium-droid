## What's new in this build

- **The Theme Editor is native** (Themes › **Edit**, and right after Create theme):
  - Details: icon (tap the tile), Name, Description, Categories (type to search or create; tap × to remove).
  - Tracks in their four sections (Unassigned, Background, Intermittent, Ebb & Flow) with groups. Per track: ▶ preview on this phone, Mode, Volume (Ebb & Flow: low and high), Interval / Duration, Mute, and ⋯ (Fade in / out, Rise, Pause between plays, Pause, Gapless, Move to, New group, Reset track, Remove link).
  - Groups: collapse, Gap / Crossfade / Pause between plays, group Volume, Interval / Duration, Mute, ⋯ (Same Rise for every file, Rename…, Upload into, Delete group…). Tap and hold a group's name to rename it.
  - **Preview mix** (the whole theme on this phone), **Upload** (pick audio files), **Add from another theme**, **Reset all**, ⋯ › **Export theme**, **?** help.
  - Presets: pick one to load it, Reload, **New**, **Save preset**, ⋯ › Rename, Set as default, Import, Export, Delete. Sequences menu with the Theme defaults.
  - **Save theme** keeps everything; **Cancel** (or Back) puts the mix back as it was.
- **Sequences are native too:** Sequences › a sequence (or pick it in the preset field, then **Edit**), or **Add sequence…**: name, time on each preset, ramp, At the end, the presets in order (⋯ to move, remove, or open **Advanced…**), ⋯ › Duplicate / Delete sequence….
  - A step's **Advanced…**: **While playing** (this step's own volumes, intervals, mutes, gaps; changed values show green; Revert changes) and **Coming in / Leaving** (Gusts, Swell, Build, Distance, Start fresh, with their settings and Pre-fade). Save saves the sequence.
- **Settings › Connection is native:** Home Assistant, MQTT and plugin connections with their status, **Test**, **Edit** (Save restarts Sonorium for HA/MQTT, after asking), ⋯ › Remove…, and the dashboard-cards line. The Settings menu now follows the web UI's order.
- Each page's **?** button shows its help (the web UI's texts): Channels, Playlists, Themes, Speakers, Speaker Groups, Floors & Areas, Status.

## Please test

1. Install over the previous build. Your servers are still linked.
2. ☰ › Themes › **Edit** on a theme you can change. Move a Volume slider, mute a track, then **Cancel**; open it again: the old values are back.
3. Change a Volume, a group's Gap, a track's Fade in; **Save theme**; open it again: the new values stay.
4. **Preview mix** (plays on the phone only), ▶ on one file.
5. Presets: **New** "Test", pick another preset (it loads), ⋯ › Delete "Test".
6. Rename the theme or add a category, Save theme: the Themes page shows it.
7. **Add from another theme**: link one file, then its ⋯ › Remove link….
8. On a theme with two presets: Sequences › **Add sequence…**, add both presets, Save; open it again, ⋯ on a step › **Advanced…**, change a volume under While playing and turn on Swell under Coming in, Save.
9. Settings › **Connection**: Test on MQTT; Edit MQTT and Cancel (Save restarts Sonorium).

## Not built yet

- The ▶ preview of a single transition in Advanced (the web UI's Web Audio preview).
- Settings › Audio, Plugins, Logs, Advanced (web view).
- Cast to speakers, finding servers on the network.
