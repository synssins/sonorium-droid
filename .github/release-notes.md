## What's new in this build

- **Fixed: nothing played on your device in alpha30.** Themes on your device play again.
- **Stop My Channel when switching between server and device** now stops My Channel on the one you leave, also when you switch in the Servers list.
- **Help:** its button sits exactly under the server button on every page and screen size, and My Channel and Backups have help too.
- **First start:** the guide shows on a new install only (not after an update), and leaves out "Add a server" when a server is already linked.
- **Your server's name:** a Sonorium renamed in its settings (Server name) shows under that name in the server button and the Servers list after the next connection; a name you gave it in the app stays. Servers found on your network show their names too.

## Please test

- Play a theme on your device (My Channel): it plays.
- Play My Channel on your device, then choose your Sonorium in the Servers list: your device stops (and the other way round).
- Give your Sonorium a name in its settings (Server name), then open the app: the server button and the Servers list show the new name.

### Earlier (alpha30)
- **Themes play straight away on your device:** theme files are read as they play (no ready-to-play copy any more, no "Getting ready" wait, nothing done in the background). The copies made before are deleted once, which frees their space. Known: on Android 8 this takes much more of the processor than on current Android; Android 8 devices suit controlling a Sonorium's channels best.
- **My Channel:** one playback at a time (listening to a server's My Channel stops your device's, and the other way round). Audio Settings › On this phone: "Stop My Channel when switching between server and device" (on). When your server can't be reached, My Channel offers "Play on this device"; the app tries the server again every minute, and choosing it in the Servers list tries at once. Before the unlock, a line at the bottom of your device's My Channel says what unlocking adds there.
- **Volume:** playback on your device plays at full volume; your phone's volume buttons set how loud. Optional (Audio Settings, on a server): "Control theme playback volume in sync with device volume" makes the volume buttons move the open channel's speakers by 5%.
- **Backups** (Settings › Backups, on a Sonorium that keeps them): back your device up to that server while connected (off until you turn it on): your presets, sequences and track settings of its themes, your own themes and your playlists. Restore a backup onto a device, or delete it.
- **Guides:** a short guide on the first start (Play a theme, Connections, Add a server) and on the first visit of Themes, Playlists, a channel card and the Theme Editor; each page's help can show its guide again.
- **Help** sits at the right of each page's title; the top bar has only the menu, the logo and the server button.
- **Servers found on your network** show in the Servers list, one tap to add; the address field shows https://sonorium.local:8808/ as an example.
- **Widgets:** a channel's Play/Stop (2×1) and a channel with what plays and previous / Play / next (4×2).
- Sync now removes a file the server replaced with another format (x.wav → x.mp3).
- Fixes: the sample theme has no Delete…; deleting on your device says "from this device"; the zip question after an import names the file and deletes it; an import that doesn't happen says why; unlocked without a themes folder, Themes keeps showing the sample with a Choose folder line.
