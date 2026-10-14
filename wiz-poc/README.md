# WiZ Light Lab (proof of concept)

A small local web UI for Philips WiZ bulbs. It covers the same features as
[myselfshravan/wiz-hack](https://github.com/myselfshravan/wiz-hack), minus the stock ticker:

| Tab | What it does |
|---|---|
| **Lights** | **Names and rooms** for each bulb, colour, on/off and **Identify** (blinks the bulb, then restores it) for each bulb or a whole room, ping (shows on/off state and Wi-Fi RSSI), and **bulb discovery that works across subnets and VLANs** |
| **Live audio** | Drives the bulbs from **what the PC is playing** (WASAPI loopback), a **microphone**, or a built-in **test signal** |
| **Music file** | Plays an MP3/WAV/FLAC/OGG in the page and syncs the bulbs to it |
| **Video ambilight** | Plays a video in the page and colours the bulbs from it: dominant colour (k-means), edges (each bulb gets its own zone around the screen), average, or hybrid (colour from the video, brightness from its soundtrack) |
| **Tuning** | Effect mode, sensitivity, brightness boost, smoothing, updates/sec, min/max brightness |

Audio effect modes: `frequency_bands` (bass=red, mid=green, treble=blue), `energy`, `rainbow`
(colour follows the dominant pitch), `multi` (each bulb shows one band), `pulse`, `strobe`,
`spectrum_pulse`, plus two extras: `chase` and `split`.

## Run it on Windows

1. Install Python 3.10 or newer from python.org. Tick **Add python.exe to PATH** during setup.
2. Double-click **`run.bat`**. The first run creates a `.venv` and installs `numpy` and `PyAudioWPatch`.
   After that, it starts the server and opens http://127.0.0.1:8765.
3. On the **Lights** tab, click **Find bulbs**, which is described below. Or open **Add bulbs by IP** and paste IPs, one per line.

## Names, rooms and identifying bulbs

* **Identify** blinks a bulb (or every bulb in a room) between bright cold white and dim warm white for about 3 seconds.
  Then it puts the bulb back exactly as it was: same colour, white temperature, scene, brightness, or off.
  The button also appears in the **Find bulbs** results, so you can identify a bulb, type its name and room, and add it in one go.
* Give each bulb a **name** and a **room**. The room box suggests rooms you already have. The bulb table is grouped
  by room, and each room has its own colour, on/off and identify controls.
* The **Target** chips at the top pick which rooms the main colour picker, On/Off and all effects (live audio,
  music, video) drive. **All** means every bulb. You can select several rooms at once.
* If a bulb gets a new IP from DHCP, discovery shows it as *moved*. When you add it, its name and room move with it.

To set the bulbs from the command line instead, run `run.bat 192.168.1.50 192.168.1.51` or `python app.py <ips...>`.

Settings are saved to `config.json` next to `app.py`. You can also edit that file by hand. It holds
`http_host` and `http_port`, which default to localhost only.

## Finding bulbs, including on other VLANs

WiZ discovery doesn't use mDNS. A bulb answers a `getPilot` UDP packet on port 38899, sent by broadcast or straight to its IP.
**Find bulbs** does three things:

1. **Broadcasts out of every network adapter on the PC.** It sends to both `255.255.255.255` and the adapter's /24
   broadcast address. On Windows, `255.255.255.255` otherwise only leaves through one adapter.
2. **Probes the subnets you list, one host at a time**, for example `192.168.20.0/24`. Broadcasts stop at the router,
   but unicast gets routed, so this is what finds bulbs on other VLANs. A /24 takes about 3 seconds.
   You can list up to 8192 hosts in total.
   The **Also scan this PC's own subnets** box does the same for the PC's own /24s. That helps on Wi-Fi that filters broadcasts.
3. **Asks each bulb that answers for its model and firmware** (`getSystemConfig`). Colour bulbs are ticked
   automatically. Plugs and white-only bulbs are listed but left unticked.

The app remembers each bulb's MAC address. If DHCP gives a bulb a new IP, discovery marks it **moved from x.x.x.x**,
and **Add selected** swaps the new IP in place.

For routed VLANs, the firewall between them has to allow **UDP from the PC to the bulbs on port 38899, and the replies
coming back**. A stateful firewall rule from the PC's VLAN to the IoT VLAN usually covers both. Even after the bulbs
are found, you need that rule for controlling them too. The `broadcast` setting in `config.json` adds one more
broadcast address, which only helps if your router forwards directed broadcasts.

## How it works

* WiZ bulbs take JSON over **UDP port 38899** with no authentication. The app sends
  `{"method":"setPilot","params":{"r":..,"g":..,"b":..,"dimming":10-100}}`, and uses `getPilot` for discovery and ping.
* **Live audio:** PyAudioWPatch captures the default output device in loopback. You don't need "Stereo Mix" enabled.
  An FFT splits the signal into bass (20–250 Hz), mid (250–4000 Hz), and treble (4–20 kHz). An auto-gain keeps
  both quiet and loud tracks using the full brightness range. A simple bass-onset detector finds beats.
* **Music and video:** the browser decodes and plays the file. It runs the same band analysis with the Web Audio API,
  and samples video frames on a 64×36 canvas. It posts the results to the server about 20 times a second, and the
  server drives the bulbs. Because of this, you don't need ffmpeg or OpenCV.
* The app skips sending a bulb the same command twice in a row, but repeats it every second in case a UDP packet was dropped.

## Tips

* Bulbs fade brightness changes themselves, so very fast effects look softer than the meters. **Strobe** and
  **Pulse** with low smoothing hit the hardest. Try **Updates / sec** between 10 and 30. Some bulbs drop packets above that.
* **Brightness method → Scale RGB** keeps `dimming` fixed and scales the colour values instead. Depending on the
  firmware this can react faster, or have no effect at all. It's there so you can compare.
* The server only listens on `127.0.0.1` by default. Don't expose it beyond your LAN, because the bulbs have no auth.

## HTTP API (for plugins and scripts)

Everything the page does goes through a small JSON API on the same port. Send `POST` requests with a JSON body.
Requests from another web page's origin are refused, but local programs and scripts can call it freely.

| Method and path | Body | Does |
|---|---|---|
| `GET /api/config` | | Full config, including bulbs, `bulb_meta` (names and rooms) and `active_groups` |
| `POST /api/config` | any config keys | Update settings, for example `{"mode":"strobe"}` or `{"active_groups":["Kitchen"]}` |
| `POST /api/color` | `{"color":"#ff0000","brightness":80}` | Colour the target rooms, unless an effect is running |
| `POST /api/power` | `{"on":true}` | Turn the target rooms on or off |
| `POST /api/group` | `{"group":"Kitchen","on"?:bool,"color"?:"#rrggbb","brightness"?:n}` | Control one room |
| `POST /api/light` | `{"ip":"…","on"?:bool,"color"?:"#rrggbb","brightness"?:n}` | Control one bulb |
| `POST /api/identify` | `{"ips":["…"]}` or `{"group":"Kitchen"}` | Blink the bulbs, then restore them |
| `POST /api/discover` | `{"scan_subnets"?:[…],"scan_local"?:bool}` | Find devices: IP, MAC, model, firmware, and whether each is new, configured or moved |
| `POST /api/bulbs/add` | `{"devices":[{"ip","mac"?,"was"?,"name"?,"group"?}]}` | Add bulbs, or move a bulb's IP |
| `POST /api/bulbs/meta` | `{"ip","name"?,"group"?}` | Rename a bulb or change its room |
| `POST /api/bulbs/remove` | `{"ip"}` | Remove a bulb |
| `POST /api/ping` | | `getPilot` reply from every configured bulb |
| `POST /api/engine/start` | `{"source":"loopback"\|"mic"\|"test"\|"browser:<label>"}` | Start an effect source |
| `POST /api/engine/stop` | | Stop the running effect |
| `POST /api/frame` | `{"raw"?:{bass,mid,treble,level,dominant},"colors"?:[[r,g,b],…]}` | Feed the `browser` source. A plugin can stream its own audio analysis or colours this way |
| `GET /api/status` | | Engine state, live band levels and beat |
