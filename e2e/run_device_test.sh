#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner): installs
# the debug app and its tests, runs EndToEndTest against the Docker server,
# and collects screenshots and the log into e2e-out/.
set -u
mkdir -p e2e-out
adb logcat -c || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

set +e
adb shell am instrument -w -r \
  -e linkUri "$LINK_URI_B64" \
  -e channelName "'$CHANNEL_NAME'" \
  -e class app.sonorium.EndToEndTest \
  app.sonorium.debug.test/androidx.test.runner.AndroidJUnitRunner | tee e2e-out/instrumentation.txt
set -e

adb pull /sdcard/Android/data/app.sonorium.debug/files/e2e e2e-out/screens || true
adb logcat -d > e2e-out/logcat.txt || true
grep -E "AndroidRuntime|chromium|Sonorium|WebView|okhttp|EndToEnd" e2e-out/logcat.txt | tail -300 > e2e-out/logcat-filtered.txt || true

# am instrument exits 0 even on failure; the result line decides.
grep -q "^OK (" e2e-out/instrumentation.txt
