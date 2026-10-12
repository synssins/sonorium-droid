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
- **alpha13** (~13:40 UTC, `557a8f9` = green `2d057a3` + notes): 48 dp standard, R4, R9, R10, `accounts`.
  to-server/11 asks the server session to validate R4, R9, R10 and the 48 sizes.
- Releases tonight: alpha8, alpha9, alpha10 (Phase 3 native), alpha11, **alpha12** (~12:45 UTC, `427299e` =
  green `65f6133` + notes: R1-R3/R5-R8 fixes, UI standard on shared components, ANR-proof device test).
- Server session re-validated `1ea7f5d` (to-app/24): R1, R2, R3, R5, R6, R7, R8 **Validated**; new **R10**
  (Back with the menu open left the server). R4, R9, R10 fixed in the commit after alpha12 (R4: a channel
  that left stays in `channels` and is in `left` too; R9: PickerField `openNow`; R10: a BackHandler for the
  open drawer). Needs CI, then alpha13 and a note asking to re-validate R4, R9, R10.
- UI standard: `shared/ui-standards.md` (to-app/20). **Touch minimum 48, decided by Chris (to-app/23)**:
  `UiSize` Standard 48 / Small 44, top bar controls 48, collapsed channel row 56 (48 controls + 4).
  Read **every** new to-app note before acting: I missed 23 once.
- to-app/18 (Keys/Announcements core features, not merged on the server yet), to-app/21 (users-core:
  not read yet), windows notes: ignore.

## Next, in order
0. **UI standards** (Chris, 11:45 UTC): `shared/ui-standards.md` in sonorium-handoff (to-app/20). Done so far
   (`8774d97` + the next commit): top bar and headers 44 dp; `UiSize` in `ui/Theme.kt` (Standard 44, Small 40,
   row 48, glyphs 20/16, disabled 0.5); IconBox radius 6, glyph by size; every 32/36 dp icon button → 40;
   text 15/17 → 16, 20 → 18. **Still to do:** card/dialog radius 12 (cards use 8), spacing (row padding
   6×12, card padding 12/16, gaps 8), list rows min 48 everywhere, text buttons 44, captions 11.
   Also to do: a fixed debug keystore in CI so test installs upgrade in place (to-app/26); Engine Lab notes
   to-app/27 (share the log from the phone), 28 (first S25 run); to-app/29 Pro mode (boards first).
1. If `1ea7f5d` is green: release **v0.1.0-alpha12**, note in to-server/ (R1-R3, R5-R8 fixed; R4, R9 open).
   If red: fix (see e2e-results summary.md and screens/99-*.png).
1. Read the server session's validation of alpha13 in to-app/ (and to-app/30 theme-packages, unread).
2. Read to-app/21 (users-core) and the "Other findings" in to-app/17.
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
