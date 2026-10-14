# WiZ Light Lab (proof of concept)

A small local web UI for Philips WiZ bulbs. It covers the same features as
[myselfshravan/wiz-hack](https://github.com/myselfshravan/wiz-hack), minus the stock ticker:

| Tab | What it does |
|---|---|
| **Lights** | Colour picker and brightness for all bulbs, on/off for all bulbs, LAN discovery, ping (shows on/off state and Wi-Fi RSSI), and a colour and on/off control for each bulb |
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
3. Paste your bulb IPs on the **Lights** tab, one per line, and click **Save**. You can also click **Discover on LAN**.

To set the bulbs from the command line instead, run `run.bat 192.168.1.50 192.168.1.51` or `python app.py <ips...>`.

Settings are saved to `config.json` next to `app.py`. You can also edit that file by hand. It holds
`http_host` and `http_port`, which default to localhost only, and `broadcast`, the discovery address.
If `255.255.255.255` finds nothing, set `broadcast` to your subnet's broadcast address, for example `192.168.1.255`.

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
