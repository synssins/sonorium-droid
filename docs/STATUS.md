# Sonorium for Android: status and how to continue

Updated 2026-10-12 ~05:20 UTC by the second app session, for the next session. Read this first.

## People and places
- **Chris** (owner). Writes plain language; keep messages short and concrete. Credits are limited:
  don't idle-poll, don't re-read large files needlessly.
- **This repo** `synssins/sonorium-droid` (public), branch **`claude/serene-dijkstra-x7nzto`**
  (all work goes here; never push elsewhere).
- **Server repo** `synssins/sonorium` (public, branch `fixes` = the `:dev` Docker image). The app
  session does not change it.
- **Handoff repo** `synssins/sonorium-handoff` (private): messages with the **server session**
  (a Claude session on Chris's PC). `to-app/` = from it, `to-server/` = from us, `shared/` =
  contracts. One message = one new file `YYYY-MM-DD-NN-topic.md`; never edit the other side's
  files; first line `Re: <file it answers>`. Key docs: `shared/native-api.md` (per-screen API
  contract, Phases 1-3, ~2800 lines: read only the section you build), `shared/engine.md`.
- **Test plan (to-app/02):** after each push, post a note in `to-server/` with the commit, what
  changed, and the screenshot links (github.com/synssins/sonorium-droid/tree/e2e-results/screens,
  and `e2e-results-android8`). The server session installs the `ci-latest` debug APK on its
  emulators against Chris's real servers and replies **Validated** / **Rejected** in `to-app/`.

## Build, CI, releases
- Local build here: Android SDK at /opt/android-sdk (install with cmdline-tools if missing:
  platforms;android-37.0, build-tools;37.0.0). Maven Central rate-limits this container: use the
  init script that rewrites repo.maven.apache.org to
  https://maven-central.storage-download.googleapis.com/maven2/ (`./gradlew -I mirror.gradle`).
  Check: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`.
- **CI** (.github/workflows/android.yml) on every push: build/test/lint, the debug APK to the
  `ci-latest` pre-release, then device tests on emulators (API 35 with 7 screen profiles and
  screenshots; API 26) against `ghcr.io/synssins/sonorium:dev` in Docker. Results are force-pushed
  to branches `e2e-results` and `e2e-results-android8` (summary.md, instrumentation.txt, logcat,
  screens/). A newer push cancels the older run. A run takes ~25 min.
- **Releases:** this session can't push tags. Trigger the workflow with
  `run_workflow` on `android.yml`, ref = the branch, input `release: v0.1.0-alphaN`. It builds the
  branch head **at dispatch time** in its own concurrency group (a later push doesn't cancel it), so
  dispatch before pushing newer, untested work. Releases so far tonight:
  **v0.1.0-alpha8** (`cd8e380`, native Channels), **v0.1.0-alpha9** (`0ae78df`, Phase 2 + Link
  channels). `.github/release-notes.md` describes alpha9; rewrite it before the next release.
- CI Gradle calls are scoped to `:app` (the `:enginelab` module uses Chaquopy, which can't use the
  configuration cache; it builds in its own non-blocking step with `--no-configuration-cache` and
  its APK goes to `ci-latest` as `sonorium-enginelab-<sha>.apk`). Locally: always `:app:` tasks.
- Mirror init script (recreate in your scratchpad, pass with `-I`): in `settingsEvaluated` and
  `allprojects`, every MavenArtifactRepository whose url starts with `https://repo.maven.apache.org`
  gets url `https://maven-central.storage-download.googleapis.com/maven2/`.
- The handoff repo is attached read-only but pushing notes to its `main` works. Release notes come from `.github/release-notes.md` (describes alpha10 now) (keep it
  current: what's new, please test, not built yet).
- Signing key and secrets are set up by Chris; package `app.sonorium` (debug `app.sonorium.debug`).

## What's built
- Linking (QR/App Link, typed address + four security words + password), pinned HTTPS,
  Keystore-encrypted tokens, Servers screen (rename, remove = unlink on server).
- **Native app (Phase 1 of native-api.md)** in `app/src/main/java/app/sonorium/`:
  `server/ServerViewModel.kt` (data, SSE live updates + 15 s safety refresh, every card action,
  toasts verbatim), `server/ChannelRules.kt` (theme icons, clock, speaker picker compaction;
  unit-tested), `ui/server/` (ServerScreen: top bar with server button + headphone button, menu
  as side panel / permanent sidebar on big landscape; ChannelCard; ChannelRow = collapsed rows,
  the design Chris approved in to-app/10; ChannelEditor with speaker picker; PersonalChannelScreen;
  Icons.kt = the web UI's SVG icons). Zones on cards (link strips, owner/Kid mode, editor fields).
- Listen here: `listen/ListenService.kt` (Media3 MediaSessionService, lock-screen controls,
  reconnect, hold_seconds 120, `listen_path`, personal channel via /api/me/channel/listen).
- **Phase 2 (native)**, commit `69dae63`: `ui/server/PlaylistsScreen.kt`, `ThemesScreen.kt`
  (preview player in the view model), `SpeakersScreen.kt`, `GroupsScreen.kt`, `SpacesScreen.kt`
  (Floors & Areas), `StatusScreen.kt` (200 ms poll while resumed); shared pieces in `PageParts.kt`;
  client-side rules in `server/{Playlist,Group,Speaker,Status,Theme}Rules.kt`, each unit-tested.
  **Link channels** dialog: `ui/server/LinkChannelsDialog.kt` (header button + Change on link bars).
  Full-screen editors use `EditorSurface` so toasts show above them.
- **Phase 3 (native)**, commits `90e17fa`..`a7306dc`: Theme Editor (`server/ThemeEditor.kt` state + calls,
  `ui/server/ThemeEditorScreen.kt`, `ThemeEditorTracks.kt`), sequence editor (`SequenceEditor.kt`) and a
  step's Advanced dialog (`AdvancedDialog.kt`; no ▶ transition preview), Settings › Connection, Audio
  Settings, Plugins, Logs, Advanced (`ConnectionScreen.kt`, `AudioScreen.kt`, `PluginsScreen.kt`,
  `LogsScreen.kt`, `AdvancedSettingsScreen.kt`; shared field editor `SettingsFields.kt`). Rules, unit-tested:
  `server/{ThemeEditor,Sequence,Advanced,Connection,Plugin,Logs,AdvancedPage}Rules.kt`. Page help `?` on every
  native page (`HelpTexts.kt`, texts generated verbatim from the web UI's `PAGE_HELP`, `#te-help`, `#tx-help`).
  Generic `SonoriumApi.json(method, path, body)` (2xx `{"error"}` = failure) + `ServerViewModel.call {}`,
  `messageOf`, `waitForRestart` for Phase 3 pages. Settings menu in the web UI's order.
- Still in the web view: Users & devices and Keys (never native), plugin-framework pages.

## State at hand-over
- Releases tonight: alpha8, alpha9, **alpha10** (`ac99860`, Phase 3 native), **alpha11** (`4648d0b`:
  live updates for settings pages, crossfade range, Android 8 focus).
- **Server session validated alpha11** (to-app/17): 48 rows Validated, 10 Rejected = issues R1-R9.
  Fixed in `1ea7f5d` (pushed 11:32; CI result: see e2e-results, not released yet):
  R1 channel editor sent `theme_id: null` (real bug: `?.let { put } ?: put(null)`), R2 default preset
  after a theme pick, R3 Advanced Save (was cancelled with the dialog's scope), R5 deleted theme
  coming back, R6 Back leaving the server (no BackHandler on pages), R7 picker text cut at half width,
  R8 log times wrapping.
  **Not fixed:** R4 (after Leave, the card's link strip keeps `Linked · …` + Leave instead of
  `Left the link` + Rejoin; look at how ChannelCard reads `links[].left` vs the zones refresh) and R9
  (Play a playlist should also open the Playlist menu: PickerField needs an "open now" request).
- `df7b910` (in `1ea7f5d`): plugin-framework pages in the menu (web view).
- to-app/18: Keys and Announcements become core features (server branch `core-features`, **not merged**
  yet): `features.keys` / `features.announcements` with `default` and `live`; Advanced's Features rows
  should list them (switch on/off; `live` = no restart); hide announce controls when
  `features.announcements.enabled` is false. to-app/12-16 are for the Windows session: ignore.
- Device test is robust to: keyboard over buttons, stale text fields, the notification shade, the
  emulator's "isn't responding" dialog (`waitPastAnr`), menu lookups (test tags `menu:<title>`).

## Next, in order
1. If `1ea7f5d` is green: release **v0.1.0-alpha12**, note in to-server/ (R1-R3, R5-R8 fixed; R4, R9 open).
   If red: fix (see e2e-results summary.md and screens/99-*.png).
2. R4 and R9 (above). Then the server session's other notes in to-app/17 ("Other findings").
3. When `core-features` merges: Keys / Announcements rows in Advanced › Features; hide announce
   controls when off.
4. Theme Editor extras: long-press drag (now ⋯ › Move to), Advanced ▶ transition preview.
5. Engine: act on Chris's lab numbers (enginelab/README.md).

## Rules that matter
- Behave exactly like the web UI (toasts, texts, order) per native-api.md; Chris's wording verbatim.
- Phones: collapsed rows by default; tablets (Galaxy Tab S10 Ultra) and small old phones (kids'
  remotes, Android 8) must both work: CI's screen profiles cover them.
- Audio tests only on Office_C97A or the Office display, ≤25 % volume (server session handles real
  devices; CI has none).
- Commit messages end with the Co-Authored-By / Claude-Session lines; no model names in commits.
