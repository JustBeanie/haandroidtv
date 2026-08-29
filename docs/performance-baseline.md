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

## 2026-08-28 physical Shield four-tile evidence

The configured physical Shield dashboard contained four tiles. The retained
measurements are:

- Cold startup: 410.5 ms median; 486 ms P95 and maximum.
- Warm startup: 105.5 ms median; 193 ms P95 and maximum.
- DPAD traversal frame overrun across 120 frames: P50 -11.15 ms,
  P90 -8.74 ms, P95 -1.21 ms, and P99 -0.54 ms.
- `gfxinfo`: 0 janky frames and 0 missed-vsync, input, UI-thread, upload,
  draw, and deadline misses.

The user explicitly approved **WAIVED — no ≥120-fps pending sample gate** and
**WAIVED — no physical 30-tile coverage requirement** for this release. The
four-tile Shield measurements remain physical evidence; they do not claim
30-tile physical coverage. The API 36 emulator and CI retain the 30-tile
benchmark coverage documented above.

## Release checklist

- [x] `ReportDrawnWhen` reports the configured dashboard only after initial focus restoration.
- [x] A versioned private snapshot renders last-known cards while live state reconnects.
- [x] Cached cards are visually identified and cannot execute commands.
- [x] Pending, success, and persistent inline failure command states are covered by tests.
- [x] 1080p shows two complete dashboard rows; primary controls fit at 720p.
- [x] CI executes single-loop cold/warm/Home-return and 30-tile DPAD benchmark
  verification and retains JSON/traces; emulator 30-tile coverage remains.
- [x] A generated Baseline Profile and startup profile are packaged in release builds.
- [x] Physical Shield Pro four-tile cold-start median is 410.5 ms, below 1.5 seconds.
- [ ] Physical Shield Pro command pending feedback appears within 100 ms. This
  measurement is explicitly user-waived for the current release: no ≥120-fps
  pending sample gate. It remains unmeasured and is recorded as `WAIVED`, not
  passed.
- [ ] Physical Shield Pro 30-tile coverage is explicitly user-waived for the
  current release. The measured four-tile results are retained; emulator
  30-tile coverage remains.
- [x] Physical Shield Pro four-tile DPAD frame-overrun P95 is -1.21 ms across
  120 frames, meeting the at-most-0-ms frame gate.

Run `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` for emulator trend data. Run `tools/validate-shield.ps1 -SelfTest` to verify the evidence parser, then follow `docs/shield-pro-validation.md` for the fail-closed physical release gate and save its output with the video/frame annotations. For the current user-approved pending-feedback waiver, pass `-SkipPendingFeedback`; this changes only that helper gate to `WAIVED`. The physical 30-tile coverage waiver is documented evidence scope and does not change validator behavior. Cold-start, frame-overrun, emulator 30-tile coverage, and manual evidence remain recorded separately.
