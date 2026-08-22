# Shield Pro release validation

Run this checklist with the release APK on an NVIDIA Shield Pro and a non-test
Home Assistant instance. Do not use a token with broader access than required
for the selected entities.

With USB debugging enabled, the included PowerShell helper can install the
debug APK, collect ten cold-start timings, and print `gfxinfo` diagnostics:

```text
./tools/validate-shield.ps1 -Serial <shield-adb-serial> -Install
```

It intentionally does not automate safety, TLS, Home Assistant, or remote
checks; record those manual results below.

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
- Traverse at least 12 tiles with every D-pad direction, then scroll to a
  thirteenth tile. Select a tile, press Back, and confirm the prior tile has
  focus again.
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

- From a cached grid, time launch-to-focused-control over ten cold/warm runs;
  the target is no more than 1.5 seconds for cached interaction.
- Trigger ten safe commands and confirm the tile displays pending feedback
  within 100 ms before the Home Assistant response returns.
- Capture `adb shell dumpsys gfxinfo dev.haquickaccess.tv` while navigating a
  large grid. Investigate visible jank, repeated allocations, or dropped focus
  before distributing the release APK.
