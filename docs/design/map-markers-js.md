# Design: Saved-Place Markers on the Map (TODO Phase 6.1–6.3)

Status: ready for implementation **after spikes B0 and M0** · Depends on: compose-host.md
(1.3b: `MapStateRepository` incl. `touches`/`pageMessages`, `MapCommands`, `PageScript`,
`WebViewTouchObserver`, `MapUrlParser`), floating-places-bubble.md (B0, B1 `WebMercator`,
B4 `NearbyPlacesSource`), schema.md (`PlaceDao.observeInBounds`,
`ImportantLocationDao.observeInBounds`)

---

## 0. Critical finding: the TODO's premise is probably false

TODO 6.1 assumes the GMaps website creates its map with the public Maps JavaScript API
(`google.maps.Map`) and that we can wrap that constructor. Evidence against it, from this
repository:

- `MainActivity.kt` blocks every host not in `allowedDomains`. `maps.googleapis.com`
  (where the public API's `maps/api/js` loader lives) is **not** in the list, so any request
  to it is already answered with an empty body by `block()`. Upstream has shipped this
  allowlist for years with a working map. So the website does not depend on the public API
  loader.
- google.com/maps is a separate, closure-compiled application with its own renderer;
  `window.google.maps.Map` is, to the best of our knowledge, not defined there.

Therefore: **do spike M0 before writing any marker code.** The design below has a
renderer-independent core and two renderers; M0 picks one.

### Spike M0 (≤ 2 h, [needs device]) — decide the renderer

1. Debug build: `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` (add in
   `onCreate` if absent; it is absent today).
2. Open the app, chrome://inspect on desktop, console:
   `typeof google, typeof google?.maps, typeof google?.maps?.Map`.
   Also check Network for any `maps/api/js` request (it would appear as blocked in logcat:
   `[NOT ON ALLOWLIST] Blocked maps.googleapis.com`).
3. Record the result in this file (§0 "M0 result") and in the PR.

Decision rule:
- `google.maps.Map` is a function **and** a map instance can be captured → **Renderer J**
  (JS API markers, §4).
- Otherwise (expected) → **Renderer P** (projected native overlay, §3).

The URL latency after a pan ends (Renderer P's re-show latency) is measured **once, in
bubble spike B0 (b)**; M0 reads it from there instead of measuring again.

M0 result: _not yet run_. M0 is a mandatory gate: no M1–M7 work starts before it is recorded.

---

## 1. User-facing behaviour

- Every saved place in the visible map area (same filter as the bubble badge: visible
  sublists) is drawn as a small pin tinted with its list colour. Home/Work (from the
  separate `ImportantLocation` table, only when set) are drawn with a distinct
  house/briefcase glyph; they come from `NearbyPlacesSource.importantInView`.
- Tapping a pin opens the floating card focused on that place
  (`PlacesBubbleActions.onMarkerTapped(id)` → `Expanded(Place(id))`), and does **not** open
  GMaps' own place sheet.
- With many pins close together, they cluster into a circle with a count; tapping a cluster
  zooms the map in on it (`MapCommand.NavigateInPage(MapUrls.camera(center, zoom + 2))`,
  compose-host.md §4).
- Settings toggle "Show saved places on map" (default on).

### Renderer P specifics (expected path)
- While the user's finger is on the map (pan/pinch/fling) pins fade out (100 ms). They fade
  back in (150 ms) at their new positions once the URL reports the new camera. Pins also
  fade out on **any** URL change (including `null` from `onPageStarted`) until the new
  camera is parsed.
- **Known limitation:** GMaps also moves the map without our touch (fitting search
  results, the "my location" button, the double-tap zoom animation, opening a place). We
  only learn about it when the URL changes after the animation, so during such an
  animation pins are drawn at stale positions for up to the B0 latency. Hiding pins on
  every `MapTouch.Tap` too (a tap may start a GMaps animation) narrows this; the remaining
  cases (button-less programmatic moves) are accepted and listed in §5.
  Rationale: we only learn the camera when GMaps rewrites the URL after settle; drawing pins
  during motion at stale positions looks broken, hiding them looks intentional.
- Pins are hidden in Street View, and in tilted/rotated views (`CameraMode.Tilted`) —
  flat projection would place them wrongly.

---

## 2. State machine (marker layer)

```
          enabled=false or mode in {StreetView, Tilted} or urlState.mode == NonMap or camera==null
   ┌──────────────────────────────────────────────────────────────┐
   ▼                                                              │
 Hidden ── camera known & enabled ──▶ Shown(camera, pins) ────────┘
                                         │   ▲
   MapTouch.GestureStart or Tap,         │   │  new camera parsed from a changed URL OR
   or ANY url change (incl. null)        ▼   │  800 ms after GestureEnd/Tap with no URL change
   (Renderer P only)                   Moving ┘  (camera didn't change: e.g. a tap-hold)
```

Renderer J has no `Moving` state (pins move natively with the map).

---

## 3. Renderer P — projected native overlay (expected)

### 3.1 Why native and not an injected DOM layer
Both would use the same camera source (the URL). A Compose layer is testable on the JVM,
gets native touch handling and TalkBack for free, and needs no DOM selectors (no drift).
The TODO's "Known limitation #2" (cannot draw at map coordinates) is about drawing *during*
motion; hiding during motion removes the problem.

### 3.2 Projection (pure, [unit-testable])

```kotlin
object WebMercator {
    // existing from bubble B1:
    fun bounds(camera: MapCamera, viewportCssPx: IntSize): GeoBounds
    // new:
    /** Screen position in CSS px relative to the WebView's top-left. */
    fun project(lat: Double, lon: Double, camera: MapCamera, viewportCssPx: IntSize): Offset
}
```

`project`: `dx = x(lon) - x(camera.center.lon)` with wrap-around (choose the `±W` copy
closest to the centre), `dy = y(lat) - y(camera.center.lat)`; result `(width/2 + dx,
height/2 + dy)`. The overlay multiplies by `density` to get px. Calibration offset from
bubble B2b applies here too. `viewportCssPx` is frozen while the IME is visible
(`MapStateRepository` ignores size reports then), so pins do not re-project when a GMaps
input is focused.

### 3.3 Clustering (pure, [unit-testable])

```kotlin
data class PinInput(val id: Long, val screen: Offset, val color: Int, val kind: PinKind)
enum class PinKind { Place, Home, Work }
sealed interface PinItem {
    data class Single(val pin: PinInput) : PinItem
    data class Cluster(val center: Offset, val ids: List<Long>, val geoCenter: LatLon) : PinItem
}
fun clusterPins(pins: List<PinInput>, cellPx: Float): List<PinItem>
```

Grid clustering: bucket by `floor(x / cell), floor(y / cell)` with `cell = 48.dp`; buckets
with one pin → `Single`; more → `Cluster` at the mean position. Home/Work never cluster.
Deterministic output order (by bucket row, then column) so tests are stable. Max 300 items
drawn; input is already capped at 501 by the DAO.

### 3.4 Components

```kotlin
data class MarkerLayerState(val visible: Boolean, val items: List<PinItem>)

@HiltViewModel
class MarkerLayerViewModel @Inject constructor(
    nearby: NearbyPlacesSource,            // bubble §5.1: shared Room query, already debounced
    mapState: MapStateRepository,          // urlState, viewportCssPx, touches (compose-host §5)
    commands: MapCommands,
    prefs: MarkerPrefs,
    @DefaultDispatcher default: CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<MarkerLayerState>
    fun onClusterTap(center: LatLon)       // → commands.send(NavigateInPage(MapUrls.camera(center, zoom + 2)))
}

@Composable
fun MarkerOverlay(
    state: MarkerLayerState,
    onPinTap: (Long) -> Unit,
    onClusterTap: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
)
```

- `MarkerOverlay` sits in the `MapScreen` `Box` **between** the WebView and
  `PlacesBubbleOverlay` (bubble always on top).
- Each pin is its own small composable (`Modifier.offset { }` + 32 dp visual, 48 dp touch
  via `minimumInteractiveComponentSize()`), anchored at bottom-centre. The overlay root has
  no pointer input — only pins consume, everything else falls through to the WebView (same
  rule as the bubble).
- Pin tap → `PlacesBubbleViewModel.onMarkerTapped(id)` (shared through
  `MapScreen`, both VMs are reachable there).
- Cluster tap → `MarkerLayerViewModel.onClusterTap` → `MapCommand.NavigateInPage` with a
  `MapUrls.camera(...)` URL (`Locale.ROOT`). No effects channel of its own.
- Semantics: pin `contentDescription = "<name>, saved in <list>"`, `role = Button`; cluster
  "N saved places, zoom in". Overlay `isTraversalGroup = true`.

### 3.5 Data flow

```
MapStateRepository.urlState + viewportCssPx ─┐
NearbyPlacesSource.inView ───────────────────┼─▶ combine ─▶ project ─▶ clusterPins ─▶ MarkerLayerState
NearbyPlacesSource.importantInView ──────────┤                                (Dispatchers.Default)
MapStateRepository.touches ──────────────────┘  (Moving ⇒ visible=false)
```

`NearbyPlacesSource` exists from bubble B4 on (it is `@ActivityRetainedScoped`, uses the
`@RetainedScope` `CoroutineScope` from compose-host.md §8 that is cancelled by
`ActivityRetainedLifecycle.addOnClearedListener`, and `shareIn(scope,
WhileSubscribed(5_000), replay = 1)`), so one Room query feeds both bubble and markers and
no refactor of the bubble VM is needed. The DAO returns rows nearest-first, so the ≤ 501
rows the markers cluster are the nearest ones, not an arbitrary subset.

---

## 4. Renderer J — JS API markers (only if M0 finds `google.maps.Map`)

### 4.1 Injection (replaces TODO Strategies 1–3)

Provide `markers.js` as a `PageScript` (`@IntoSet`, compose-host.md §4.3). The host
installs it with `WebViewCompat.addDocumentStartJavaScript(webView, script,
allowedOriginRules)` (androidx.webkit, feature `WebViewFeature.DOCUMENT_START_SCRIPT`),
which runs it in every matching frame **before any page script** — exactly what TODO
Strategy 3 (HTML rewriting) tries to achieve, with none of the streaming risk. Origin
rules: `setOf("https://www.google.com", "https://maps.google.com")`. Fallback if the
feature is unsupported: the host re-evaluates it in `onPageStarted` (TODO Strategy 1) —
accept the race.

Do not intercept/rewrite responses (Strategies 2/3) unless both fail on device.

### 4.2 Bridge

The host's single `GmwBridge` listener (`WebViewCompat.addWebMessageListener`, feature
`WEB_MESSAGE_LISTENER`, compose-host.md §4.3) is used instead of `addJavascriptInterface`:
origin-restricted, no reflection surface, works in iframes per rules. JS calls
`GmwBridge.postMessage(json)`; the VM reads `mapState.pageMessages` filtered by `type`.

Messages JS → Android (JSON, `type` field): `{"type":"mapReady"}`,
`{"type":"markerTap","id":123}`.
Android → JS: `MapCommand.EvaluateJs("window.__gmw?.markers?.set(<json>)")` (or
`PostMessage`), never a direct WebView reference in the VM.

**Set-the-whole-list protocol** (not add/remove): Android always sends the complete current
pin list (≤ 300 items, `[{id,lat,lon,color,kind,title}]`). JS diffs by id. This is
idempotent, survives map re-creation (re-send on `mapReady`), and cannot desync.

### 4.3 Script (asset `assets/js/markers.js`, loaded via `context.assets`)

Responsibilities: wrap `google.maps.Map` (constructor wrap, keep prototype:
`Wrapped.prototype = Orig.prototype`), keep `__gmw.map`, post `mapReady`, maintain
`Map<id, Marker>`, `click` → post `markerTap` and `stopPropagation` where possible, icon =
SVG data URI tinted with `color`. Escape `title` by passing JSON, never string
concatenation.

---

## 5. Edge cases and failure modes

| Case | Handling |
|---|---|
| Camera from URL lags pan end by > 1 s (measured in M0) | Pins reappear late; acceptable. Never draw at stale positions to hide latency. If M0 measures > 2 s, record it and ask the owner whether Renderer P is still worth shipping. |
| Fling continues after finger lifts | `Moving` lasts until the URL changes, not until `GestureEnd`. 800 ms timeout only applies when no URL change follows (tap-hold). |
| Programmatic camera change (we `loadUrl`/`navigateInPage` to a place) | URL changes → `Moving` → reprojection. During a full load the old pins are hidden because the tracker reports `null` on `onPageStarted` (bubble §5.1) until a new camera is parsed. |
| GMaps moves the map by itself (fit search results, "my location", double-tap animation, open a place) | Pins hide on the triggering `Tap`/`GestureStart` and on the following URL change. Moves with no touch and no URL change until they end (e.g. a "my location" animation started by GPS) show stale pins until the URL updates: **accepted limitation**, bounded by the B0 latency. |
| GMaps bottom sheet expanded over the map | Pins draw over the sheet. Mitigation v1: none (document). v2 idea: hide pins whose y is below the sheet's top, sheet top read via DOM — out of scope. |
| Pin under the bubble/card | Bubble/card are above; fine. |
| Satellite `m` zoom | Uses converted zoom (bubble §5.2). If the calibration is off, pins are hidden in `Satellite3dMeters` mode until fixed (flag in `BubbleDefaults`/`MarkerDefaults`). |
| Tilted/rotated | Hidden. |
| 1000s of places | DAO cap 501 → clustering → ≤ 300 composables. |
| Marker tap vs GMaps POI underneath | Native pin consumes the touch; GMaps never sees it (Renderer P). For Renderer J, verify GMaps doesn't also open a sheet (TODO open question). |
| URL format drift | Parser null → layer Hidden. Never crash. |
| DomStorage disabled upstream | Irrelevant to Renderer P. For J, our script uses `window` globals only. |

---

## 6. Task breakdown

| ID | Task | Tag |
|---|---|---|
| M0 | Spike: renderer decision; write result into §0 (latency comes from bubble B0) — mandatory gate | [needs device] |
| M1 | `WebMercator.project` + wrap-around + calibration offset; tests incl. antimeridian and centre = (w/2, h/2) | [unit-testable] |
| M2 | `clusterPins` + tests (empty, one, two in same cell, Home never clustered, deterministic order) | [unit-testable] |
| M3 | `importantInView` stream on `NearbyPlacesSource` (if bubble B4 did not add it) + tests | [unit-testable] |
| M4 | `MarkerLayerViewModel` + `Moving` logic with fake `touches` and URL changes (incl. `null`), `advanceTimeBy` for 800 ms timeout | [unit-testable] |
| M5 | `MarkerOverlay` composable + Compose tests: pin tap callback, cluster tap callback, passthrough (same pattern as bubble T-C9), 48 dp touch | [unit-testable] (Compose/Robolectric); previews (10 pins, cluster, RTL, dark) [needs human review] |
| M6 | Integrate into `MapScreen` (z-order per compose-host §7.2), wire pin tap → bubble `onMarkerTapped`, cluster → `NavigateInPage`; settings toggle in `MarkerPrefs` | [needs device] |
| M7 (J only) | `markers.js` as a `PageScript`, `markerTap`/`mapReady` handling from `pageMessages`, set-list protocol | [needs device] |

---

## 7. Acceptance criteria

1. M0 result recorded; only the chosen renderer is implemented.
2. At zoom ≥ 12 on a flat map, each pin's tip is within 8 dp of the true location (compare
   against GMaps' own POI for a saved place that is also a GMaps POI).
3. Pins are never shown at stale positions during or after motion that starts with a touch
   on the map or with a URL change. Map moves GMaps starts without either are the accepted
   limitation in §1/§5.
4. Tapping a pin opens the bubble card focused on that place; GMaps' sheet does not open.
5. Map gestures outside pins are unaffected.
6. 500 places in view: no dropped frames noticeable on a mid-range phone when pins re-appear
   (human judgement, D-M5).

## 8. Manual device checklist

| ID | Check |
|---|---|
| D-M1 | Saved places near you show pins in list colours; Home/Work show their glyphs |
| D-M2 | Pan: pins fade out while moving, reappear at correct spots after settling |
| D-M3 | Pinch zoom in/out: same; positions correct at z 10, 14, 18 |
| D-M4 | Tap pin → card opens on that place; no GMaps sheet |
| D-M5 | Zoom out with many places → clusters with counts; tap cluster zooms in |
| D-M6 | Switch to satellite: pins correct or hidden (per calibration flag) |
| D-M7 | Street View and tilted 3D: no pins |
| D-M8 | Rotate device: pins reposition correctly after the map settles |
| D-M9 | TalkBack: pins are focusable and read their name and list |
| D-M10 | Toggle setting off: pins gone; on: back |
