# Deploying through Home Assistant ADB

Use this route when the Shield is already managed by Home Assistant's **Android
Debug Bridge** integration. It lets Home Assistant upload the APK, install it
through the Shield's existing ADB authorization, launch it, and return command
output in the media player's `adb_response` attribute.

On current Shield firmware, Android's package manager cannot read APKs from
shared storage such as `/storage/emulated/0/Download`. The required deployment
sequence is therefore **upload → copy to `/data/local/tmp` → install**. Do not
skip the copy step.

This is a deployment aid, not an app requirement. HA Quick Access still makes
its own foreground-only WSS connection to Home Assistant after it launches.

## Build and stage the APK

Choose the build appropriate to the task:

| Purpose | Build command | Package | HA staging path |
| --- | --- | --- | --- |
| Debug on a Shield | `./gradlew :app:assembleDebug` | `dev.haquickaccess.tv.debug` | `/config/www/ha-quick-access/app-debug.apk` |
| Distribution release | `./gradlew :app:assembleRelease` | `dev.haquickaccess.tv` | `/config/www/ha-quick-access/app-release.apk` |

Release builds require the private `keystore.properties` file described in the
README. Do not create a throwaway release key: users could not update a build
signed by a key that is later discarded. Debug builds use the standard Android
debug key and install alongside the release package, making them suitable for
development and physical-device validation only.

Stage the selected artifact on the Home Assistant host. When the built-in
**Samba share** app is enabled, copy it from the development computer to:

```text
\\<home-assistant-host>\config\www\ha-quick-access\<apk-name>
```

For example, stage the debug artifact as
`\\<home-assistant-host>\config\www\ha-quick-access\app-debug.apk`. Create
the `ha-quick-access` directory if it does not exist. The Home Assistant path
used in the actions below is then `/config/www/ha-quick-access/<apk-name>`.
Keep APKs and signing material out of Git.

Finally, confirm the Shield's **Android Debug Bridge** integration is available,
note its media-player entity ID (for example `media_player.living_room_shield`),
and confirm that the Shield has already accepted Home Assistant's ADB
authorization.

## Install and launch

In **Developer tools → Actions**, run these actions in order. Replace the
example entity ID and APK placeholders with the selected build's values. The
commands use a debug APK. For a release build, consistently substitute:

- `app-debug.apk` → `app-release.apk`
- `ha-quick-access-debug.apk` → `ha-quick-access-release.apk`
- `dev.haquickaccess.tv.debug` → `dev.haquickaccess.tv`

```yaml
# 1. Copy the APK from the HA host to the Shield's shared Download directory.
action: androidtv.upload
target:
  entity_id: media_player.living_room_shield
data:
  local_path: /config/www/ha-quick-access/app-debug.apk
  device_path: /storage/emulated/0/Download/ha-quick-access-debug.apk
```

```yaml
# 2. Move the APK to the ADB-readable install location.
#    pm install cannot read from /storage/emulated/0 on current Shield firmware.
action: androidtv.adb_command
target:
  entity_id: media_player.living_room_shield
data:
  command: cp /storage/emulated/0/Download/ha-quick-access-debug.apk /data/local/tmp/ha-quick-access-debug.apk
```

```yaml
# 3. Install or replace the app, preserving any existing app data.
action: androidtv.adb_command
target:
  entity_id: media_player.living_room_shield
data:
  command: pm install -r /data/local/tmp/ha-quick-access-debug.apk
```

```yaml
# 4. Start the app and return Android's launch timing/result.
action: androidtv.adb_command
target:
  entity_id: media_player.living_room_shield
data:
  command: am start -W -n dev.haquickaccess.tv.debug/dev.haquickaccess.tv.MainActivity
```

The install action should return `Success`. If it does not, inspect the
`adb_response` attribute on the media-player entity before retrying. Do not
downgrade the app or clear its data unless that is deliberate: either operation
would discard the encrypted connection token and tile configuration.

## Initial Home Assistant connection

The first launch displays **Connect Home Assistant**. Complete this directly
on the Shield with the remote:

1. In Home Assistant, create a **long-lived access token** under the intended
   user's **Profile → Security** page. Prefer a dedicated user whose
   permissions are limited to the entities this control surface should use.
2. Enter the Home Assistant base URL in the app, for example
   `https://hass.example.net`. Use the same HTTPS URL the Shield can reach; do
   not append `/api`, `/api/websocket`, credentials, or a URL fragment.
3. Paste the new long-lived access token into the token field and select
   **Connect**. Never enter the Home Assistant MCP webhook URL, an MCP OAuth
   token, or a Samba credential here.
4. Wait for the connected state, then add the desired supported entities as
   tiles. Check that a safe control reflects a live state update before adding
   security-sensitive controls.

The app encrypts this token with a non-exportable Android Keystore key and
clears the entry field after saving. The token must still be treated as a
secret: do not put it in source control, screenshots, logs, ADB commands, or
chat messages. Clearing the app connection deletes the saved encrypted token.

## Remote verification commands

Run these through `androidtv.adb_command` against the same entity while
performing the physical-device checklist:

```yaml
# Prove the expected debug package is installed and show its code path.
command: pm path dev.haquickaccess.tv.debug
```

```yaml
# Inspect package version, requested permissions, and launch component.
command: dumpsys package dev.haquickaccess.tv.debug
```

```yaml
# Collect the rendering diagnostics called for by the Shield checklist.
command: dumpsys gfxinfo dev.haquickaccess.tv.debug
```

The integration exposes raw shell-command output as `adb_response`; record the
package result, launch result, and `gfxinfo` result with the manual test
evidence. Optionally remove both Shield copies after a successful install:

```yaml
action: androidtv.adb_command
target:
  entity_id: media_player.living_room_shield
data:
  command: rm -f /data/local/tmp/ha-quick-access-debug.apk /storage/emulated/0/Download/ha-quick-access-debug.apk
```

## Starting the app later

For normal use, map the Shield Settings button to HA Quick Access as described
in the release checklist. For a Home Assistant automation, either run the
launch command above or configure the release package `dev.haquickaccess.tv`
in the Android Debug Bridge integration's application list and select that
media-player source.
