# HA Quick Access

HA Quick Access is a native Android TV control surface for Home Assistant. It is
designed for NVIDIA Shield and other D-pad-first TVs: a 4-column grid of large
Home Assistant-inspired tiles, secure local configuration, and live entity
updates while the app is open.

## What it supports

- Direct controls for lights, switches, fans, and input booleans.
- Dimmable lights and fans with a staged 0–100% detail control.
- Climate mode and target-temperature controls when the entity reports them.
- Cover commands and positions, with confirmation before opening garage, gate,
  or door covers.
- Alarm arm/disarm controls. Disarm requires a fresh Home Assistant code every
  time; the app never stores that code.
- Optional Android TV Home channel with up to four configurable deep links.

## Security model

The app accepts only HTTPS Home Assistant URLs and connects using WSS. Create a
long-lived access token in Home Assistant under **Profile → Security**. The
token is encrypted with a non-exportable Android Keystore AES key, and Android
backup is disabled. Do not use HTTP or disable certificate validation.

## Run locally

1. Install JDK 17 and the Android SDK platform for API 36.
2. Open the project in Android Studio or run `./gradlew :app:assembleDebug`.
3. Sideload the resulting APK on the Shield with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. In the app, enter the HTTPS base URL of Home Assistant and paste a long-lived token.

To make the Shield remote's customizable Settings button open the app, choose
**Settings → Remotes & Accessories → Customize Settings button** on the Shield,
then select HA Quick Access. The app restores the last focused tile.

## Signed release builds

`assembleRelease` deliberately produces `app-release-unsigned.apk` unless a
private root-level `keystore.properties` is present. The file is ignored by Git;
create it locally with these four values:

```properties
storeFile=path/to/release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

With this file in place, `./gradlew :app:assembleRelease` produces the
Shield-installable `app/build/outputs/apk/release/app-release.apk`. Never commit
the properties file or the keystore.

For Google Play, build the signed Android App Bundle instead:

```text
./gradlew :app:bundleRelease
```

The resulting upload artifact is
`app/build/outputs/bundle/release/app-release.aab`. `bundleRelease` fails early
when signing credentials are absent, so an unsigned bundle cannot accidentally
be submitted. See [Play Store release guide](docs/play-store-release.md) for
the remaining console steps and [privacy policy](docs/privacy-policy.html) for
the public-policy source.

## Quality checks

Run the JVM checks with:

```text
./gradlew :app:lintDebug :app:testDebugUnitTest :app:koverXmlReportDebug :app:koverVerifyDebug
```

Run the device-side checks on an Android TV emulator or connected Shield with:

```text
./gradlew :app:connectedDebugAndroidTest
```

Kover enforces at least 80% line and branch coverage for the unit-testable data
protocol/validation, domain, and view-model logic. Android Keystore, DataStore,
TV Provider, focus, and Compose behavior are verified by instrumentation tests.
Pull requests run lint, unit tests, coverage verification, an Android TV
emulator smoke test, and a debug APK build in GitHub Actions.

Before distributing a release APK, run the physical-device checklist in
[Shield Pro release validation](docs/shield-pro-validation.md).
The included `tools/validate-shield.ps1` helper installs an APK, measures cold
starts, and captures `gfxinfo` once a Shield is connected through ADB.
When the Shield is already connected to Home Assistant's Android Debug Bridge
integration, use the [Home Assistant ADB deployment guide](docs/home-assistant-adb-deployment.md)
to upload, install, launch, and collect the same diagnostics remotely.
To make the global Codex `home-assistant` connector available to every new
Shield validation task, follow the credential-safe [Codex Home Assistant MCP
setup](docs/codex-home-assistant-mcp.md) and run its read-only verification.

## Limitations

- The Home-screen channel is opt-in and displays cached state; it does not keep
  a background socket alive.
- The app intentionally does not draw over other apps or intercept global
  remote keys.
- Home Assistant entities vary by integration. Controls are only exposed when
  their entity attributes indicate a compatible capability.
