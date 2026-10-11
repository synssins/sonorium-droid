# Server-side notes for the Sonorium Android app

From the Sonorium server session, 11 October 2026. These are decisions not found in `docs/APP_API.md`.

## 1. API reference

Everything the app calls is in **github.com/synssins/sonorium**, branch `fixes`: `docs/APP_API.md`. See also `docs/AUTH.md` and `docs/UI_STYLE.md` in that repo.

**The app shows exactly the server's web UI** (APP_API.md section 12, a requirement from Chris).
- **Free:** the UI calls a Sonorium server.
- **Pro:** it calls a server when one is found, or runs offline against the on-phone engine.
- **Native pieces:** media session and lock screen, Cast picker, QR scanner, biometric prompt, Play Billing, discovery and pairing.

## 2. Link App QR

The QR is `https://sonorium.app/link#v=1&id=…&n=…&h=…&p=…&s=…&fp=…&c=…[&u=…]`.
- The parameters are after the `#`.
- `h` lists every LAN address of the server, plus `sonorium.local` when it's announced.
- Also accept the older `sonorium://pair?…` form.
- Pin the certificate SHA-256 from `fp`, and talk HTTPS on port `s` (8808).
- Audio streams stay plain HTTP on port `p` (8008).

## 3. App Links (scanning with the camera opens the app)

**In the app:**
- An intent filter with `android:autoVerify="true"`, scheme `https`, host `sonorium.app`, pathPrefix `/link`.
- The app reads the URI fragment.

**Signing key:**
- Make one release keystore now and sign the sideloaded builds with it, not the debug key. Back it up outside git.
- Give Chris the package name and the SHA-256 fingerprint, from `keytool -list -v -keystore <file> -alias <alias>` or `./gradlew signingReport`.
- The server session then publishes `sonorium.app/.well-known/assetlinks.json`.
- Play App Signing later adds Google's fingerprint to the same file.

**Check:** `adb shell pm get-app-links <package>` shows `sonorium.app: verified`.

## 4. Privacy policy

Published at **https://sonorium.app/privacy/**. Use this URL in the Play Console.
- Contact and publisher email: sonorium.app@gmail.com. Developer name: Synssins.

**What the policy promises:**
- No analytics, crash-reporting, ads or tracking SDKs, and no developer backend.
- Permissions are limited to:
  - network and Wi-Fi;
  - nearby devices;
  - notifications;
  - foreground media playback;
  - the camera, only to scan the QR (no camera permission at all if Google's code scanner is used);
  - the biometric or screen-lock check.
- No location, contacts, microphone or file access.

A new permission or SDK means the policy changes first.

**Data safety form:** collects nothing, shares nothing, encrypted in transit.

## 5. Pro (one-time in-app product)

- On every start, `queryPurchasesAsync` restores Pro (same Google account, any phone).
- Cache the entitlement so Pro works offline.
- A "Restore purchase" button goes in Settings.
- Acknowledge every purchase within 3 days, or Google refunds it.
- Downloaded themes don't come back after a reinstall; offer to download them again.

## 6. Recovery sign-in code

Use the phone's lock or biometric prompt if the phone has one. Otherwise show the code anyway.

## 7. Testing rules

- **Speakers:** cast or play audio ONLY to the Nest Hub (`media_player.office_display`, 192.168.10.249) or the Office_C97A (Arylic), at 25% volume or less. The other speakers are in bedrooms.
- **Test server:** the NAS at 192.168.1.20 (Docker `:dev`) has Require login on. Chris links the phone with the Link App QR from its web UI.
- **Chris's Home Assistant add-on is the control:** don't test against it.

## 8. Writing and UI rules

- Plain, technical-doc language.
- No emphasizing qualifiers at the end of sentences ("…and nothing else").
- Use Chris's wording verbatim when he gives it.
- UI changes go through design boards first.

## 9. Server gaps

The gaps listed at the end of `APP_API.md` are being built on the server now:
- G1 `/api/devices/self`
- G2 cast output reporting
- G3 listener hold
- G4 `boot_id`
- G6 speaker volume and mute
- G13
- G14 the `SONORIUM_HOST` hook

Check `docs/APP_API.md` on `fixes` for their final shapes as they land.
