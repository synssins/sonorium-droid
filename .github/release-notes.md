## What's new in this build

- **Your preset is kept again:** the preset or sequence you chose comes back after the app is closed fully.
- **Playlists start at once:** picking a playlist plays its first entry right away; the other entries' themes get ready in the background while it plays, and the progress line names the theme being got ready.
- Add from another theme: each track's checkbox is named by its track (for TalkBack).

## Please test

- Your device › My channel: choose a preset, close the app fully, open it again: the preset is still chosen.
- While a sequence plays, pick a playlist whose second entry is a theme you haven't played yet: the first entry plays at once.

### Earlier (alpha22)
- **The first Save to phone works:** after choosing the themes folder, the save waits for the connection to your Sonorium instead of giving up, then downloads the theme you asked for.
- **Picking a playlist while a sequence plays:** the card shows the playlist's first entry at once, the theme already playing isn't got ready again, and the lock screen shows "Entry 1 of n".
- **Steps keep counting:** a sequence's countdown can't freeze any more.
- On your device, the Preview mix button's description no longer mentions speakers.
- Theme exports and downloads carry tidy metadata.

### Earlier (alpha21)
- **Timers keep time on your device:** sequence steps and playlist entries change when their countdown says, also where the sound drains faster than real time.
- **Picking a playlist while a sequence plays:** the sequence stops at once, the card shows the playlist and its entries while its themes get ready, and the lock screen shows the entry's theme and "Entry n of m".
- **Words for the device:** on your device, help and labels describe the device (no speakers, New Channel or Save to phone lines; the mute button says Mute).
- **Your preset is kept:** the preset or sequence you chose for a theme is still chosen after the app restarts.
- The starter themes lost some stale entries that ended up in exports.

### Earlier (alpha20)
- **Previews on your device:** the ▶ on a theme in Themes, Preview mix in the Theme Editor, and the ▶ of a linked file all play from the device itself, while My channel keeps playing.
- **The first Save to phone downloads:** after choosing the themes folder, the theme you asked for is saved (it could be dropped before).
- My channel on the device refreshes when something changes, not every second.

### Earlier (alpha19)
- **Your device in the Servers list:** this phone or tablet shows up in the list (with a device icon) next to your Sonorium. Choose it and the whole app works with the device's own themes, presets, sequences and playlists, played on the device; choose your Sonorium to go back. Nothing is copied between them (Save to phone and Update on phone still bring a theme down).
- **The same screens either way:** Channels, My channel, Playlists, Themes and the Theme Editor look and work as they do with your Sonorium. My channel on the device is a channel card like any other: Theme or Playlist, preset or sequence with its step and Next, volume and mute, recent changes, and Cast.
- **Playlists on the device:** make, edit, duplicate and delete playlists from the themes on the device, and play them in My channel.
- **Menus follow what's there:** pages that only make sense on a Sonorium (Speakers, Speaker Groups, Floors & Areas, Advanced, Logs, Plugins, Connection, Status) don't show while the device is chosen.
- **The whole Theme Editor on the device:** Upload, groups, moving tracks, Add from another theme (linked files play too), Remove link and Export, as with your Sonorium.
- **Audio Settings on the device:** crossfade between themes, default volume and master gain.
- **Mix** on every channel card: the Theme Editor on the theme that's playing.

### Earlier (alpha18)
- Starter themes show their presets before the first Play; the first Save to phone adds the starter themes once; starter copies offer Update on phone; the lock screen follows at once; "update ready" without a restart; the notifications permission is asked at the first Play; more of the Theme Editor on the phone.

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
