#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/tv-diagnostics
collect_diagnostics() {
  result=$?
  if [ "$result" -ne 0 ]; then
    adb logcat -d -t 3000 > build/tv-diagnostics/logcat.txt 2>&1 || true
    adb shell uiautomator dump /sdcard/haquickaccess-window.xml >/dev/null 2>&1 || true
    adb pull /sdcard/haquickaccess-window.xml build/tv-diagnostics/window.xml >/dev/null 2>&1 || true
    adb exec-out screencap -p > build/tv-diagnostics/screen.png 2>/dev/null || true
    adb shell dumpsys meminfo dev.haquickaccess.tv.debug > build/tv-diagnostics/memory.txt 2>&1 || true
  fi
  exit "$result"
}
trap collect_diagnostics EXIT
./gradlew "$@"
