#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner): installs
# the debug app and its tests, runs EndToEndTest against the Docker server,
# then ScreensTest once per screen profile, and collects screenshots and the
# log into e2e-out/.
set -u
mkdir -p e2e-out
adb logcat -c || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

RUNNER=app.sonorium.debug.test/androidx.test.runner.AndroidJUnitRunner

# A fresh Link App code now: codes last 5 minutes, and building plus booting the
# emulator can take longer than that.
LINK_URI_B64=$(python3 e2e/make_link.py https://127.0.0.1:8808 10.0.2.2 "$CI_ADMIN" "$CI_PASSWORD")

set +e
# QA screen tour, fresh install (no server yet): recorded, never fails the run
adb shell am instrument -w -r -e class app.sonorium.ScreenTourTest#offline "$RUNNER" > e2e-out/tour-instrumentation.txt 2>&1

adb shell am instrument -w -r \
  -e linkUri "$LINK_URI_B64" \
  -e channelName "'$CHANNEL_NAME'" \
  -e class app.sonorium.EndToEndTest,app.sonorium.PcmDecoderTest,app.sonorium.PhoneEngineTest,app.sonorium.PackageDownloadTest,app.sonorium.CastStreamTest \
  "$RUNNER" | tee e2e-out/instrumentation.txt
grep -q "^OK (" e2e-out/instrumentation.txt
e2e=$?

# Screen profiles: name, size (portrait, px), density (dpi), font scale.
# Sizes for Samsung devices are their default display settings, approximately.
# Smallest to largest, so layouts can be compared across the range.
profiles=(
  "small-phone 720x1280 320 1.0"
  "pixel-6 1080x2400 420 1.0"
  "galaxy-s25-ultra 1080x2340 450 1.0"
  "phone-large-font 1080x2400 420 1.3"
  "foldable-inner 1812x2176 420 1.0"
  "tablet-11in 1600x2560 320 1.0"
  "galaxy-tab-s10-ultra 1848x2960 340 1.0"
)
if [ "$e2e" -eq 0 ] && [ "${SCREENS:-1}" = "1" ]; then
  for p in "${profiles[@]}"; do
    read -r name size density font <<<"$p"
    echo "== screens: $name ($size @ ${density}dpi, font x$font)"
    adb shell wm size "$size"
    adb shell wm density "$density"
    adb shell settings put system font_scale "$font"
    sleep 3
    adb shell am instrument -w -r -e profile "$name" -e class app.sonorium.ScreensTest "$RUNNER" \
      | tee -a e2e-out/screens-instrumentation.txt | tail -3
  done
  adb shell wm size reset
  adb shell wm density reset
  adb shell settings put system font_scale 1.0
fi
# QA screen tour with the server: linked, unreachable, then removed in the app (last: it unlinks)
if [ "$e2e" -eq 0 ]; then
  for m in linked unreachable removed; do
    adb shell am instrument -w -r -e class "app.sonorium.ScreenTourTest#$m" "$RUNNER" >> e2e-out/tour-instrumentation.txt 2>&1
  done
  adb shell cmd connectivity airplane-mode disable || true
  adb shell svc wifi enable || true
fi
set -e

adb pull /sdcard/Android/data/app.sonorium.debug/files/e2e e2e-out/screens || true
adb logcat -d > e2e-out/logcat.txt || true
grep -E "AndroidRuntime|chromium|Sonorium|WebView|okhttp|EndToEnd|ScreensTest" e2e-out/logcat.txt | tail -400 > e2e-out/logcat-filtered.txt || true

# A gallery page per profile, viewable on GitHub.
if [ -d e2e-out/screens ]; then
  {
    echo "# Screenshots"
    echo
    echo "| Profile | Pixels | Density | CSS viewport (portrait) | Font |"
    echo "|---|---|---|---|---|"
    for p in "${profiles[@]}"; do
      read -r name size density font <<<"$p"
      w=${size%x*}; h=${size#*x}
      echo "| [$name](#$name) | $size | $density | $((w * 160 / density))x$((h * 160 / density)) | $font |"
    done
    for p in "${profiles[@]}"; do
      read -r name _ <<<"$p"
      dir="e2e-out/screens/$name/"
      [ -d "$dir" ] || continue
      echo
      echo "## $name"
      for f in "$dir"*.png; do
        [ -e "$f" ] || continue
        echo
        echo "**$(basename "$f" .png)**"
        echo
        echo "<img src=\"$name/$(basename "$f")\" width=\"420\">"
      done
    done
  } > e2e-out/screens/README.md
fi

# The QA tour's gallery: every state, every screen
if [ -d e2e-out/screens/tour ]; then
  {
    echo "# QA screen tour"
    for d in e2e-out/screens/tour/*/; do
      state=$(basename "$d")
      echo
      echo "## $state"
      for f in "$d"*.png; do
        [ -e "$f" ] || continue
        echo
        echo "**$(basename "$f" .png)**"
        echo
        echo "<img src=\"$state/$(basename "$f")\" width=\"360\">"
      done
    done
  } > e2e-out/screens/tour/README.md
fi

exit "$e2e"
