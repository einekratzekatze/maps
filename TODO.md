# TODO

Tasks are roughly ordered by dependency. Items within a phase can often be parallelised.

**Design specs win over this file.** Where a phase has a spec in `docs/design/`, the spec is
the source of truth and this file only tracks status: `compose-host.md` (1.3 and everything
shared), `schema.md` (3.1, 4 storage, 5 storage, 8.3), `floating-places-bubble.md` (1.2, 3.2),
`nav-handoff.md` (2.2, 3.4), `map-markers-js.md` (6.1–6.3), `custom-search-field.md` (8).
Review findings and their resolution: `docs/design/REVIEW.md`.

**Verification tags:** `[unit-testable]` = JVM tests prove it (Robolectric counts and must be
named); `[build]` = proven by `./gradlew assembleDebug testDebugUnitTest lint` alone;
`[needs device]` = a human must check it on a phone/emulator; `[needs human review]` =
visual judgement (previews, animation feel).

---

## Device spikes (mandatory gates) `[needs device]`

These are short human sessions with a debug build. Each one blocks the work listed after
it; agents must not start that work before the spike's results are written into its spec.

- [ ] **B0** (≤ 1 h) — URL/viewport behaviour of mobile GMaps: does `@lat,lng,zoomz` update
  on pan, latency, does `doUpdateVisitedHistory` fire, real URLs per suffix, CSS viewport,
  in-page navigation via synthetic link click, non-map URLs.
  Spec: `floating-places-bubble.md` §0.1. Blocks: bubble B1+, markers M1+, search viewport bias.
- [ ] **N0** (≤ 1 h) — capture Start/Directions intent fixtures, chooser-cancel lifecycle.
  Spec: `nav-handoff.md` §4.0. Blocks: N1, N2, N5.
- [ ] **M0** (≤ 1 h; latency taken from B0) — does `google.maps.Map` exist? Picks Renderer P or J.
  Spec: `map-markers-js.md` §0. Blocks: M1–M7.
- [ ] **S0** (≤ 2 h) — GMaps search DOM in English and German, `selectors.json`, search URL form.
  Spec: `custom-search-field.md` §5. Blocks: S7, S8.

---

## Pre-Phase 0 — SDK & Dependency Housekeeping ✓

- [x] `compileSdk` → 36
- [x] `targetSdk` → 36
- [x] `minSdkVersion` → 26 (drops pre-Oreo; `canUseLocation` dead code removed)
- [x] `androidx.webkit` → 1.15.0
- [x] `FreeDroidWarn` + `GithubStar` removed; JitPack repo removed
- [x] Package renamed to `io.github.einekratzekatze.maps`
- [x] Android 16 back navigation: `enableOnBackInvokedCallback="false"` temporary opt-out
      → replace with `OnBackPressedDispatcher` in Phase 0 (ComponentActivity migration)
- [x] Android 16 edge-to-edge: `fitsSystemWindows="true"` already on root layout — handled
- [x] Gradle wrapper → 9.4.1; AGP → 9.0.1 (9.1.0 incompatible)
- [ ] Smoke-test: build and run on device/emulator after these changes
      > **Blocked on a device** (`[needs device]`); agents cannot complete it. The baseline
      > check for agents is `./gradlew assembleDebug testDebugUnitTest lint`.

---

## Phase 0 — Foundation

### 0.1 Migrate to Kotlin + ComponentActivity ✓

**Verification:** `[needs device]`
- [x] Convert `MainActivity.java` → `MainActivity.kt`
- [x] Change base class from `Activity` → `ComponentActivity` (required for Compose)
- [x] Verify all upstream behaviour still works after migration (smoke test)

### 0.2 Add dependencies (`app/build.gradle`)

**Verification:** `[build]`
Versions (KSP, Hilt, Compose compiler, Room) are settled by the Gradle work and verified as
in `docs/design/REVIEW.md` §5; specs do not pin them.
- [x] Jetpack Compose BOM + `compose-ui`, `compose-material3`, `activity-compose`
- [x] Hilt (`hilt-android`, `hilt-compiler`)
- [x] Room (`room-runtime`, `room-ktx`, `room-compiler`)
- [x] DataStore Preferences (`datastore-preferences`)
- [x] WorkManager (`work-runtime-ktx`)
- [x] Kotlin Coroutines (`kotlinx-coroutines-android`)
- [ ] Lifecycle ViewModel + Compose integration (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`)
      > partial: `lifecycle-viewmodel-compose` added (39360e9); `lifecycle-runtime-compose` still missing
- [ ] Test deps: JUnit, `kotlinx-coroutines-test`, Turbine, Robolectric ≥ 4.16 (SDK 36),
  `compose-ui-test-junit4`, `ui-test-manifest`; `testOptions.unitTests.includeAndroidResources = true`

### 0.3 Project architecture skeleton

**Verification:** `[build]`
- [x] Set up Hilt `@HiltAndroidApp` in `Application` subclass
- [x] Add `@AndroidEntryPoint` to `MainActivity`
- [ ] Create package structure:
  ```
  ui/          — Compose screens and components
  data/        — Room DB, DAOs, repositories
  domain/      — models, use cases
  navigation/  — nav handoff logic
  ```

---

## Phase 1 — UI Shell

### 1.1 Top bar

**Verification:** `[needs device]`
- [ ] Add a `TopAppBar` above the WebView (replace `FEATURE_NO_TITLE`)
- [ ] Slots: app icon/title (left), settings icon (right)
- [ ] Settings icon opens settings screen (Phase 7)

### 1.2 Floating places bubble

**Spec:** `docs/design/floating-places-bubble.md` (tasks B0–B10; supersedes the bullets
below where they differ — e.g. no `Popup`, card ≤ 50 % height on phones, hidden in
Directions mode, badge counts distinct places).

**Verification:** `[unit-testable]` + `[needs device]` per task
- [ ] Add a draggable `FloatingActionButton` (or custom `View`) anchored to screen edge
  - Sits above WebView, detached from map coordinates
  - Shows badge count of saved places in current viewport
- [ ] Tap → expands into a hovering card
  - Card floats above the WebView
  - Top bar inside the card: title ("Nearby" / place name) + **X button** to close
  - The X or tapping outside closes it
  - Contents: Home/Work shortcuts (always at top), then nearby saved places
    (filtered by current viewport, see Phase 3.2)
  - Each place row: name, list colour chip, "Navigate" button
- [ ] Implement in Compose as a `Popup` or elevated `Card`; host in a `Box` with the WebView

### 1.3 Wrap WebView in Compose

**Spec:** `docs/design/compose-host.md`. Prerequisite of every feature spec.

**Verification:** `[needs device]` + `[unit-testable]` (see spec §9)
- [ ] **1.3a** Host and insets: `enableEdgeToEdge()`; remove `clearFlags`,
  `FEATURE_NO_TITLE`, `fitsSystemWindows`, `activity_main.xml`; NoActionBar theme;
  `adjustResize`; extend `configChanges` (fontScale, density, locale, layoutDirection,
  keyboard, keyboardHidden, navigation, …); keep the cookie wipe out of recreation paths;
  `WebViewHost` as the single WebView owner; `AndroidView(factory = { host.webView })`;
  `Scaffold` + `Box` with insets applied once (`consumeWindowInsets`)
- [ ] **1.3b** `MapController`, `MapCommands`, `MapStateRepository`, `WebViewTouchObserver`,
  `PageScript`/`GmwBridge`, DI modules (`@DefaultDispatcher`, `@RetainedScope`), `MapBackHandler`
- [ ] **1.3c** Shared types `LatLon`, `MapCamera`, `GeoBounds`, `MapMode`, `NavTarget`, `MapUrls`

---

## Phase 2 — Navigation Handoff

### 2.1 Investigate what "Start" emits ✓ (partial)

**Verification:** `[needs device]`
Confirmed with Pixel 7 UA: GMaps web emits a **Firebase Dynamic Link** intent:
```
intent://maps.app.goo.gl/?link=<encoded-maps-url>&apn=com.google.android.apps.maps&...
#Intent;package=com.google.android.gms;action=...VIEW_DYNAMIC_LINK;...;end
```
The destination and coordinates live inside the `link=` query parameter:
`https://www.google.com/maps/dir//<Destination>/@<lat>,<lon>,<zoom>z/data=...`

- [x] URL type identified: Firebase Dynamic Link via `intent://maps.app.goo.gl/?link=`
- [x] Confirmed: Pixel 7 UA triggers native app handoff; generic UA does not
- [x] Confirmed: emitted by the **Start button** specifically

### 2.2 Implement intent intercept

**Spec:** `docs/design/nav-handoff.md` (tasks N0–N9). Corrections carried over from the spec:
the `link=` extraction already uses `getQueryParameter` (done); `@lat,lon` in a `/maps/dir/`
URL is the **camera**, not the destination (use `!1d/!2d` pairs from `data=`); the geo
form is `geo:<lat>,<lon>?q=<lat>,<lon>(<label>)`; add `<queries>` and exclude our own
component from the chooser; `Uri`-based parsing needs Robolectric tests.

**Verification:** `[unit-testable]` (pure logic, Robolectric) + `[needs device]` (WebView/UI wiring)
Both Directions and Start emit `intent://maps.app.goo.gl/?link=` Firebase Dynamic Links.
Discriminator is the outer `utm_campaign` query param:
- `ml-adir-*` → Directions button → load decoded `link` URL in WebView
- `ml-navnp-*` → Start button → extract destination, fire nav intent

- [x] Fix existing `link=` extraction: already uses `getQueryParameter("link")` (verified in nav-handoff.md §0)
- [ ] In `shouldOverrideUrlLoading`, for `intent://maps.app.goo.gl/?link=` URLs:
  - Parse outer URL with `Uri`
  - If `utm_campaign` contains `navnp`:
    - Extract `link` param → parse as Uri
    - Destination name: path segment after `/maps/dir//`
    - Coordinates: from the `data=` blob (`!1d<lon>!2d<lat>`), **not** `@lat,lon` (that is the camera)
    - Fire nav intent (see below)
  - Otherwise: load decoded `link` URL in WebView (existing behaviour)
  - Log a warning if campaign matches neither known pattern (Google may change names)
- [ ] Navigation intent builder:
  - Default: `geo:<lat>,<lon>?q=<lat>,<lon>(<label>)` (works with OsmAnd, Organic Maps, others)
  - OsmAnd deep link option: `osmand.navigation:…` only if device check D-N5 proves it works
  - Respect preferred nav app from settings (Phase 7)
- [ ] If no nav app handles the intent, show a chooser (our own app excluded; `ActivityNotFoundException` caught)
- [ ] `<queries>` for `geo` and `google.navigation` in the manifest

---

## Phase 3 — Local Lists (Room)

### 3.1 Room schema

**Spec:** `docs/design/schema.md` — the **single** schema (version 1, `exportSchema = true`,
enums stored by name). Do not take entity fields from this file or from the feature specs.

**Verification:** `[unit-testable]` (Robolectric, in-memory Room)
- [ ] DB1: entities `PlaceList`, `Place` (+ `externalSublistId`, `nameNormalized`,
  `addressNormalized`, `(lat, lon)` index), `ImportantLocation` (Home/Work seeded with
  nullable coordinates), `Drive` (nullable origin coords, waypoint JSON, travel mode),
  `ExternalList`, `ExternalSublist`, `RecentSearch`; converters; `TextNormalizer`; `WaypointJson`
- [ ] DB2: `PlaceDao` (incl. ordered, capped `observeInBounds`, `search`) + `ImportantLocationDao` + `PlacesRepository`
- [ ] DB3: `PlaceListDao`, `DriveDao` + repositories
- [ ] DB4: `RecentSearchDao` + repository (see 8.3)
- [ ] DB5: `ExternalListDao.replaceContent` (with Phase 5.1)

### 3.2 Viewport-based filtering

**Spec:** `docs/design/floating-places-bubble.md` §5 (tasks B0, B1, B2a/B2b, B4).

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring)
- [ ] Track current map viewport by parsing `@lat,lng,zoomz` from URL updates
  - **Not** `shouldOverrideUrlLoading`: it does not fire for `history.replaceState`, which is
    how GMaps updates the camera (superseded)
  - Primary hook: `WebViewClient.doUpdateVisitedHistory`; `onPageStarted` reports "unknown";
    `onPageFinished` reports the URL; 1 s poll of `webView.url` as fallback only (delete if
    spike B0 shows `doUpdateVisitedHistory` covers every case)
- [ ] Implement Web Mercator bounding box calculation from center + zoom + screen size
- [ ] Query `PlaceDao.observeInBounds` (schema.md: nearest-first `ORDER BY`, capped at 501)
- [ ] Expose result as `StateFlow` in ViewModel → drives bubble badge count

### 3.3 List management UI

**Verification:** `[needs device]`
- [ ] Lists screen: shows all lists with item counts
- [ ] List detail screen: items, add/edit/delete
- [ ] Place detail: name, coords, notes, list assignment
- [ ] Drive item: show origin → destination, "Navigate" replays the drive

### 3.4 Saved Drives capture

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring)
Moved here from Phase 2 — this is a data/list feature, not part of the nav handoff itself.

- [ ] **Setting: "Offer to save drive on Start" (default: on)**
  - **Decided (owner, 2026-10-21):** fire the nav intent immediately on Start; remember the
    pending drive (origin, destination, timestamp). When the user returns to the app, show a
    non-blocking "Save this drive?" prompt with list picker. Spec: `nav-handoff.md` §2, §6
  - **Arm only after `ON_STOP`** follows the launch, so cancelling the chooser (or a
    permission dialog, or any pause/resume without leaving the app) never shows the prompt
  - **Expiry:** the prompt is only shown if the user returns within a timeout (default 4 hours,
    named constant, easy to change); after that the pending drive is silently discarded.
    Persist the pending drive (DataStore) so it survives process death; unit-test the expiry
    logic with an injectable clock
  - do not show again box, which disables the setting
  - When disabled: fire nav intent immediately and record no pending drive
  - Stored in DataStore Preferences
- [ ] Detect active route from URL: parse `/maps/dir/` pattern
  - `https://www.google.com/maps/dir/origin/destination/@lat,lng,zoom`
- [ ] Extract origin, destination (and waypoints if present) from URL segments
- [ ] **Google Takeout import for Drives**: parse route data from Takeout archive if present
  - Check `Maps/` directory in Takeout for saved routes format (format TBD — needs investigation)

### 3.5 Import / Export

**Verification:** `[unit-testable]` (KML/GPX writers and Takeout parsing); share-target intent is `[needs device]`
- [ ] **KML export**: `XmlPullParser`-based writer, `<Folder>` per list, `<Placemark>` per place/drive
- [ ] **GPX export**: `<wpt>` per place, `<trk>` per drive
- [ ] **Google Takeout import**: parse `Saved Places.json` and `Maps.json` from a Takeout archive
  - Saved places: GeoJSON FeatureCollection with `properties.Title`, `geometry.coordinates`
  - Drives: format TBD (see 3.4)
- [ ] Share target: accept `.kml` / `.gpx` file intents for import

---

## Phase 4 — Important Locations (Home / Work)

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring); JS selector injection is device-only

- [ ] Stored in the separate `ImportantLocation` table (`schema.md` §1.3: `kind` HOME | WORK |
  CUSTOM, nullable `lat/lon`), not as a `PlaceList` type — survives list clears, allows unset Home
- [ ] Pre-seeded with HOME (id 1) and WORK (id 2) on database creation (null coords until the user sets them)
- [ ] Quick-access row in the floating bubble (always visible, above nearby places list)
- [ ] "Set as Home/Work" option when long-pressing a place or from place detail
- [ ] "Navigate Home/Work" fires nav intent directly (Phase 2.2)
- [ ] Pre-fill WebView direction field via JS injection:
  ```js
  // Inject destination into GMaps direction box
  document.querySelector('input[aria-label="Choose destination"]').value = '<address>';
  ```
  (selector will need verification against live GMaps DOM; `aria-label`s are localised —
  follow the selector priority in `custom-search-field.md` §0; inject via `MapCommand.EvaluateJs`)

---

## Phase 5 — External Lists (Google My Maps)

### 5.1 KML fetch and parse

**Verification:** `[unit-testable]`
- [ ] Fetch KML from `https://www.google.com/maps/d/kml?mid=<map_id>` (public maps only)
  - Extract `mid` from user-pasted `google.com/maps/d/u/0/...` URL
- [ ] Parse with `XmlPullParser`:
  - `<Document><name>` → list name
  - `<Folder><name>` → sublist name
  - `<Placemark>` within folder → place (name + coordinates)
- [ ] Store parsed data into `ExternalList`, `ExternalSublist`, `Place` rows in Room
- [ ] Run fetch in `WorkManager` `CoroutineWorker` (one-time on import, periodic on refresh)

### 5.2 Sublist toggle behaviour

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring)
- [ ] Each sublist shows a visibility toggle in the list detail screen
- [ ] Toggle updates `ExternalSublist.visible` in Room → filters out of bubble/viewport query
- [ ] When **all sublists are toggled off**: show confirmation dialog "Remove this list?"
  - Yes → delete `ExternalList`, `ExternalSublist`, and all linked `Place` rows
  - No → keep all sublists toggled off (differ from GMaps auto-remove if user prefers)

### 5.3 Editing restriction

**Verification:** `[needs device]`
- [ ] External list items have no edit/delete actions in the UI
- [ ] "Edit source" button opens the `google.com/maps/d/` URL in the WebView
- [ ] Manual refresh button in list header → re-triggers WorkManager fetch

---

## Phase 6 — Map Markers via JS Injection

> **Superseded by `docs/design/map-markers-js.md`.** The premise below (GMaps web uses the
> public `google.maps.Map`) is probably false: `maps.googleapis.com` is blocked by the
> allowlist and the map still works. Spike **M0** decides between a projected native overlay
> (Renderer P, expected) and JS API markers (Renderer J). The strategies below apply only to
> Renderer J, and even then `addDocumentStartJavaScript` replaces Strategies 1–3. Keep the
> text for history; do not implement from it.

Markers injected into the Maps JS API instance pan/zoom natively with the map — no
coordinate desync, no FrameLayout projection math. The floating bubble (Phase 1.2) remains
for list management UI; markers are the visual layer on the map itself.

### 6.1 Capture the map instance — injection timing (try in order, stop at first success)

**Verification:** `[needs device]`

**Strategy 1 — `onPageStarted` + JS polling (try first)**
Simplest: no request interception, pure JS handles the race.
- [ ] In `onPageStarted`, call `evaluateJavascript` with:
  ```js
  (function() {
      if (window.__mapWrapInstalled) return;
      window.__mapWrapInstalled = true;
      window.__ourMarkers = {};
      function tryWrap() {
          if (!window.google?.maps?.Map) { setTimeout(tryWrap, 50); return; }
          const _orig = google.maps.Map;
          google.maps.Map = function(el, opts) {
              const inst = new _orig(el, opts);
              window.__mapInstance = inst;
              window.__dispatchMarkersReady?.();
              return inst;
          };
      }
      tryWrap();
  })();
  ```
- [ ] Verify: add a test marker after `__dispatchMarkersReady` fires — does it appear and
  pan with the map?
- [ ] Known risk: if GMaps initialises the map synchronously before the first `setTimeout(tryWrap)`
  tick, the constructor wrap is missed. Watch for this in testing.

**Strategy 2 — Intercept `maps/api/js` in `shouldInterceptRequest` (if Strategy 1 races)**
Eliminates timing entirely — our code runs before the API is available to GMaps.
- [ ] First, confirm the URL exists: check logs from Strategy 1 testing for a request matching
  `googleapis.com/maps/api/js` or similar
- [ ] If found, in `shouldInterceptRequest`:
  1. Let the original request complete (fetch via `OkHttp` or `URL.openStream`)
  2. Prepend the constructor-wrap script (without the polling loop — API is available immediately)
  3. Return a `WebResourceResponse` with the combined JS
- [ ] Add `maps.googleapis.com` to the allowed domains list if not already present

**Strategy 3 — Inject `<script>` into the main HTML response (last resort)**
Most reliable timing, most complex implementation.
- [ ] In `shouldInterceptRequest`, match the main `maps.google.com` HTML document request
  (check `request.getRequestHeaders()["Accept"]` contains `text/html`)
- [ ] Read original response stream, inject `<script>` tag immediately after `<head>`
- [ ] Return modified stream as `WebResourceResponse`
- [ ] Risk: response streaming + string manipulation on potentially large HTML is fragile;
  encoding/chunked transfer issues possible

**All strategies**
- [ ] Re-add markers after SPA navigations that recreate the map instance (constructor wrap
  handles capture regardless of strategy; trigger re-add from `__dispatchMarkersReady`)

### 6.2 Android ↔ JS marker bridge

**Verification:** `[needs device]`
> **Superseded:** use `WebViewCompat.addWebMessageListener` (the host's single `GmwBridge`,
> `compose-host.md` §4.3), not `addJavascriptInterface`: origin-restricted, no reflection
> surface. Android → JS sends the whole pin list (set-the-whole-list protocol), not
> add/remove calls. See `map-markers-js.md` §4.2.
- [ ] ~~Add `@JavascriptInterface` object injected via `addJavascriptInterface`~~:
  - Expose only named methods (no class-level reflection exposure)
  - Methods: `onMapReady()` callback from JS → Android pushes current viewport's places back
- [ ] From Android side, call `evaluateJavascript` to add/remove markers when Room data changes:
  ```js
  addOurMarker(lat, lng, title, id, listColor)
  removeOurMarker(id)
  clearOurMarkers()
  ```
- [ ] Markers use a custom icon tinted by list colour (Maps JS API `Icon` with SVG data URI)

### 6.3 Marker tap → bubble

**Verification:** `[needs device]`
- [ ] Marker `click` listener calls back to Android via the JS bridge
- [ ] Android opens the floating bubble focused on that place's detail
- [ ] Suppresses the default Maps info window (which would show a Google-sourced card)

### 6.4 UI Cleanup (MutationObserver)

**Verification:** `[needs device]`
- [ ] Replace one-shot `onPageFinished` CSS injection (MainActivity.kt `onPageFinished`) with a persistent `MutationObserver`
  to catch async-rendered promoted elements:
  ```js
  const observer = new MutationObserver(() => { /* hide promoted elements */ });
  observer.observe(document.body, { childList: true, subtree: true });
  ```
- [ ] Selectors to hide (review against live GMaps — these drift):
  - `.ml-persistent-promo-banner` (already done upstream)
  - Sponsored pins (selector TBD)
  - "Explore" recommendation cards (selector TBD)
  - Sign-in nag banners (selector TBD)
- [ ] Document that selectors need periodic maintenance

---

## Phase 7 — Settings

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring)

- [ ] **Preferred navigation app**: dropdown of installed apps that handle `geo:` intents
- [ ] **Home / Work**: name + address/coordinates editor (links to Phase 4)
- [ ] **External list refresh interval**: off / daily / weekly (WorkManager periodic config)
- [ ] **Default list**: which list new saved places go into
- [ ] **Offer to save drive on Start**: toggle (default on) — links to Phase 3.4
- [ ] **Clear browsing data**: button that calls `resetWebView(exit = false)` (MainActivity.kt)
  — clears cache, cookies, history, SSL decisions without reloading
- [ ] Store all settings in DataStore Preferences (not SharedPreferences)
- [ ] Settings screen reachable from top bar icon (Phase 1.1)

---

## Phase 8 — Custom Search Field

**Spec:** `docs/design/custom-search-field.md` (tasks S0–S10; supersedes 8.1's German
`aria-label` selectors and the "half-width pill" strategies — the whole pill is hidden and
our own menu button clicks GMaps' hidden one).

Replace the GMaps web search bar with a native Compose search field that surfaces saved
places and recent searches, while preserving the burger menu.

### 8.1 Hide GMaps search field, keep burger menu (try in order, stop at first success)

**Verification:** `[needs device]`

**Strategy 1 — Constrain container width (try first)**
Avoids touching child element visibility; the white pill background survives.
- [ ] Inject CSS that caps the search container to the width of just the burger icon:
  ```css
  /* target the container holding aria-label="Menü" */
  [aria-label="Menü"] { display: block !important; }
  [aria-label="Menü"] ~ * { display: none !important; }
  /* or constrain the parent: */
  .bVzuaf { max-width: 48px !important; overflow: hidden !important; }
  ```
- [ ] Verify: white background intact, icon tappable, side panel still opens normally
- [ ] Prefer `aria-label` selectors over minified class names where possible

**Strategy 2 — `visibility: hidden` on child elements (fallback)**
Used if Strategy 1 breaks layout or the panel stops opening correctly.
- [ ] Hide individual children while leaving the container in the DOM:
  ```css
  [aria-label="Löschen"],
  .J4baR,
  [aria-label="Anmelden"] { display: none !important; }
  [role="button"][aria-haspopup="true"] { visibility: hidden !important; }
  ```
- [ ] White background is lost — compensate by injecting a circular white background
  onto the menu button itself:
  ```css
  [aria-label="Menü"] {
      background: white !important;
      border-radius: 50% !important;
      box-shadow: 0 1px 4px rgba(0,0,0,.3) !important;
  }
  ```
- [ ] Verify icon is tappable and visually acceptable

**Both strategies**
- [ ] Re-apply whichever strategy succeeds on every SPA navigation via MutationObserver
  (GMaps recreates the search field DOM on navigation — see Open Questions for explanation)
  Document every CSS selector used; mark which are `aria-label`-stable vs class-fragile

### 8.2 Top bar search field (Compose)

**Verification:** `[needs device]`
- [ ] Replace static top bar title with an always-visible search `TextField` in the `TopAppBar`
- [ ] On focus: show suggestion dropdown (saved places + recent searches matching input)
- [ ] On submit: construct search URL and call `mapsWebView.loadUrl()`
  - URL: `https://www.google.com/maps/search/<encoded_query>` (verify format in open questions)
- [ ] On selecting a saved place suggestion: navigate directly to its coordinates
  (`https://www.google.com/maps/place/@<lat>,<lon>,17z`)
- [ ] Clear / X button inside field when non-empty
- [ ] Unfocus field (collapse suggestions) on map tap — detect via WebView touch listener

### 8.3 Suggestions data

**Spec:** storage and queries in `docs/design/schema.md` (DB2 `PlaceDao.search`, DB4
`RecentSearchDao`); behaviour in `custom-search-field.md` §4.

**Verification:** `[unit-testable]` (Robolectric)
- [ ] **Saved places**: `PlaceDao.search` on `nameNormalized`/`addressNormalized` (accent- and
  case-insensitive, `LIKE … ESCAPE`), prefix matches first, show with list colour chip
- [ ] **Recent searches**: `RecentSearch` entity — `id`, `query`, `queryNormalized` (unique),
  `timestamp`; show most recent first, capped at 20 entries; delete individual entries with swipe
- [ ] Store new search in `RecentSearch` on every submit

### 8.4 Visibility state machine

**Verification:** `[unit-testable]` (pure logic) + `[needs device]` (WebView/UI wiring)
Hide our search field (show map-only top bar) when GMaps is in a mode that owns the top area:
- [ ] Derive the mode from `MapStateRepository.urlState` (the shared `MapUrlParser`, fed by
  `doUpdateVisitedHistory` + fallbacks — **not** `shouldOverrideUrlLoading`, which does not
  fire for GMaps' SPA URL updates; see 3.2):
  - `/maps/dir/` → directions mode: hide search field, show back/close button instead
    (the floating bubble is hidden in this mode too)
  - Street View / non-map pages: no field
  - All other URLs: show search field
- [ ] Animate field in/out with a crossfade or slide to avoid jarring transitions

---

## Open Questions (to resolve before/during implementation)

### Navigation
- [ ] **"Start" intent content** — what exactly does GMaps web emit? (Phase 2.1 test)
  - Log `shouldOverrideUrlLoading` + `shouldInterceptRequest` while tapping Start
  - Test with generic UA vs Pixel 7 UA — GMaps may behave differently
- [ ] **Drive waypoints from URL** — GMaps encodes multi-stop routes in the URL but the
  format is undocumented. Log URLs during multi-stop route creation and verify parseability
  before committing to waypoint support.

### Map Markers
- [ ] **JS injection timing** — resolved by working through Phase 6.1 strategies in order.
  If Strategy 1 (polling) works reliably in practice, stop there.
- [ ] **Map instance survival across SPA navigation** — does GMaps web recreate the
  `google.maps.Map` instance when navigating between views (search → place → directions)?
  If so, the constructor wrap re-captures it; if not, markers persist naturally. Test both.
- [ ] **Marker clustering** — at what point does marker count become a performance problem?
  No built-in clustering in Maps JS API; `markerclustererplus` would need to be injected.
  Defer until there's a real list size to test against.
- [ ] **DomStorage** — upstream disables it (`MainActivity.kt`, `domStorageEnabled`). Some GMaps features
  may silently break. Test with it enabled to see if it affects marker/map behaviour.

### UI
- [ ] **Bottom sheet coexistence** — GMaps web has its own bottom sheet (place info,
  directions). Does our floating bubble card visually conflict with it? Test on device
  to determine if z-ordering or positioning needs adjustment.
- [ ] **Marker tap vs GMaps info window** — when we suppress the default info window on
  marker tap, does GMaps web show anything else (e.g. a place card from its own data)?
  May need additional JS to suppress.

### External Lists
- [ ] **KML for private My Maps** — `google.com/maps/d/kml` only works for public maps.
  Private maps require OAuth. Decision: public only, document limitation clearly in UI.

### Technical Migration
- [x] **Compose + FEATURE_NO_TITLE** — resolved in 0.1 for the XML UI; **reopened by 1.3**:
  `clearFlags`/`FEATURE_NO_TITLE`/`fitsSystemWindows` are removed in favour of
  `enableEdgeToEdge()` + a NoActionBar theme (`compose-host.md` §2)
- [x] **Back stack migration** — resolved in 0.1 via `OnBackPressedDispatcher`

### Custom Search (Phase 8)
- [ ] **Google autocomplete — resolved via optional user API key**
  User provides their own Google Places API key in Settings → app calls
  `places.googleapis.com/v1/places:autocomplete` directly (not via WebView).
  If no key configured: no autocomplete, submit-only. Key stored in DataStore,
  restricted to package name + SHA-1 in Google Cloud Console.
  With 500ms debounce an active user stays within the free tier (~2.5k req/month).
- [ ] **Burger menu** — resolved via Phase 8.1 two-strategy sequence.
  Try width-constrain first (preserves pill background); fall back to child-hiding
  + injected circular background if layout breaks.
- [ ] **GMaps field re-appearance after SPA nav** — GMaps re-renders its search field on
  navigations. CSS hiding must be re-applied via MutationObserver (same pattern as Phase 6.4).
  > MutationObserver watches the DOM for changes. When GMaps tears down and rebuilds the
  > search field (which happens on every SPA navigation), the observer fires and we
  > re-apply our CSS immediately. Without this the field would reappear on every navigation.
  > "Selector drift": the minified class names GMaps uses (e.g. `bVzuaf`) change on each
  > Google deploy. Use `aria-label` selectors where possible; document every selector used
  > so they're easy to find and fix when they break.
- [ ] **Our field vs GMaps field showing same query** — after submitting a search, GMaps
  populates its own (hidden) field with the query. Track query in our own ViewModel state;
  no need to sync with the hidden field.
- [ ] **Keyboard + layout interaction** — TopAppBar search field + virtual keyboard may
  clash with WebView resize behaviour (`adjustResize` vs `adjustPan`). Test on device.
- [x] **Directions mode** — our search field is replaced by a back/close title and the bubble
  is hidden (default chosen 2026-10-21, owner may veto; `compose-host.md` §7.3)
- [ ] **Search URL format** — verify which produces better WebView results:
  `https://www.google.com/maps/search/<query>` vs `https://www.google.com/maps?q=<query>`
- [ ] **Voice search** — replacing the native field loses the mic button. Adding our own
  requires `RECORD_AUDIO` permission + `SpeechRecognizer` intent. Decide if in scope.
- [ ] **Takeout import for recent searches** — Google Takeout includes search history
  (`My Activity/Maps/`). Worth importing for pre-populating recents on first run?

---

## Existing Features (already implemented)

- [x] **Clipboard geo URI conversion** (`initShareLinkListener`, MainActivity.kt) — watches
  clipboard for copied GMaps URLs, extracts `!3d<lat>!4d<lon>` coordinates, rewrites the
  clipboard entry as `geo:<lat>,<lon>?q=<lat>,<lon>` for use in any navigation app
