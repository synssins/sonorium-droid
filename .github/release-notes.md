## What's new in this build

- **Themes folder:** themes on this device go to a default folder from the start, with no question asked. Settings > Audio > On this device shows where; **Change…** picks another folder and offers to move the themes there, **Use default** goes back.
- **When the themes folder is gone** (removed, or its access withdrawn), the app says so and offers to pick a folder. Saving in the theme editor then keeps the edits and saves them in the new folder.
- **Settings > Audio: On this device** holds the settings for this device (Stop My Channel when switching, volume keys, themes folder).
- **Virtual speaker (demo server):** on a demo Sonorium, a channel playing on the account's speaker plays on this device, until another device of the same account takes it over.
- **Addresses as typed:** an address with https:// and no port uses 443, and playback on this device uses the same address the app reached the server at.
- **Servers behind a reverse proxy** with a public certificate are trusted the normal way; only self-signed certificates are pinned.

## Please test

- A new install: Settings > Audio > On this device shows the default themes folder; Change… moves the themes, Use default goes back.
- Remove the themes folder, then save a theme in the editor: the edits are kept and saved in the folder you pick.
- A server reached by https://name with no port, and one behind a reverse proxy with a public certificate: it links, and Listen on this device plays.
- On a demo Sonorium: play a channel with the account's speaker; it plays here and stops when another device takes it.

### Earlier (alpha32)
- **Channel cards are shorter:** the Recent changes list is gone from the cards.
- **Help on every page on tablets** too, right under the server button.
- Each release also comes as an Android App Bundle for Google Play.
