# TV-first GUI overhaul plan

## Objective

Make HA Quick Access feel native when it is opened from a remote shortcut, an
Android TV Home/Projectivy tile, or another TV app. Every screen must be
usable with a D-pad, visibly communicate focus, and return to the right
control without exposing the Home Assistant token or keeping a background
connection alive.

## Current findings

- BedroomTvPro launches and resumes `dev.haquickaccess.tv.debug` successfully;
  no app crash was found in the device log sample.
- The current BedroomTvPro dashboard is live with four 4K-safe, D-pad
  focusable tiles: Kitchen Island Lights, Upper Cabinet Lights, Kitchen
  Overhead, and Kitchen Under Cabinet. The live `Focus` URI for
  `light.kitchen_island_lights` focused the configured card without changing
  its `Off` state.
- TV text entry follows the platform keyboard convention: Back dismisses the
  IME, and the next D-pad Down moves focus from search to the All filter. This
  was verified on the Shield after the manager focus handoff fix.
- A Home-channel `Focus` deep link only persisted the selected entity. It did
  not command Compose to move D-pad focus to that tile after returning from
  another app. This has been fixed with a one-shot, acknowledged focus request
  and regression test.
- The dashboard now uses a 5% overscan-safe content frame, adaptive 3/4/5
  column grid, animated scale/elevation/outline focus feedback, and a focused
  control context strip. The dedicated tile move mode is now implemented; the
  remaining work is launcher-specific validation and reducing duplicated
  focus-surface code rather than the core TV shell.
- Configured tiles can now be reordered with position-aware `Earlier` and
  `Later` remote actions; the first and last actions are disabled at their
  respective bounds. The manager also shows a visual current-order preview and
  a dedicated move mode with explicit `Earlier`, `Later`, and `Finish moving`
  actions.
- The app already publishes standard Android TV preview programs, which is the
  right interoperability contract for launchers including Projectivy. The
  BedroomTvPro does not currently have Projectivy installed, so Projectivy
  presentation still needs on-device validation.

## Product principles

1. **D-pad first.** Every actionable item has a deterministic initial focus,
   visible focus halo, scale/elevation feedback, and sensible return focus.
2. **Immediate orientation.** A launch lands on either the requested action or
   the last focused tile; it never leaves focus on a hidden or unrelated view.
3. **Fast, calm app switching.** Use cached tile state for the first frame,
   show connection refresh unobtrusively, and do not block navigation on the
   foreground WSS session.
4. **Large-screen legibility.** Use an overscan-safe content frame, minimum
   target sizes, high contrast, concise labels, and no text-only primary cues.
5. **Safe actions stay explicit.** Destructive or security-sensitive controls
   retain confirmations. Quick toggles immediately provide visible success,
   pending, or error feedback.
6. **Launcher neutral, Projectivy polished.** Publish standard Android TV
   preview channels rather than depending on a private launcher API. Add a
   Projectivy setup helper only when its package is installed.

## Target information architecture

```text
Launch from another app / Home channel
        |
        +-- Toggle -> perform action -> focused tile + result feedback
        +-- Focus  -> scroll to and focus the matching dashboard tile
        +-- Details -> open a focused, dismissible detail sheet
        |
        v
Dashboard
        +-- Quick controls (adaptive grid)
        +-- Recent/pinned context and connection status
        +-- Settings
              +-- Tile management
              +-- Launcher & Projectivy setup
              +-- Diagnostics
```

## Design and implementation phases

### 1. TV shell and navigation foundation

- Replace per-screen ad-hoc focus styling with reusable `TvFocusSurface`,
  `TvButton`, `TvListRow`, and dialog primitives.
- Define a single content frame with 5% horizontal/vertical safe margins,
  adaptive column count, and minimum 48 dp D-pad targets.
- Keep a focus-restoration map by screen and route. A back action returns to
  the item that opened the screen or dialog.
- Add unobtrusive transient feedback for command pending/success/failure and a
  connection-status indicator that does not move focus.

**Acceptance:** cold launch, Home return, deep link, dialog close, and Back
all leave exactly one visible focused element.

### 2. Dashboard control redesign

- Use responsive 3/4/5-column control cards based on available width rather
  than a fixed four-column grid.
- Give each card a recognizable icon, short state line, pending progress, and
  a 4–6% animated focus scale with elevation/outline. Preserve spacing so
  focused cards never visually collide.
- Make action semantics consistent: Select performs the primary safe action;
  long Select opens details; unavailable controls remain visible but explain
  why they cannot be activated.
- Add a focused-card context strip (control name, action hint, connection
  state) for distant viewing.

**Acceptance:** a remote-only user can identify the selected card, activate a
safe control, open details, and move across every grid edge without focus loss.

### 3. Quick-launch behavior

- Retain the newly added one-shot `FocusRequest` model so a `Focus` deep link
  scrolls and moves real Compose focus to the target tile.
- Add explicit deep-link results: a toggle keeps the target visible and shows
  command progress/result; details always takes initial focus inside the
  dialog.
- Make invalid/stale shortcuts recover gracefully: show a focused message with
  a single `Manage controls` action instead of silently opening an unrelated
  dashboard.
- Cache the last rendered tile model locally for first-frame continuity while
  the foreground connection rehydrates.

**Acceptance:** launching from another app repeatedly, including the same tile
twice, always focuses or reports on the requested control within one D-pad
action; no shortcut can cause an invisible focus target.

### 4. Projectivy and Android TV Home tiles

- Keep using the standard `TYPE_PREVIEW` channel and `PreviewProgram` intent
  URIs; this is what Projectivy can discover through its channel editor.
- Publish four user-chosen quick-action cards with unique 16:9 artwork, the
  explicit 16:9 poster-art aspect ratio, state-aware title/description, stable
  internal IDs, and deterministic ordering/weights.
- Add `Projectivy detected` guidance when package
  `com.spocky.projengmenu` is present: `Projectivy settings > Edit Channels >
  HA Quick Access`. Do not attempt to change Projectivy's default-launcher or
  accessibility settings.
- Add a launcher's preview-channel diagnostics panel: channel ID, program
  count, browsable-request state, last refresh result, and a refresh action.
- Respect launcher removal/browsability callbacks so a user-hidden channel is
  not silently reinserted.

**Acceptance:** after enabling the HA Quick Access channel in Projectivy, all
four cards render without cropping, activate the correct deep link, and remain
stable across launcher restart and app update.

### 5. Settings and management usability

- Convert dense text/button rows into large, focusable list rows with a clear
  primary action and an overflow/detail path for destructive changes.
- Add a dedicated move mode to complement the existing visual order preview
  and position-aware `Earlier` / `Later` actions.
- Keep keyboard entry isolated to setup/detail screens and return focus to the
  invoking control when it closes.

**Acceptance:** configuration is comprehensible from ten feet away and can be
completed using only the Shield remote.

### 6. Validation

- Add unit tests for focus request sequencing, stale deep links, shortcut
  removal, and publisher ordering/aspect metadata.
- Add Compose/instrumentation tests for initial focus, D-pad traversal, Back
  restoration, deep-link focus, and detail-dialog focus trapping.
- Validate on BedroomTvPro with the stock launcher and, once Projectivy is
  installed, with Projectivy's channel enabled. Capture focus/window state,
  launch timing, and post-interaction `gfxinfo` separately from cold-start
  rendering.

## v0.2.0 audit and remaining work

The v0.2.0 codebase implements the adaptive dashboard, one-shot deep-link
focus requests, stale-shortcut recovery, reconnect-time tile caching,
per-control 16:9 Home-channel artwork, Projectivy detection guidance, and the
dedicated move mode. The JVM suite, Kover 80% line/branch gate, release build,
and Android lint all pass.

The following work remains before the plan's acceptance criteria can be
claimed as complete:

1. Extract the repeated focus styling into shared TV focus surface, button,
   list-row, and dialog primitives; add transient success feedback so actions
   report pending, success, and failure consistently.
2. Complete launcher diagnostics (channel ID, program count, browsable request
   state, and last refresh result), respect launcher-hidden/removal state, and
   add publisher ordering/aspect-metadata tests.
3. Run the physical Shield regression checklist and validate the channel on a
   Projectivy-equipped device. Those hardware/launcher results cannot be
   inferred from local unit or Compose tests.

## Rollout order

1. Run the phase 6 regression suite on BedroomTvPro, including move-mode and
   deep-link focus restoration.
2. Complete launcher diagnostics/callback handling and publisher metadata
   tests, then re-run the cross-launcher suite.
3. Install or identify a Projectivy-equipped test TV and validate the existing
   standard preview-channel presentation there.

This order avoids coupling the core TV UX to one launcher while still making
Projectivy a first-class, tested destination.
