# Adversarial review of the design specs

Reviewed 2026-10-21 against the working tree at `140d0b1` (with uncommitted `PROJECT_GOALS.md`/`TODO.md`
edits), `MainActivity.kt`, the manifest, the Gradle files and the four specs. Abbreviations:
`floating-places-bubble.md` = **FPB** (primary target), `nav-handoff.md` = **NH**, `map-markers-js.md` = **MM**,
`custom-search-field.md` = **CSF**. Severity: **blocker** = a literal implementation gets a broken build or
feature, or cannot start; **should-fix** = a real bug, gap or inconsistency that will cost rework; **nit** =
wording or polish. Counts: 5 blockers, 24 should-fix, 10 nits.

## 1. Blockers

### BL-1: FPB §8 / §5.1. The bubble's core input has not been validated, and no spike comes first
The badge, the list, all of MM Renderer P and the CSF "bias to viewport" feature all assume one thing: mobile
GMaps, running with the Pixel 7 UA, rewrites `@lat,lng,zoomz` in the URL after every pan or zoom. Nobody has
checked this. The B1 fixtures are hand-written. B2, the first task that observes reality, comes *after* B1 and
in parallel with B3 and B5. The other three specs each start with a spike (N0, M0, S0). FPB does not.
**Fix:** add **B0 (≤ 1 h, [needs device])** before B1. Log `doUpdateVisitedHistory`, `onPageFinished` and
`webView.url` while you pan, pinch, fling, open a place, search, open directions, switch to satellite and open
Street View. Record the following in FPB §0: (a) whether the URL updates on pan at all; (b) the latency after
the map settles; (c) whether `doUpdateVisitedHistory` fires for each update (see SF-1); (d) real URLs for
every suffix kind (`z`, `m`, `a`, tilt and heading); (e) `window.innerWidth` and `devicePixelRatio`. Turn the
real URLs into the B1 fixtures. Merge M0's latency measurement into B0 so it is done once.

### BL-2: FPB §3.2 and §4.5. `AnimatedContent` combined with `Modifier.offset` on the child will clip the bubble
`AnimatedContent` sizes its container to the content (56 dp) at the top-left of the `BoxWithConstraints`. Its
default `SizeTransform(clip = true)` clips the content to that box. The bubble is placed with `Modifier.offset
{ position.offset }` *inside* the content. Anywhere other than (0,0) it is therefore drawn, and hit-tested,
outside a 56 dp clip rect. The likely result is that the bubble is invisible or cannot be tapped. The card has
the same problem.
**Fix:** do not put the positioning offset inside `AnimatedContent`. Either (a) render bubble and card as two
siblings, each with its own `AnimatedVisibility` and each with its own `Modifier.offset` on the outer node; or
(b) put the offset on the `AnimatedContent` modifier and pass `using SizeTransform(clip = false)`. Add a
Compose test, T-C13: with the anchor at `End, 0.9`, the node tagged `bubble` `isDisplayed()` and a click on it
reaches `onBubbleTap`.

### BL-3: FPB §5.4, NH §6, CSF §4 and TODO 3.1/4. The schema is amended in three places, and Important Locations cannot be modelled
- FPB adds `Place.externalSublistId` and an `(lat, lon)` index. NH makes `Drive.originLat/Lon` nullable and
  specifies a waypoint JSON shape. CSF adds `Place.nameNormalized`, `Place.addressNormalized` and the
  `RecentSearch` entity. TODO 3.1 contains none of these. Three different implementers would each edit the
  same `@Database`.
- TODO 4 says Home and Work are "pre-seeded … empty coords". TODO 3.1 has non-null `Place.lat/lon`, so an
  unset Home cannot be stored. FPB's `ImportantShortcut(kind, …)` and `observeImportant()` need a stable
  `kind`. A localised list name or place name ("Home", "Zuhause") cannot serve as a key. No spec defines
  `observeImportant()`.
**Fix:** before Phase 3.1 starts, rewrite TODO 3.1 as the **single** schema (version 1, with `exportSchema =
true`) that includes every amendment above. Model Important Locations explicitly. Either use a separate
`ImportantLocation(id, kind: HOME|WORK|CUSTOM, label, lat: Double?, lon: Double?, address, sortOrder)` table,
which is simpler and matches the goal "stored separately; survive list clears", or add `Place.importantKind`
and allow nullable coordinates only for that kind. Add the `observeImportant()` signature to FPB §5.4. State
that enums are stored by `name` (FPB's SQL compares `l.type != 'IMPORTANT_LOCATION'`; an ordinal converter
would break it silently).

### BL-4: Build. Hilt 2.56.2 on AGP 9 is very likely to fail, and KSP is resolved by accident
See §5. Phase 0.2 and 0.3 cannot be merged until this is verified. Every FPB task except B1 and B5 depends on
Hilt.

### BL-5: FPB §8 prerequisite "Phase 1.3 Compose host". This is the riskiest step and has no spec
Every spec assumes a working `MapScreen` with an `AndroidView` WebView. The facts below make Phase 1.3
non-trivial, and no spec covers them:
- `MainActivity` owns `mapsWebView` and calls `resetWebView(true)`, which destroys the WebView, in
  `onDestroy`.
- `onCreate` calls `window.clearFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)` and
  `requestWindowFeature(FEATURE_NO_TITLE)`, and relies on `fitsSystemWindows`. That is incompatible with
  Compose edge-to-edge insets (see SF-7). The `OnBackPressedCallback` reads `mapsWebView` directly.
- FPB §6 only mentions in passing that the WebView must be detached from an old parent when re-added.

**Fix:** write a short `compose-host.md`, or a section of TODO 1.3, with these points:
- Create the WebView in the Activity and hand it to `AndroidView(factory = { webView })`, never `remember`ed
  inside composition.
- Call `enableEdgeToEdge()` and remove `clearFlags`/`FEATURE_NO_TITLE`. Set
  `windowSoftInputMode="adjustResize"`. Delete `activity_main.xml`.
- Give the WebView, its lifecycle hooks, the touch observer (SF-12) and a `MapController` (SF-11) one owner.

Split it into 1.3a (host and insets, [needs device]) and 1.3b (MapController, MapStateRepository wiring).

## 2. Should-fix

### Android, Compose and WebView correctness

**SF-1: FPB §0 row 6 and §5.1. Viewport hooks: the reasoning is right, the plan needs tightening.** The spec
is correct that `shouldOverrideUrlLoading` does not fire for `pushState`/`replaceState`. TODO 3.2's hook is
therefore wrong, and so is its `onPageStarted` fallback. Chromium's WebView calls `doUpdateVisitedHistory`
from `didFinishNavigation` for every committed main-frame navigation, including same-document ones, so it very
likely *does* fire for `replaceState`. B0 confirms this. Reading `webView.url` 2×/s on the main thread costs a
JNI call and a string; that is harmless but should not be the main mechanism.
**Fix:** keep the poll, but make it a 1 s fallback behind a `BubbleDefaults.UrlPollMs` constant. Delete it if
B0 shows that `doUpdateVisitedHistory` covers every case. Update TODO 3.2 and 8.4 so they stop telling
implementers to hook `shouldOverrideUrlLoading`.

**SF-2: FPB §0 row 5 and S12. The claim that "process death is the only recreation" is false.**
`configChanges` omits `fontScale`, `density`, `locale`, `layoutDirection`, `keyboard|keyboardHidden` and
`navigation`. Changing the font size (this is D19 itself), the display size or the language, or attaching a
hardware keyboard, recreates the Activity. Then `onDestroy → resetWebView(true)` destroys the WebView *and
wipes cookies*, and the new Activity reloads the start URL. The `@ActivityRetainedScoped` repository and the
ViewModels survive, so the VM's `expanded` survives while the map resets. `MapUrlTracker`, which holds the
WebView, must not leak the old instance.
**Fix:** correct the facts row. Add the recreation case to the S-table: the VM keeps `Expanded`,
`MapStateRepository.url` is reset to null on the new WebView, and the tracker is created per Activity. Owner
decision D8: either add the missing `configChanges`, or guard `resetWebView(true)` with
`!isChangingConfigurations`.

**SF-3: FPB §4.5. `pointerInput(bounds)` restarts the gesture mid-drag.** When `bounds` changes (insets, IME,
split-screen resize, the DataStore anchor load) the `pointerInput` coroutine is cancelled.
`detectDragGestures` does **not** call `onDragCancel` on coroutine cancellation, so `isDragging` stays true
and the bubble is left mid-screen. The edge-case row "Rotation while Dragging → onDragCancel fires" is
unproven.
**Fix:** key `pointerInput(Unit)` and read the bounds through `rememberUpdatedState`. Wrap the gesture in `try
{ … } finally { if (dragging) settle(Velocity.Zero) }`.

**SF-4: FPB S6 and §4.4/§4.5. An interrupted settle never persists the anchor.** `onDragEnd = { scope.launch {
onAnchorChange(position.settle(v)) } }`. If a new touch cancels `animateTo` (which the spec relies on for S6),
`settle` throws `CancellationException` and `onAnchorChange` never runs. That contradicts S6's "anchor
persisted = the target".
**Fix:** split it into `val target = position.computeTarget(v); onAnchorChange(target);
position.animateTo(target)`. Persisting before animating is safe because the anchor is a target.

**SF-5: FPB §4.6 and §6 "DataStore not yet loaded". The first frame flickers and the anchor feeds back.**
`anchor` starts at `Default` and then jumps when DataStore emits, which is visible at every launch. After
every settle, the persisted anchor is emitted back. If that triggers `snapToAnchor` while the spring is still
running, the animation is cut off.
**Fix:** make `anchor: StateFlow<BubbleAnchor?>` and do not render the bubble while it is null. Apply a
DataStore emission only when it differs from the local target and no drag or settle is running.

**SF-6: FPB §3.2. Insets are applied twice.** `Box(Modifier.padding(inner))` followed by
`windowInsetsPadding(safeDrawing.only(Horizontal + Bottom))` on the overlay. M3 `Scaffold` passes `inner` as
padding but does not consume those insets for descendants. The bottom navigation-bar inset (and the cutout
inset) is therefore applied twice, which lifts the bubble's `maxY` by an extra 24–48 dp.
**Fix:** use `Modifier.padding(inner).consumeWindowInsets(inner)` on the Box, then `safeDrawing` on the
overlay. Check D4 with 3-button navigation.

**SF-7: FPB §5.7 "IME". `WindowInsets.isImeVisible` does not work under the current window setup.** It is
`@ExperimentalLayoutApi`. It reports correctly only when the decor does not fit system windows
(`enableEdgeToEdge()`) and with `adjustResize`. Only CSF §6 adds `adjustResize`. On API 26–29 the value is a
heuristic.
**Fix:** list both in FPB §8 prerequisites (see BL-5). Add D12b: a device running API ≤ 29, or skip it with a
documented caveat.

**SF-8: FPB §5.7 "outside tap". A "map tap" cannot be told apart from a tap on GMaps' own controls.** The
`OnTouchListener` sees every touch on the WebView. A tap on GMaps' bottom sheet, layers button or side menu
also closes our card. The first tap of a double-tap zoom closes the card too, which is a mild contradiction of
D3.
**Fix:** document both cases as intended. Emit `Tap` only after `doubleTapTimeout` passes with no second down
(cancel it if one arrives), so a double-tap zoom keeps the card open. Add both cases to D9.

**SF-9: FPB §1 and §1.1. On phones the card covers most of the map, which weakens the stated rationale.** The
card width is `min(360 dp, maxWidth − 24 dp)`. On a 360–412 dp phone that is effectively full width, and the
card is 60 % of the height. Option A's argument, "pan and watch the list update", leaves about 40 % of the map
visible. Badge bounds still use the whole WebView, so the list includes places under the card. This is not a
bug, but it is a UX premise that is asserted rather than tested.
**Fix:** add D21 ("card open on a 360 dp phone: is panning still useful?"). Consider a narrower compact card
(`maxWidth × 0.85`, 45 % height) or bottom anchoring on compact widths. Owner decision D2.

**SF-10: FPB §5.6 "Place click URL". `loadUrl` reloads the whole page.** A row tap calls
`webView.loadUrl(/maps/place/…)`. That cold-starts the SPA (seconds on a mid-range phone), recreates the map
(MM must re-add pins), adds a history entry, and **discards an open directions route** if the user was in
`/maps/dir/`.
**Fix:** in Directions mode, a row tap only collapses the card (or asks first). Document the reload cost. Add
a later task "pan via JS if MM Renderer J exists". Add D22: row tap while a route is shown.

### Cross-spec interfaces and data model

**SF-11: All four specs define their own ways to drive the WebView.** FPB has `BubbleEffect.LoadUrl`. CSF has
`SearchEffect.LoadUrl` and `OpenGmapsMenu`. MM's cluster tap needs a `LoadUrl`, but `MarkerLayerViewModel` has
no effects channel. NH has a `WebLoader` interface. MM and CSF both call `evaluateJavascript` and
`addDocumentStartJavaScript`.
**Fix:** define one `MapController` interface (`loadUrl`, `evaluateJs`, `postMessage`), implemented by the
Activity-scoped WebView holder. ViewModels emit to it through one `@ActivityRetainedScoped MapCommands`
channel that `MapScreen` collects once. NH's `WebLoader` should be this interface.

**SF-12: MM §3.5, FPB §5.7 and CSF §7. Touch events have no defined route into ViewModels.** MM feeds
`WebViewTouchObserver.events` directly into `MarkerLayerViewModel`. The observer holds a WebView and cannot be
injected into a VM. FPB wires it in `MapScreen`. A `View` has only one `OnTouchListener`, so there must be
exactly one observer.
**Fix:** the Activity installs the single observer and forwards its events into `MapStateRepository.touches:
SharedFlow<MapTouch>`. All three ViewModels consume them from there. State this in FPB §5.1 next to the URL
forwarding.

**SF-13: CSF §2.1 vs FPB §5.2. Two parsers classify the same URL.** `MapModeParser` (CSF) and
`MapUrlParser.CameraMode.StreetView` (FPB) detect Street View independently, so they will drift apart.
**Fix:** use one `MapUrlParser.parse(url): MapUrlState(camera: MapCamera?, mode: MapMode, displayText:
String?)` in B1. CSF S1 extends it instead of creating a parallel parser. `MapStateRepository` exposes
`urlState`.

**SF-14: MM §3.5 / M3. `NearbyPlacesSource` has no scope, and markers need Home/Work too.** `shareIn` inside
an `@ActivityRetainedScoped` class needs a `CoroutineScope` that is cancelled by
`ActivityRetainedLifecycle.addOnClearedListener`, and no spec provides one. Also, MM draws Home/Work glyphs,
but `observeInBounds` excludes `IMPORTANT_LOCATION`.
**Fix:** define the scope (a Hilt module providing `@ActivityRetainedScoped CoroutineScope`). Move the
`distinctUntilChanged → debounce → flatMapLatest` pipeline from the FPB VM into `NearbyPlacesSource` from the
start, which removes the M3 refactor. Add a combined "important in bounds" stream for markers.

**SF-15: FPB §5.4/§5.5. `LIMIT 501` without `ORDER BY` makes "100 nearest" false.** SQLite returns an
arbitrary 501 rows. When more than 501 are in view, the VM sorts *those*, and the footer "Showing 100 nearest
of N" shows N = 501 when the true number is larger. MM clusters from the same arbitrary subset, so cluster
counts are wrong.
**Fix:** add `ORDER BY (p.lat-:cLat)*(p.lat-:cLat) + ((p.lon-:cLon)*:cosLat)*((p.lon-:cLon)*:cosLat)` (handle
the antimeridian by passing `cLon` shifted per branch, or accept the approximation). Change the footer to
"Showing 100 nearest · 500+ in view" when truncated. Also note that the composite `(lat, lon)` index only
gives a range scan on `lat`. That is acceptable, but measure B3 timings on a device, not under Robolectric
(host SQLite).

**SF-16: FPB §5.6 vs NH §5. `NavTarget` and `LatLon` are defined twice and do not match.** FPB calls
`NavTarget(lat, lon, label = name)`. NH defines `NavTarget(latLon: LatLon?, label: String?)` and its own
`LatLon`. FPB defines `LatLon` in `domain/GeoModels.kt`.
**Fix:** `LatLon` lives in `domain/GeoModels.kt` (B1). NH imports it. FPB uses `NavTarget(LatLon(lat, lon),
name)`. If NH needs `Parcelable` for `PendingDrive`, it must either keep `GeoModels` free of Android (and map
to a parcelable DTO) or apply `kotlin-parcelize` to the domain types. Choose the first.

**SF-17: FPB §5.6 "until Phase 2 lands … plain `geo:` intent". This can loop back into our own app.** The
manifest makes this app a `geo:` handler. Without NH's chooser exclusion and `<queries>`, "Navigate" can open
the app itself, and `ActivityNotFoundException` is uncaught.
**Fix:** make B8 depend on NH N3 and N4 (they are small), or require that B8's interim launcher uses
`createChooser` with `EXTRA_EXCLUDE_COMPONENTS` and a try/catch.

**SF-18: FPB §2 and CSF §7 "Back while editing". The claim that only one back handler is enabled at a time is
false.** The first system back while the IME is up hides the IME and is never delivered to the dispatcher. The
search field stays focused (`editing = true`) and the bubble becomes visible again. The user can then expand
the card, so both `BackHandler`s are enabled and the result depends on composition order.
**Fix:** FPB visibility becomes `enabled && !imeVisible && !searchEditing && mode !in {StreetView}`, with
`searchEditing` published through `MapStateRepository` or a small shared `OverlayCoordinator`. Alternatively,
CSF clears focus when the IME hides. Choose one and write it into both specs.

**SF-19: MM §5 "Programmatic camera change". The spec relies on behaviour that FPB's tracker does not have.**
MM says `onPageStarted` sets the camera to null, but `MapUrlTracker` has no `onPageStarted` hook. MM also
hides pins only on our touch gestures. GMaps moves the map without touch too: fitting search results, the "my
location" button, double-tap animations and opening a place. Pins are then drawn at stale positions until the
URL updates, which violates MM acceptance criterion 3.
**Fix:** add `onPageStarted(url) → reportUrl(null)` to the `MapUrlTracker` API in FPB §5.1. MM must enter
`Moving` on *any* URL change and on any `MapTouch`, and must state its limitation for animations GMaps starts
by itself.

**SF-20: FPB §2 S9 and §1. The focused-place card has no spec.** `Expanded(Place(id))` is reachable through a
marker tap, but nothing says what that card shows, how the user gets back to `Nearby`, or what happens in
these cases: the place is deleted, the bubble is disabled (`bubble_enabled = false`, so `visible = false` and
the marker tap does nothing), the IME is up, or a marker is tapped while already in `Place(a)`.
**Fix:** add a section that defines the Place card content (name, list, address, notes, Navigate, "Show all
nearby" back-chevron). Define the transitions `Place(a) → Place(b)` and `Place → Nearby`. When the place is
deleted, fall back to `Nearby`. When the bubble is disabled, a marker tap still opens the card. Add VM tests
for each.

**SF-21: FPB §6. Bubble edge cases that are missing.** Add a row for each:
- GMaps **directions mode**: the bubble and the card cover GMaps' route UI. Hide the bubble or keep it? (D5)
- GMaps **side menu / drawer** open, **photo viewer** or other full-screen GMaps overlays.
- **Non-map pages:** `consent.google.com` on first run, My Maps `/maps/d/` ("Edit source", TODO 5.3). The URL
  parses to null and the bubble shows "position unknown" over a consent form. Hide the bubble when the host or
  path is not a map page.
- **Second finger** on the bubble during a drag (`detectDragGestures` tracks one pointer; the second must not
  reach the WebView).
- **Settings or list screens** (Phase 3.3/7): the bubble must not float over them. This depends on the
  navigation structure, which no spec defines.
- **NH save-drive prompt**: it is bottom-anchored, and the collapsed bubble at `maxY` can sit on top of the
  prompt's buttons. NH puts the prompt "below the bubble card". Raise the bubble's effective `maxY` while the
  prompt is shown, or hide the bubble.
- `bubble_enabled` switched off while expanded (S10; add a test); a corrupt DataStore file
  (`ReplaceFileCorruptionHandler` falling back to defaults).

**SF-22: FPB §5.2 "Tilted → expanded by 1.5×" vs acceptance criterion 4.** The expansion counts off-screen
places in the badge, while criterion 4 says the badge "equals" the number of places in view.
**Fix:** relax criterion 4 for `Tilted` and `Satellite3dMeters`, or hide the badge in those modes (MM already
hides pins there).

### Tasks, tagging and Sonnet-sizing

**SF-23: Tasks that are too big or tagged wrong.**
- **B2** combines the tracker, the repository, MainActivity wiring, a debug crosshair *and* a human
  calibration. Split it into B2a (tracker and repository plus wiring, [needs device] smoke test) and B2b
  (crosshair and calibration, human). Most of B2a also becomes unit-testable if `MapUrlTracker` takes a `() ->
  String?` URL supplier instead of a `WebView`.
- **B8** combines overlay integration, `BackHandler`, the touch observer, IME, the effects collector and the
  nav launch. Split it into B8a (overlay plus Back plus IME) and B8b (touch observer plus effects and
  `MapController`).
- **B6:** "Previews render" is not something a JVM test proves. Tag it [needs human review], and keep
  T-C1–T-C6 as the [unit-testable] part.
- **B3:** the "seeded-DB timing log" under Robolectric measures host SQLite. Move timing to D-checklist item
  D23.
- **NH N1/N2:** `android.net.Uri` / `Uri.decode` are stubs in plain JVM tests ("Method … not mocked", or
  silently null if `returnDefaultValues` is set). They need Robolectric. Say so, and do not suggest
  `java.net.URI` (it throws on `intent://…#Intent;…;end` strings that contain unescaped characters).
- **TODO 0.2/0.3** are tagged "[unit-testable] (build-verified)". They are build-verified, not unit-testable.
  Add a `[build]` tag to PROJECT_GOALS' definition of done.
- Missing prerequisites in FPB §8: a Hilt module for the `@DefaultDispatcher` qualifier; `testOptions {
  unitTests { includeAndroidResources = true } }` (Robolectric and Compose tests need it); Robolectric ≥ 4.16
  for SDK 36, which runs on JDK 21 (that is already the documented `JAVA_HOME`).

**SF-24: NH §2/§3. The reversal of TODO 3.4 has a concrete bug, in addition to the trade-offs in §4.** "Show
prompt on ON_RESUME" also fires when the user **cancels the chooser**. The chooser is a translucent activity,
so we only pause and the nav app never launches. It also fires after the location-permission dialog and any
other pause/resume.
**Fix:** arm the prompt only after `ON_STOP` follows the launch. `Launched` from `startActivity` does not
prove that a nav app opened when a chooser is shown.

## 3. Nits

- **N-1, FPB §4.5:** "Order matters: `pointerInput` before `clickable`". The reasoning is off. The leftmost
  modifier is the *outer* node, so `clickable` sees the Main pass first. Taps are suppressed because
  `clickable` checks consumption in the Final pass. The code works in either order, so drop the claim or
  correct it.
- **N-2, FPB §4.5 velocity:** `position.offset + change.position` mixes an asynchronously updated offset with
  a local position. Feed the tracker an accumulated `startPos + Σ dragAmount` instead.
- **N-3, FPB §7:** `semantics(mergeDescendants)` plus `clickable(onClickLabel)` plus `onClick(label)` set the
  same thing three times; choose one. `traversalIndex = -1f` only orders siblings within a traversal group.
  Its effect relative to the `AndroidView`'s virtual tree is unverified, so test it in D14 rather than
  assuming it.
- **N-4, FPB §4.1:** `minY = 72 dp` assumes GMaps' search pill is present. After CSF hides it, the band is
  wasted. Make it depend on `searchHidden`.
- **N-5, FPB §5.4:** an FK on `externalSublistId` without an index triggers a Room warning; add
  `Index("externalSublistId")`.
- **N-6, CSF §6:** conditional `imePadding()` on the WebView container changes the reported viewport size, so
  `bounds` change while GMaps inputs are focused. This is harmless for the bubble (it is hidden) but MM pins
  would re-project. Freeze `viewportCssPx` while the IME is visible.
- **N-7, FPB §0 row 3:** "KSP is pinned to `2.1.20-1.0.32`" is misleading. That is the declared version, but
  Gradle resolves it to `2.2.10-2.0.2` (see §5).
- **N-8, FPB §5.6:** coordinates are formatted with `"%.7f".format(Locale.ROOT, x)`. That is correct; also add
  a test that runs under `Locale.GERMANY`, as NH N3 already does.
- **N-9, TODO 3.2, 8.4 and Phase 6.1:** these still contain the superseded assumptions
  (`shouldOverrideUrlLoading`, `google.maps.Map`, German `aria-label`s). Add "superseded by docs/design/…"
  pointers so a Sonnet implementer reading TODO alone is not misled. Also carry the specs' valid corrections
  back into TODO 2.2: `link=` already uses `getQueryParameter`; `@lat,lon` in `/maps/dir/` is the camera;
  use `geo:lat,lon?q=lat,lon(label)`; add `<queries>`.
- **N-10, MM §4.2 / TODO 6.2:** `addWebMessageListener` instead of `addJavascriptInterface` is a good call.
  Write the same choice back into TODO 6.2.

## 4. NH reversal of TODO 3.4 ("navigate first, ask 'Save drive?' on return")

**Verdict: the reversal is sound, provided SF-24 is fixed. One alternative deserves owner consideration.**
TODO 3.4 asks for a prompt that is both "non-blocking" and shown "before firing the nav intent", which
contradicts itself. Any prompt shown before the intent blocks the handoff.

| | Prompt before launch (TODO) | Launch, then ask on return (NH) | Auto-log to "Recent drives", no prompt (alternative) |
|---|---|---|---|
| Friction between Start and driving | One extra decision every time | None | None |
| Capture rate | High (user is present) | Lower. Many users never return to this app after navigating, and the prompt expires after 6 h | 100 % logged; the user chooses what to keep later |
| Context when deciding | Best (route visible) | Weaker ("Home → Café" after the fact) | Weakest, but nothing is lost |
| Safety | User is still parked | The prompt can appear when the user switches back **mid-drive**; it should not demand interaction then | No interaction |
| Robustness | Simple | Needs `SavedStateHandle`, the ON_STOP/ON_RESUME arming (SF-24), and the 6 h expiry. Lost if the task is swiped from recents or the phone reboots | One Room insert at launch time; survives everything |
| Fits "Don't ask again" | Yes | Yes | Not needed (the setting becomes "Log drives") |
| Data volume / privacy | Only explicit saves | Only explicit saves | Stores every route until pruned. Needs a cap (e.g. 20, like recents) and a "Clear" option |

Further notes on NH §6:
- The 30 s auto-dismiss can expire before a user who just came back has read the prompt. Prefer "dismiss on
  next map interaction".
- `PendingDrive : Parcelable` adds `kotlin-parcelize`. With the auto-log alternative it is not needed.
- NH does not say how `NavHandoffController` (activity-scoped) reaches `DriveCaptureViewModel.offer()`. Route
  it through `MapStateRepository`/`MapCommands` (SF-11).

## 5. Gradle, KSP and AGP 9: what must be verified before adding Room or Hilt

Observed in the build files and via `./gradlew --offline buildEnvironment`:
- AGP `9.0.1` with built-in Kotlin (the app module applies no Kotlin plugin). It resolves **KGP `2.2.10`**.
  Gradle `9.4.1`.
- The buildscript declares KSP `2.1.20-1.0.32` (KSP1). Gradle **resolves it to `2.2.10-2.0.2`** because the
  classpath also pulls in KSP `2.2.10-2.0.2` artifacts (`symbol-processing-api`/`common-deps`; the dependency
  tree places them under the hilt-android-gradle-plugin entry). The effective KSP version is therefore the
  result of conflict resolution, not a choice. A KSP2 `2.3.9` artifact is already in the local Gradle cache.
- Hilt Gradle plugin `2.56.2` is on the classpath but not applied. No version catalog, no `plugins {}` DSL.

Verify each of these, in this order, on a throwaway branch:
1. **KSP and built-in Kotlin:** pin KSP explicitly to a release whose notes state AGP 9 built-in Kotlin
   support (the 2.3.x line, which is no longer tied to the Kotlin version). Check that `ksp` tasks run and
   that sources appear in `app/build/generated/ksp/debug`. Watch for "kotlin.sourceSets DSL not allowed with
   built-in Kotlin". If it appears, the KSP version is too old. Do not paper over it with
   `android.disallowKotlinSourceSets=false` without recording why.
2. **Hilt:** 2.56.2 predates AGP 9 and uses the legacy variant and extension APIs that AGP 9 hides by default
   (`android.newDsl=true`). Check the Dagger release notes for the first version that supports AGP 9 (believed
   to be 2.59+) and use the same version for `hilt-android` and `hilt-compiler` (via `ksp`). Do **not** set
   `android.newDsl=false` as a workaround without an owner decision; it is deprecated.
3. **Compose compiler:** `org.jetbrains.kotlin.plugin.compose` must equal the KGP version actually resolved
   (2.2.10 today). If KGP is later bumped by adding it to the classpath, bump both together. Check with
   `buildEnvironment | grep kotlin-gradle-plugin`.
4. **Room:** use Room 2.7+ (KSP2-compatible). For schema export, prefer `ksp { arg("room.schemaLocation", …)
   }` over the `androidx.room` Gradle plugin until that plugin is confirmed on AGP 9. Commit `app/schemas/`.
5. **Parcelize** (only if NH keeps `Parcelable`) at the KGP version. **Tests:** Robolectric ≥ 4.16 (SDK 36),
   `includeAndroidResources = true`, JDK 21 (`JAVA_HOME=$HOME/.jdks/ms-21.0.11`).
6. **Done when:** a minimal `@HiltAndroidApp` class, one `@Entity`/`@Dao`/`@Database`, one `@HiltViewModel`
   and one `@Composable` pass `./gradlew assembleDebug testDebugUnitTest lint`, and `buildEnvironment` shows
   exactly one KSP and one KGP version.
7. Migrate to `plugins {}` with explicit versions (ideally `gradle/libs.versions.toml`) in the same PR, so
   that later resolution surprises are visible. Keep AGP at 9.0.x (TODO notes that 9.1.0 is incompatible).

## 6. Decisions the owner must make

1. **D1, drive capture:** accept NH's "launch first, ask on return" (with the SF-24 fix), go back to TODO's
   "ask first", or switch to the auto-log "Recent drives" alternative (§4).
2. **D2, card form factor on phones:** accept a near-full-width card covering about 60 % of the map, a compact
   or bottom-anchored card on narrow screens, or option B (bottom sheet). (SF-9)
3. **D3, schema:** who folds all amendments into TODO 3.1, and which Important Locations model (separate
   `ImportantLocation` table, or a `kind` column with nullable coordinates)? (BL-3)
4. **D4, row tap:** a full `loadUrl` reload is acceptable, or a row tap only collapses or confirms when a
   route or search is active. (SF-10)
5. **D5, bubble in Directions mode and over GMaps full-screen UI:** hide it or keep it. (SF-21)
6. **D6, badge semantics:** do duplicates across lists count twice, and does the badge show in tilted and
   satellite modes? (FPB §5.5, SF-22)
7. **D7, spike order:** schedule the device spikes B0, N0, M0 and S0 (about 6 h of human time) before any
   dependent implementation.
8. **D8, configuration changes:** add `fontScale|density|locale|layoutDirection|keyboard|
   keyboardHidden|navigation` to `configChanges`, or keep recreation and stop `resetWebView(true)` from wiping
   cookies on configuration changes. (SF-2)
9. **D9, Hilt on AGP 9:** if no AGP-9-compatible Hilt exists or works, choose between `android.newDsl=false`
   (deprecated) and manual DI for now. (§5)

## 7. Resolution (applied 2026-10-21)

Specs edited: all four, plus two new ones: `schema.md` (the single Room schema) and
`compose-host.md` (Phase 1.3 and everything the specs share). `TODO.md` now points at the
specs, lists the device spikes as gates and defines the `[build]` / `[needs human review]` tags.

**Defaults chosen, owner may veto:**

| # | Default | Where |
|---|---|---|
| D2 | Card on phones: ≤ 50 % of the overlay height and ≤ 360 dp wide (also ≤ 85 % of width below 600 dp) | FPB §3.3, D21 |
| D4 | Row tap prefers in-page (SPA) navigation via `MapController.navigateInPage`, falls back to `loadUrl` | FPB §5.6, compose-host §4.1, B0 (f) |
| D5 | Bubble hidden in Directions mode (top bar shows back/close instead), Street View and non-map pages | FPB §2, compose-host §7.3 |
| D6 | Badge counts distinct places (same normalised name + coordinates across lists = 1); in Tilted/Satellite modes it is an approximation (criterion 4 relaxed) | FPB §5.5, §10 |
| D7 | Device spikes B0, N0, M0, S0 run before any dependent work | TODO "Device spikes", each spec |
| D9 | Hilt (and KSP/Compose compiler/Room) versions are decided by the Gradle agent, not the specs | TODO 0.2, FPB §8 |
| D3 | Important Locations: separate `ImportantLocation` table (HOME id 1, WORK id 2, nullable coords), not a `PlaceList` type | schema §1.3, TODO 4 |
| — | Bubble hidden while the "Save this drive?" prompt is visible (instead of raising its `maxY`) | FPB §6, compose-host §7.3 |
| — | Drive prompt has no 30 s timer; it is dismissed by Not now, swipe, Back or the next map gesture | NH §6.3 |
| — | Back priority: search editing > drive prompt > bubble card > secondary screen > WebView history, via one `MapBackHandler` | compose-host §7.4 |

Decided by the owner: **D1** (launch immediately, ask on return, 4 h expiry, armed only after
`ON_STOP`). Applied as instructed: **D8** (extend `configChanges`; also keep the cookie wipe
out of recreation paths).

| Item | Status | Resolution |
|---|---|---|
| BL-1 | fixed / deferred-to-device-spike | B0 added as a mandatory gate (FPB §0.1, TODO); M0 latency merged into it. Running it needs a device. |
| BL-2 | fixed | Bubble and card are two `AnimatedVisibility` siblings with the offset on the outer node (FPB §3.2); T-C13 |
| BL-3 | fixed | `schema.md`: one v1 schema with every amendment, `ImportantLocation` table, enums by name; TODO 3.1/4/8.3 and all specs defer to it |
| BL-4 | deferred (Gradle agent) | Out of scope here; if no AGP-9-compatible Hilt works, D9 goes to the owner |
| BL-5 | fixed / deferred-to-device-spike | `compose-host.md` (1.3a/b/c); its device checks D-H1–D-H8 need a device |
| SF-1 | fixed | `doUpdateVisitedHistory` primary, 1 s poll fallback (`UrlPollMs`); TODO 3.2/8.4 corrected |
| SF-2 | fixed | Facts row corrected; `configChanges` extended; wipe guarded (`isChangingConfigurations`, `WebSessionViewModel`); S14 |
| SF-3 | fixed | `pointerInput(Unit)`, bounds via state, `try/finally` settle (FPB §4.5); D25 |
| SF-4 | fixed | `computeTarget` → persist → `animateTo`; T-C14 |
| SF-5 | fixed | `anchor: StateFlow<BubbleAnchor?>`, render nothing until loaded; echo ignored |
| SF-6 | fixed | `padding(inner).consumeWindowInsets(inner)` (FPB §3.2, compose-host §7.1) |
| SF-7 | fixed / deferred-to-device-spike | Edge-to-edge + `adjustResize` in 1.3a; API ≤ 29 behaviour is D12b |
| SF-8 | fixed | `Tap` emitted after `doubleTapTimeout`; GMaps-control taps documented as intended; D9 |
| SF-9 | fixed (default D2) | Phone card limits + D21 |
| SF-10 | fixed (default D4) | `navigateInPage` with `loadUrl` fallback; route case moot (bubble hidden in Directions); D22 |
| SF-11 | fixed | `MapController` + `MapCommands` (compose-host §4); NH `WebLoader` removed |
| SF-12 | fixed | Single observer → `MapStateRepository.touches` (compose-host §5.2) |
| SF-13 | fixed | One `MapUrlParser.parse → MapUrlState`; `MapModeParser` removed |
| SF-14 | fixed | `@RetainedScope` scope (compose-host §8); `NearbyPlacesSource` from B4; `importantInView` for markers |
| SF-15 | fixed | `ORDER BY` with wrapped `dLon`; "500+ in view" footer; timing moved to D23 |
| SF-16 | fixed | `LatLon`/`NavTarget` defined once (compose-host §6); domain not `Parcelable`; pending drive is a DataStore JSON DTO |
| SF-17 | fixed | No interim `geo:` launcher; B8b depends on N3 + N4 |
| SF-18 | fixed | Search clears focus on IME hide; bubble gate includes `!searchEditing`; one prioritised `MapBackHandler` |
| SF-19 | fixed | `onPageStarted → null`; markers enter `Moving` on any URL change and `Tap`; limitation stated |
| SF-20 | fixed | FPB §5.8 Place card, transitions S9–S9d, T-C15 |
| SF-21 | fixed / deferred-to-device-spike | Rows added; side menu/photo viewer detection depends on B0 (g) / D26 |
| SF-22 | fixed (default D6) | Criterion 4 relaxed for Tilted/Satellite |
| SF-23 | fixed / needs-owner | B2→B2a/B2b, B8→B8a/B8b, B6 previews split to B6p [needs human review], B3 timing → D23, NH N1–N3 Robolectric, prerequisites listed; `[build]` tag defined in TODO — adding it to PROJECT_GOALS' definition of done is left to the owner (file not edited) |
| SF-24 | fixed | Prompt armed only after `ON_STOP` (`!isChangingConfigurations`); D-N10 |
| N-1 … N-10 | fixed | N-1/N-2 FPB §4.5; N-3 FPB §7 (D14 verifies traversal); N-4 `minY` depends on `gmapsSearchHidden`; N-5 schema indices; N-6 viewport frozen while IME visible; N-7 facts row; N-8 `Locale.GERMANY` tests in B1/1.3c; N-9 TODO pointers; N-10 TODO 6.2 |
