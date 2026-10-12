## What's new in this build

- **The app is native.** Tapping a server opens Sonorium's own Android screens, not the web page:
  - **Top bar:** ☰ menu, the Sonorium logo, the green **server button** (the server's name, or its address when unnamed; tap it for the server list) and, when the server supports it, the **headphone button** for your personal channel.
  - **Menu:** a side panel (Playback, Configuration, Settings, System). On large tablets in landscape it stays open as a sidebar.
  - **Channels:** every card control works natively: Play/Stop, Theme, Preset/sequence, playlist mode, Next with the countdown, speaker volume and mute, Listen here with this phone's volume, Recent changes, Edit, Delete. Wide screens show several cards side by side.
  - **Channel editor:** name, Theme or Playlist, preset, volume, Announcements, and the speakers (group, or the floor › area › speaker picker with search).
  - **Live updates:** changes made elsewhere (web UI, Home Assistant, another phone) appear within a second or two.
- **Pages not native yet** (Playlists, Themes, Settings pages, Status) open in the web view at that page. The server button there returns to the server list.
- **My channel** (headphone button): appears once the server has the personal channel.

## Please test

1. Install over the previous build. Your servers are still linked.
2. Open a server: the native Channels screen appears.
3. On a card: change Theme and Preset; Play and Stop (on a channel with speakers); move the volume; mute and unmute the speakers. The web UI in a browser shows the same changes, and changes made in the browser show up in the app.
4. 🎧 on a card: it plays on the phone, keeps playing with the screen off, with lock-screen controls.
5. ✎ Edit a channel: change its name and speakers, save. **New Channel**: create one, then delete it with ⋯ › Delete….
6. ☰ menu: Playlists, Themes, Settings › …, Status open in the web view; Back returns to Channels.
7. Rotate, and try the tablet: the menu is a side panel on phones; several cards per row on wide screens.
8. The server button: the server list opens; open another server.

## Not built yet

- Native Playlists, Themes, Speakers, Groups, Floors & Areas and Status (next), then the Theme Editor and Settings.
- Zones on cards (owners, Kid mode, linked channels) when Zones are switched on.
- Cast to speakers, finding servers on the network, downloads and uploads from the phone.
