## What's new in this build

- **A channel heard on this device** (Listen, My Channel on a server, the virtual speaker) now shows like a theme played on the device: the theme as the title, the preset or step and the channel under it, the theme's icon as the picture, and the same buttons (Next theme, Stop). Previous and next go through the presets, or to the next step of a sequence or playlist; Next theme changes the channel's theme on the server. The notification follows changes made elsewhere.
- **Help stays in view on small phones:** the title row's buttons make room for it (Create theme and, on the smallest screens, New Channel show as icons).
- **Status bar icons are light** on every screen.
- **Fixed: Move after changing the themes folder** did nothing; the themes now move to the new folder.
- **Fixed: saving an edited theme after its folder went away** into a chosen folder lost the edits; they are saved there now, and Cancel asks before leaving unsaved changes.
- **Audio Settings says when the themes folder can't be reached**, instead of showing it as in use.
- **The address field** no longer autocorrects or capitalises, so http:// stays as typed. Two servers with one name show their ports.
- **Wording:** "device" instead of "phone" and other changes across the app; Advanced settings has an Audio Ducking heading (Amount, Attack, Release).

## Please test

- Listen to a server channel: the notification shows the theme, the preset and channel, and the theme's icon; Next theme and the previous/next buttons change the channel; Stop ends listening here.
- On a demo server, the virtual speaker: the notification shows the playing channel's theme.
- Change the themes folder with themes in it: Move moves them, and the list shows them.
- Edit a theme, remove its folder, Save theme, Choose folder: the edits are saved in the chosen folder.
- A small phone: every page shows its help button under the server button; the status bar icons are readable.

### Earlier (alpha33)
- **Themes folder:** themes on this device go to a default folder from the start, with no question asked. Settings > Audio > On this device shows where; **Change…** picks another folder and offers to move the themes there, **Use default** goes back.
- **When the themes folder is gone** (removed, or its access withdrawn), the app says so and offers to pick a folder. Saving in the theme editor then keeps the edits and saves them in the new folder.
- **Settings > Audio: On this device** holds the settings for this device (Stop My Channel when switching, volume keys, themes folder).
- **Virtual speaker (demo server):** on a demo Sonorium, a channel playing on the account's speaker plays on this device, until another device of the same account takes it over.
- **Addresses as typed:** an address with https:// and no port uses 443, and playback on this device uses the same address the app reached the server at.
- **Servers behind a reverse proxy** with a public certificate are trusted the normal way; only self-signed certificates are pinned.
