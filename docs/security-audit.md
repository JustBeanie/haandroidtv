# Security audit record

This document is the review template for each release candidate. Never paste
tokens, alarm codes, signing passwords, private keys, or unredacted logs here.

## Scope and threat model

- Commit/release: <!-- redacted hash or tag -->
- Reviewer/date: <!-- name/date -->
- Android TV versions/devices: <!-- versions -->
- Test Home Assistant: disposable instance with least-privilege token
- Threats: malicious local app/launcher, crafted deep link, hostile LAN/server,
  compromised dependency or CI action

## Evidence checklist

| Area | Evidence | Result | Finding IDs |
|---|---|---|---|
| Exported components and deep links | merged release manifest, `LaunchIntentValidatorTest`, stale/duplicate launch tests | Pass locally; instrumentation runs in CI | |
| Token/Keystore and backup | `SettingsRepositoryInstrumentedTest`, `TokenCipher`, backup/data-extraction XML, clear-on-delete path | Pass by inspection; instrumentation runs in CI | |
| HTTPS/WSS and certificate validation | `UrlValidatorTest`, `HomeAssistantProtocolTest`, OkHttp default TLS client configuration | Pass by inspection/tests | |
| Home Assistant commands and confirmations | command-factory tests, `DashboardViewModelTest` cover/alarm/launcher flows | Pass locally; UI instrumentation runs in CI | |
| Malformed/oversized protocol input | protocol mapping tests, oversized WebSocket test, capped message parser | Pass locally | |
| APK/release configuration | debug/release lint and builds, merged-manifest gate, APK string scan | Pass locally | |
| Secrets and signing custody | redacted tracked/history/build scan; local signing-file custody review | Pass; owner confirmed custody is safe | |
| Dependencies/plugins/actions | debug/release Gradle reports, build-environment reports, CI OSV/dependency-review | CI vulnerability result required | SEC-003 |

## Audit matrix

| Threat / data flow | Code path | Control and regression evidence | Residual risk |
|---|---|---|---|
| Crafted external launch | `AndroidManifest.xml` → `MainActivity.dispatchLaunchIntent` → `LaunchIntentValidator` | Exact scheme/host, one path segment, entity-ID and behavior allowlists; validator tests | Any installed caller can invoke the public deep link, by design; it cannot supply a token or bypass the live-state lookup. |
| Replay or stale launcher target | `DashboardViewModel.handleLaunchRequest` and pending request collector | Monotonic request sequence, latest-request replacement, live entity resolution, stale-shortcut recovery tests | A valid old shortcut can still request the current entity action; users must revoke unwanted Home shortcuts. |
| Token entry and persistence | Compose setup state → `SettingsRepository.saveConnection` → `TokenCipher` → DataStore | HTTPS/blank/length validation, AES-GCM Android Keystore envelope, clearConnection deletes both key and store | Plaintext exists transiently in UI/repository memory while connecting; Android memory compromise is outside this control boundary. |
| Network authentication | `HomeAssistantRepository` → `HomeAssistantWebSocket` → OkHttp | URL is normalized to HTTPS; WebSocket is derived as WSS; default OkHttp hostname/certificate validation remains enabled; timeout/reconnect tests | User-controlled CA/proxy/device trust configuration can change platform trust; certificate pinning is not configured. |
| Hostile server payload | WebSocket listener → JSON parser → entity/state map | 1 MiB frame cap, malformed JSON ignored, entity/state bounds, failed-session reset and pending-request completion | 1 MiB is bounded but may still be expensive on constrained devices; no live adversarial server test is included. |
| Unauthorized service call | UI/shortcut → `DashboardViewModel` → `HomeAssistantCommandFactory` | Domain/action compatibility, finite/range checks, bounded strings, alarm-mode allowlist; command and confirmation tests | Home Assistant token permissions remain the final authorization boundary. |
| Sensitive controls | cover/alarm detail flows and launcher behavior | Secure cover open confirmation; alarm requires fresh entered code; external launch only supports toggle/focus/details and never accepts a code | A user who has access to the app can operate configured controls, as intended. |
| Release/build supply chain | Gradle version catalog, wrapper, R8, GitHub Actions | Dependency and plugin reports, release lint/R8 build, read-only workflow permissions, OSV and dependency-review gates | Action tags are maintained references rather than immutable SHAs; CI must review scanner output before release. |

## Findings

For each finding record severity (Critical/High/Medium/Low), confidence,
exploit preconditions, impact, exact code path, evidence command or test,
recommended remediation, regression test, owner, and status.

### Current backlog

| ID | Severity | Status | Evidence and remediation |
|---|---|---|---|
| SEC-001 | High | Closed | Local ignored signing material was not tracked or present in Git history/build artifacts. Owner confirmed the key is safe; retain it outside source control and CI logs. |
| SEC-002 | Medium | Closed | Local unit tests, lint, debug APK, release compilation/R8, release lint, release APK packaging, and merged-manifest checks pass with Java 17 and a single `ANDROID_USER_HOME`. |
| SEC-003 | High | Pending CI | Initial OSV scan identified vulnerable build-tool transitive dependencies; they are now forced to fixed versions and the locked graph rescans clean locally. CI OSV/dependency-review results are still required before release and all critical/high results must be triaged. |

### Dependency vulnerability remediation

The initial OSV scan reached these packages through Android lint and Unified
Test Platform configurations (not the shipped runtime APK). Compatibility was
verified by regenerating `app/gradle.lockfile` and completing the debug/release
builds and lint tasks.

| Advisory | Affected coordinate/version | Reachable configuration | Fixed/resolved version | Status |
|---|---|---|---|---|
| GHSA-558v-64gr-wgg4 (8.7) and related Netty advisories | `io.netty:*` 4.1.93.Final / 4.1.110.Final | Unified Test Platform host/core | 4.1.137.Final | Remediated; OSV clean |
| GHSA-j288-q9x7-2f5v (6.5) | `org.apache.commons:commons-lang3` 3.16.0 | `androidLintTool`, UTP result listener | 3.18.0 | Remediated; OSV clean |
| GHSA-7r82-7xv7-xcpj (5.3) | `org.apache.httpcomponents:httpclient` 4.5.6 | `androidLintTool`, UTP result listener | 4.5.13 | Remediated; OSV clean |
| GHSA-wg6q-6289-32hp (6.3) | `org.bouncycastle:bcpkix-jdk18on` 1.79 | `androidLintTool`, UTP result listener | 1.84 | Remediated; OSV clean |
| GHSA-574f-3g2m-x479 (9.3) and related Bouncy Castle advisories | `org.bouncycastle:bcprov-jdk18on` 1.79 | `androidLintTool`, UTP result listener | 1.84 | Remediated; OSV clean |

The temporary local OSV scan found 384 locked Maven packages and returned no
issues after remediation. CI repeats the scan from a clean checkout and must
be the release authority.

### Implemented controls and verification notes

- External control deep links are restricted to the `haquickaccess://control`
  contract, supported entity-ID syntax, and `toggle`/`focus`/`details` behavior.
- Connection persistence revalidates URLs through the strict HTTPS normalizer,
  rejects blank/oversized tokens, and stores only the encrypted envelope.
- WebSocket entity parsing rejects malformed identifiers/oversized state values;
  oversized messages fail the active session closed.
- The command factory validates entity domains, numeric ranges, finite values,
  alarm modes, and bounded user-provided strings before serialization.

The local runner also checks that only the intended exported activity and the
platform Profile Installer receiver appear in the merged release manifest,
scans tracked files/history/build outputs for secret-shaped material without
printing matches, and writes dependency evidence under `build/security-audit/`.
The local environment currently lacks OSV Scanner and an Android TV emulator.
The `android-tv-instrumentation` CI job runs the repository’s instrumentation
and UI scenarios on an Android TV profile; a completed CI result is still
required before release. These are explicit evidence gaps, not claims of a
clean vulnerability or runtime result.

## Release decision

- Critical/high findings: SEC-003 remains open until CI evidence is attached
- Known exploitable dependency issues: release blocked until OSV/dependency-review output is reviewed
- Signing material: owner-confirmed safe; continue keeping it outside source control and CI logs
- Reviewer approval: <!-- name/date -->
