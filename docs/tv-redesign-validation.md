# TV redesign validation

This redesign uses Favorites, All controls, and Settings as the top-level TV destinations. Select keeps quick actions on favorite cards; holding Select opens details where supported. All controls supports search, domain filters, and pinning. Setup separates the address and masked token, and forgetting the connection requires confirmation.

## Reliability changes

- WebSocket callbacks are serialized and scoped to a connection generation. Old callbacks and cancelled requests cannot tear down replacement sessions.
- Readiness requires authentication, an acknowledged subscription, and a complete state snapshot. Changes received during synchronization are merged before controls become available.
- Initialization and commands time out; pending commands and initialization buffers are bounded. Commands are never replayed after reconnecting.
- Connection validation uses a separate gateway. Backgrounding cancels pending validation and commands, stops the active feed, and clears transient token/alarm text.
- Favorites and shortcuts update from current DataStore values in one transaction. Home channel publication and configuration changes share a transaction lock.
- Cached tiles use schema version 2 and a hash of the saved connection identity. Older/unmatched caches are not displayed. Snapshot projections and focus writes are deduplicated/debounced.
- The large dashboard image and repeated snapshot projections were removed. Native TV buttons, surfaces, and tab navigation replace custom controls where appropriate; screens are separated from their event contract and ViewModel.

## Reproducible local checks

Use JDK 17 and an Android SDK with platform 36. Run builds sequentially in one checkout, particularly on Windows where overlapping Gradle builds can hold the same output JAR open.

~~~sh
./gradlew :app:testDebugUnitTest :app:koverXmlReportDebug :app:koverVerifyDebug
./gradlew :app:lintDebug :app:lintRelease :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:generateReleaseBaselineProfile
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true
~~~

The CI UI matrix covers Android TV API 33/x86 and API 36/x86_64. The first-launch smoke test clears its own persistent connection before launching the real activity and waits for DataStore-backed setup visibility. Both workflows use the shared emulator readiness and failure-diagnostic scripts. Failures upload logs, window hierarchy, screenshots, and memory information.

~~~powershell
./tools/validate-shield.ps1 -SelfTest
./tools/security-audit.ps1 -SkipDependencyScan
./tools/verify-release-artifacts.ps1 -Artifact app/build/outputs/bundle/release/app-release.aab
~~~

The artifact verifier checks the icon (512 × 512), feature graphic (1024 × 500), separate TV banner (1280 × 720), all four native ABIs, and 16 KB ELF load-segment alignment for the 64-bit libraries. Bundletool validation and its PAGE_ALIGNMENT_16K configuration must also pass. A private upload key is required for bundleRelease; signing material remains ignored.

## Evidence from this implementation

On November 16, 2026, 131 JVM tests passed. Coverage was 97.30% of lines and 80.68% of branches, with both existing 80% gates retained. All 23 instrumentation tests passed on each of API 33/x86 (1 GB RAM) and API 36/x86_64 TV emulators. Debug and release lint passed with 25 advisory warnings, including newer available dependencies and vector paths. The Shield helper self-test passed all 10 checks.

OSV-Scanner 2.7.1 checked 377 locked packages and reported no vulnerabilities. The repository security audit passed. The signed bundle passed bundletool validation, the ABI/ELF/asset verifier, and PAGE_ALIGNMENT_16K inspection; the release APK passed zipalign with 16 KB alignment. Jarsigner reported “jar verified” and also emitted self-signed certificate, timestamp, and ZIP stream-order warnings (recorded in `build/final-signature.txt`); Play Console acceptance remains an external gate. Generated test, lint, coverage, benchmark, and audit reports remain in the ignored build directories.

The final signed AAB is 5,221,064 bytes with SHA-256 `DB7BFD548271F0AC1F1C52848E20CC4C85D6A12552F9B18E6139D1893D609AD1`. The release APK is 2,870,646 bytes.

A minified benchmark build with 30 synthetic controls was exercised through 30 Home/return cycles on the 1 GB API 33 emulator. PSS was 51,599 / 56,141 / 55,419 / 56,903 KB at cycles 1 / 10 / 20 / 30; RSS was 102,920 / 110,244 / 109,144 / 109,724 KB. Every sample contained one Activity, one ViewRoot, and seven Views. This is a bounded retention check, not proof of no leaks. SwiftShader reported zero app Graphics memory, so these samples cannot certify the full physical-TV memory budget.

Native captures cover setup, Favorites, All controls, Settings, and light details at 1920 × 1080, 3840 × 2160, and 1280 × 720 with 1.3× text. An independent visual review found four issues: missing compact action hints, initial Settings heading clipping, misleading Fan/Button icons, and a dark text caret. After one correction batch, the reviewer scored all four resolved (`disposition: ship`, scoped to those fixes). Five current 1080p listing captures are checked in under `play-store-assets/screenshots`.

Release baseline and startup profiles were regenerated from the final UI, each containing 18,530 rules. The profile journey waits for the restored dashboard card after Back before sending a details command.

All four macrobenchmark journeys completed in emulator dry-run mode; the four measured journeys passed, with the baseline-generation rule skipped for this smoke run. These exercise cold start, grid traversal, settings return, and details/Home return. Timing values from this environment are not release performance claims.

## Release gates that require external validation

- Physical Shield and modest-TV remote testing, sustained reconnect/background cycles, Home channel behavior, TalkBack, and memory profiling on the actual supported devices.
- For a 1 GB low-memory TV, measure Android's documented total memory categories (Anon + Swap + Graphics + File), not just PSS. The documented ceiling is 280 MB; the recommended Anon + Swap + Graphics target is 200 MB or less.
- A 16 KB-page-size runtime/pre-launch check, in addition to the binary alignment checks.
- Synchronize the separately hosted privacy policy with the updated local/in-app policy, which now explicitly describes cached favorite names and states. This task did not publish to the separate policy repository.
- Play Console internal-track upload, reviewer access credentials, policy/data-safety declarations, and the Play pre-launch report. No upload or publication is performed by these changes.
- Run the updated GitHub Actions workflows after pushing this branch; local equivalent checks do not establish a hosted CI result.
- The historical Shield validation and performance waivers predate this redesign; they do not certify this build. Emulator timings are diagnostic and are not physical-TV performance claims.

## Standards checked

- [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
- [Android TV memory guidance](https://developer.android.com/training/tv/playback/memory)
- [Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Play preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151)

The app remains TV-only, minSdk 26, targetSdk 36, with a Leanback launcher, no touchscreen requirement, HTTPS/WSS-only access, disabled backups, and Keystore-encrypted credentials. These are implementation checks, not a guarantee of Play approval.
