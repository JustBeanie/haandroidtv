---
name: HA Quick Access
description: Immediate household controls from a TV remote.
colors:
  background: "#080D19"
  surface: "#121B2C"
  surface-focused: "#1A2942"
  surface-active: "#211F1C"
  border: "#263753"
  focus: "#59D5FF"
  cyan: "#9CE7FF"
  active: "#FF9800"
  connected: "#4CAF50"
  error: "#FF6B78"
  text: "#F6F8FF"
  muted: "#AFBED4"
typography:
  display:
    fontFamily: "sans-serif"
    fontSize: "30sp"
  headline:
    fontFamily: "sans-serif"
    fontSize: "28sp"
  dialog-title:
    fontFamily: "sans-serif"
    fontSize: "25sp"
  title:
    fontFamily: "sans-serif"
    fontSize: "18sp"
  body:
    fontFamily: "sans-serif"
    fontSize: "17sp"
  control:
    fontFamily: "sans-serif"
    fontSize: "16sp"
  label:
    fontFamily: "sans-serif"
    fontSize: "14sp"
  compact-hint:
    fontFamily: "sans-serif"
    fontSize: "13sp"
rounded:
  context: "8dp"
  field: "10dp"
  card: "12dp"
  dialog: "16dp"
  row: "18dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  body: "16dp"
  card: "18dp"
  section: "20dp"
  dialog: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.background}"
    typography: "{typography.control}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.control}"
  button-focused:
    backgroundColor: "{colors.text}"
    textColor: "{colors.background}"
    typography: "{typography.control}"
  button-destructive:
    backgroundColor: "rgba(255,107,120,0.2)"
    textColor: "{colors.text}"
    typography: "{typography.control}"
  text-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.field}"
    padding: "14dp"
  navigation:
    textColor: "{colors.text}"
    typography: "{typography.title}"
    padding: "12dp 20dp"
  control-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    padding: "{spacing.card}"
  control-card-active:
    backgroundColor: "{colors.surface-active}"
    textColor: "{colors.active}"
    rounded: "{rounded.card}"
  control-card-focused:
    backgroundColor: "{colors.surface-focused}"
    textColor: "{colors.text}"
    rounded: "{rounded.card}"
  list-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.title}"
    rounded: "{rounded.row}"
    padding: "17dp 20dp"
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.dialog}"
    padding: "{spacing.dialog}"
  focused-context:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    typography: "{typography.label}"
    rounded: "{rounded.context}"
    padding: "12dp 18dp"
---

# Design System: HA Quick Access

## Overview

**Creative North Star: "Immediate household controls from a TV remote"**

Native Android TV Material surfaces make household controls legible from a sofa and predictable with a directional pad. Dark navy grounds the interface; restrained tonal layers separate controls, cyan identifies focus, and amber identifies active device values.

The system favors familiar tabs, pill buttons, rounded cards, and scrollable dialogs. Android system typography is intentional. This is a native Compose app, with no website font or browser layout requirement.

**Key Characteristics:**

- Clear remote focus, persistent context, and TV-safe edges.
- Tonal surfaces with semantic color and actual device state.
- Adaptive density that preserves readable text and reachable actions.

The tokens above are extracted from the implementation. Native dimensions use **dp**; text uses **sp** and respects Android font scaling. Font weight, line height, button shape, tab indicator shape, and unspecified transitions inherit Android TV Material defaults. The sidecar's HTML/CSS snippets are illustrative panel previews only; they are not the app runtime or a pixel-equivalent Android renderer.

## Colors

Cool navy surfaces establish the base, with bright focus and state colors carrying functional meaning.

### Primary

- **Focus Cyan** (`focus`, source `HaBlue`): focused outlines and primary button fill.
- **Soft Cyan** (`cyan`, source `HaCyan`): inactive control icons and values, contextual emphasis, and the text caret.

### Secondary

- **Active Amber** (`active`): active control values, active borders, and value-stepper fill.

### Tertiary

- **Connected Green** (`connected`): connected status and selected management controls.
- **Attention Red** (`error`): errors, unavailable controls, alarm state, and destructive-action tint. Red alarm state does not itself mean a failed connection.

### Neutral

- **Night Navy** (`background`): full-screen ground.
- **Control Navy** (`surface`): controls, rows, context strips, and dialogs.
- **Focus Navy** (`surface-focused`): focused cards and the inactive selected-tab pill.
- **Active Charcoal** (`surface-active`): active, unfocused device cards.
- **Slate Border** (`border`): resting card and field strokes, stepper track.
- **Near White** (`text`): primary labels and focused button fill.
- **Muted Blue Gray** (`muted`): descriptions, field labels, and non-connected status text.

**The Focus Is Separate Rule.** Focus identifies the current remote target; amber identifies device activity. A focused active card retains its amber value while receiving the cyan focus outline.

**The Truthful State Rule.** Connection and command states are written in text. Cached values read as last known; pending and failed actions keep their own feedback instead of implying success.

## Typography

**Display Font:** Android system sans-serif.  
**Body Font:** Android system sans-serif.

The app uses the platform's familiar TV text rendering. Sizes form a functional hierarchy; the implementation does not install a custom font or a custom weight/line-height scale.

- **Display:** setup, diagnostics, and privacy headings.
- **Headline:** main destination headings.
- **Dialog title:** common entity-detail heading; confirmation and browser dialogs also use a local 26sp title.
- **Title:** tile names, list-row titles, and navigation tabs.
- **Body:** fields, setup explanation, and detail copy.
- **Control:** button labels, state values, and supporting screen copy.
- **Label:** field labels, row descriptions, connection state, and context hints.
- **Compact hint:** the focused action hint when available content height is constrained.

Tile names and values allow two lines with ellipsis; context-strip names use one line. The compact header retains the action hint instead of retaining a duplicate screen title.

## Layout

The root reserves 5% of each screen dimension, with minimum safe margins of 24dp horizontally and 20dp vertically. Navigation and connection status sit above the content region with the section spacing token between them.

Favorites uses three columns below 780dp of available grid width, four from 780dp, and five from 1100dp. Grid gaps are 20dp and content padding is 4dp. Tile minimum height is 128dp multiplied by the Android font scale, clamped to at least 1. Two complete rows are used only when the grid height supports twice that minimum plus 28dp; otherwise it scrolls as a single visible row. Ordinary tile height caps at 142dp, rising when the scaled minimum is larger.

Favorites and All controls use their compact form below 360dp of available content height. Favorites keeps a focused action hint; All controls removes the duplicate heading while retaining search, filters, and results. Settings keeps its heading outside the scrolling list.

Setup is centered within a maximum width of 560dp; fields cap at 520dp. Setup scrolls and accommodates the keyboard. Dialogs occupy at most 90% of window width, normally cap at 600dp, and scroll within 85% of window height. Detail action rows wrap when necessary.

**The Fit Before Density Rule.** Preserve safe edges, font scaling, and reachable controls; reduce visible rows and use scrolling when the viewport cannot hold the full composition.

## Elevation & Depth

Depth comes from surface tones, outlines, and small focus scaling. Dashboard cards explicitly have zero shadow elevation. Shared surfaces and buttons retain native TV Material behavior; the app defines no reusable custom shadow system.

Focused cards scale to 1.025 over 150ms. Shared focus surfaces and buttons scale to 1.03, while list rows use 1.015. Keep focus changes small enough that neighboring controls and labels remain readable.

## Shapes

Use the frontmatter's context, field, card, dialog, and row corner roles. Native buttons and tab indicators retain pill silhouettes from TV Material. Device icons sit in circular tinted containers. Use vector icons with actual control meaning: Fan uses the bundled Material fan vector; Button uses TouchApp.

Resting cards and fields have a 1dp stroke. Fields and shared focus surfaces use a 2dp cyan focus stroke; dashboard cards use 3dp. An active card's resting amber border is 72% opaque. Its icon tint is 18% opaque in the circular background.

## Components

### Buttons

Use `TvButton` through `HaButton`. Buttons have a minimum height of 48dp. Primary, secondary, and destructive variants use the recorded colors; all become near-white with dark text when focused. Native disabled rendering is inherited. Filters reuse these buttons, with the chosen filter using the primary variant; there is no separate chip system.

### Cards / Containers

Favorites cards place the icon and state above the device name, using the card padding token. Focus takes precedence for background and border; activity remains visible in the value and icon. Pending commands keep the card focusable while repeat activation is ignored. Offline and unavailable controls expose their disabled state without dropping the remote target.

### Inputs / Fields

Single-line fields have a separate muted label, a minimum height of 48dp, and a soft cyan caret. Focus changes the border. Tokens and secret values use the native password transformation. All controls search transfers D-pad Down into its results list.

### Navigation

Three native tabs lead to Favorites, All controls, and Settings. The selected destination remains identifiable while focus moves. The status label stays alongside navigation. Tab Down moves into content; the top Favorites row returns Up to navigation. Retain the platform focus restoration used by the content region.

### List Rows

Shared rows have a minimum height of 82dp, title and muted subtitle, and a trailing vector chevron. Settings and All controls reuse this pattern. The row's subtle focus scaling supplements its cyan border and brighter surface.

### Dialogs and Value Controls

Reuse `TvDialog` for details and confirmation. Content scrolls within the bounded panel. Value steppers pair native decrement/increment buttons with a 12dp-high amber progress fill on the slate track; the fill reflects the staged value. Detail action rows wrap, and Back closes the current interaction.

### Focused Control Context

The standard Favorites context strip names the focused entity and describes Select or Hold Select. In compact mode, the hint moves into the header. Failure context remains visible with a dismiss action. Keep instructions tied to the actual focused entity and command feedback.

## Do's and Don'ts

### Do:

- **Do** use native TV Material controls and the Android system typeface.
- **Do** preserve TV-safe margins, font scaling, and visible remote focus.
- **Do** distinguish device activity, remote focus, connection state, and command feedback.
- **Do** retain a focused action hint in compact Favorites layouts.
- **Do** reuse semantic vector icons and the shared button, field, row, and dialog components.

### Don't:

- **Don't** use cached states as evidence that controls are live.
- **Don't** force two card rows when the available height cannot fit them.
- **Don't** remove the current remote target while a command is pending.
- **Don't** treat illustrative HTML previews or synthesized tonal ramps as native runtime tokens.

Implementation evidence: `HaQuickAccessApp.kt`, `TvNavigation.kt`, `TvComponents.kt`, `UiPrimitives.kt`, `DashboardScreen.kt`, `AllControlsScreen.kt`, `ConnectionSettingsScreens.kt`, and `ControlDetails.kt` under `app/src/main/java/dev/haquickaccess/tv/ui/`. PRODUCT.md remains the product authority.

