## What's new in this build

- **Adding a server by address:** type just a name or an IP, with or without a port. The app finds the server's HTTPS address by itself and uses it; the Servers list shows the address in use. Only when nothing answers does it say so.
- **Next and Previous on the notification** for a channel heard here go through the server (playlist entry, sequence step, else preset).
- **Wording:** no "own" left in the help.

## Please test

- Servers › Enter address: a bare IP, an IP with the HTTP port (8008), an http:// address. Each links over HTTPS, and the list shows the https:// address.
- An address where no Sonorium answers: one message, "Couldn't reach …".

### Earlier (alpha35)
- **Unlock Pro through Google Play** in release builds: the price comes from Google Play, a purchase waiting for its payment unlocks once it goes through, Restore purchase finds an earlier one, and a refund or a promo code is picked up when the app opens. Test builds still unlock locally and charge nothing.
- **Previous on the lock screen and notification** works for a theme on this device and for a channel heard here: back a playlist entry, a sequence step or a preset (a server channel needs a Sonorium from this release on; older ones step back through presets).
- **Help texts** follow the new wording: "device" instead of "phone", "a server" instead of "your server".
