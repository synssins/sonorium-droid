# Sonorium Engine Lab

A separate test app (`app.sonorium.enginelab`, not part of the Sonorium app) to measure whether the
phone can mix themes itself: the server's own mixer (`sonorium.engine`, Python, through Chaquopy)
against a minimal Kotlin mixer, on CPU and battery.

- `src/main/python/sonorium/`: the 24 engine files from synssins/sonorium branch `engine-core`
  at `5a637af` (the set listed in shared/engine.md 3), unchanged. Copyright Chris Kliewer,
  PolyForm Noncommercial 1.0.0 (see that repository). Refresh them when the engine changes.
- `src/main/python/engine_theme.py`: the engine tests' generated theme (sine/noise WAVs).
- `src/main/python/lab.py`: the calls the Kotlin side makes.
- Decoding: `app/src/main/java/app/sonorium/audio/PcmDecoder.kt` (shared with the app):
  MediaCodec → 16-bit mono 44100 Hz WAV, laid out as `mirror_lookup` expects (engine.md 5a).
- Python 3.12 + numpy 1.26.2 (Chaquopy 17). arm64 only. CI puts the APK on the `ci-latest`
  pre-release as `sonorium-enginelab-<commit>.apk`.

Build locally: `./gradlew --no-configuration-cache :enginelab:assembleDebug` (Chaquopy can't use the
configuration cache).

## The screen

- **Make the test theme** / **Import a theme folder…** (pick a theme folder copied from the server's
  media folder: audio files, group folders, `metadata.json`, `presets.json`).
- **Predecode**: every audio file → WAV with MediaCodec; shows the time and the storage used.
- **Benchmark 60 s**: CPU seconds to render one minute, Python engine or Kotlin mixer.
- **Play in background**: a foreground service plays through AudioTrack with a partial wake lock;
  every minute it appends to `Android/data/app.sonorium.enginelab/files/lab-log.csv`
  (render thread CPU, % of one core, battery %). The notification shows the same.

## Battery benchmark plan (Galaxy S25 Ultra)

Goal: the battery cost per hour of (A) streaming a channel from the server (what the app does
today), (B) the Python engine on the phone, (C) the Kotlin mixer, at the same volume, screen off.

Setup, same for every run:
1. Charge to 100 %, unplug, wait 10 minutes. Wi-Fi on, mobile data and Bluetooth off, brightness
   minimum, no other media apps. Battery saver and "Put unused apps to sleep" off for both apps.
2. Same output for all runs: the phone speaker at 25 % (or the same wired/BT headphones every time).
3. The same theme everywhere: a real one imported from the server (e.g. A Rainy Day as saved) and
   predecoded once before the runs (predecoding cost is measured separately, by the Predecode button).
4. Before each run: `adb shell dumpsys batterystats --reset`.

Runs (60 minutes each, screen off; start at ≥ 90 %, never below 30 %):
- **A. Stream**: Sonorium app, Listen here on a channel playing that theme with no speakers.
- **B. Python engine**: lab, Play in background: Python engine.
- **C. Kotlin mixer**: lab, Play in background: Kotlin mixer.
- **D. Worst case**: B with every track of the theme unmuted and looping (engine.md 8).

Record for each: battery % at start and after 60 min (from lab-log.csv for B-D; Settings › Battery
for A), `adb shell dumpsys batterystats app.sonorium.enginelab` (or `app.sonorium`) → the app's
estimated mAh and CPU time, and any audio dropouts heard. Repeat each run twice, on different days
if possible; report the average and spread.

Decision rule (proposal): the phone-side engine is worth it when B costs at most ~1.5x A per hour
and has no dropouts; if B is far above C, the Python overhead (not the mixing) is the problem, and a
Kotlin port of the mixer becomes the option to weigh.
