## What's new in this build

- **Servers screen:** each server shows its name (or its address when unnamed) and **Connected** on the one you're using. **Rename** gives it a name on this phone; **Remove** asks first, then unlinks the phone on the server and forgets it here.
- **Listen here plays in the background:** the channel card's 🎧 Listen button now plays through the app's own player. It keeps playing with the screen off or in another app, with controls on the lock screen and in the notification. Pause there stops listening; Play starts it again. Unplugging headphones pauses. If Wi-Fi drops, it reconnects.
- The card's listen volume slider sets the phone's volume for that channel.
- Android 13+ asks once to allow notifications (for the playback controls).
- **Menu fix:** the ☰ menu button opens the menu; before, it only dimmed the screen. The app's web view told the page its screen height was 0, so the menu (and the sidebar on wide screens) had no height.
- **Old phones:** if the phone's Android System WebView is too old for the web UI (older than version 106), the app says so and offers **Update**, which opens it in the Play Store.
- The server's web UI opens inside the app (since alpha4).

## What should work

- **Linking:** scan a Link App code with the phone's camera or **Scan Link App code**, or **Enter address** with your username and password (check the four words).
- **Server list:** tap a server to open it. Starting the app opens the server used last. Back returns to the list. **Forget** removes a server from the phone.
- **The web UI, everything it does in a browser:** channels, themes, presets, playlists, speakers, settings. Phone layout in portrait; desktop layout with the sidebar on wide screens.
- **Unlinking:** if an admin removes the phone in Users & devices, the app says so and goes back to the list.
- **Crash report:** if the app closes unexpectedly, the next start shows the error with **Copy**.

## Please test

1. Install over the previous build. The servers you linked are still there.
2. Open a server. Tap ☰: the menu opens below the top bar. Tap a page: it opens and the menu closes.
3. Rotate to landscape: wide screens show the sidebar with every menu item.
4. Channels: play, stop, change the theme, preset and volume. The server's web UI in a browser shows the same changes.
5. Leave the app and come back: the page is still there and up to date.
6. Tablet: open a server and check both orientations.
7. ☰ › **Servers**: the server list opens; tap another server to switch.
8. Listen: tap 🎧 on a channel. Sound plays on the phone. Turn the screen off: it keeps playing. Use Pause and Play on the lock screen: the card's button follows. Change the listen slider. Walk out of Wi-Fi range and back: it reconnects.
9. Servers screen: **Rename** a server, then open it; **Remove** one (you'll need a new code to link it again). Removed phones disappear from Settings › Users & devices on the server.

## Not built yet

- Cast, finding servers on the network, downloads (theme export, logs) and theme uploads from the phone.

## Known issues

- In landscape on phones the web UI uses the desktop layout and doesn't fit the height. This is being fixed in the web UI.
