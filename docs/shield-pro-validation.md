# Shield Pro release validation

Run this checklist with the release APK on an NVIDIA Shield Pro and a non-test
Home Assistant instance. Do not use a token with broader access than required
for the selected entities.

Before connecting hardware, verify the helper's modern and legacy `gfxinfo`
parsers. CI runs the same hardware-free check on every branch build:

```text
./tools/validate-shield.ps1 -SelfTest
```

With USB debugging enabled, the helper can install the debug APK, collect ten
cold and warm launch timings, traverse the grid five times, calculate frame
overrun percentiles, and print `gfxinfo` diagnostics:

```text
./tools/validate-shield.ps1 -Serial <shield-adb-serial> -Install
```

Debug timings are informational only; debug execution is not a release
performance gate. Benchmark the signed, minified release APK before
distribution:

```text
./tools/validate-shield.ps1 -Serial <shield-adb-serial> -Install -ApkPath app/build/outputs/apk/release/app-release.apk -PackageName dev.haquickaccess.tv -BuildVariant Release -PendingLatencyMs 58,50,67,58,75,50,67,58,50,67
```

Replace the example pending values with ten measurements from the procedure
below. Release mode fails unless all ten cold launches are captured, the cold
median is at most 1.5 seconds, at least 30 valid frame records are captured,
frame-overrun P95 is at most 0 ms, and all ten pending-feedback measurements
are at most 100 ms. It records P99 for regression comparison.

The pending-feedback measurement may be waived only after explicit user
approval. Record that exception by replacing `-PendingLatencyMs ...` with
`-SkipPendingFeedback` on the release command. The helper reports this gate as
`WAIVED` and explicitly states that no latency pass is claimed. It still
enforces the cold-launch and frame-overrun gates and all manual checks remain
required. Without the flag, missing pending-feedback samples continue to fail
closed; never infer a waiver from absent evidence.

## Current physical evidence and user-approved waivers

The current release has measured physical Shield evidence on the configured
four-tile dashboard:

- Cold launch: 410.5 ms median; 486 ms P95 and maximum.
- Warm launch: 105.5 ms median; 193 ms P95 and maximum.
- Frame overrun across 120 frames: P50 -11.15 ms, P90 -8.74 ms,
  P95 -1.21 ms, and P99 -0.54 ms.
- `gfxinfo`: 0 janky frames and 0 missed-vsync, input, UI-thread, upload,
  draw, and deadline misses.

The user explicitly approved these two release exceptions:

- Pending-feedback latency: **WAIVED — no ≥120-fps pending sample gate.**
  Invoke the helper with `-SkipPendingFeedback` so the saved output says
  `WAIVED`; this is not a passing latency result.
- Physical dashboard size: **WAIVED — no physical 30-tile coverage
  requirement.** The measured four-tile Shield evidence above is retained.
  The 30-tile emulator benchmark and CI coverage remain required and are not
  replaced by this waiver.

These documented exceptions do not weaken the helper's defaults. Without
`-SkipPendingFeedback`, release validation still fails closed when the ten
pending samples are absent. The physical cold-launch and frame-overrun results
remain measured results, not waivers.

The helper deliberately refuses non-NVIDIA devices so emulator trend results
cannot accidentally be recorded as the physical Shield release gate.

It intentionally does not automate safety, TLS, Home Assistant, or visual
focus/layout checks; record those manual results below.

For a signed release APK, pass `-ApkPath app/build/outputs/apk/release/app-release.apk`
and `-PackageName dev.haquickaccess.tv`.

If the Shield's ADB connection is instead managed by Home Assistant, use the
[Home Assistant ADB deployment guide](home-assistant-adb-deployment.md) to
upload the signed APK, install it, launch it, and collect `gfxinfo` through the
existing authenticated ADB connection.

## Remote and launcher

- Create the ignored `keystore.properties` described in the README, run
  `./gradlew :app:assembleRelease`, then install the signed output with
  `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Complete setup using only the Shield remote; verify no touch or pointer input
  is needed.
- For full physical coverage, traverse at least 12 tiles with every D-pad
  direction, then scroll to a thirteenth tile. Under the current user-approved
  physical 30-tile coverage waiver, traverse every tile in the measured
  four-tile dashboard instead. Select a tile, press Back, and confirm the prior
  tile has focus again.
- Hold the center/Select key on a dimmer tile and confirm it opens details;
  a normal Select must still perform the safe direct action.
- Assign the Shield Settings button under **Settings → Remotes & Accessories →
  Customize Settings button**. Press it from the launcher and from a media app;
  it should launch HA Quick Access to the last focused tile without overlaying
  the media app.

## Connection and safety

- Confirm HTTP, an invalid certificate, an endpoint with credentials, and a
  URL with a fragment are rejected. Confirm a valid HTTPS endpoint establishes
  a WSS session and live state changes update a tile.
- Turn a dimmer or fan level to a staged value, select Cancel, and verify Home
  Assistant is unchanged. Repeat with Apply and verify the state updates.
- Exercise climate mode/temperature bounds and cover open/close/stop/position.
  Garage, gate, and door opens — including a position increase that opens one
  — must show confirmation before the service call.
- Arm an alarm and disarm it with a fresh code. Return to the panel and verify
  the code field is empty. Inspect Home Assistant and logcat to confirm no code
  was logged.
- Disconnect the network while the app is foregrounded, restore it, and verify
  reconnection and state recovery. Put the app in the background and confirm
  the socket closes.

## Android TV Home channel

- Configure one each of Toggle, Focus, and Details shortcuts.
- Choose **Add to Android TV Home**, approve the system prompt, and check the
  preview channel content and labels.
- Launch every shortcut. Toggle only safe entities; secure covers and alarms
  must still open their confirmation/detail flow.
- Remove the channel and verify its entries disappear from Android TV Home.

## Performance

- A full physical coverage run uses a 30-tile grid. For the current release,
  the user waived that physical 30-tile requirement and accepted the measured
  four-tile Shield run recorded above; the 30-tile emulator benchmark remains
  required. Launch the configured grid once and wait for live state to be
  ready. The helper then records ten cold and warm launches. The release
  cold-start median target is no more than 1.5 seconds to a cached, focused
  control.
- Record ten safe command selections at 120 fps or faster with the Home
  Assistant response delayed enough to keep `Pending` visible. For each sample,
  count frames from the Select press being registered through the first frame
  showing pending feedback, then calculate `frames / capture_fps * 1000`.
  Supply the ten millisecond values with `-PendingLatencyMs`; every sample must
  be at most 100 ms. Keep the video or frame annotations with the run output.
  For the current release, the user explicitly waived the ≥120-fps pending
  sample gate. Run the release helper with `-SkipPendingFeedback` and retain its
  `WAIVED` output with the release evidence. A waiver is not a passing latency
  measurement.
- The helper repeats the same D-pad journey as Macrobenchmark five times,
  parses `dumpsys gfxinfo ... framestats`, and reports frame-overrun P50, P90,
  P95, and P99. The release gate requires P95 at most 0 ms. On older Shield
  firmware without `FrameDeadline`, it reports a warning and uses
  `IntendedVsync` plus the reported or measured display interval.
- Watch the traversal while it runs and confirm there is no dropped focus,
  overlapping scaled cards, clipped rows, or modal focus theft. Save the full
  console output alongside the signed APK and visual evidence.
