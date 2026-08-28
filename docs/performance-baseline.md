# Performance baseline

This file is the reviewable performance checklist for the TV dashboard. Emulator numbers are trend data only; the physical NVIDIA Shield Pro result is the release authority.

## 2026-08-27 emulator baseline

Device fixture: API 36 Android TV x86_64 emulator at 1920×1080 with 30 deterministic dashboard controls.

- Cold startup, time to full display: 672.3 ms median (640.2–693.2 ms), 10 iterations.
- Cold startup, time to initial display: 551.9 ms median (531.9–584.7 ms), 10 iterations.
- Warm startup, time to full display: 112.4 ms median (92.4–120.4 ms), 10 iterations.
- Warm startup, time to initial display: 50.0 ms median (40.9–60.0 ms), 10 iterations.
- Android TV Home return, time to full display: 503.3 ms median (467.4–577.5 ms), 10 iterations.
- Android TV Home return, time to initial display: 376.7 ms median (57.4–408.2 ms), 10 iterations.
- DPAD traversal frame CPU duration: P50 9.7 ms, P90 19.5 ms, P95 20.4 ms, P99 22.3 ms.
- DPAD traversal frame overrun: P50 -5.1 ms, P90 4.2 ms, P95 5.0 ms, P99 6.6 ms.

The profiled emulator cold-start trend is below the 1.5-second target. Consecutive profiled runs produced frame-overrun P95 values from -2.3 ms to 5.0 ms, illustrating emulator timing variance; rerun the authoritative frame gate on the Shield.

## Release checklist

- [x] `ReportDrawnWhen` reports the configured dashboard only after initial focus restoration.
- [x] A versioned private snapshot renders last-known cards while live state reconnects.
- [x] Cached cards are visually identified and cannot execute commands.
- [x] Pending, success, and persistent inline failure command states are covered by tests.
- [x] 1080p shows two complete dashboard rows; primary controls fit at 720p.
- [x] Cold/warm startup and 30-tile DPAD macrobenchmarks run in CI and retain their artifacts.
- [x] A generated Baseline Profile and startup profile are packaged in release builds.
- [ ] Physical Shield Pro cold-start median is at most 1.5 seconds.
- [ ] Physical Shield Pro command pending feedback appears within 100 ms.
- [ ] Physical Shield Pro DPAD frame-overrun P95 is at most 0 ms.

Run `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` for emulator trend data. Run `tools/validate-shield.ps1` for the physical release gate and save its output with the release evidence.
