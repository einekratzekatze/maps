# GMaps Web Frontend – Project Goals

## Concept

An Android app that wraps the Google Maps **website** in a WebView, adding quality-of-life
features that the native Google Maps app gates behind an account or omits entirely from the
mobile web UI. Privacy handling is inherited from upstream and is not a development concern here.

Intended for cases where OpenStreetMap coverage is insufficient and the native Google Maps
app is undesirable.

## Based on

- [`Divested-Mobile/Maps`](https://github.com/Divested-Mobile/Maps) – original GMaps WV (archived)
- [`woheller69/maps`](https://github.com/woheller69/maps) – active fork providing the WebView base
- Both are minimal WebView wrappers with no account layer or list features — this project adds those

## Scope: What This Project Builds

All WebView plumbing, privacy features, and request handling are **upstream's responsibility**.
This project focuses exclusively on **user-facing features** layered on top.

---

## Features

### UI Cleanup via JS Injection
Remove clutter from the Maps web UI to improve usability:
- Sponsored results and promoted pins
- "Explore" recommendation popups
- Sign-in nag banners
- Bottom sheet ads and upsells

> **Note on SPA behaviour**: Google Maps loads content dynamically after `onPageFinished`.
> Injected JS will need a `MutationObserver` to catch async-rendered UI elements, not just
> a one-shot injection.

---

### Compose UI Shell
A Jetpack Compose shell around the WebView (TODO Phase 1):
- **Top bar** (`TopAppBar`) above the WebView with app title and a settings icon
- **Floating places bubble**: draggable, detached from map coordinates; expands into a
  hovering card with Home/Work shortcuts and saved places near the current viewport
- WebView hosted via `AndroidView` inside a `Scaffold`

### Settings Screen
A Compose settings screen (TODO Phase 7), persisted in **DataStore Preferences**:
preferred navigation app, Home/Work, external list refresh interval, default list,
"offer to save drive on Start", clear browsing data.

### Map Markers via JS Injection
Saved places are drawn as markers inside the Maps page itself (TODO Phase 6), so they
pan/zoom natively with the map (see Known Limitation 2):
- Capture the `google.maps.Map` instance by wrapping its constructor
- Android <-> JS bridge (`@JavascriptInterface`) to add/remove markers and report taps
- Marker tap opens the floating bubble on that place

### Custom Native Search Field
Replace the GMaps web search bar with a native Compose search field in the top bar
(TODO Phase 8), keeping GMaps' burger menu:
- Suggestions from saved places and recent searches (Room)
- Optional user-supplied Places API key for autocomplete (stored in DataStore)
- Hidden when GMaps is in directions mode

### Local Lists (Account replacement)
Save and organise places locally using **Room** — no Google account needed.

- Organize into named lists (e.g. "Restaurants", "To visit")
- Show saved places in the floating places bubble (and as map markers)
- Import/export as **KML or GPX** for interoperability with OsmAnd/Organic Maps
- Import from Google Takeout (Data Requests)

#### Important Locations (Home / Work)
- Special named anchors: Home, Work, + user-defined labels
- Stored separately from regular lists; survive list clears
- Appear as quick-access shortcuts in the app's navigation/search UI
- Pre-fill the WebView's direction destination field via JS injection

#### Saved Drives
A **Drive** is a saved route:
- Stores origin, destination, optional ordered waypoints
- Lives within a named list (e.g. "Commutes") alongside saved places
- Replaying a Drive hands off to the configured navigation app (same Intent path as ad-hoc navigation)
- Exports to KML `<LineString>` or GPX `<trk>`

#### External Lists (Google My Maps — `google.com/maps/d/`)
Read-only import of Google My Maps custom maps:

- **Import**: user pastes a `google.com/maps/d/u/0/…` URL → app fetches the public KML endpoint
- **Structure**: My Maps layers → sublists; places within each layer → list items
- **Sublist toggle**: each layer can be toggled on/off independently
  - Toggling off hides that sublist's items from the bottom sheet
  - When **all sublists are toggled off** → prompt to remove the whole list
    (mirrors GMaps behaviour: GMaps removes the list from your account when all layers are hidden)
- **Whole-list removal**: deletes all locally cached data; re-adding requires re-entering the URL
- **Editing restriction**: no local editing of imported items — read-only
  Editing is only possible by opening the source My Maps URL, then refreshing
  (mirrors GMaps: edit in My Maps, not in GMaps itself)
- **Refresh**: manual refresh re-fetches KML; WorkManager can handle periodic refresh

---

### Navigation Handoff
Google Maps web provides only a static direction list, not live navigation:

- Detect when user starts navigation → extract destination → hand off via Android Intent
  - `geo:<lat>,<lon>` URI (generic — OsmAnd, Organic Maps, etc.)
  - OsmAnd-specific deep link for richer options
- User configures preferred navigation app in settings

---

## Tech Stack

| Component | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 (`ComponentActivity`) |
| DI | Hilt |
| Local storage | Room (lists, places, drives) |
| Settings | DataStore Preferences |
| Background work | WorkManager (external list refresh, export) |
| Async | Kotlin Coroutines / Flow |
| WebView | Android `WebView` via upstream, hosted in Compose via `AndroidView` |

*(Unimplemented components are up for debate)*

---

## Definition of Done / Verification

Task status is tracked in `TODO.md`; each subsection carries a verification tag.

- Every task must pass: `./gradlew assembleDebug testDebugUnitTest lint`
  - No `java` is on PATH on this machine; run with `JAVA_HOME=$HOME/.jdks/ms-21.0.11`
  - Baseline (commit 39360e9): `assembleDebug testDebugUnitTest lint` is green
  - `[build]` tag: the task is proven by this gradle command alone (no device, no unit-test logic of its own)
- Pure-logic code (URL parsing, KML/GPX read/write, Mercator bounding box, Takeout parsing)
  needs unit tests. Tag: `[unit-testable]`
- Device-dependent work (Compose UI layout, JS injection against the live GMaps DOM,
  WebView behaviour) is marked **"needs device verification"** (`[needs device]`).
  Agents cannot consider it verified; it may be marked "implemented, unverified" until
  a human tests it on a device or emulator.

### Locale caveat
Phase 8.1 selectors use German aria-labels (`Menü`, `Löschen`, `Anmelden`) and break under
other locales. Prefer locale-independent selectors (roles, structure, ids) or document the
dependency explicitly next to each selector.

---

## Known Limitations

| # | Limitation | Status |
|---|---|---|
| 1 | Live navigation not available in web version | Mitigated via navigation app handoff |
| 2 | Saved place overlays on the map canvas | Cannot draw native views over WebView at map coords; floating bubble access (Phase 1.2) and JS-injected markers (Phase 6) are the workaround |
| 3 | Navigation detection in SPA | URL-watching is unreliable; needs JS hook on the directions/start button |

---

## Platform Notes

- Minimum SDK: Android 8.0+ (align with upstream)
- The upstream `woheller69/maps` fork will stop working on **certified Android** devices
  in 2026/2027 (Google requiring developer identity submission)

---

## Out of Scope

- Real-time navigation / moving map (requires native app or Navigation SDK + API key)
- Google account sync or sign-in
- iOS support
- Additional Privacy features — handled by upstream