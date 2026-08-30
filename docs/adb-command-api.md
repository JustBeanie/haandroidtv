# ADB command API

HA Quick Access exposes a device-local API through an exported broadcast
receiver protected by `android.permission.DUMP`. The Android shell user used by
ADB can call it; ordinary third-party applications cannot call it without the
protected permission.

Use the release component for the installed release package:

```text
PACKAGE=dev.haquickaccess.tv
RECEIVER=$PACKAGE/.data.AdbCommandReceiver
```

For a debug build, replace `dev.haquickaccess.tv` with
`dev.haquickaccess.tv.debug` everywhere, including the configuration directory.

## Import configuration

Write a temporary JSON file named exactly
`ha-quick-access-config.json` into the app-specific external Documents
directory. Nullable fields are left unchanged; an empty array explicitly clears
that list.

```json
{
  "base_url": "https://ha.example.net",
  "token": "LONG_LIVED_ACCESS_TOKEN",
  "tiles": [
    "light.kitchen",
    "switch.living_room"
  ],
  "shortcuts": [
    { "entity_id": "light.kitchen", "behavior": "toggle" },
    { "entity_id": "switch.living_room", "behavior": "focus" }
  ],
  "home_channel_enabled": true
}
```

The token is accepted only from this temporary file, not from broadcast extras.
The app encrypts it with Android Keystore and deletes the import file after the
import attempt. Protect the file while transferring it and do not commit it or
leave it in shared storage.

```powershell
$package = "dev.haquickaccess.tv"
$remoteFile = "/sdcard/Android/data/$package/files/Documents/ha-quick-access-config.json"
adb shell mkdir -p "/sdcard/Android/data/$package/files/Documents"
adb push .\ha-quick-access-config.json $remoteFile
adb shell am broadcast -n "$package/.data.AdbCommandReceiver" `
  -a "${package}.action.CONFIGURE" `
  --es "${package}.extra.CONFIG_FILE" $remoteFile
```

The import validates HTTPS URL syntax, credential pairing, Home Assistant entity
IDs, shortcut behaviors, duplicate entries, and size limits before writing any
settings. A successful command returns `result=0` and `Configuration applied`.

## Query and clear

The query never returns the token. It returns the normalized URL, whether a token
is configured, dashboard entities, Home screen shortcuts, and channel state.

```powershell
adb shell am broadcast -n "$package/.data.AdbCommandReceiver" `
  -a "${package}.action.QUERY"
```

To remove the Home channel and all saved connection, tile, shortcut, and focus
settings:

```powershell
adb shell am broadcast -n "$package/.data.AdbCommandReceiver" `
  -a "${package}.action.CLEAR_CONFIGURATION"
```

## Control and launch

The control action starts the app with a validated entity request. Supported
behaviors are `toggle`, `focus`, and `details`; omitted behavior defaults to
`details`.

```powershell
adb shell am broadcast -n "$package/.data.AdbCommandReceiver" `
  -a "${package}.action.CONTROL" `
  --es "${package}.extra.ENTITY_ID" "light.kitchen" `
  --es "${package}.extra.BEHAVIOR" "toggle"
```

The existing URI form is also supported and is useful for Android TV launcher
and Home-channel integrations:

```powershell
adb shell am start -W -a android.intent.action.VIEW `
  -d "haquickaccess://control/light.kitchen?behavior=toggle" `
  -n "$package/dev.haquickaccess.tv.MainActivity"
```

Configuration import starts or brings the app to the foreground. If the Home
channel was enabled or shortcuts changed, the app waits for initial Home
Assistant state and republishes the channel.
