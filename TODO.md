# TODO

Tasks are roughly ordered by dependency. Items within a phase can often be parallelised.

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

---

## Phase 0 — Foundation

### 0.1 Migrate to Kotlin + ComponentActivity ✓
- [x] Convert `MainActivity.java` → `MainActivity.kt`
- [x] Change base class from `Activity` → `ComponentActivity` (required for Compose)
- [x] Verify all upstream behaviour still works after migration (smoke test)

### 0.2 Add dependencies (`app/build.gradle`)
- [ ] Jetpack Compose BOM + `compose-ui`, `compose-material3`, `activity-compose`
- [ ] Hilt (`hilt-android`, `hilt-compiler`)
- [ ] Room (`room-runtime`, `room-ktx`, `room-compiler`)
- [ ] DataStore Preferences (`datastore-preferences`)
- [ ] WorkManager (`work-runtime-ktx`)
- [ ] Kotlin Coroutines (`kotlinx-coroutines-android`)
- [ ] Lifecycle ViewModel + Compose integration (`lifecycle-viewmodel-compose`)

### 0.3 Project architecture skeleton
- [ ] Set up Hilt `@HiltAndroidApp` in `Application` subclass
- [ ] Add `@AndroidEntryPoint` to `MainActivity`
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
- [ ] Add a `TopAppBar` above the WebView (replace `FEATURE_NO_TITLE`)
- [ ] Slots: app icon/title (left), settings icon (right)
- [ ] Settings icon opens settings screen (Phase 4)

### 1.2 Floating places bubble
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
- [ ] Replace `setContentView(R.layout.activity_main)` with Compose `setContent {}`
- [ ] WebView rendered via `AndroidView { mapsWebView }`
- [ ] Layout: `Scaffold` with `TopAppBar`, `Box` containing WebView + floating bubble

---

## Phase 2 — Navigation Handoff

### 2.1 Investigate what "Start" emits ✓ (partial)
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
Both Directions and Start emit `intent://maps.app.goo.gl/?link=` Firebase Dynamic Links.
Discriminator is the outer `utm_campaign` query param:
- `ml-adir-*` → Directions button → load decoded `link` URL in WebView
- `ml-navnp-*` → Start button → extract destination, fire nav intent

- [ ] Fix existing `link=` extraction: replace string split with
  `Uri.parse(url).getQueryParameter("link")` — handles encoding correctly
- [ ] In `shouldOverrideUrlLoading`, for `intent://maps.app.goo.gl/?link=` URLs:
  - Parse outer URL with `Uri`
  - If `utm_campaign` contains `navnp`:
    - Extract `link` param → parse as Uri
    - Destination name: path segment after `/maps/dir//`
    - Coordinates: `@lat,lon` from path
    - Fire nav intent (see below)
  - Otherwise: load decoded `link` URL in WebView (existing behaviour)
  - Log a warning if campaign matches neither known pattern (Google may change names)
- [ ] Navigation intent builder:
  - Default: `geo:<lat>,<lon>?q=<label>` (works with OsmAnd, Organic Maps, others)
  - OsmAnd deep link option: `osmand.navigation:target?lat=&lon=&name=`
  - Respect preferred nav app from settings (Phase 7)
- [ ] If no nav app handles the intent, show a chooser

---

## Phase 3 — Local Lists (Room)

### 3.1 Room schema
- [ ] `Place` entity: `id`, `name`, `lat`, `lon`, `address`, `notes`, `listId`, `createdAt`
- [ ] `PlaceList` entity: `id`, `name`, `color`, `sortOrder`, `type` (NORMAL | IMPORTANT_LOCATION | EXTERNAL)
- [ ] `Drive` entity: `id`, `listId`, `originLabel`, `originLat`, `originLon`, `destLabel`, `destLat`, `destLon`, `waypoints` (JSON array), `createdAt`
- [ ] `ExternalList` entity: `id`, `listId`, `sourceUrl`, `lastFetchedAt`, `rawKml`
- [ ] `ExternalSublist` entity: `id`, `externalListId`, `name`, `visible` (Boolean)
- [ ] DAOs for all entities with `Flow` return types for reactive UI
- [ ] Repository classes wrapping the DAOs

### 3.2 Viewport-based filtering
- [ ] Track current map viewport by parsing `@lat,lng,zoomz` from URL updates
  - Hook into `shouldOverrideUrlLoading` — fires on every SPA navigation
  - Also `onPageStarted` as fallback
- [ ] Implement Web Mercator bounding box calculation from center + zoom + screen size
- [ ] Query `PlaceDao` for places within bounding box
- [ ] Expose result as `StateFlow` in ViewModel → drives bubble badge count

### 3.3 List management UI
- [ ] Lists screen: shows all lists with item counts
- [ ] List detail screen: items, add/edit/delete
- [ ] Place detail: name, coords, notes, list assignment
- [ ] Drive item: show origin → destination, "Navigate" replays the drive

### 3.4 Saved Drives capture
Moved here from Phase 2 — this is a data/list feature, not part of the nav handoff itself.

- [ ] **Setting: "Offer to save drive on Start" (default: on)**
  - When enabled: intercept the Start action (Phase 2.2), before firing the nav intent show
    a non-blocking prompt "Save this drive?" with list picker
  - do not show again box, which disables the setting
  - When disabled: fire nav intent immediately with no prompt
  - Stored in DataStore Preferences
- [ ] Detect active route from URL: parse `/maps/dir/` pattern
  - `https://www.google.com/maps/dir/origin/destination/@lat,lng,zoom`
- [ ] Extract origin, destination (and waypoints if present) from URL segments
- [ ] **Google Takeout import for Drives**: parse route data from Takeout archive if present
  - Check `Maps/` directory in Takeout for saved routes format (format TBD — needs investigation)

### 3.5 Import / Export
- [ ] **KML export**: `XmlPullParser`-based writer, `<Folder>` per list, `<Placemark>` per place/drive
- [ ] **GPX export**: `<wpt>` per place, `<trk>` per drive
- [ ] **Google Takeout import**: parse `Saved Places.json` and `Maps.json` from a Takeout archive
  - Saved places: GeoJSON FeatureCollection with `properties.Title`, `geometry.coordinates`
  - Drives: format TBD (see 3.4)
- [ ] Share target: accept `.kml` / `.gpx` file intents for import

---

## Phase 4 — Important Locations (Home / Work)

- [ ] Stored as `PlaceList` entries with `type = IMPORTANT_LOCATION`, linked `Place` rows
- [ ] Pre-seeded with "Home" and "Work" on first run (empty coords until user sets them)
- [ ] Quick-access row in the floating bubble (always visible, above nearby places list)
- [ ] "Set as Home/Work" option when long-pressing a place or from place detail
- [ ] "Navigate Home/Work" fires nav intent directly (Phase 2.2)
- [ ] Pre-fill WebView direction field via JS injection:
  ```js
  // Inject destination into GMaps direction box
  document.querySelector('input[aria-label="Choose destination"]').value = '<address>';
  ```
  (selector will need verification against live GMaps DOM)

---

## Phase 5 — External Lists (Google My Maps)

### 5.1 KML fetch and parse
- [ ] Fetch KML from `https://www.google.com/maps/d/kml?mid=<map_id>` (public maps only)
  - Extract `mid` from user-pasted `google.com/maps/d/u/0/...` URL
- [ ] Parse with `XmlPullParser`:
  - `<Document><name>` → list name
  - `<Folder><name>` → sublist name
  - `<Placemark>` within folder → place (name + coordinates)
- [ ] Store parsed data into `ExternalList`, `ExternalSublist`, `Place` rows in Room
- [ ] Run fetch in `WorkManager` `CoroutineWorker` (one-time on import, periodic on refresh)

### 5.2 Sublist toggle behaviour
- [ ] Each sublist shows a visibility toggle in the list detail screen
- [ ] Toggle updates `ExternalSublist.visible` in Room → filters out of bubble/viewport query
- [ ] When **all sublists are toggled off**: show confirmation dialog "Remove this list?"
  - Yes → delete `ExternalList`, `ExternalSublist`, and all linked `Place` rows
  - No → keep all sublists toggled off (differ from GMaps auto-remove if user prefers)

### 5.3 Editing restriction
- [ ] External list items have no edit/delete actions in the UI
- [ ] "Edit source" button opens the `google.com/maps/d/` URL in the WebView
- [ ] Manual refresh button in list header → re-triggers WorkManager fetch

---

## Phase 6 — Map Markers via JS Injection

Markers injected into the Maps JS API instance pan/zoom natively with the map — no
coordinate desync, no FrameLayout projection math. The floating bubble (Phase 1.2) remains
for list management UI; markers are the visual layer on the map itself.

### 6.1 Capture the map instance — injection timing (try in order, stop at first success)

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

---
- [ ] Re-add markers after SPA navigations that recreate the map instance (constructor wrap
  handles capture regardless of strategy; trigger re-add from `__dispatchMarkersReady`)

### 6.2 Android ↔ JS marker bridge
- [ ] Add `@JavascriptInterface` object injected via `addJavascriptInterface`:
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
- [ ] Marker `click` listener calls back to Android via the JS bridge
- [ ] Android opens the floating bubble focused on that place's detail
- [ ] Suppresses the default Maps info window (which would show a Google-sourced card)

### 6.4 UI Cleanup (MutationObserver)
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
- [ ] **DomStorage** — upstream disables it (MainActivity.java:335). Some GMaps features
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
- [x] **Compose + FEATURE_NO_TITLE** — resolved in 0.1; window flags set directly, no conflict
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
- [ ] **Directions mode** — should our search field hide when GMaps shows directions UI
  (`/maps/dir/` URL)? Or stay visible? Affects the URL-state machine design.
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

---

## Phase 8 — Custom Search Field

Replace the GMaps web search bar with a native Compose search field that surfaces saved
places and recent searches, while preserving the burger menu.

### 8.1 Hide GMaps search field, keep burger menu (try in order, stop at first success)

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

---
- [ ] Re-apply whichever strategy succeeds on every SPA navigation via MutationObserver
  (GMaps recreates the search field DOM on navigation — see Open Questions for explanation)
  Document every CSS selector used; mark which are `aria-label`-stable vs class-fragile

### 8.2 Top bar search field (Compose)
- [ ] Replace static top bar title with an always-visible search `TextField` in the `TopAppBar`
- [ ] On focus: show suggestion dropdown (saved places + recent searches matching input)
- [ ] On submit: construct search URL and call `mapsWebView.loadUrl()`
  - URL: `https://www.google.com/maps/search/<encoded_query>` (verify format in open questions)
- [ ] On selecting a saved place suggestion: navigate directly to its coordinates
  (`https://www.google.com/maps/place/@<lat>,<lon>,17z`)
- [ ] Clear / X button inside field when non-empty
- [ ] Unfocus field (collapse suggestions) on map tap — detect via WebView touch listener

### 8.3 Suggestions data
- [ ] **Saved places**: query Room `LIKE '%input%'` on name and address, show with list colour chip
- [ ] **Recent searches**: `RecentSearch` entity — `id`, `query`, `timestamp`; show most recent first,
  capped at ~20 entries; delete individual entries with swipe
- [ ] Store new search in `RecentSearch` on every submit

### 8.4 Visibility state machine
Hide our search field (show map-only top bar) when GMaps is in a mode that owns the top area:
- [ ] Parse current URL on every `shouldOverrideUrlLoading` update:
  - `/maps/dir/` → directions mode: hide search field, show back/close button instead
  - All other URLs: show search field
- [ ] Animate field in/out with a crossfade or slide to ashovoid jarring transitions