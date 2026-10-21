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

### Local Lists (Account replacement)
Save and organise places locally using **Room** — no Google account needed.

- Organize into named lists (e.g. "Restaurants", "To visit")
- Show saved places accessible from a bottom sheet
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
| DI | Hilt |
| Local storage | Room |
| Background work | WorkManager (external list refresh, export) |
| WebView | Android `WebView` via upstream |

*(Unimplemented components are up for debate)*

---

## Known Limitations

| # | Limitation | Status |
|---|---|---|
| 1 | Live navigation not available in web version | Mitigated via navigation app handoff |
| 2 | Saved place overlays on the map canvas | Cannot draw native views over WebView at map coords; bottom sheet access is the workaround |
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