# Shield upgrade validation — November 16, 2026

The signed v0.3.0 APK was installed over v0.2.0 on a physical NVIDIA Shield
running Android 11/API 30. Android reported a successful update to versionCode
3. The saved HTTPS connection, encrypted token, four favorites, and disabled
Home channel survived the update. No app data was cleared.

Direct network ADB was reachable but unauthorized, without a visible approval
prompt. Deployment and testing used the Shield's existing authorized Home
Assistant ADB connection. APK hashes were verified before installation.

## Defects reproduced and fixed

- Initial synchronization failed with “Home Assistant sent an oversized
  message.” The server had roughly 3,500 entities and a 1.6 MB state snapshot,
  exceeding the client's 1 MiB character limit. The limit is now eight times
  larger; unsupported entities are still discarded. Over-limit payloads stop
  the session without an automatic retry loop.
- A dimmable light reported `brightness: null` while off, hiding its brightness
  controls. Capability detection now uses `supported_color_modes`, with current
  brightness retained as a compatibility fallback. This follows the
  [Home Assistant light contract](https://contributors.home-assistant.io/core/entities/light/#color-modes).
- The empty dashboard incorrectly offered first-use setup when saved favorites
  could not load. It now preserves that distinction and offers Diagnostics.
  Toggle-only controls no longer advertise unavailable detail actions.
- The warm-return helper could capture the preceding cold launch's `TotalTime`
  when it relaunched during the Home transition. It now waits one second after
  Home before starting the measured return.
- Disabling the Home channel through configuration import discarded its saved
  ID without deleting the provider entry. The receiver now removes the channel
  before clearing that ID, under the existing publication transaction lock.
  Invalid imports are validated before any provider deletion.
- The setup address and token fields trapped D-pad Down on the physical TV.
  Both now explicitly move focus to their next action, using the existing TV
  text-field focus mechanism. An instrumentation regression completes setup
  with D-pad navigation after entering each value.

The installed follow-up is versionName 0.3.1/versionCode 4, signed with the
existing release key. The v0.3.0 release and its assets are unchanged.

## Artifact and local regression evidence

The final signed validation APK has SHA-256
`51cdbf314ae85cd4a45feb6a3719b2fec7917375d971dbe60c80085cb4b38bf7`.
All 134 JVM tests, both 80% coverage gates, debug/release lint, and all 25
instrumentation tests on an API 36 Android TV emulator passed. Regression
coverage includes a snapshot above the old limit, continued state updates,
unsupported-entity filtering, off-state dimmers, and recovery from a failed
initial connection without losing favorites.

Remote D-pad injection on the physical Shield verified the dimmer/UI fixes in
APK `6f4f132598976f13eda99ae0bacbb7e053ef4c80c20c176d52fca4a9934e7386`:

- Long Select opens an off-state dimmer with Cancel focused.
- A staged 55% value leaves the real light off until applied.
- Back cancels and restores focus to the original favorite.
- The first normal Select after returning toggles the selected light.
- Apply 50% produces Home Assistant brightness 128, and live state updates
  reach the tile. A transient Home Assistant scene restored the original off
  state after each action; the final state was verified and the scene removed.

The system Home-channel consent prompt, visible launcher cards, and actual
Toggle, Focus, and Details launches passed. The subsequent APK
`457ec519fa531a92b27db91afde0db67f686697f4943d8f2989ef9de93d918c9`
added the ADB channel cleanup fix. With that APK, an invalid HTTP import requesting
channel removal was rejected without changing the active channel. A valid
disable import then removed the visible channel and all three cards, cleared
the temporary shortcuts, and retained the connection and four favorites.
Temporary import files were automatically deleted.

The final APK above adds the setup focus fix. On the physical Shield, Down
moved from the URL field to Continue and from the token field to Edit address;
Right reached Validate and save. A temporary local HTTPS endpoint with a
self-signed certificate was rejected with a certificate trust error using
dummy credentials. The test server recorded the certificate-rejection alert.
The original saved connection and favorites remained intact, and the app
reconnected after leaving setup. The temporary server and certificate were
removed. A preceding public expired-certificate endpoint timed out, so that
attempt is not counted as a certificate-validation pass.

HTTP, credential-bearing, and fragment-bearing configuration URLs were
rejected on the physical device without changing its saved connection.
The user verified that the physical Settings button opens Shield Settings and
explicitly chose to keep that mapping.

Raw command output, hierarchy captures, APKs, and physical screenshots remain
in ignored `build/shield-validation/`. Private endpoint and entity names are
not included in this report.

## Performance evidence from the final installed APK

These measurements used the final signed APK identified above, including the
setup focus fix. The device retained its four configured favorites. Cold and
warm timings each include ten samples; warm returns wait one second after
Home before starting the measured activity. The lifecycle test includes thirty
Home-return cycles, with checkpoints after every ten cycles.

| Measurement | Result |
| --- | --- |
| Ten cold launches | Median 451 ms; maximum 487 ms |
| Ten returns after Home settled | Median 97 ms; maximum 137 ms |
| Five D-pad traversals | 424 rendered frames; 3 janky frames (0.71%) |
| Retained `gfxinfo` frame records | 119 valid records; P95 overrun −3.25 ms; P99 −2.35 ms |
| PSS at 0 / 10 / 20 / 30 Home-return cycles | 89,443 / 68,717 / 96,154 / 85,406 KB |
| RSS at the same checkpoints | 201,896 / 184,860 / 212,832 / 202,584 KB |
| Retained UI objects | One Activity, one ViewRoot, seven Views at every checkpoint |
| Application threads | 26 at every checkpoint |
| Application TCP connections | One foreground HTTPS connection, zero in the background, one after return |

API 30 did not expose `FrameDeadline`; the repository's legacy parser estimated
the display interval from vsync records. These are bounded retention samples,
not a proof that every leak or race is absent. Neither PSS nor RSS alone
certifies the Android TV low-memory-device budget.

## Scope and remaining checks

The previously documented physical 30-tile and ≥120-fps pending-feedback
waivers remain exceptions, not passing measurements. No pending-latency claim
is made. The 30-tile automated benchmark remains required.

The Shield uses 4 KB pages, so it cannot validate a 16 KB runtime. A separate
modest physical TV, TalkBack, fresh remote-only setup with the on-screen
keyboard, and deliberate network-outage recovery require their own recorded
checks. Alarm arming,
garage/door motion, and climate changes were not exercised on household
devices. Play Console and policy-publication requirements remain external
release gates.
