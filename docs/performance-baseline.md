# Performance baseline

This file is the reviewable performance checklist for the TV dashboard. Emulator numbers are trend data only; the physical NVIDIA Shield Pro result is the release authority.

## 2026-08-28 emulator baseline

Device fixture: API 36 Android TV x86_64 emulator at 1920×1080 with 30 deterministic dashboard controls.

- Cold startup, time to full display: 731.7 ms median (714.7–799.0 ms), 10 iterations.
- Cold startup, time to initial display: 605.5 ms median (577.1–626.8 ms), 10 iterations.
- Warm startup, time to full display: 120.6 ms median (111.4–134.9 ms), 10 iterations.
- Warm startup, time to initial display: 56.7 ms median (46.2–61.3 ms), 10 iterations.
- Android TV Home return, time to full display: 549.6 ms median (516.7–569.0 ms), 10 iterations.
- Android TV Home return, time to initial display: 420.7 ms median (404.1–429.6 ms), 10 iterations.
- DPAD traversal frame CPU duration: P50 11.5 ms, P90 21.4 ms, P95 26.5 ms, P99 35.5 ms.
- DPAD traversal frame overrun: P50 -3.0 ms, P90 6.7 ms, P95 16.0 ms, P99 22.8 ms.

The profiled emulator cold-start trend is below the 1.5-second target. Consecutive profiled runs produced frame-overrun P95 values from -2.3 ms to 16.0 ms, illustrating substantial emulator timing variance; rerun the authoritative frame gate on the Shield.

## Release checklist

- [x] `ReportDrawnWhen` reports the configured dashboard only after initial focus restoration.
- [x] A versioned private snapshot renders last-known cards while live state reconnects.
- [x] Cached cards are visually identified and cannot execute commands.
- [x] Pending, success, and persistent inline failure command states are covered by tests.
- [x] 1080p shows two complete dashboard rows; primary controls fit at 720p.
- [x] CI executes single-loop cold/warm/Home-return and 30-tile DPAD benchmark verification and retains JSON/traces; full iterations run locally and on the Shield.
- [x] A generated Baseline Profile and startup profile are packaged in release builds.
- [ ] Physical Shield Pro cold-start median is at most 1.5 seconds.
- [ ] Physical Shield Pro command pending feedback appears within 100 ms.
- [ ] Physical Shield Pro DPAD frame-overrun P95 is at most 0 ms.

Run `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` for emulator trend data. Run `tools/validate-shield.ps1 -SelfTest` to verify the evidence parser, then follow `docs/shield-pro-validation.md` for the fail-closed physical release gate and save its output with the video/frame annotations.
