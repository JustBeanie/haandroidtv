#!/usr/bin/env bash
set -euo pipefail

# Boot completion can precede the emulated storage mount. AndroidX Benchmark
# needs this shared location for scripts readable by both the app and adb shell.
timeout 180 adb wait-for-device
for attempt in $(seq 1 90); do
  if adb shell 'test "$(getprop sys.boot_completed)" = 1 && mkdir -p /sdcard/Android/media && touch /sdcard/Android/media/.haquickaccess-ci-ready && rm /sdcard/Android/media/.haquickaccess-ci-ready'; then
    exit 0
  fi
  sleep 2
done

echo 'Android TV boot or external storage did not become ready within 180 seconds.' >&2
adb shell getprop sys.boot_completed || true
adb shell df /sdcard || true
exit 1
