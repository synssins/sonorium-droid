## What's new in this build

- **More of the Theme Editor on the phone:** **Upload** audio files (into the theme or a group), **new groups**, **rename** a group (tap and hold its name, or ⋯ › Rename…) and **Delete group…**, and **Remove link…** on a linked file. The files change in the theme's own folder, as a server would change them. Still on the server only: adding links to other themes' files, moving a track to another group, Preview mix and Export.
- **Theme details, preset import and a sequence step's tracks** on the phone now use the server's own code, so the files come out exactly as the server writes them. Opening a theme in the editor no longer has to get its audio ready first.
- **Starter themes show their presets** in My channel before they are played for the first time.
- **The first Save to phone works:** after choosing the themes folder, the theme you asked for downloads (it used to be dropped), the starter themes are added once, and the download replaces the starter copy of that theme instead of adding "Tavern (1)" next to it.
- **Lock screen and notification follow at once** when the theme, preset or step changes.
- **"Update ready"** shows on a server theme as soon as the server changes it, without restarting the app.
- **Notifications permission** is asked when something first plays, not on the first screen.
- **Starter themes can be updated from your server:** a server theme whose phone copy is the starter one offers **Update on phone** (⋯), so its presets and sequences come over.
- **Import theme** keeps the zip you picked even when the page changes while Android's file picker is open (the import used to be dropped now and then).
- **My channel opens on the phone half** while a theme plays on the phone.

## Please test

- Themes › On this phone › a theme's **Edit**: **Upload** a file into a group, rename that group (tap and hold its name), then ⋯ › **Delete group…**. Check the folder in the Files app after each step. Close the editor and play the theme.
- On a fresh install: choose the themes folder, then save Tavern from your server: one Tavern only, with your server's presets.
- A starter theme in My channel: its presets show before you press Play.
- Themes › On this phone › Import theme: pick a zip; it's imported every time.

### Earlier (alpha17)
- **Lock screen and notification control** while playing on the phone: artwork, theme, preset or step; Previous / Next through presets or steps; Next theme and Stop.
- **Edit themes on this phone** (Themes › On this phone › Edit, or Mix in My channel); closing the editor updates My channel's lists; **Update on phone** asks first.
- **Save to phone on tablets and foldables** keeps the themes folder picked the first time.

### Earlier (alpha16)
A full QA pass over every screen (code review plus screenshots of every screen on emulators in each connection state), and the fixes:

- **Starter themes:** Tavern, A Rainy Day and Sleigh Ride come with the app; they're added to your themes folder when you choose it.
- **Servers:** removing a server clears it from the main screen at once (it used to stay on the top-bar button with its channels). The server button shows its status: connected, connecting (dot) or can't reach (dot); "Connect" when there's none.
- **No stale information:** with no server, or when it can't be reached, the server's pages say so ("No server connected" / "Can't reach …" with Try again, Servers and Play on this phone) instead of showing old data as if it were live. The app reconnects by itself when the network comes back, also when it was started without a connection.
- **Playing on the phone:** Stop works while a theme is being got ready; removing the playing theme stops it; a failed import no longer blocks trying again; the decoded copies are deleted with the theme and free space is checked first; lock screen and headset Pause pause; plain-language errors; Android 15's audio rules handled (the sound starting late or not at all).
- **Cast:** "No speakers found" after 10 s with Look again; switching speakers doesn't end the new cast; the phone's Wi-Fi address is used.
- **Everywhere:** consistent dialogs (named actions, Cancel first), touch targets of 44–48 dp, sizes and wording, help texts written for a phone, accessibility labels. Floors & Areas' buttons fit on phones. Tapping the playing notification opens My channel.

Tested in alpha16:

- Remove your server (Servers › Remove), press Back: "No server connected", and "Connect" on the top-bar button.
- Wi-Fi off with the app open, then on again: the line clears by itself.
- Choose a themes folder: the three starter themes appear; play one from the headphone button with Wi-Fi off.

### Earlier (alpha15)
- Picking another theme while playing on the phone switches to it; sequences on the phone; Cast to one speaker.

### Earlier (alpha14)
- Themes on this phone (Import, Save to phone, Update on phone), played on this phone with no server; the app opens without a server.

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
