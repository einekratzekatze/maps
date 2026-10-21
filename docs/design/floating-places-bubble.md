# Design: Floating Places Bubble (TODO Phase 1.2)

Status: ready for implementation **after device spike B0** · Owner: TBD · Depends on: Phase 0.2,
0.3, 1.3 (`compose-host.md`), schema DB1/DB2 (`schema.md`), 3.2

This document is the single source of truth for the bubble. When it disagrees with
TODO.md, this document wins for the bubble; flag the discrepancy in the PR. Shared pieces
are defined elsewhere and only referenced here: the database in `schema.md`; the WebView
owner, `MapController`/`MapCommands`, `MapStateRepository`, `WebViewTouchObserver`,
`MapUrlParser`, `LatLon`/`NavTarget`, insets, z-order, visibility gates and back priority in
`compose-host.md`.

Defaults applied on 2026-10-21 (owner may veto, see REVIEW.md "Resolution"): card ≤ 50 %
height and ≤ 360 dp wide on phones (§3.3); row tap prefers in-page navigation, falls back to
`loadUrl` (§5.6); bubble hidden in Directions mode (§2); badge counts distinct places (§5.5);
B0 runs before dependent work (§8).

---

## 0. Facts about the current code (verified 2026-10-21)

These were checked by reading the repository, not assumed:

| Fact | Where | Consequence for the bubble |
|---|---|---|
| UI is still XML: `setContentView(R.layout.activity_main)`, a `LinearLayout` with `fitsSystemWindows="true"` holding the WebView | `MainActivity.kt:138`, `activity_main.xml` | Phase 1.3 (`compose-host.md`) is a hard prerequisite. |
| No Compose, Hilt, Room, DataStore, coroutines in `app/build.gradle`; only `webkit:1.15.0` and `activity-ktx:1.13.0` | `app/build.gradle` | Phase 0.2 is a hard prerequisite. |
| Hilt and KSP Gradle plugins are on the buildscript classpath but not applied. KSP is *declared* as `2.1.20-1.0.32` but Gradle *resolves* `2.2.10-2.0.2` by conflict resolution (REVIEW §5) | root `build.gradle` | Versions of KSP, Hilt and the Compose compiler plugin are settled by the Phase 0.2/0.3 Gradle work, not by this spec. This spec only needs them to build. |
| No Kotlin plugin applied; AGP 9 built-in Kotlin is in use | `app/build.gradle` | Compose additionally needs `org.jetbrains.kotlin.plugin.compose` at the resolved Kotlin version. |
| Activity declares `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"` only | `AndroidManifest.xml` | Font size, display size, language and hardware-keyboard changes currently **recreate** the Activity and wipe cookies. Phase 1.3 extends `configChanges` and keeps the wipe out of recreation paths (`compose-host.md` §2.2–2.3). After that, recreation is rare but still possible; S14 covers it. |
| WebView state is not saved (`saveState` is never called) and `resetWebView(false)` runs in `onCreate` | `MainActivity.kt:154` | After process death the map restarts from the start URL. Restoring "card expanded" is pointless; don't. |
| `shouldOverrideUrlLoading` is the only URL hook; there is no `doUpdateVisitedHistory` override | `MainActivity.kt:226` | **TODO 3.2's old claim that `shouldOverrideUrlLoading` "fires on every SPA navigation" is wrong**: it does not fire for `history.pushState/replaceState`, which is how GMaps updates `@lat,lng,zoom` while you pan. `doUpdateVisitedHistory` is the primary hook (Chromium calls it from `didFinishNavigation` for same-document navigations too); B0 confirms. See §5.1. |
| No `addJavascriptInterface` / message listener exists yet | whole file | The bubble does not need one (see §5.1). |
| Back is handled by one `OnBackPressedCallback` that goes back in WebView history | `MainActivity.kt:162` | Back priority is defined once in `compose-host.md` §7.4; the bubble does not register its own `BackHandler`. |
| `maps.googleapis.com` is not on the allowlist | `MainActivity.kt:68` | Irrelevant for the bubble; relevant for markers (see map-markers-js.md). |

### 0.1 Spike B0 (mandatory gate, ≤ 1 h, [needs device])

Everything in this spec, map-markers-js Renderer P and custom-search's "bias to viewport"
assume that mobile GMaps (Pixel 7 UA) rewrites `@lat,lng,zoomz` in the URL after every pan
or zoom. Nobody has observed this yet, so B0 runs **before B1** and before M0/S0-dependent
work. It also absorbs M0's latency measurement, so that is done once.

Debug build, temporary logging only (not merged): log every `doUpdateVisitedHistory(url,
isReload)`, `onPageStarted`, `onPageFinished`, and a 250 ms poll of `webView.url`, each with
`SystemClock.uptimeMillis()`. Then pan, pinch, fling, double-tap zoom, open a place, search,
open directions, switch to satellite, tilt/rotate, open Street View, and press "my location".
Record in this section:

| # | Question | Result |
|---|---|---|
| a | Does the URL update on pan / zoom at all? | _not yet run_ |
| b | Latency from the map visually settling to the URL change (median, max of ≥ 10) | _not yet run_ |
| c | Does `doUpdateVisitedHistory` fire for every URL change the poll sees? (If yes: the poll stays as a 1 s fallback only, or is deleted; §5.1) | _not yet run_ |
| d | Real URLs for each suffix kind: `z`, `m` (satellite), `a` (Street View), with tilt/heading | _paste into `MapUrlParserTest` fixtures_ |
| e | `window.innerWidth` / `innerHeight` / `devicePixelRatio` (chrome://inspect) vs WebView px / density | _not yet run_ |
| f | Does a synthetic `<a href="/maps/place/…">` click (via `evaluateJavascript`) navigate in-page without a reload? (decides `MapController.navigateInPage`, compose-host §4.1) | _not yet run_ |
| g | URLs seen for non-map pages: consent page, `/maps/d/` | _not yet run_ |

If (a) is "no": stop, and write to the owner. The badge, the list and Renderer P need a
different camera source (a JS hook), which is a new spec.

---

## 1. What the user sees

A round 56 dp button ("the bubble") floats over the map, stuck to the left or right screen
edge. It shows a pin/bookmark icon and, when saved places are inside the visible map area,
a small badge with their count ("3", "99+").

- **Drag** it anywhere; on release it glides to the nearer side edge and stays at the height
  you dropped it. Position is remembered across app restarts.
- **Tap** it: it turns into a floating card next to the edge it lives on. The card shows:
  - header: "Nearby" (or the place name when opened from a map marker, Phase 6.3, §5.8), a
    subtitle like "12 saved places in view", and an **X** button;
  - an "Important" row: Home / Work (+ custom) chips — always present, even when empty;
  - the list of saved places in the visible map area, nearest to map centre first. Each
    row: list-colour dot, name, "list name · 350 m" ("Restaurants +1 · 350 m" when the same
    place is saved in two lists), and a **Navigate** button.
- The card stays open while you pan the map; the list updates live when the map settles.
- The card closes on: X, system back, a single *tap* (not a pan, not a double-tap zoom) on
  the map or on GMaps' own controls, tapping a row (which also moves the map to that place),
  or the keyboard opening.
- The bubble is **not shown** in GMaps Directions mode (the top bar shows a back/close
  button there, custom-search-field.md), in Street View, on non-map pages (consent page,
  My Maps editor), while the keyboard is up or our search field is being edited, and while
  the "Save this drive?" prompt is visible (compose-host.md §7.3).

### 1.1 Interaction design: alternatives considered

**A. Edge-snapping draggable bubble that expands into an anchored, non-modal card (RECOMMENDED)**
- + Matches TODO 1.2; minimal map occlusion when collapsed (56 dp); user can move it away
  from whatever GMaps UI it covers on their device.
- + Non-modal card lets you pan and watch the list update — that is the point of a
  viewport-filtered list. On a 360–412 dp phone this only holds if the card leaves enough
  map visible, hence the phone limits in §3.3 (≤ 50 % height). Still an asserted premise:
  D21 tests it.
- − Most complex: drag physics, edge gesture conflict, outside-tap detection.

**B. Fixed FAB (bottom-end) + Material3 `ModalBottomSheet`**
- + Trivial to build, accessible out of the box, well-known pattern.
- − GMaps web already owns the bottom of the screen (place sheet, directions list, "my
  location" button). Two stacked bottom sheets is exactly the open question in TODO
  "Bottom sheet coexistence" — B makes it worse, not better.
- − Modal sheet blocks the map; non-modal `BottomSheetScaffold` puts a peek bar permanently
  over GMaps' own sheet.

**C. Half-hidden edge tab, swipe inward to open a side drawer**
- + Zero occlusion, nice on tablets.
- − Swipe-from-edge is the Android gesture-navigation back gesture. Even with
  `systemGestureExclusion` (max 200 dp per edge) it is fragile and undiscoverable.
- − Not discoverable for TalkBack users without extra work.

**Decision: A.** It is the only option that solves the GMaps-bottom-sheet conflict
(user moves the bubble) and keeps the map usable while browsing nearby places. B is the
documented fallback: if step B7 (drag) cannot be made reliable on device within its
budget, ship B with the same `NearbyCard` content (the content composables are shared, so
nothing is wasted).

---

## 2. State machine

Two layers of state. Keep them separate; mixing them is the most common way this goes wrong.

1. **Presentation state** (ViewModel, survives nothing special, is the source of truth for
   *what* is shown): `Collapsed`, `Expanded(focus)`.
2. **Gesture/position state** (Compose `BubblePositionState`, UI only): `Resting`,
   `Dragging`, `Settling`.

Visibility is an orthogonal gate computed from inputs (definition owned by
compose-host.md §7.3):
`visible = enabled && !imeVisible && !searchEditing && !drivePromptVisible && mode in {Map, Search, Place}`,
where `mode = mapState.urlState.mode`. Directions, StreetView and NonMap therefore hide the bubble.

```
                           ┌───────────────────── visible == false ─────────────────────┐
                           │  (feature off | IME | search editing | drive prompt |     │
                           │   Directions | StreetView | NonMap)                        │
                           │   -> render nothing, force Collapsed                      │
                           └────────────────────────────────────────────────────────────┘

 Presentation (ViewModel)                     Gesture (Compose, only while Collapsed)

   ┌───────────┐  onBubbleTap / onMarkerTap      ┌─────────┐ pointer down + slop ┌──────────┐
   │ Collapsed │ ─────────────────────────────▶  │ Resting │ ──────────────────▶ │ Dragging │
   │           │ ◀───────────────────────────── └─────────┘                     └──────────┘
   └───────────┘  onClose (X | back | map tap |      ▲      animation end            │ up/cancel
         ▲        row tap | IME shown | hidden)      └───────── Settling ◀───────────┘
         │                                             (spring to edge anchor)
   ┌─────┴─────────────┐
   │ Expanded(focus)   │  focus = Nearby | Place(placeId)
   │                   │  onMarkerTap(id) while expanded -> Expanded(Place(id))
   └───────────────────┘
```

Rules (each is a test case):

| # | In state | Event | Result |
|---|---|---|---|
| S1 | Collapsed/Resting | tap (down+up within slop) | `Expanded(Nearby)` |
| S2 | Collapsed/Resting | move beyond touch slop | `Dragging`; tap is NOT delivered |
| S3 | Dragging | up | `Settling` towards computed anchor (§4) |
| S4 | Dragging | cancel (e.g. system gesture stole it) | `Settling` towards anchor computed from current position, zero velocity |
| S5 | Settling | animation ends | `Resting`; persist anchor |
| S6 | Settling | pointer down on bubble | stop animation where it is → `Dragging` on slop, or S1 on tap. The anchor was already persisted when the settle *started* (§4.5), so the persisted anchor is the target, not the interrupted position |
| S7 | Expanded(any) | X / back / map `Tap` / IME visible / row tap | `Collapsed` |
| S8 | Expanded(any) | map pan or double-tap zoom on the WebView | stays Expanded; content updates when viewport settles |
| S9 | Collapsed or Expanded(Nearby) | marker tap (Phase 6.3) | `Expanded(Place(id))` |
| S9b | Expanded(Place(a)) | marker tap on b | `Expanded(Place(b))` |
| S9c | Expanded(Place(a)) | "Show all nearby" chevron | `Expanded(Nearby)` |
| S9d | Expanded(Place(a)) | place a deleted (detail flow emits null) | `Expanded(Nearby)` |
| S10 | any | `cardAllowed` → false (see below) | `Collapsed`, and position state snaps to anchor (no animation) |
| S11 | Expanded | rotation / font or display size change (no recreation) | stays Expanded, card re-laid-out to new constraints |
| S12 | any | process death + restore | `Collapsed`, anchor restored from DataStore |
| S13 | Expanded | tap on bubble area | there is no bubble while Expanded (it is replaced by the card) |
| S14 | any | Activity recreated by a configuration change (rare after compose-host §2.2) | The retained VM keeps `expanded`/`focus`; the new WebView reloads the last URL; `mapState.url` is null until the tracker reports again, so content shows `ViewportUnknown` briefly. The tracker and touch observer are recreated with the new host; nothing holds the old WebView. |
| S15 | Expanded | `bubble_enabled` switched off | `Collapsed`, bubble gone (test) |

The gate is split in two so that markers keep working with the bubble switched off:
`cardAllowed = !imeVisible && !searchEditing && !drivePromptVisible && mode in {Map, Search, Place}`;
`bubbleVisible = enabled && cardAllowed`. The card is shown iff `expanded && cardAllowed`.
With `enabled == false` only a marker tap (S9) can expand, and the X/back/tap rules still
collapse it.

The bubble is **not** draggable while Expanded, and the card is never draggable.

---

## 3. Components and Compose structure

### 3.1 Package layout

```
io.github.einekratzekatze.maps
├── map/                        // WebView-facing; host pieces are compose-host.md (1.3b)
│   ├── url/MapUrlParser.kt     // pure: URL -> MapUrlState (camera part here, B1) [unit]
│   ├── WebMercator.kt          // pure: camera + size -> GeoBounds  [unit]
│   ├── MapUrlTracker.kt        // URL supplier + hooks -> reportUrl  [unit with fake supplier; device smoke]
│   └── NearbyPlacesSource.kt   // @ActivityRetainedScoped shared pipeline (§5.1)  [unit]
├── domain/
│   ├── GeoModels.kt            // compose-host.md §6.1 (LatLon, MapCamera, GeoBounds, MapMode, MapUrlState)
│   ├── Distance.kt             // haversine + formatting            [unit]
│   ├── NearbyDedupe.kt         // distinct-place grouping (§5.5)    [unit]
│   └── NearbyPlace.kt
├── data/db/                    // schema.md (PlaceDao, ImportantLocationDao, PlacesRepository)
├── data/prefs/
│   └── BubblePrefs.kt          // DataStore: anchor, enabled
└── ui/bubble/
    ├── PlacesBubbleViewModel.kt
    ├── BubbleUiState.kt
    ├── BubbleSnap.kt           // pure snap math                    [unit]
    ├── BubblePositionState.kt  // Animatable + drag handling
    ├── PlacesBubbleOverlay.kt  // stateful entry point (VM-wired)
    ├── PlacesBubble.kt         // stateless collapsed button + badge
    ├── NearbyCard.kt           // stateless expanded card (Nearby focus)
    ├── PlaceCard.kt            // stateless expanded card (Place focus, §5.8)
    ├── PlaceRow.kt, ImportantRow.kt, EmptyStates.kt
    └── BubblePreviews.kt
```

### 3.2 Compose tree

```kotlin
// ui/MapScreen.kt (Phase 1.3 creates this, compose-host.md §7.1; bubble adds the overlay line)
Scaffold(topBar = { MapTopBar(...) }, contentWindowInsets = …) { inner ->
    Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {   // insets applied ONCE
        MapWebView(host, Modifier.fillMaxSize())             // AndroidView, bottom of z-order
        // MarkerOverlay, DriveSavePrompt (other specs)
        PlacesBubbleOverlay(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}
```

Without `consumeWindowInsets(inner)` the overlay's `safeDrawing` would re-apply the
navigation-bar and cutout insets that `inner` already contains (24–48 dp too high).

`PlacesBubbleOverlay` must NOT have a background, `clickable`, or `pointerInput` on its
full-size root. Only the bubble and the card consume pointer events. Compose hit-testing
stops at the first sibling that contains the pointer and has pointer input; everywhere
else the event falls through to the `AndroidView` (the WebView). A full-size scrim would
block map panning — that is explicitly not wanted (S8).

Do **not** use `Popup` / `Dialog` for the card: a `Popup` is a separate window (focus,
IME, insets, and z-order relative to the WebView all become harder, and Compose tests
need `isDialog`/`isPopup` matchers). The card is a plain composable inside the overlay
`Box`, positioned with `Modifier.offset { }`.

```kotlin
@Composable
fun PlacesBubbleOverlay(
    modifier: Modifier = Modifier,
    viewModel: PlacesBubbleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAnchor by viewModel.anchor.collectAsStateWithLifecycle()
    val anchor = savedAnchor ?: return      // DataStore not loaded yet: render nothing (§4.6)
    PlacesBubbleLayout(
        state = state,
        savedAnchor = anchor,
        onAnchorChange = viewModel::onAnchorSettled,
        actions = viewModel,            // PlacesBubbleActions
        modifier = modifier,
    )
}

/** Stateless; this is what previews and Compose UI tests target. */
@Composable
fun PlacesBubbleLayout(
    state: BubbleUiState,
    savedAnchor: BubbleAnchor,
    onAnchorChange: (BubbleAnchor) -> Unit,
    actions: PlacesBubbleActions,
    modifier: Modifier = Modifier,
)
```

Inside `PlacesBubbleLayout`:

```
BoxWithConstraints(modifier) {                 // gives us maxWidth/maxHeight for bounds
    val bounds = remember(constraints, density, state.searchHidden) {
        BubbleBounds.from(constraints, density, state.searchHidden)
    }
    val position = rememberBubblePositionState(savedAnchor, bounds)
    // No BackHandler here: MapScreen's single MapBackHandler calls actions.onClose()
    // (compose-host.md §7.4).

    // Two siblings, each with its own AnimatedVisibility, each positioned by its own
    // OUTER modifier. Never put Modifier.offset inside AnimatedContent/AnimatedVisibility
    // content: the container is sized to the child (56 dp) at (0,0) and clips, so an
    // offset child is drawn and hit-tested outside the clip (invisible / untappable).
    AnimatedVisibility(
        visible = state.bubbleVisible && !state.expanded,
        modifier = Modifier.offset { position.offset.round() },     // outer node carries the position
        enter = fadeIn(tween(90)), exit = fadeOut(tween(90)),
    ) {
        PlacesBubble(state.badge, position, onTap = actions::onBubbleTap, ...)   // no offset inside
    }
    val cardPlacement = remember(position.restingOffset, position.side, bounds) {
        cardPlacement(position.restingOffset, position.side, bounds)          // pure, §3.3
    }
    AnimatedVisibility(
        visible = state.expanded && state.cardAllowed,
        modifier = Modifier.offset { cardPlacement.topLeft },
        enter = cardEnter(position.side), exit = cardExit(position.side),
    ) {
        when (val f = state.focus) {
            CardFocus.Nearby -> NearbyCard(state, Modifier.sizeIn(maxWidth = cardPlacement.width, maxHeight = cardPlacement.maxHeight), ...)
            is CardFocus.Place -> PlaceCard(state.placeCard, ...)
        }
    }
}
```

`AnimatedVisibility`'s default enter/exit only animate alpha/scale of the child; the
container is still child-sized, but because the offset is on the `AnimatedVisibility` node
itself the clip rect moves with it. Test T-C13 guards this.

### 3.3 Visual spec

| Element | Value |
|---|---|
| Bubble size | 56 dp circle, `Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 6.dp)`, `colorScheme.primaryContainer` |
| Bubble icon | `Icons.Outlined.Bookmarks` 24 dp, `onPrimaryContainer` |
| Bubble while dragging | scale 1.08, shadowElevation 12 dp (animate 100 ms) |
| Edge margin | 12 dp from the side edge (after insets) |
| Badge | M3 `Badge` at top-end of the bubble (top-start when on the right edge, so it isn't clipped and points inward), `colorScheme.error`/`onError`, text `labelSmall` |
| Card width | `min(360.dp, maxWidth - 2 * 12.dp)` — never wider than 360 dp. On a compact phone (`maxWidth < 600.dp`) additionally `≤ 0.85f * maxWidth`, so a strip of map stays visible beside the card |
| Card max height | compact phone (`maxWidth < 600.dp` and `maxHeight ≥ 480.dp`): `min(480.dp, 0.5f * maxHeight)`; larger windows: `min(480.dp, 0.6f * maxHeight)`; short windows (`maxHeight < 480.dp`, landscape/split): `0.9f * maxHeight` |
| Card shape / elevation | `RoundedCornerShape(20.dp)`, `ElevatedCard`, shadow 8 dp |
| Card vertical placement | top = `clamp(bubbleY - 24.dp, topBound, bottomBound - cardMaxHeight)` (uses the max height, so placement does not depend on measured content and needs no second pass) |
| Card horizontal placement | aligned to the bubble's edge, 12 dp margin |
| Placement function | `cardPlacement(restingOffset, side, bounds): CardPlacement(topLeft: IntOffset, width: Dp, maxHeight: Dp)` — pure, in `BubbleSnap.kt`, unit-tested with B5 (360×640 dp phone → width 306 dp, max height 320 dp; 800×360 dp landscape → 360 dp, 324 dp) |
| Header | title `titleMedium`, subtitle `bodySmall`, X `IconButton` (48 dp) |
| Important row | `LazyRow` of `AssistChip`s (Home, Work, customs); unset ⇒ chip label "Set Home", `leadingIcon = Icons.Outlined.AddLocation` |
| Place row | min height 56 dp; 12 dp colour dot; name `bodyLarge` 1 line ellipsis; secondary `bodySmall` "List · 350 m"; trailing `IconButton(Icons.Filled.Directions)` |
| Expand animation | 220 ms `FastOutSlowInEasing`; card `scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(if (right) 1f else 0f, pivotY))` + `fadeIn`; bubble `fadeOut(90 ms)`. Two `AnimatedVisibility` siblings crossfading, not `AnimatedContent` (§3.2) |
| Collapse animation | 160 ms, reverse |

All numbers live in one `object BubbleDefaults` so a tester can tune them in one place.

---

## 4. Drag physics and edge snapping

### 4.1 Bounds

```kotlin
data class BubbleBounds(
    val minX: Float, val maxX: Float,   // left-edge x and right-edge x of the bubble's top-left corner
    val minY: Float, val maxY: Float,   // allowed top-left y range
    val sizePx: Float,
) {
    companion object {
        fun from(constraints: Constraints, density: Density, searchHidden: Boolean): BubbleBounds
    }
}
```

- `minX = margin`, `maxX = width - margin - size`.
- `minY = 72.dp` while GMaps' own search pill is visible at the top of the WebView;
  `minY = 12.dp` once custom search hides it (`searchHidden` =
  `MapStateRepository.overlay.gmapsSearchHidden`, set by the custom-search injector; false
  until Phase 8 lands).
- `maxY = height - 136.dp - size` (keeps it clear of GMaps' "my location" button and the
  collapsed GMaps bottom sheet on a typical phone).
- If `maxY < minY` (tiny window / split screen): `minY = maxY = (height - size) / 2`.
- Both constants are in `BubbleDefaults` and are the first thing to tune from device testing.

### 4.2 Anchor (what is persisted)

```kotlin
enum class EdgeSide { Start, End }   // layout-direction aware: Start = left in LTR, right in RTL
data class BubbleAnchor(val side: EdgeSide, val yFraction: Float /* 0f..1f within [minY, maxY] */) {
    companion object { val Default = BubbleAnchor(EdgeSide.End, 0.45f) }
}
```

Persisting a fraction (not pixels) makes rotation, split-screen and font/density changes
free: the offset is always recomputed as `anchorToOffset(anchor, bounds)`.

### 4.3 Snap computation (pure, unit-tested)

```kotlin
/** Pure. All values in px. Velocity in px/s. Layout direction resolved by caller. */
fun computeSnapAnchor(
    releaseTopLeft: Offset,
    velocity: Velocity,
    bounds: BubbleBounds,
    isRtl: Boolean,
): BubbleAnchor

fun anchorToOffset(anchor: BubbleAnchor, bounds: BubbleBounds, isRtl: Boolean): Offset
```

Algorithm:

1. Projected position (fling projection, exponential-decay approximation):
   `projX = x + vx * 0.15f`, `projY = y + vy * 0.10f`.
2. Side: if `|vx| > 1000 px/s` (`BubbleDefaults.FlingSideVelocity`, ~ a deliberate flick)
   the sign of `vx` decides; otherwise the side whose edge is nearer to
   `projX + size/2` vs `width/2`. Ties → `End`.
3. `y = projY.coerceIn(minY, maxY)`; `yFraction = (y - minY) / (maxY - minY)` (0.5 if
   range is 0).
4. Map physical left/right to `Start`/`End` with `isRtl`.

### 4.4 Animation

```kotlin
@Stable
class BubblePositionState internal constructor(initial: Offset, ...) {
    val offset: Offset                     // current top-left, read in Modifier.offset { }
    val isDragging: Boolean
    val side: EdgeSide                     // current/target side, for the card and badge placement
    val restingOffset: Offset              // anchorToOffset(current target anchor); used for card placement
    var bounds: BubbleBounds               // updated from composition; read at gesture time, never a pointerInput key
    suspend fun dragBy(delta: Offset)      // snapTo(clamped(offset + delta)) – 1:1, no lag
    fun computeTarget(velocity: Velocity): BubbleAnchor     // pure wrapper over computeSnapAnchor (§4.3)
    suspend fun animateTo(target: BubbleAnchor)             // spring; may be cancelled by a new touch
    suspend fun snapToAnchor(anchor: BubbleAnchor)          // no animation (bounds changed, hidden)
}
```

Settling is split into *compute + persist* and *animate* on purpose: if a new touch cancels
`animateTo` (S6), `CancellationException` must not skip persistence. Persisting before the
animation finishes is correct because the anchor is the target, not the current pixel position.

- Backed by one `Animatable(Offset, Offset.VectorConverter)`. Launch every `snapTo`/`animateTo`
  from one `rememberCoroutineScope()`; `Animatable`'s internal mutex cancels a running
  settle when a new drag starts (gives S6 for free). If drag events visibly lag on device,
  switch `dragBy` to a non-suspending `mutableStateOf<Offset>` during the drag and only
  hand off to the `Animatable` (`snapTo(current)` then `animateTo`) on release.
- During drag: `snapTo`, clamped to the full overlay (not to `minY..maxY` — the user may
  drag over the top band; it's only the *resting* position that is constrained). Clamp to
  `[0, width - size] × [0, height - size]` so it can't leave the screen.
- Settle: `animateTo(target, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), initialVelocity = velocity)`.
  Slight overshoot at the edge is intended (feels physical); the clamp is reapplied by the
  spring target so the resting position is always valid.
- Velocity: `androidx.compose.ui.input.pointer.util.VelocityTracker`, `addPosition` on
  every drag event; `calculateVelocity()` on release; reset on start.
- When `bounds` change (rotation, split-screen, IME, font/display size) and not dragging:
  `position.bounds = bounds` then `snapToAnchor(savedAnchor)` inside `LaunchedEffect(bounds)`.
  While dragging, only `position.bounds` is updated; the settle uses the new bounds.

### 4.5 Gesture wiring (exact)

The position offset is applied on the **outer** `AnimatedVisibility` node (§3.2), not here.

```kotlin
val currentOnAnchorChange by rememberUpdatedState(onAnchorChange)
Modifier
    .systemGestureExclusion()            // API 29+: prevents the back gesture from stealing drags at the edge
    .pointerInput(Unit) {                // keyed on Unit: a bounds change must NOT restart the gesture
        val tracker = VelocityTracker()
        var dragging = false
        var startPos = Offset.Zero
        var accumulated = Offset.Zero
        fun settle(v: Velocity) {
            dragging = false
            val target = position.computeTarget(v)       // reads position.bounds (latest)
            currentOnAnchorChange(target)                 // persist FIRST (S6)
            scope.launch { position.animateTo(target) }
        }
        try {
            detectDragGestures(
                onDragStart = {
                    dragging = true; tracker.resetTracking()
                    startPos = position.offset; accumulated = Offset.Zero
                    scope.launch { position.startDrag() }
                },
                onDrag = { change, delta ->
                    change.consume()
                    accumulated += delta
                    tracker.addPosition(change.uptimeMillis, startPos + accumulated)   // overlay space
                    scope.launch { position.dragBy(delta) }
                },
                onDragEnd = { settle(tracker.calculateVelocity()) },
                onDragCancel = { settle(Velocity.Zero) },
            )
        } finally {
            // pointerInput coroutine cancelled mid-drag (node detached, composition change):
            // detectDragGestures does NOT call onDragCancel in that case.
            if (dragging) settle(Velocity.Zero)
        }
    }
    .clickable(onClickLabel = stringResource(R.string.bubble_open), role = Role.Button) { onTap() }  // the only place label/role are set (§7)
```

- `detectDragGestures` waits for touch slop before consuming, so a tap still reaches
  `clickable`; once slop is passed it consumes the changes, and `clickable` checks for
  consumption (in the Final pass) and does not fire. This is S1/S2. It works with either
  modifier order; keep the order above for readability.
- Velocity is fed `startPos + Σ dragAmount` (overlay space), not `change.position` (local
  space, ~constant because the bubble moves with the finger) and not
  `position.offset + change.position` (mixes an asynchronously updated offset with a local
  position).
- A second finger on the bubble during a drag is ignored by `detectDragGestures` (it tracks
  one pointer), and because the bubble's node contains it, it does not reach the WebView.
  Covered by D24.
- `systemGestureExclusion()` is required: the bubble sits within ~12–68 dp of the edge and
  Android's gesture-nav back zone is the outer ~24 dp. Without it, a drag that starts on the
  outer part of the bubble triggers Back (which here means WebView history back — very
  confusing). Exclusion is capped at 200 dp per edge by the system; 56 dp is fine.

### 4.6 Persistence

`BubblePrefs` (DataStore Preferences): keys `bubble_side` (String "Start"/"End"),
`bubble_y_fraction` (Float), `bubble_enabled` (Boolean, default true). The DataStore is
created with `ReplaceFileCorruptionHandler { emptyPreferences() }`, so a corrupt file falls
back to defaults instead of crashing.

- The ViewModel exposes `anchor: StateFlow<BubbleAnchor?>`, **initially `null`**; the
  overlay renders nothing while it is null (no first-frame jump from `Default` to the saved
  anchor). A missing key maps to `BubbleAnchor.Default`, so the first emission is never null
  after load.
- Writes happen on `onAnchorSettled` only (start of settle, §4.5), never during drag.
- Feedback loop: every settle is persisted and then re-emitted by DataStore. `BubblePositionState`
  applies an emitted anchor only if it **differs from its current target** and no drag or
  settle animation is running; otherwise it is ignored. So an echo never cuts off a spring.

---

## 5. Data flow

```
WebView ──(doUpdateVisitedHistory [primary], onPageStarted→null, onPageFinished,
           1 s poll fallback)──▶ MapUrlTracker ──reportUrl──▶ MapStateRepository (compose-host §5)
     │                                                              │ urlState = MapUrlParser.parse(url)
     │ the single OnTouchListener (compose-host §5.2)               │ bounds = WebMercator(camera, viewportCssPx)
     ▼                                                              ▼
WebViewTouchObserver ──reportTouch──▶ MapStateRepository.touches   NearbyPlacesSource (@ActivityRetainedScoped)
                                              │                      bounds → distinctUntilChanged → debounce(250 ms)
                                              │                      → flatMapLatest { repo.observeInBounds(b, center, 501) }
                                              │                      → shareIn(retainedScope, WhileSubscribed(5 s), replay 1)
                                              ▼                                     │  (also feeds map-markers-js)
                                   PlacesBubbleViewModel ◀───────────────────────────┘
                                     │      ▲            ◀── PlacesRepository.observeImportant()
                                     │      │            ◀── PlacesRepository.observeHasAnyPlaces()
                                     │      │            ◀── MapStateRepository.overlay, urlState
                                     │      └── BubblePrefs.anchor
                         uiState: StateFlow<BubbleUiState>   MapCommands.send(...)  (compose-host §4)
                                     ▼                                   ▼
                              PlacesBubbleLayout             MapScreen collector: NavigateInPage/LoadUrl → MapController
                                                                                  Navigate → NavLauncher (nav-handoff.md)
```

### 5.1 Where the viewport comes from (replaces TODO 3.2's old hook)

GMaps updates `@lat,lng,zoomz` with `history.replaceState` after the map settles. That does
not trigger `shouldOverrideUrlLoading`. `MapUrlTracker` merges these sources and de-dupes
(`distinctUntilChanged` on the string):

1. **Primary:** `WebViewClient.doUpdateVisitedHistory(view, url, isReload)`. Chromium's
   WebView calls it from `didFinishNavigation` for every committed main-frame navigation,
   including same-document ones, so it very likely fires for `replaceState`. B0 (c) confirms.
2. `onPageStarted(view, url)` → reports **`null`** (a full load is starting; the old camera
   is stale; markers hide).
3. `onPageFinished(view, url)` → reports the URL.
4. **Fallback:** a poll of the URL every `BubbleDefaults.UrlPollMs = 1000` ms while the
   activity is `RESUMED` (`repeatOnLifecycle(RESUMED)`). Delete it if B0 shows (1) covers
   every case; keep the constant so it can be re-enabled.

```kotlin
/** Unit-testable: takes a URL supplier, not a WebView. The host passes { webView.url }. */
class MapUrlTracker(
    private val currentUrl: () -> String?,
    private val report: (String?) -> Unit,          // = mapState::reportUrl
    private val pollMs: Long = BubbleDefaults.UrlPollMs,
) {
    fun onVisitedHistory(url: String)   // from WebViewClient.doUpdateVisitedHistory
    fun onPageStarted(url: String)      // reports null
    fun onPageFinished(url: String)
    suspend fun pollWhileResumed()      // launched by the host in repeatOnLifecycle(RESUMED)
}
```

No JavaScript is needed for the bubble. (If the page-script bridge exists, it may also push
`location.href` — still through `MapUrlTracker.onVisitedHistory`.)

`MapUrlTracker` is created per `WebViewHost` (so per Activity instance; it never outlives
its WebView) and reports into the `@ActivityRetainedScoped` `MapStateRepository` defined in
compose-host.md §5. The ViewModel never touches the WebView.

**`NearbyPlacesSource`** (this spec, B4) is the one shared pipeline for "saved places in
the current viewport", consumed by the bubble and by map-markers-js:

```kotlin
@ActivityRetainedScoped
class NearbyPlacesSource @Inject constructor(
    mapState: MapStateRepository, places: PlacesRepository,
    @RetainedScope scope: CoroutineScope,           // compose-host.md §8
) {
    /** null = viewport unknown. Raw rows, nearest first, ≤ 501 (schema.md §2.2). */
    val inView: SharedFlow<NearbyResult?>           // NearbyResult(camera, bounds, rows)
    /** Important locations with coordinates inside the viewport (markers draw Home/Work). */
    val importantInView: SharedFlow<List<ImportantLocation>>
}
```

### 5.2 URL parsing

The parser is shared (compose-host.md §6.3): `MapUrlParser.parse(url): MapUrlState`. B1
implements the camera part; custom-search S1 adds `mode`/`displayText` to the same object.
Types (`MapCamera(center: LatLon, zoom, mode)`, `CameraMode`) are in compose-host.md §6.1.

Regex on the path: `@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(\d+(?:\.\d+)?)([zma])((?:,\d+(?:\.\d+)?[yht])*)`

| Suffix | Meaning | Handling |
|---|---|---|
| `15z`, `15.25z` | web zoom (fractional allowed) | `Map`, zoom as-is |
| `1234m` | altitude-style span used in satellite/3D views | `Satellite3dMeters`; convert: `zoom = log2(156543.034 * cos(lat) * viewportHeightCssPx / meters)` — **calibrate on device** (B2b) and adjust the constant; if calibration fails, treat as unknown viewport |
| `3a` (+ `,75y,90h,80t`) | Street View | `StreetView` → viewport unknown, bubble hidden |
| any `,Nt` with N > 0 or `,Nh` with N ≠ 0 alongside `z`/`m` | tilted/rotated map | `Tilted` → bounds computed as if flat but **expanded by 1.5×** (conservative; nothing visible is missed) |

Fixtures (copy into `MapUrlParserTest`; B0 replaces/extends them with real captured URLs).
Notation: `camera mode / MapMode`.
```
https://www.google.com/maps/@52.5200066,13.404954,15z                       -> Map 52.5200066 13.404954 15.0 / Map
https://www.google.com/maps/@52.52,13.40,14.5z?entry=ttu                    -> Map 14.5 / Map
https://www.google.com/maps/place/Brandenburger+Tor/@52.5162746,13.3755154,17z/data=!3m1!4b1 -> Map 17.0 / Place
https://www.google.com/maps/search/cafe/@48.13,11.57,13z                    -> Map 13.0 / Search
https://www.google.com/maps/dir/A/B/@52.5,13.4,12z                          -> Map 12.0 / Directions
https://www.google.com/maps/@52.5,13.4,3000m/data=!3m1!1e3                  -> Satellite3dMeters 3000 / Map
https://www.google.com/maps/@52.51,13.37,3a,75y,90h,80t/data=!3m6           -> StreetView / StreetView
https://www.google.com/maps                                                  -> camera null / Map
https://www.google.com/maps/@-33.86,151.20,12z                               -> negative lat ok / Map
https://www.google.com/maps/d/edit?mid=abc                                   -> camera null / NonMap
https://consent.google.com/ml?continue=https://www.google.com/maps           -> camera null / NonMap
about:blank                                                                  -> camera null / NonMap
null                                                                         -> MapUrlState.Unknown
```

### 5.3 Viewport bounds (Web Mercator)

```kotlin
// GeoBounds: compose-host.md §6.1
object WebMercator {
    const val TILE = 256.0
    fun bounds(camera: MapCamera, viewportCssPx: IntSize): GeoBounds
}
```

- World size `W = 256 * 2^zoom` CSS px. `x = (lon + 180) / 360 * W`,
  `y = (1 - ln(tan φ + sec φ) / π) / 2 * W`.
- `west/east` from `x ∓ width/2`; `north/south` from `y ∓ height/2`, inverted.
- Clamp latitude to ±85.05112878. If `width ≥ W`, `west = -180, east = 180`.
- Normalise longitudes into [-180, 180); `west > east` means antimeridian crossing.
- Viewport CSS px = WebView px / `displayMetrics.density` (GMaps uses `width=device-width`;
  verified in B0 (e) with `window.innerWidth` via chrome://inspect).
- While the IME is visible, `MapStateRepository` ignores viewport size reports (compose-host
  §5), so focusing a GMaps input does not change `bounds`.
- Test vector: camera (0, 0, z=1), viewport 256×256 → west -90, east 90, north 66.5133,
  south -66.5133.
- GMaps' collapsed bottom sheet covers part of the WebView but the URL centre is the centre
  of the whole map canvas; we use the whole WebView. Calibrate in B2b: drop the parsed
  centre as a debug crosshair (debug builds only, `BuildConfig.DEBUG`) and compare.

### 5.4 Room queries

Defined in `schema.md` (the only schema source): `PlaceDao.observeInBounds` (bbox, hidden
sublists excluded, **ordered nearest-first** via `ORDER BY`, `LIMIT`), `observeHasAnyPlaces`,
`observeDetail` (focused card, §5.8) and `ImportantLocationDao.observeAll` (=
`PlacesRepository.observeImportant()`). Important Locations are a separate table, so the
place queries need no `type != 'IMPORTANT_LOCATION'` filter. Task B3 is schema task DB2.

### 5.5 Badge semantics and update cost

- **Counted:** **distinct places** among the rows returned by `observeInBounds` (visible
  sublists, inside the current viewport bounds). Two rows are the same place when
  `nameNormalized` is equal and both coordinates are equal after rounding to 5 decimals
  (~1 m). Pure function `dedupeNearby(rows): List<NearbyGroup>` in `domain/NearbyDedupe.kt`
  ([unit-testable]); each group keeps the nearest row's id and all list names/colours
  (row shows the first list + "+N"). Home/Work are **not** counted (they're always shown in
  the card; counting them would make the badge meaningless).
- Display: `0` → no badge; `1..99` → number; `≥100` → "99+".
- Viewport unknown (no camera, Street View, non-map page) → no badge, and the card shows the
  "map position unknown" state.
- `Tilted` and `Satellite3dMeters`: bounds are approximate (§5.2), so the badge is an
  approximation there too (it may include places just off screen). Acceptance criterion 4
  is relaxed for these modes.
- One query feeds both badge and list: limit 501, nearest first. `count =
  dedupeNearby(rows).size`; `truncated = rows.size > 500`. No separate `COUNT(*)`. When
  truncated the badge shows "99+" regardless of the deduped count.
- Cost control lives in `NearbyPlacesSource` (§5.1): `bounds` → `distinctUntilChanged()` →
  `debounce(250 ms)` → `flatMapLatest { repo.observeInBounds(it, center, 501) }` →
  `shareIn`. GMaps only rewrites the URL when the map settles, so in practice this is ≤ 1
  query per pan. Room re-emits automatically when places change. Timing is measured on a
  device (D23), not under Robolectric (host SQLite).
- Sorting and distance: the DAO already returns nearest first (equirectangular). The
  ViewModel computes the haversine distance for display on `Dispatchers.Default`, re-sorts
  the deduped groups by it (ties by name, `Collator`), and shows the first 100.
  Footer when more than 100: "Showing 100 nearest of N" when not truncated, "Showing 100
  nearest · 500+ in view" when truncated. Both end with "· zoom in to narrow".

### 5.6 ViewModel

```kotlin
data class BubbleUiState(
    val bubbleVisible: Boolean,             // enabled && cardAllowed (§2)
    val cardAllowed: Boolean,
    val searchHidden: Boolean,              // GMaps pill hidden by custom search → minY (§4.1)
    val expanded: Boolean,
    val focus: CardFocus,
    val badge: BadgeState,
    val important: List<ImportantShortcut>,
    val content: NearbyContent,
    val placeCard: PlaceCardState?,         // non-null iff focus is Place (§5.8)
)

sealed interface CardFocus { data object Nearby : CardFocus; data class Place(val placeId: Long) : CardFocus }

sealed interface BadgeState {
    data object Hidden : BadgeState
    data class Count(val n: Int, val truncated: Boolean) : BadgeState {
        val label: String get() = if (truncated || n > 99) "99+" else n.toString()
    }
}

sealed interface NearbyContent {
    data object Loading : NearbyContent                       // before first Room emission (normally < 1 frame)
    data object ViewportUnknown : NearbyContent
    data object NoSavedPlaces : NearbyContent                 // DB has zero places
    data object NoneInView : NearbyContent                    // DB has places, none in bounds
    data class Places(val items: List<NearbyPlace>, val totalInView: Int, val truncated: Boolean) : NearbyContent
}

data class NearbyPlace(
    val id: Long, val name: String, val lat: Double, val lon: Double,
    val listName: String, val extraListCount: Int,            // "Restaurants +1"
    val listColor: Int /* ARGB */, val distanceMeters: Double,
)

// ImportantKind is the schema enum (HOME, WORK, CUSTOM; schema.md §1)
data class ImportantShortcut(
    val id: Long,                     // ImportantLocation.id (HOME = 1, WORK = 2)
    val kind: ImportantKind,
    val label: String,                // resolved: "Home"/"Work" from resources, or the custom label
    val latLon: LatLon?,              // null = not set
) { val isSet: Boolean get() = latLon != null }

interface PlacesBubbleActions {
    fun onBubbleTap()
    fun onClose()
    fun onPlaceClick(id: Long)              // move map there + collapse
    fun onNavigateClick(id: Long)           // nav handoff
    fun onImportantClick(id: Long)
    fun onMarkerTapped(id: Long)            // Phase 6.3
    fun onShowAllNearby()                   // Place card back-chevron (§5.8)
}

sealed interface BubbleEffect {            // UI-only; WebView-driving goes through MapCommands
    data class OpenImportantEditor(val id: Long) : BubbleEffect
}

@HiltViewModel
class PlacesBubbleViewModel @Inject constructor(
    private val places: PlacesRepository,
    private val nearby: NearbyPlacesSource,
    private val mapState: MapStateRepository,
    private val commands: MapCommands,
    private val prefs: BubblePrefs,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel(), PlacesBubbleActions {
    val uiState: StateFlow<BubbleUiState>      // stateIn(viewModelScope, WhileSubscribed(5_000), initial)
    val anchor: StateFlow<BubbleAnchor?>       // null until DataStore loaded (§4.6)
    val effects: Flow<BubbleEffect>            // Channel(BUFFERED).receiveAsFlow()
    fun onAnchorSettled(anchor: BubbleAnchor)
}
```

- `expanded`/`focus` are a `MutableStateFlow` inside the VM (not `SavedStateHandle`; see
  S12). They survive a configuration-change recreation (S14).
- Inputs the VM collects in `init`: `mapState.touches` (`Tap` → collapse, S7),
  `mapState.overlay` (IME, search editing, drive prompt), `mapState.urlState` (mode).
  No `onMapTapped`/`onImeVisibilityChanged` actions: those signals come from the repository
  (compose-host.md §5), not from the composable.
- `cardAllowed`/`bubbleVisible` exactly as in §2.
- **Row tap** (`onPlaceClick`): `commands.send(NavigateInPage(MapUrls.place(latLon, 17.0)))`
  then collapse. `MapController.navigateInPage` tries GMaps' in-page navigation and falls
  back to `loadUrl` (compose-host.md §4.1; B0 (f) decides whether the in-page path works).
  A full `loadUrl` cold-starts the SPA (seconds on a mid-range phone) and adds a history
  entry; that is the accepted fallback cost. The directions-route case cannot occur: the
  bubble is hidden in Directions mode. `MapUrls.place` formats with `Locale.ROOT`, 7
  decimals (never default locale, or German devices produce `52,52`).
- `onNavigateClick` sends `MapCommand.Navigate(NavTarget(LatLon(lat, lon), name))`
  (`NavTarget`, `LatLon`: compose-host.md §6). The collector hands it to `NavLauncher`
  (nav-handoff N4), which excludes our own package from the chooser and catches
  `ActivityNotFoundException`. There is no interim plain-`geo:` launcher: B8b depends on
  N3 + N4 (both small), because a bare `geo:` intent can resolve to this app itself.
- Unset Home/Work chip → `OpenImportantEditor(id)` (until Phase 4/7 exists: no-op + Toast
  "Set Home in settings (coming soon)" — keep the chip so layout is final). A set chip →
  `NavigateInPage(MapUrls.place(...))` + collapse, like a row.

### 5.7 Outside tap and IME

Touches come from the single `WebViewTouchObserver` owned by the host, via
`MapStateRepository.touches` (compose-host.md §5.2). The bubble does not install a listener.

- `MapTouch.Tap` is emitted only after `doubleTapTimeout` with no second down, so a
  double-tap zoom keeps the card open (S8). Single taps close the card.
- The tap still reaches GMaps (we don't consume it). So tapping a POI while the card is
  open closes our card **and** opens GMaps' place sheet. This is intentional (non-modal).
- A tap on GMaps' own controls (bottom sheet, layers button, side menu) also closes our
  card. We cannot tell those apart from the map without DOM hooks; documented as intended.
  D9 covers both cases.
- IME: `MapScreen` reports `WindowInsets.isImeVisible` into `mapState.setImeVisible`
  (compose-host.md §7.1); the VM reads `overlay.imeVisible`. When the IME is visible the
  bubble is hidden and the card collapsed (it would otherwise jump or cover GMaps' inputs).
  This requires `enableEdgeToEdge()` + `adjustResize` (compose-host 1.3a); on API 26–29
  `isImeVisible` is a heuristic (D12b).

### 5.8 Focused-place card (`Expanded(Place(id))`)

Reached only by a marker tap (Phase 6.3, S9). Same frame, size and placement as the Nearby
card.

| Element | Content |
|---|---|
| Header | back-chevron `IconButton` "Show all nearby" (→ `onShowAllNearby`, S9c), title = place name (1 line, ellipsis), X |
| Body | list chip (colour dot + list name), address (if any, selectable text), notes (if any, max 6 lines, then "More" expands), distance from map centre |
| Actions | **Navigate** (filled button, `onNavigateClick(id)`), **Show on map** (`onPlaceClick(id)`) |

```kotlin
data class PlaceCardState(
    val id: Long, val name: String, val listName: String, val listColor: Int,
    val address: String?, val notes: String?, val distanceMeters: Double?,
)
```

- Source: `places.observeDetail(id)` (schema.md §2.2), `flatMapLatest` on `focus`.
- Transitions: S9 (marker tap from Collapsed or Nearby), S9b (`Place(a)` → `Place(b)`,
  card content swaps without the enter animation), S9c (chevron → Nearby), S9d (detail
  emits null because the place was deleted or an external list was refreshed → Nearby).
- `bubble_enabled == false`: a marker tap still opens this card (§2, `cardAllowed`).
- IME visible / search editing / Directions etc.: `cardAllowed` false → Collapsed (S10).
- VM tests: one per transition above, plus "marker tap while disabled opens card".

---

## 6. Edge cases and failure modes

| Case | Expected behaviour / mitigation |
|---|---|
| Overlay full-size root accidentally gets `pointerInput`/`clickable`/background click | Map can't be panned. Guarded by Compose test T-C9 and device check D3. |
| Bubble or card drawn/hit-tested outside a clip rect | Offsets are on the outer `AnimatedVisibility` nodes (§3.2). Guarded by T-C13. |
| Bubble drag starts within the gesture-nav back zone | `systemGestureExclusion()`; device check D5 with gesture navigation on. |
| Drag ends with a fast fling across the screen | Side follows fling direction above 1000 px/s (§4.3). |
| Drag released over the top band (GMaps search) | Snaps to `minY`. |
| Bounds change while Dragging (rotation, IME, split-screen resize, anchor load) | `pointerInput(Unit)` is not restarted; `position.bounds` is updated and the settle uses it. If the gesture coroutine is cancelled anyway, the `finally` block settles (§4.5). D25. |
| Rotation while Expanded | Stays expanded; card re-clamped (S11). |
| Activity recreated by a configuration change | S14. |
| Split screen / tiny window | `minY = maxY` centre; card max height 0.9 × height; card scrolls. |
| Landscape with display cutout | Overlay uses `safeDrawing` after `consumeWindowInsets` → bubble never under the cutout, inset not applied twice. |
| RTL locale (ar, fa are shipped) | `EdgeSide.Start/End` are logical; default `End` = left in RTL. Preview P9. |
| Font scale 200% / display size max | Activity not recreated (compose-host §2.2); rows grow; badge text "99+" must still fit (badge auto-widens); card scrolls. |
| Keyboard opens (GMaps' own field or our search field) | Bubble hidden, card collapsed. |
| Our search field focused, IME then hidden by Back | Custom search clears focus when the IME hides; until then `searchEditing` keeps the bubble hidden (§2). |
| Viewport unknown at startup (`/maps` without `@`) | No badge; card shows "Move the map to see saved places here". |
| Street View | Bubble hidden entirely. |
| GMaps Directions mode | Bubble hidden; the top bar shows a back/close control (custom search). Returning to a map URL shows it again. |
| GMaps side menu / drawer, photo viewer or other full-screen GMaps overlay | Not detectable from the URL in all cases. If B0 shows a URL change (e.g. photo viewer `/photos/`, `data=…!4m…`), map it to `NonMap`; otherwise the bubble stays and the user can drag it away. Tapping inside those overlays closes the card (§5.7). Recorded in D26. |
| Non-map pages: `consent.google.com` on first run, My Maps `/maps/d/` ("Edit source", TODO 5.3) | `MapMode.NonMap` → bubble hidden (not "position unknown" over a consent form). |
| Second finger on the bubble during a drag | Ignored by `detectDragGestures`; does not reach the WebView (§4.5). D24. |
| Settings or list screens (Phase 3.3/7) | Separate navigation destinations; `MapScreen` and its overlays are not composed (compose-host.md §7.3). |
| Nav-handoff "Save this drive?" prompt visible | Bubble hidden (`drivePromptVisible`), so it cannot cover the prompt's buttons; it returns when the prompt is dismissed. |
| `bubble_enabled` switched off while expanded | Collapsed (S15). Markers can still open the Place card. |
| Corrupt DataStore file | `ReplaceFileCorruptionHandler` → defaults (§4.6). |
| Zero saved places | Badge hidden; card shows Important row + "No saved places yet. Places you save will appear here." |
| Places exist, none in view | "No saved places in this area. Zoom out to see more." |
| 10 000 places in view (zoomed out to world) | Query capped at 501, nearest first; list shows 100; badge "99+"; footer "500+ in view". |
| Same place saved in two lists | One row, "List +1", counted once (§5.5). |
| Antimeridian (Fiji, zoomed out over Pacific) | `west > east` branch in SQL, wrapped `dLon` in `ORDER BY`; test with fixture lon 179.9 and -179.9. |
| Zoom 0–2 (world wider than viewport) | Bounds = full longitude range. |
| GMaps changes URL format (DOM/URL drift) | Parser returns no camera → "position unknown" state, bubble still works as a shortcut list (Important row). Log once per session at WARN with the URL. Never crash. |
| URL polling while app in background | `repeatOnLifecycle(RESUMED)` stops it. |
| DataStore not yet loaded | Nothing rendered until the anchor is known (§4.6); no first-frame jump. |
| DataStore echo of our own settle | Ignored when equal to the current target; never interrupts a running spring (§4.6). |
| Process death | Collapsed, anchor restored, map at start URL. |
| Dark mode toggle (activity not recreated due to `configChanges=uiMode`) | `AndroidComposeView` updates `LocalConfiguration`, so `isSystemInDarkTheme()` recomposes. Device check D11. |
| WebView `AndroidView` removed and re-added to composition | Handled by the host (compose-host.md §3): the factory detaches the WebView from any previous parent. |
| Two quick taps on bubble | First expands; second lands on the card (header area) — harmless. No double-expand. |
| Card open + user drags the map or double-tap zooms | Card stays; list refreshes on settle (S8). |
| Navigate tapped with no nav app installed | Handled by nav handoff (Toast + chooser), card stays open. |

---

## 7. Accessibility

- **Bubble semantics**: `Modifier.semantics(mergeDescendants = true) { contentDescription =
  "Saved places"; stateDescription = "12 in view" / "none in view" / "map position unknown" }`.
  The click label and role are set **once**, by `clickable(onClickLabel = "Show nearby saved
  places", role = Role.Button)`; do not also set `onClick(label)` in semantics.
- **No live region on the badge.** It changes on every pan; announcing it would be noise.
  TalkBack reads the new state when focus lands on the bubble.
- **Dragging is not accessible**, so provide custom actions on the bubble:
  "Move to left edge", "Move to right edge", "Move up", "Move down" (±0.2 yFraction).
  They call the same `onAnchorChange` path (animated settle).
- **Touch targets**: bubble 56 dp; X and Navigate are `IconButton` (48 dp); place row min
  height 56 dp; chips use M3 minimum (48 dp touch via `minimumInteractiveComponentSize`).
- **Card**: root `Modifier.semantics { paneTitle = "Nearby saved places" }` so TalkBack
  announces the pane; `isTraversalGroup = true`. On expand, a `FocusRequester` on the
  header title is requested after the enter animation (`LaunchedEffect(expanded)` + 250 ms
  or the card's `AnimatedVisibility` `transition.isRunning == false`). On collapse, focus
  returns to the bubble (`FocusRequester` on the bubble).
- **Place card**: `paneTitle` = place name; the back-chevron has
  `contentDescription = "Show all nearby saved places"`.
- **Rows**: row merged description "Café Einstein, Restaurants, 350 metres"; the colour
  dot has no description (list name is in text — colour is never the only signal).
  Navigate button: `contentDescription = "Navigate to Café Einstein"`.
- **Distance strings for TalkBack**: visible "350 m", spoken "350 metres" via a separate
  `contentDescription` built from plurals resources.
- **Traversal order**: the WebView's virtual a11y tree is large; we *try* the overlay
  `Modifier.semantics { traversalIndex = -1f }` so the bubble comes first in swipe order.
  `traversalIndex` only orders siblings within one traversal group, and its effect relative
  to the `AndroidView`'s virtual tree is unverified: D14 checks it; if it has no effect,
  drop it rather than add workarounds.
- **Contrast**: use M3 colour roles only; verify with Accessibility Scanner (D13).
- All strings in `res/values/strings.xml` (the project ships 25 locales; add English only,
  translators follow). Use plurals for counts.

---

## 8. Task breakdown

Each task is a separate PR, small enough to review in ~15 minutes. Tags:
**[unit-testable]** = JVM tests prove it (Robolectric counts; say so in the PR);
**[needs device]** = must be checked on a phone/emulator by the human;
**[needs human review]** = visual judgement (previews, animation feel); some are several.

Prerequisites (not part of this feature, must be merged first):
- Phase 0.2 deps (+ `org.jetbrains.kotlin.plugin.compose`,
  `androidx.compose.material:material-icons-extended` or copy the 3 icons as vectors,
  `lifecycle-runtime-compose`), test deps: `junit`, `kotlinx-coroutines-test`,
  `app.cash.turbine:turbine`, `org.robolectric:robolectric` **≥ 4.16** (SDK 36; runs on the
  documented JDK 21), `androidx.compose.ui:ui-test-junit4`, `ui-test-manifest`
  (debugImplementation); and `android { testOptions { unitTests { includeAndroidResources =
  true } } }` (Robolectric and Compose tests need it). Versions (incl. Hilt) are chosen by
  the Gradle work, not here.
- Phase 0.3 Hilt skeleton.
- Phase 1.3a + 1.3b (`compose-host.md`): host, insets, `MapController`/`MapCommands`,
  `MapStateRepository`, `WebViewTouchObserver`, `@DefaultDispatcher` and `@RetainedScope`
  modules, `MapBackHandler`.
- Schema DB1 (`schema.md`).
- **Spike B0** (§0.1). Mandatory gate: B1's fixtures and B2a's hook choice depend on it.

| ID | Task | Tag | Done when |
|---|---|---|---|
| B0 | Device spike (§0.1); results written into §0.1 | [needs device] | Table §0.1 filled; real URLs committed as fixtures |
| B1 | `MapUrlParser` camera part (+ `MapMode` rules from compose-host §6.3), `WebMercator`, `Distance` (haversine + `formatDistance(meters, locale)`); `GeoModels` if 1.3c has not landed | [unit-testable] | All §5.2 fixtures + §5.3 test vector + antimeridian + zoom-0 pass; `Locale.GERMANY` default-locale test for every formatter |
| B2a | `MapUrlTracker` (URL supplier, `doUpdateVisitedHistory` primary, `onPageStarted`→null, 1 s poll fallback) + wiring into `WebViewHost` | [unit-testable] (fake supplier, virtual time) + [needs device] smoke | Unit tests: de-dupe, null on page start, poll only while resumed; on device: panning logs a new camera ≤ 1 s after the map settles |
| B2b | Debug-only crosshair + log line `camera=... bounds=...`; centre and `m`-suffix calibration | [needs device] (human) | Crosshair matches map centre within ~10 px at z ≥ 12; calibration constant recorded in a code comment |
| B3 | = schema task DB2 (`PlaceDao.observeInBounds` with `ORDER BY`, `observeHasAnyPlaces`, `observeDetail`, `ImportantLocationDao`) | [unit-testable] (Robolectric in-memory Room) | schema.md §5 tests; **no timing in this task** (host SQLite) — timing is D23 |
| B4 | `NearbyPlacesSource` + `dedupeNearby` + `PlacesBubbleViewModel` + `BubbleUiState` mapping with fakes | [unit-testable] (Turbine, `StandardTestDispatcher`) | Tests for every `NearbyContent` branch, badge labels 0/1/99/100/truncated, dedupe (same name+coords in two lists = 1), debounce (advanceTimeBy), state rows S1, S7 (incl. `Tap` from touches), S9–S9d, S10, S12, S14, S15; row tap sends `NavigateInPage`; Navigate sends `MapCommand.Navigate` |
| B5 | `BubbleBounds` (incl. `searchHidden`), `computeSnapAnchor`, `anchorToOffset`, `cardPlacement` | [unit-testable] | Tests: slow release left half → Start; fast fling right from left half → End; y clamp top/bottom; zero range; RTL mirror; round-trip anchor→offset→anchor; card sizes from §3.3 |
| B6 | Stateless UI: `PlacesBubble`, `BubbleBadge`, `NearbyCard`, `PlaceCard`, `PlaceRow`, `ImportantRow`, empty states; Compose tests | [unit-testable] (Compose UI tests via Robolectric) | T-C1..T-C6, T-C13 pass |
| B6p | `BubblePreviews.kt` (§9.3) | [needs human review] | Owner looked at P1–P14 in Android Studio |
| B7 | `BubblePositionState` + gesture wiring (§4.5) + `systemGestureExclusion` + `BubblePrefs` persistence (§4.6) | [unit-testable] + [needs device] | T-C7, T-C8, T-C14 pass; D1–D6, D24, D25 pass on device |
| B8a | Integrate `PlacesBubbleOverlay` into `MapScreen` (insets per §3.2), bubble branch of `MapBackHandler`, visibility gates from `overlay`/`urlState` | [needs device] | T-C9, T-C12 pass; D7, D10, D11, D12, D12b, D18, D26 pass |
| B8b | Command wiring: row tap → `NavigateInPage`, Navigate → `NavLauncher`; `Tap` from `mapState.touches` collapses. **Depends on nav-handoff N3 + N4** | [needs device] | D8, D9, D21, D22 pass |
| B9 | Accessibility: semantics, custom actions, pane titles, focus handoff, traversal index, plurals | [unit-testable] + [needs device] | T-C10..T-C12 pass; D13–D15 pass with TalkBack |
| B10 | Expand/collapse animation polish + `BubbleDefaults` tuning from device feedback | [needs device] | Human signs off D16 |

Order: B0 → B1 → (B2a, B3, B5 in parallel) → B2b → B4 → B6 → B7 → B8a → B8b → B9 → B10.
B6 can start as soon as B4's state classes exist (B4 can merge the data classes first).
B6p any time after B6.

Instructions for implementer agents:
- Do not combine tasks in one PR.
- Every public function in B1/B5 gets KDoc with units (px vs dp vs CSS px, degrees).
- Never format coordinates with default locale; use `Locale.ROOT` / `"%.7f".format(Locale.ROOT, x)`.
- No `!!` in new code; the existing `MainActivity` uses many — don't add more.

---

## 9. Test plan

### 9.1 JVM unit tests (`app/src/test`)

`MapUrlParserTest`, `WebMercatorTest`, `DistanceTest`, `BubbleSnapTest` (incl.
`cardPlacement`), `NearbyDedupeTest`, `MapUrlTrackerTest`, `PlacesBubbleViewModelTest`;
`PlaceDaoTest` is in schema.md §5 (Robolectric + `Room.inMemoryDatabaseBuilder`).
Run: `./gradlew :app:testDebugUnitTest`.

### 9.2 Compose UI tests

Run on JVM with Robolectric ≥ 4.16 (`@RunWith(AndroidJUnit4::class)`, `@Config(sdk = [35])`,
`@GraphicsMode(NATIVE)`) for speed; the same files can be moved to `androidTest` if
Robolectric misbehaves with the target SDK. Tests target `PlacesBubbleLayout` with a fake
`PlacesBubbleActions` that records calls, and a simple `mutableStateOf(BubbleUiState)`
driver so the test can play the VM. Use `createComposeRule()`; for back use
`createAndroidComposeRule<ComponentActivity>()`.

Test tags (constants in `BubbleTestTags`): `bubble`, `bubble_badge`, `nearby_card`,
`nearby_card_close`, `place_row_<id>`, `place_row_nav_<id>`, `important_chip_<kind>`,
`empty_state`, `place_card`, `place_card_back`.

| ID | Test |
|---|---|
| T-C1 | Badge: `Count(7)` shows "7"; `Count(150)` shows "99+"; `Hidden` → `bubble_badge` doesNotExist |
| T-C2 | Tap `bubble` → `actions.onBubbleTap` called once; with state flipped to expanded, `nearby_card` isDisplayed and `bubble` doesNotExist (after `mainClock.advanceTimeBy(300)`) |
| T-C3 | Tap `nearby_card_close` → `onClose` |
| T-C4 | Each `NearbyContent` variant renders its empty-state text / rows; `Places` with 100 items: `performScrollToNode(hasTestTag("place_row_99"))` succeeds |
| T-C5 | Tap row → `onPlaceClick(id)`; tap row's nav button → `onNavigateClick(id)` and NOT `onPlaceClick` |
| T-C6 | Important chips present in every content state, including `NoSavedPlaces` |
| T-C7 | Drag: `onNodeWithTag("bubble").performTouchInput { down(center); moveBy(Offset(-width*3, 0f), delayMillis = 100); up() }`; `mainClock.advanceTimeBy(1000)`; bubble `getBoundsInRoot().left` ≈ margin (±1 dp); `onAnchorChange` received `side = Start`; `onBubbleTap` NOT called |
| T-C8 | Small movement (< slop) + up counts as tap |
| T-C9 | Overlay passthrough: put a `Box(Modifier.fillMaxSize().testTag("under").clickable { hits++ })` *below* `PlacesBubbleLayout` in the test; click at a point away from the bubble → `hits == 1`, with card expanded too (click outside card) |
| T-C10 | Bubble `assertHeightIsAtLeast(48.dp)`; X and nav buttons `assertHeightIsAtLeast(48.dp)` |
| T-C11 | Bubble has `ContentDescription` "Saved places" and `StateDescription` "7 in view"; custom action "Move to left edge" exists and invoking it (`performSemanticsAction(SemanticsActions.CustomActions)` → find by label → invoke) calls `onAnchorChange(side = Start)` |
| T-C12 | Card root has `PaneTitle`; with `MapBackHandler` (compose-host §7.4) composed around the layout, back press (`activity.onBackPressedDispatcher.onBackPressed()`) while expanded → `onClose` |
| T-C13 | Clipping guard (BL-2): anchor `End, 0.9` in a 360×720 dp box → node `bubble` `isDisplayed()`, its bounds are at the right edge, and `performClick()` reaches `onBubbleTap`; expanded with the same anchor → `nearby_card_close` `isDisplayed()` and clickable |
| T-C14 | Interrupted settle persists the target: drag-release with a fling, then `down()` on the bubble before the spring ends → `onAnchorChange` was called exactly once with the computed target |
| T-C15 | Place card: state with `focus = Place(1)` shows the name, `place_card_back` → `onShowAllNearby`, Navigate → `onNavigateClick(1)` |

### 9.3 Previews (for visual review in Android Studio)

In `BubblePreviews.kt`, all wrapping `PlacesBubbleLayout` in the app theme inside a
`Box(Modifier.size(360.dp, 720.dp).background(Color(0xFFE5E3DF)))` (map-ish grey):

P1 collapsed, no badge · P2 collapsed, badge 7 · P3 collapsed, badge 99+ on left edge ·
P4 expanded, 8 places · P5 expanded, `NoSavedPlaces` · P6 expanded, `NoneInView` ·
P7 expanded, `ViewportUnknown` · P8 expanded, Home set / Work unset, very long place
names · P9 RTL (`locale = "ar"`) · P10 `@PreviewFontScale` on P4 · P11
`@PreviewLightDark` on P2 and P4 · P12 landscape
(`device = "spec:width=800dp,height=360dp"`) on P4 · P13 truncated list footer ·
P14 Place card (long notes, no address).

Use `@PreviewParameter` with a `BubbleUiStateProvider` to avoid copy-paste. Screenshot
testing (Roborazzi or the Compose Preview Screenshot plugin) is **optional, not in scope**;
check AGP 9 compatibility before adding either.

### 9.4 Manual device checklist (for the human tester)

Test on one gesture-navigation phone (Android 13+) and, if possible, one 3-button-nav
device. Seed data: debug-only menu or `adb` DB push with ~20 places around your city plus
one place in Fiji (lon 179.x) and one hidden-sublist place.

| ID | Check | Pass if |
|---|---|---|
| D1 | Drag bubble around slowly, release in left half | Glides to left edge, slight overshoot, settles ≤ 0.5 s |
| D2 | Flick it hard to the right from the left half | Ends on right edge |
| D3 | With bubble collapsed AND with card open: pan, pinch, double-tap the map in areas not covered by our UI | Map responds exactly as without the bubble |
| D4 | Drop bubble at very top / very bottom | It clamps below GMaps' search pill and above the "my location" button. Note any overlap with screenshots |
| D5 | Gesture nav on: start dragging from the outer half of the bubble horizontally inward | Bubble drags; Back does NOT fire |
| D6 | Kill app (swipe away), reopen | Bubble on same side and height |
| D7 | Tap bubble | Card opens next to it, X visible, content correct |
| D8 | Card open: pan the map so saved places enter/leave view | Card list and subtitle update within ~1 s after map stops |
| D9 | Card open: single tap on empty map / on a POI | Card closes; tapping POI also opens GMaps place sheet |
| D10 | Card open: press Back | Card closes; map does NOT go back in history. Second Back does history-back |
| D11 | Rotate with card open; toggle dark mode with card open | No crash, card re-laid-out, colours switch |
| D12 | Tap GMaps search field (keyboard opens) | Bubble disappears; reappears when keyboard closes |
| D13 | Run Accessibility Scanner on map with card open | No contrast/touch-target errors from our UI |
| D14 | TalkBack: swipe to bubble | Reads "Saved places, 7 in view, button"; double-tap opens; announces "Nearby saved places"; focus on title |
| D15 | TalkBack: use Actions menu on bubble → "Move to left edge" | Bubble moves |
| D16 | Overall feel: animations, positions, colours, badge readability in sunlight | Human sign-off; tweak `BubbleDefaults` |
| D17 | Zoom out to whole world; go to Fiji | Badge 99+ / footer shows; Fiji place counted when the view straddles 180° |
| D18 | Open Street View | Bubble hidden; back to map → bubble returns |
| D19 | Font size max + display size max in system settings | Card usable and scrollable, nothing clipped |
| D20 | Split screen with another app | Bubble visible, card fits |
| D12b | (API ≤ 29 device or emulator, if available) repeat D12 | Bubble hides/returns with the keyboard; if the heuristic fails, record it as a known caveat |
| D21 | 360 dp-wide phone, card open: pan the map | Enough map stays visible (≥ 50 % height, a strip beside the card) for "pan and watch the list" to be useful. Owner judgement |
| D22 | Tap a row | Map moves to the place; note whether it was in-page (no white flash/reload) or a full reload; ≤ 3 s on a mid-range phone |
| D23 | Seed 10 000 places; pan around and log `observeInBounds` timing (debug log) | Query < 20 ms at city zoom, < 100 ms zoomed out to the world; record numbers |
| D24 | Drag the bubble, put a second finger on it mid-drag | Drag continues normally; the map does not zoom/pan |
| D25 | Start dragging, rotate the device (or open split-screen) mid-drag, release | Bubble settles on an edge; nothing stuck mid-screen |
| D26 | Open GMaps directions, the side menu, a photo viewer, and (first run) the consent page | Bubble hidden in Directions and on the consent page; for the menu/photo viewer record what happens |
| D27 | Change system font size and language with the card open (no recreation since compose-host §2.2) | Card stays open, re-laid-out; map not reloaded |

---

## 10. Acceptance criteria (feature-level)

1. All JVM and Compose tests in §9.1–9.2 pass in CI (`./gradlew testDebugUnitTest`).
2. D1–D27 pass, or each failure has a filed follow-up the owner accepted.
3. Map gestures are never blocked outside the bubble/card bounds (D3, T-C9).
4. On a flat map (`CameraMode.Map`), the badge equals the number of distinct (§5.5),
   visible-sublist saved places within the visible map, within 1 s of the map settling
   (D8), capped at "99+". In `Tilted` and `Satellite3dMeters` it is an approximation that
   may include places just off screen.
5. The bubble never crashes or blocks the app when the URL cannot be parsed; it degrades to
   the "position unknown" state.
6. Bubble position persists across process death; expanded state does not.
7. All interactive elements ≥ 48 dp; all have TalkBack labels; dragging has accessible
   alternatives.
8. No new `!!`, no new locale-dependent coordinate formatting, no `Popup`/`Dialog` used for the card.
9. The bubble is hidden in Directions mode, Street View and on non-map pages.
