# Google Play release guide

This document is the release hand-off for version 0.2.0 (version code 2).
It assumes an Android TV-only distribution.

## Before creating the release

1. Create a Play Console developer account and create the app with package name
   `dev.haquickaccess.tv`. The package name is permanent after the first upload.
2. Keep the public `haquickaccess-privacy-policy` repository enabled (or
   publish the same unmodified policy on another public, non-authenticated HTTPS
   page). That repository intentionally contains only `privacy-policy.html`,
   not the private app source or release documentation. The app includes the
   same policy text under Settings > Privacy policy. Ensure
   `https://justbeanie.github.io/haquickaccess-privacy-policy/privacy-policy.html`
   is live before uploading and enter that URL in Play Console.
3. Create and securely back up the upload keystore. Do not use a debug key and
   do not commit the keystore or `keystore.properties`.
4. Populate the required `keystore.properties` values described in the README,
   then run `./gradlew :app:bundleRelease`. Upload only
   `app/build/outputs/bundle/release/app-release.aab`.

## Play Console declarations

The following answers reflect the current source code and must be reviewed if
the app changes.

| Console section | Current answer |
| --- | --- |
| App type | App; Android TV only |
| Ads | No ads |
| Data safety: data collected or shared | No. The app has no developer-operated backend, analytics, advertising, or crash-reporting SDK. The Home Assistant URL and token are sent only to the user-selected Home Assistant server. |
| Data deletion | User can remove the connection in-app; uninstalling removes local app data. |
| App access | All functionality needs a user-operated Home Assistant server and a long-lived access token. Give Play reviewers a test server URL and disposable token through the App access instructions; never put a real household token in public listing text. |
| Content rating | Complete the IARC questionnaire based on the actual control features. The app has no user-generated content, ads, gambling, or violence content. |
| Target audience | Select the actual intended age group. Do not select children unless the product and policy review support it. |
| Permissions | Normal `INTERNET` and `ACCESS_NETWORK_STATE`; Android TV preview-channel permission. No runtime-dangerous permissions. |

The current `minSdk` is 26, which satisfies Android TV's requirement to support
commonly used TV devices with a minimum SDK of 31 or lower. The app ships no
native code; verify the Play pre-launch report after upload for the current
64-bit and 16 KB page-size checks.

## Store listing copy

**App name:** HA Quick Access

**Short description (80 characters or fewer):**
Control your Home Assistant devices from an Android TV remote.

**Full description:**

HA Quick Access is a fast, remote-first Home Assistant control surface for
Android TV. Connect directly to your own Home Assistant server and keep the
controls you use most in a clear, responsive TV grid.

* Control lights, switches, fans, climate, covers, alarms, scenes, scripts,
  and buttons when supported by your Home Assistant entities.
* Adjust dimmable lights and fans, climate modes and temperatures, and cover
  positions from the remote.
* Confirm safety-sensitive garage, gate, door, and alarm actions.
* Optionally add up to four shortcuts to the Android TV Home screen.
* Keep your long-lived Home Assistant token encrypted with Android Keystore.

Requires a reachable HTTPS Home Assistant server and a long-lived access token.
HA Quick Access is independent software and is not affiliated with Home
Assistant.

## Required creative assets

The prepared upload assets are in `play-store-assets/`. Re-capture screenshots
if the release UI changes materially:

1. `app-icon-512x512.png`: 512 x 512 PNG with no transparency.
2. `feature-graphic-1280x720.png`: 1280 x 720 PNG without device frames or
   promotional claims.
3. `screenshots/01-setup.png` and `screenshots/02-dashboard.png`: 1920 x 1080
   captures of the actual Compose UI rendered on an Android TV emulator. The
   configured dashboard uses synthetic demo entities and contains no server,
   token, household name, or camera imagery.
4. A reviewer test account/server and concise login instructions in the
   non-public App access section.

## Final release gate

1. Run `:app:lintRelease`, `:app:testDebugUnitTest`, and
   `:app:koverVerifyDebug` with JDK 17.
2. Install the signed release APK on an Android TV device and complete the
   physical-device checks in `shield-pro-validation.md`.
3. Upload the signed `.aab` to the internal testing track first. Verify install,
   setup, connection, remote navigation, deep links, Home channel, connection
   removal, and fresh reinstall.
4. Resolve every Play pre-launch report issue, then promote through closed/open
   testing as appropriate before production.
