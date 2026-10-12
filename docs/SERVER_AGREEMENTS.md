# Agreements with the Sonorium server

What the app and the server (synssins/sonorium, branch `fixes`) agreed beyond
`docs/APP_API.md` in the server repo. The API reference stays the source of
truth for anything built; this file records plans and decisions.

## Web UI host hooks (`window.SONORIUM_HOST`, host_api 1)

| Hook | Status | Notes |
|---|---|---|
| `baseUrl`, `streamBase`, `authHeader`, `unlinked`, `on` | built | APP_API.md 12.2.2 |
| `listen`, `stopListening`, `setListenVolume`, `on('listening')` | built | Native player: `ListenService` |
| `showServers()` | app: built | Opens the app's Servers screen |
| `server = {id, name, address}` | app: built | `name` is null when the user hasn't named the server; the page shows `address` |
| Server button (top bar) and Settings › Servers | server: agreed | Both carry `data-host="servers"`; the app then stops adding its own Servers menu item |

## Phone landscape menu

The phone layout in landscape opens ☰ as a side panel `min(320px, 85vw)`
wide, not the full width (server side).

## Personal channel (planned, after server 1.6.2)

- One hidden channel per account, shared by all of that account's devices;
  local only (never speakers). Routes: `GET/PUT /api/me/channel`,
  `POST/DELETE /api/me/channel/listen`; `/api/info.personal_channel: true` when
  supported (hide it otherwise).
- The page passes `personal: true` in `listen()`'s info. The app then uses
  `/api/me/channel/listen` (not `/api/sessions/{id}/listen`) to re-listen and
  to stop. `listening` events use the page's id for the personal channel.
- Listening is counted per device (by token): DELETE removes only the calling
  device; mixing stops when the last device leaves; the 15 s release after a
  closed stream is per device.
- Each device's volume stays local (`setListenVolume`).
- The web UI shows it as a headphone entry at the top of Channels, only when
  `SONORIUM_HOST` exists and `personal_channel` is true.

## Pro (offline)

The app serves the same API subset on the loopback `baseUrl` for the bundled
copy of the web UI (APP_API.md 12.3/12.4), not a native view. The personal
channel view uses only self-contained `/api/me/channel*` routes (exact list
with the server build). Engine (Kotlin mixer or embedded Python): open,
battery benchmark first.

## Announcements

The server mixes announcements into the stream (per-channel switch,
`PUT /api/sessions/{id}/announcements`, on by default; Home Assistant, MQTT,
`POST /api/announce`, plugins). The app has no announcement handling: no
per-device setting, no separate clips. Settings › Notifications is removed on
the server; don't link to it or test it.

## CI

The device test uses `ghcr.io/synssins/sonorium:dev`, which only the server's
`fixes` branch publishes (other branches publish `:branch-<name>`).
