# Architecture

The app uses a single foreground-only Home Assistant WebSocket. It authenticates
with the encrypted long-lived token, requests initial states, subscribes to
`state_changed`, and issues correlated `call_service` commands.
`HomeAssistantProtocol` keeps frame construction and entity mapping pure and
unit-testable; `CappedReconnectBackoff` bounds retries from one to thirty
seconds and resets after a good authentication. Leaving the activity closes the
socket.

`SettingsRepository` owns URL, encrypted-token envelope, tile order, launcher
shortcut configuration, and focus restoration. `TokenCipher` owns the Android
Keystore key; it never exposes encrypted values in logs. Before a connection is
persisted, the app performs a bounded authenticated WebSocket handshake; rejected
tokens stay only in memory and the prior foreground session resumes.

The UI is Compose for TV with immutable `DashboardUiState`. `DashboardViewModel`
maps Home Assistant state into controls and owns pending/error states. Its
foreground-session coordinator starts exactly one socket after saved settings are
available and stops it when the activity leaves the foreground. Platform code is
isolated in `HomeChannelPublisher`, which writes opt-in preview-channel entries
using `TvProvider` and deep-links back to the activity.

The JVM test gate covers pure data protocol/validation, domain, and view-model
logic at 80% line and branch coverage. Android-only adapters (Keystore,
DataStore, TV Provider, and Compose focus) are covered through instrumentation
tests on an Android TV emulator or a Shield.
