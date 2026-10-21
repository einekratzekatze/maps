# Design: Compose host for the WebView (TODO Phase 1.3)

Status: ready for implementation once Phase 0.2/0.3 build is green · Owner: TBD ·
Prerequisite of: `floating-places-bubble.md`, `map-markers-js.md`, `custom-search-field.md`,
`nav-handoff.md` (wiring parts) · Data model: `schema.md`

This spec owns everything the four feature specs share: the window setup, the single WebView
owner, the one way ViewModels drive the WebView (`MapController` / `MapCommands`), the one
way touches and URLs reach ViewModels (`MapStateRepository`), the one URL parser, the shared
geo/nav types, the overlay z-order and the back-press priority. Feature specs reference these
by name and must not define their own versions.

---

## 0. Facts about the current code (verified 2026-10-21)

| Fact | Where | Consequence |
|---|---|---|
| `onCreate` calls `window.clearFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)` and `requestWindowFeature(FEATURE_NO_TITLE)`, then `setContentView(R.layout.activity_main)` (root `LinearLayout`, `fitsSystemWindows="true"`) | `MainActivity.kt:135–138` | Incompatible with Compose insets: `WindowInsets.*` only report correctly when the decor does not fit system windows. All three go. |
| App theme is `@android:style/Theme.DeviceDefault` (has an action bar; `FEATURE_NO_TITLE` hides it) | `AndroidManifest.xml:13` | Switch to `@android:style/Theme.DeviceDefault.NoActionBar` so `FEATURE_NO_TITLE` is unnecessary. Our UI is themed by the Compose M3 theme; the window theme only matters before the first frame. |
| `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"` | `AndroidManifest.xml:16` | Font size, display size, language, hardware keyboard etc. **recreate** the Activity (§2.2). |
| `onDestroy` → `resetWebView(true)`: clears cache, history, **all cookies**, web storage, destroys the WebView | `MainActivity.kt:310–339` | On a recreation this logs the user out of the consent cookie and reloads the start URL. Must be kept out of recreation paths (§2.3). |
| `onCreate` → `resetWebView(false)` (same wipe, without destroy) | `MainActivity.kt:154` | Same: must not run on a configuration-change recreation (§2.3). Upstream behaviour (wipe per launch) is kept for real launches and after process death. |
| One `OnBackPressedCallback` reads `mapsWebView` directly and does history-back or `finish()` | `MainActivity.kt:162` | Stays as the lowest-priority callback, reading the WebView through the host (§7). |
| `shouldOverrideUrlLoading` handles `intent://maps.app.goo.gl/?link=` inline | `MainActivity.kt:236–238` | Becomes a call into `NavHandoffController` (nav-handoff.md §5). |
| No `doUpdateVisitedHistory`, no `onPageStarted`, no touch listener, no JS bridge | — | Added by the host (§4, §5). |
| `windowSoftInputMode` not set | manifest | Set `adjustResize` (§2.1). |

---

## 1. Target structure

```
io.github.einekratzekatze.maps
├── MainActivity.kt              // @AndroidEntryPoint; creates WebViewHost; setContent { AppRoot() }
├── map/
│   ├── WebViewHost.kt           // single owner of the WebView (§3)            [device]
│   ├── MapController.kt         // interface + WebViewHost implements it (§4)  [unit: fakes]
│   ├── MapCommands.kt           // @ActivityRetainedScoped command channel (§4)
│   ├── MapStateRepository.kt    // @ActivityRetainedScoped state hub (§5)
│   ├── WebViewTouchObserver.kt  // the only OnTouchListener (§5.2)             [unit: MotionEvent via Robolectric]
│   ├── MapUrlTracker.kt         // URL sources → reportUrl (bubble B2a)
│   ├── PageScript.kt            // document-start scripts + message bridge (§4.3)
│   └── url/
│       ├── MapUrlParser.kt      // the ONE page-URL parser (§6)                [unit]
│       ├── MapUrls.kt           // the ONE URL builder (Locale.ROOT)           [unit]
│       └── DynamicLinkClassifier.kt // intent://maps.app.goo.gl (nav-handoff §4.1) [unit, Robolectric]
├── domain/GeoModels.kt          // LatLon, MapCamera, CameraMode, GeoBounds, MapMode, MapUrlState (§6)
├── navigation/NavTarget.kt      // NavTarget (§6.2)
├── di/                          // DispatchersModule, RetainedScopeModule (§8)
└── ui/
    ├── AppRoot.kt               // theme + (later) NavHost: map destination, settings, lists
    └── MapScreen.kt             // Scaffold + Box z-order (§7) + MapBackHandler + command collector
```

---

## 2. Window, manifest and Activity lifecycle

### 2.1 Window setup (1.3a)

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()                 // androidx.activity; replaces clearFlags + fitsSystemWindows
    super.onCreate(savedInstanceState)
    host = WebViewHost(this, …)        // §3
    setContent { AppTheme { AppRoot(host) } }
}
```

- Delete `window.clearFlags(...)`, `requestWindowFeature(FEATURE_NO_TITLE)`,
  `setContentView`, `findViewById(R.id.mapsWebView)` and `res/layout/activity_main.xml`.
- Manifest: `android:theme="@android:style/Theme.DeviceDefault.NoActionBar"` on the
  activity (or application), `android:windowSoftInputMode="adjustResize"`.
- `WindowInsets.isImeVisible` (used by bubble and search) is `@ExperimentalLayoutApi`; it is
  reliable only with edge-to-edge + `adjustResize`, i.e. after this step. On API 26–29 it is
  a heuristic based on the bottom inset: accept that and test it (bubble D12b).

### 2.2 `configChanges` (decision D8: extend, so the Activity is not recreated)

```xml
android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|fontScale|density|locale|layoutDirection|keyboard|keyboardHidden|navigation|touchscreen|colorMode|mcc|mnc"
```

Consequences, each covered by a device check (§10):
- Compose re-reads `LocalConfiguration`/`LocalDensity`, so font scale, display size, RTL
  switch and language changes recompose our UI without recreation. `stringResource`
  re-resolves on `LocalConfiguration` change.
- The WebView keeps its page. **GMaps' own UI language does not change until the next page
  load**; that is accepted (document in the PR). Our strings switch immediately.
- `onConfigurationChanged` keeps the existing algorithmic-darkening update.
- Per-app language (`LocaleManager`, API 33) is delivered as `locale|layoutDirection` and
  is covered.

### 2.3 Wiping stays out of recreation paths

Some changes can still recreate the Activity (an unlisted config flag, `recreate()` from a
future settings screen, multi-window edge cases). For those:

- `onDestroy`: `if (!isChangingConfigurations) host.destroy(wipe = true)`; when changing
  configurations, only detach the WebView (`host.destroy(wipe = false)` destroys the view
  without touching cookies/storage).
- `onCreate`: run upstream's per-launch wipe (`resetWebView(false)`) **only** when this is
  not a configuration-change recreation. Signal: a tiny `@HiltViewModel class
  WebSessionViewModel { var started = false }` obtained in `onCreate`. ViewModels survive
  configuration changes but not process death, so:
  - first launch / after process death: `started == false` → wipe, set `started = true`
    (upstream behaviour preserved);
  - configuration-change recreation: `started == true` → no wipe; the new WebView loads
    the last URL from `MapStateRepository.url` (survives in the retained scope) instead of
    the start URL.
- Unit test (Robolectric, `ActivityScenario.recreate()`): cookie set before recreate is
  still present after; with `scenario.close()` + relaunch it is gone.

### 2.4 Process death

Nothing is restored except DataStore- and Room-backed state. The WebView restarts at the
start URL (or the intent URL). Feature ViewModels must treat `MapStateRepository.url ==
null` as "position unknown".

---

## 3. `WebViewHost`: the single owner

```kotlin
/** Activity-scoped (created in onCreate, destroyed in onDestroy). Not injected into ViewModels. */
class WebViewHost(
    activity: ComponentActivity,
    private val mapState: MapStateRepository,
    private val commands: MapCommands,
    private val navHandoff: NavHandoffController,
    private val pageScripts: Set<@JvmSuppressWildcards PageScript>,
) : MapController {
    val webView: WebView                         // created here with the Activity context
    fun destroy(wipe: Boolean)
}
```

Owns, and is the only code that touches:
- the `WebView` instance, its settings and both clients (the existing allowlist,
  `shouldInterceptRequest`, `shouldOverrideUrlLoading`, geolocation prompt are moved here
  unchanged; `MainActivity` keeps only lifecycle glue);
- `MapUrlTracker` (URL sources, bubble spec §5.1) and `WebViewTouchObserver` (§5.2);
- `addDocumentStartJavaScript` and `addWebMessageListener` (§4.3);
- `addOnLayoutChangeListener` → `mapState.reportViewportSize(...)`.

Compose side:

```kotlin
@Composable
fun MapWebView(host: WebViewHost, modifier: Modifier) {
    AndroidView(
        factory = { host.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = modifier,
        onRelease = { /* nothing: never destroy here; the host owns the lifetime */ },
    )
}
```

The WebView is **never** created or `remember`ed inside composition.

---

## 4. Driving the WebView: `MapController` and `MapCommands`

### 4.1 Interface

```kotlin
interface MapController {
    /** Full page load. Cold-starts the GMaps SPA (seconds on mid-range phones), adds a history entry. */
    fun loadUrl(url: String)

    /**
     * Prefer GMaps' in-page (SPA) navigation; fall back to [loadUrl].
     * Implementation: evaluateJs that creates an <a href=url> inside document.body and click()s it,
     * so GMaps' own router handles it without a reload. If the URL has not changed to the
     * target's path within 1.5 s (observed via MapStateRepository.url), call loadUrl(url).
     * Whether the router accepts synthetic clicks is verified in spike B0 (bubble spec);
     * if it does not, this method is just loadUrl and says so in a code comment.
     */
    fun navigateInPage(url: String)

    fun evaluateJs(script: String, callback: ((String?) -> Unit)? = null)

    /** Android → page message (Renderer J, ui.js). Uses the reply proxy of the GmwBridge listener. */
    fun postMessage(json: String)

    fun canGoBack(): Boolean
    fun goBack()
}
```

`WebViewHost` implements it. Tests use `FakeMapController` (records calls).

### 4.2 ViewModels never hold the controller

ViewModels must not reference the WebView or the Activity-scoped host. They emit commands:

```kotlin
sealed interface MapCommand {
    data class LoadUrl(val url: String) : MapCommand
    data class NavigateInPage(val url: String) : MapCommand
    data class EvaluateJs(val script: String) : MapCommand
    data class PostMessage(val json: String) : MapCommand
    data class Navigate(val target: NavTarget) : MapCommand   // → NavLauncher (nav-handoff §5)
    data object OpenGmapsMenu : MapCommand                    // custom search §5
}

@ActivityRetainedScoped
class MapCommands @Inject constructor() {
    private val channel = Channel<MapCommand>(Channel.BUFFERED)
    fun send(cmd: MapCommand)            // trySend; logs WARN if the buffer is full
    val commands: Flow<MapCommand> = channel.receiveAsFlow()
}
```

`MapScreen` collects `commands` exactly once, with `repeatOnLifecycle(STARTED)` inside a
`LaunchedEffect(Unit)`, and dispatches to `host` (`MapController`) or `NavLauncher`.
Commands sent while stopped wait in the buffer. This replaces `BubbleEffect.LoadUrl`,
`SearchEffect.LoadUrl/OpenGmapsMenu`, the marker cluster `LoadUrl` and nav-handoff's
`WebLoader`. UI-only one-shot effects that do not touch the WebView (e.g. "open the Home
editor") stay in each ViewModel.

### 4.3 Page scripts and the message bridge

```kotlin
interface PageScript {             // provided with Hilt @IntoSet by features (ui.js, markers.js)
    val id: String
    val allowedOrigins: Set<String>   // e.g. setOf("https://www.google.com", "https://maps.google.com")
    fun source(context: Context): String
}
```

At WebView creation the host installs every `PageScript` with
`WebViewCompat.addDocumentStartJavaScript` (if `WebViewFeature.DOCUMENT_START_SCRIPT`), else
re-evaluates them in `onPageStarted`. It registers **one** `addWebMessageListener` named
`GmwBridge` (feature `WEB_MESSAGE_LISTENER`) and forwards each message as
`PageMessage(type: String, json: String)` into `MapStateRepository.pageMessages`. No
`addJavascriptInterface` anywhere (supersedes TODO 6.2).

---

## 5. Getting state out of the WebView: `MapStateRepository`

```kotlin
@ActivityRetainedScoped
class MapStateRepository @Inject constructor(@RetainedScope scope: CoroutineScope) {
    // URL
    val url: StateFlow<String?>
    val urlState: StateFlow<MapUrlState>            // url.map(MapUrlParser::parse), stateIn(scope)
    fun reportUrl(url: String?)                     // from MapUrlTracker; null on onPageStarted

    // Viewport
    val viewportCssPx: StateFlow<IntSize?>
    fun reportViewportSize(widthPx: Int, heightPx: Int, density: Float)  // ignored while imeVisible (N-6)
    val bounds: Flow<GeoBounds?>                    // combine(urlState.camera, viewportCssPx) → WebMercator

    // Touch and page events
    val touches: SharedFlow<MapTouch>               // from WebViewTouchObserver, extraBufferCapacity = 16
    fun reportTouch(t: MapTouch)
    val pageMessages: SharedFlow<PageMessage>
    fun reportPageMessage(m: PageMessage)

    // Overlay coordination (§7)
    val overlay: StateFlow<OverlayState>
    fun setImeVisible(v: Boolean)
    fun setSearchEditing(v: Boolean)
    fun setDrivePromptVisible(v: Boolean)
    fun setGmapsSearchHidden(v: Boolean)            // custom-search injector succeeded (bubble minY)
}

data class OverlayState(
    val imeVisible: Boolean = false,
    val searchEditing: Boolean = false,
    val drivePromptVisible: Boolean = false,
    val gmapsSearchHidden: Boolean = false,
)
```

### 5.1 URL sources

Implemented by `MapUrlTracker` (bubble B2a): `doUpdateVisitedHistory` is primary; a 1 s poll
of `webView.url` is the fallback; `onPageStarted(url)` reports `null`; `onPageFinished(url)`
reports the URL. See bubble spec §5.1.

### 5.2 Touch routing

`WebViewTouchObserver` is installed by the host and is the **only** `OnTouchListener` on the
WebView (a `View` has one). It always returns `false` (observe only) and calls
`mapState.reportTouch`:

```kotlin
sealed interface MapTouch {
    data object Tap : MapTouch           // single tap, emitted only after doubleTapTimeout with no second down
    data object GestureStart : MapTouch  // first move past slop, or ACTION_POINTER_DOWN, or a second down (double-tap)
    data object GestureEnd : MapTouch    // ACTION_UP / ACTION_CANCEL of a gesture
}
```

- Tap = `ACTION_UP` with movement < `scaledTouchSlop`, duration < `longPressTimeout`, one
  pointer only; held back for `ViewConfiguration.getDoubleTapTimeout()` and cancelled if a
  second `ACTION_DOWN` arrives in that window (so double-tap zoom does not close our card).
- Consumers (bubble, markers, search, drive prompt) read `mapState.touches` in their
  ViewModels. Nobody else calls `setOnTouchListener`.
- Known, intended: a tap on GMaps' own controls (bottom sheet, layers button, side menu) is
  also a `Tap`. We cannot tell them apart from the map without DOM hooks.

---

## 6. The one URL parser and the shared types

### 6.1 `domain/GeoModels.kt` (pure Kotlin, no Android, no Parcelable)

```kotlin
data class LatLon(val lat: Double, val lon: Double)
enum class CameraMode { Map, Satellite3dMeters, StreetView, Tilted }
data class MapCamera(val center: LatLon, val zoom: Double, val mode: CameraMode)
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val crossesAntimeridian: Boolean get() = west > east
    fun contains(p: LatLon): Boolean
}
enum class MapMode { Map, Search, Place, Directions, StreetView, NonMap }
data class MapUrlState(
    val camera: MapCamera?,      // null = viewport unknown
    val mode: MapMode,
    val displayText: String?,    // decoded search query (Search) or place name (Place), else null
) { companion object { val Unknown = MapUrlState(null, MapMode.NonMap, null) } }
```

`MapCamera.lat`/`lon` in older drafts are `center.lat`/`center.lon`.

### 6.2 `navigation/NavTarget.kt`

```kotlin
data class NavTarget(val latLon: LatLon?, val label: String?) {
    init { require(latLon != null || label != null) }
}
```

Used by the bubble (`NavTarget(LatLon(lat, lon), name)`), nav-handoff and drive replay.
Anything that must be persisted (pending drive) is mapped to a DataStore/JSON DTO in the
data layer; domain types are never `Parcelable`.

### 6.3 `MapUrlParser`

```kotlin
object MapUrlParser {
    fun parse(url: String?): MapUrlState
}
```

One parser, one set of fixtures. Bubble B1 implements `camera`; custom-search S1 adds
`mode`/`displayText` **to this object** (no separate `MapModeParser`). Rules:
- Host not `www.google.com`/`maps.google.com`, or path not under `/maps`, or path under
  `/maps/d/` (My Maps), or `consent.google.com`, or `about:blank` → `MapMode.NonMap`,
  camera null.
- `@…,3a,` camera → `MapMode.StreetView` and `CameraMode.StreetView` (the two are derived
  from the same match, so they cannot disagree).
- `/maps/dir/` → `Directions`; `/maps/search/` → `Search`; `/maps/place/` → `Place`; else `Map`.
- Camera regex and suffix table: bubble spec §5.2.

`MapUrls` (builder, `Locale.ROOT`, 7 decimals, `Uri.encode` for text) is the only place URLs
are assembled: `place(latLon, zoom)`, `search(query, camera?)`, `camera(latLon, zoom)`,
`prediction(primary, placeId)`.

`DynamicLinkClassifier` (nav-handoff §4.1) handles `intent://` URLs and lives in the same
package so all URL knowledge is in one place.

---

## 7. `MapScreen`: insets, z-order, visibility gates, back priority

### 7.1 Layout and insets (applied once)

```kotlin
Scaffold(
    topBar = { MapTopBar(...) },                       // TopAppBar handles the status-bar inset itself
    contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
) { inner ->
    Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
        MapWebView(host, Modifier.fillMaxSize().then(if (imePadWebView) Modifier.imePadding() else Modifier))
        MarkerOverlay(...)                             // map-markers-js.md
        DriveSavePrompt(..., Modifier.align(BottomCenter))  // nav-handoff.md §6
        PlacesBubbleOverlay(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        SuggestionPanel(..., Modifier.align(TopCenter).imePadding())  // custom-search-field.md
        MapBackHandler(...)
    }
}
```

- `consumeWindowInsets(inner)` is required: without it every descendant's `safeDrawing`
  re-applies the navigation-bar and cutout insets (24–48 dp too high).
- After consumption, `safeDrawing` on the overlays only adds what is left (the IME, which
  hides the bubble anyway).
- `imePadWebView = !overlay.searchEditing` (custom-search §6).
- `LaunchedEffect(WindowInsets.isImeVisible) { mapState.setImeVisible(it) }`.

### 7.2 Z-order (bottom → top)

WebView < `MarkerOverlay` < `DriveSavePrompt` < `PlacesBubbleOverlay` < `SuggestionPanel`.
None of the overlay roots has a background, `clickable` or `pointerInput`; only their
children consume touches (bubble T-C9 pattern).

### 7.3 Visibility gates (single definition, consumed by the feature ViewModels)

| Overlay | Visible when |
|---|---|
| Bubble | `bubble_enabled && !imeVisible && !searchEditing && !drivePromptVisible && urlState.mode in {Map, Search, Place}` (hidden in Directions, StreetView, NonMap) |
| Markers | `markers_enabled && camera != null && camera.mode in {Map, Satellite3dMeters*}` (*per calibration flag) and `urlState.mode != NonMap` |
| Search field | `urlState.mode in {Map, Search, Place}`; Directions shows a back/close title instead; StreetView/NonMap hide the top bar content |
| Drive prompt | nav-handoff §6 |

Secondary screens (settings, lists; Phases 3.3/7) are separate destinations in `AppRoot`'s
navigation. `MapScreen` and therefore every overlay is not composed while they are shown, so
the bubble can never float over them.

### 7.4 Back priority (highest first)

1. Search field editing → clear focus (custom-search §7).
2. Drive-save prompt visible → dismiss as "Not now" (nav-handoff §6).
3. Bubble card expanded → collapse (bubble S7).
4. Secondary screen on top → pop (navigation, when it exists).
5. WebView `canGoBack()` → `goBack()`; else `finish()` (the existing Activity callback).

Implemented as **one** `MapBackHandler` composable (`BackHandler(enabled = any of 1–3)`) that
calls the highest-priority action, instead of one `BackHandler` per overlay whose priority
would depend on composition order. The Activity's history-back callback is registered in
`onCreate` before `setContent`, so any enabled Compose handler wins over it.

The IME case: the first system Back while the keyboard is up only hides the keyboard and is
not dispatched. Custom search therefore clears focus when the IME hides (custom-search §7),
and the bubble gate above includes `!searchEditing`, so at most one of 1–3 can be enabled
in practice; the explicit order covers the rest.

---

## 8. DI plumbing (1.3b)

- `@Qualifier DefaultDispatcher`, `IoDispatcher`, `MainDispatcher` + `DispatchersModule`
  (`@InstallIn(SingletonComponent)`).
- `@Qualifier RetainedScope`: `@Provides @ActivityRetainedScoped fun scope(lifecycle:
  ActivityRetainedLifecycle): CoroutineScope = CoroutineScope(SupervisorJob() +
  Dispatchers.Main.immediate).also { s -> lifecycle.addOnClearedListener { s.cancel() } }`
  in `@InstallIn(ActivityRetainedComponent)`. Used by `MapStateRepository` and
  `NearbyPlacesSource` (bubble §5.1) for `stateIn`/`shareIn`.
- The Hilt version is chosen by the Gradle work (Phase 0.2/0.3), not here.

---

## 9. Tasks

| ID | Task | Tag | Done when |
|---|---|---|---|
| 1.3a | Window/manifest (§2.1, §2.2), theme, delete `activity_main.xml`, `WebViewHost` with the existing clients moved in, `MapWebView`, `MapScreen` Scaffold + Box + insets (§7.1), `WebSessionViewModel` wipe guard (§2.3), existing back callback via host | [needs device] + [unit-testable] (Robolectric recreate/cookie test) | Builds; D-H1–D-H7 pass |
| 1.3b | `MapController`, `MapCommands` + collector, `MapStateRepository` (URL/viewport/touch/overlay fields), `WebViewTouchObserver`, `PageScript` installer + `GmwBridge`, DI modules (§8), `MapBackHandler` skeleton | [unit-testable] (fakes; Robolectric `MotionEvent` tests for the observer: tap, double-tap, drag, two-finger) + [needs device] smoke | Tests pass; D-H8 passes |
| 1.3c | `GeoModels`, `NavTarget`, `MapUrls` (shared types) — may merge with bubble B1 | [unit-testable] | Tests incl. `Locale.GERMANY` default locale |

1.3a → 1.3b. 1.3c has no dependency. Phase 1.1 (top bar) can land with 1.3a.

## 10. Device checklist

| ID | Check |
|---|---|
| D-H1 | Map loads; status bar and navigation bar do not cover GMaps' search pill or bottom sheet (gesture nav **and** 3-button nav) |
| D-H2 | Rotate: no reload, cookies kept (consent banner does not reappear) |
| D-H3 | Change system font size and display size with the app open: no reload, our UI rescales |
| D-H4 | Change system language (or per-app language on 13+): no reload; our strings switch; GMaps strings switch on the next page load |
| D-H5 | Toggle dark mode: no reload; algorithmic darkening follows |
| D-H6 | Back: history-back as before; at the start page the app closes |
| D-H7 | Swipe the app away and reopen: cookies wiped, start URL (upstream behaviour unchanged) |
| D-H8 | With a debug log on `touches`: single tap → one `Tap` after ~300 ms; double-tap zoom → no `Tap`; pan → `GestureStart`/`GestureEnd` |
