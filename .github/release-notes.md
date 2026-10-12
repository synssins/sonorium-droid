## What's new in this build

- **Themes on this phone, played on this phone, no server needed:**
  - The app opens without a server (a Connect button on Channels; the headphone button explains what it needs).
  - **Themes › On this phone:** pick a folder for themes once (Music/Sonorium suggested; you can see and copy the theme folders in the Files app), then **Import theme** (a .zip from the server's Export), or **Remove from phone**.
  - **Save to phone** (server Themes › ⋯ on a theme): downloads the theme, checks every file, resumes after a dropped connection, keeps the server's playback settings. **Update on phone** shows when the server's copy changed.
  - **The headphone button › On this phone:** pick a theme and preset, volume, **Play**. The same engine as the server mixes it on the phone; it keeps playing with the screen off (media notification).

## Please test

- With no server reachable (Wi-Fi off): open the app, ☰ › Themes › On this phone › Import theme (a zip exported from your server), then the headphone button › On this phone › pick it › Play.
- With your server: Themes › ⋯ › **Save to phone** on a theme; it appears under On this phone and plays from there.

### Earlier (alpha13)
- **Touch sizes: 48 dp** (your decision): buttons and icon buttons are 48 dp (small ones 44 dp), the top bar's buttons are all the same 48 dp, collapsed channel rows are 56 dp tall.
- **Leave on a linked channel** now shows "Left the link" with **Rejoin**.
- **Play a playlist** opens the playlist menu at once.
- **Back with the menu open** just closes the menu.
- Linking with a username and password follows the server's new `accounts` flag.

### Earlier (alpha12)
- **Fixes from the server session's check of alpha11:** New/Edit Channel now saves the theme you pick (it was lost), and preselects the theme's ★ preset; a sequence step's **Advanced… › Save** saves properly; a deleted theme leaves the Themes list at once; **Back** on any page returns to Channels (it used to leave the server); dropdowns use their full width before "…"; log times fit on one line.
- **One size standard for buttons** (shared with the web UI): top-bar and header buttons are all the same height (the headphone button was smaller); small icon buttons are a bit larger; text sizes follow one scale.

- **Plugin pages** (from plugins that add their own pages) are listed in the ☰ menu under **Plugins** and open in the web view.
- **Since alpha10:** Settings pages update when settings or plugins change elsewhere (and after a restart); the Theme Editor's Merry-go-round Crossfade follows the server's allowed range; on Android 8 the Theme Editor no longer pops the keyboard up by itself.

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
- **Settings › Audio Settings is native:** Crossfade, Default volume, Master output gain as a draft; **Save settings** / **Cancel**; ⋯ › Reset to defaults….
- **Settings › Plugins is native:** Installed (on/off, each plugin's form and actions, its settings, ⋯ › Uninstall…) and Catalog (Install / Update), **Upload plugin** (.zip).
- **Settings › Logs is native:** the server's log, updated every 3 s while it shows; search, All / Info / Warnings / Errors / Playback, Pause, Copy, Download.
- **Settings › Advanced is native:** Features (with Restart now), every playback value with its default, reset and unusual-value warnings, the Zones switch, and Network › Stream URL (standalone).
- Each page's **?** button shows its help (the web UI's texts): Channels, Playlists, Themes, Speakers, Speaker Groups, Floors & Areas, Status.

### Tested in alpha13

- Top bar: the menu, server and headphone buttons are the same height. Leave/Rejoin a link (Zones). Play a playlist on a card: the menu opens. Open the ☰ menu, press Back: only the menu closes.

0. New Channel: pick a theme: its ★ preset is chosen; Create: the channel plays that theme. Back from My channel / Themes returns to Channels.

1. Install over the previous build. Your servers are still linked.
2. ☰ › Themes › **Edit** on a theme you can change. Move a Volume slider, mute a track, then **Cancel**; open it again: the old values are back.
3. Change a Volume, a group's Gap, a track's Fade in; **Save theme**; open it again: the new values stay.
4. **Preview mix** (plays on the phone only), ▶ on one file.
5. Presets: **New** "Test", pick another preset (it loads), ⋯ › Delete "Test".
6. Rename the theme or add a category, Save theme: the Themes page shows it.
7. **Add from another theme**: link one file, then its ⋯ › Remove link….
8. On a theme with two presets: Sequences › **Add sequence…**, add both presets, Save; open it again, ⋯ on a step › **Advanced…**, change a volume under While playing and turn on Swell under Coming in, Save.
9. Settings › **Connection**: Test on MQTT; Edit MQTT and Cancel (Save restarts Sonorium).
10. Settings › **Audio Settings**: move Crossfade, Cancel; move it again, Save settings.
11. Settings › **Plugins**: switch a plugin off and on; open the Catalog tab.
12. Settings › **Logs**: play a channel and pick **Playback**; Pause, then Resume.
13. Settings › **Advanced**: change Track fade to 8, then its reset button; turn Zones off and on (if you use them).

## Not built yet

- The ▶ preview of a single transition in Advanced (the web UI's Web Audio preview).
- Users & devices and Keys stay in the web UI (by design).
- Cast to speakers, finding servers on the network.
