# Design: Navigation Handoff (TODO 2.2) and Drive Capture (TODO 3.4)

Status: ready for implementation **after device spike N0** · Depends on: Phase 0.2/0.3;
Phase 1.3 (`compose-host.md`: `MapCommands`, `MapStateRepository`, `NavTarget`/`LatLon`,
`DynamicLinkClassifier` location, back priority); DataStore (settings, pending drive);
`schema.md` `Drive` entity (DB3) for capture only

---

## 0. Facts about the current code (verified)

- `shouldOverrideUrlLoading` (`MainActivity.kt:236–238`) already extracts `link` with
  `url.toUri().getQueryParameter("link")` and loads it in the WebView for **every**
  `intent://maps.app.goo.gl/?link=` URL. **TODO 2.2's first bullet ("replace string split
  with `getQueryParameter`") is already done.** The remaining work is the Start/Directions
  split.
- No `<queries>` element in `AndroidManifest.xml`. On Android 11+ (we target 36) package
  visibility hides other apps, so "list installed nav apps" (Phase 7) and
  `resolveActivity` return nothing without it.
- `ActivityNotFoundException` is not caught anywhere in the current `startActivity` calls.
- The manifest makes this app a `geo:` handler, so any `geo:` intent we fire can resolve
  to ourselves unless excluded (§5).

## 1. Two important corrections to the TODO

1. **`@lat,lon` in a `/maps/dir/` URL is the camera centre, not the destination.**
   TODO 2.2 said "Coordinates: `@lat,lon` from path". Using it would navigate the user to
   wherever the map happened to be centred. Destination coordinates live in the `data=`
   blob (`!1d<lon>!2d<lat>` pairs — note lon first) or must be resolved from the
   destination text. See §4.
2. **`geo:<lat>,<lon>?q=<label>` makes many apps *search* `<label>` near the point**,
   not go to the point. Use `geo:<lat>,<lon>?q=<lat>,<lon>(<label>)` (the same form the
   existing clipboard converter uses, plus a label).

## 2. User-facing behaviour

- Tapping **Directions** in GMaps: unchanged — the directions page loads in the WebView.
- Tapping **Start** in GMaps directions:
  1. The configured navigation app opens **immediately** with the destination
     (chooser if none configured, or if the configured app is gone).
  2. If "Offer to save drive on Start" is on and the route was parsed, when the user
     returns to our app (within 4 hours) a non-modal card at the bottom says "Save this
     drive? Home → Café Einstein" with [List ▾] [Save] [Not now] and a "Don't ask again"
     checkbox.
- If we cannot determine the destination, we do **not** guess: Toast "Couldn't read the
  destination — opening in Google Maps web instead" and load the `link` URL in the WebView
  (today's behaviour).

**Decided (owner, 2026-10-21; matches TODO 3.4):** fire the nav intent immediately, ask
"Save this drive?" on return, with a 4 h expiry. Two details from the review are part of
the decision:
- The prompt is armed only after the Activity reaches **`ON_STOP`** following the launch.
  Cancelling the chooser (a translucent activity: we only pause), the location-permission
  dialog, or any other pause/resume therefore never shows the prompt.
- The pending drive is persisted in **DataStore** (not `SavedStateHandle`), so it survives
  process death and the task being swiped from recents, and the expiry is testable with an
  injectable clock.

## 3. State machine

```
 WebView emits intent://maps.app.goo.gl/?link=…
          │ DynamicLinkClassifier.classify(outerUri)
          ├── Directions ───────────────▶ MapController.loadUrl(link)                    (done)
          ├── Unknown (log WARN once) ──▶ loadUrl(link)                                  (done)
          └── Start
                 │ RouteParser.parse(link, currentPageUrl)
                 ├── no destination ────▶ Toast + loadUrl(link)                          (done)
                 └── RouteInfo
                        │ NavLauncher.launch(target)
                        ├── NoApp ──────────▶ Toast "No navigation app installed"        (done)
                        └── Started (startActivity returned; a chooser may be showing)
                               │ offerSave && route.isSavable
                               ├── no ──────▶ (done)
                               └── yes ─────▶ PendingDrive{route, createdAt, armed=false}
                                              written to PendingDriveStore (DataStore)
                                                 │
                                                 │ Activity ON_STOP  (nav app really in front)
                                                 ▼
                                              armed = true
                                                 │ Activity ON_START/ON_RESUME
                                                 │   now - createdAt ≤ 4 h ?
                                                 ├── no ──▶ clear silently                (done)
                                                 ▼ yes
                                           SavePromptVisible ── Save ─▶ insert Drive ─▶ Snackbar "Saved to <list>"
                                                 │ Not now / swipe / Back / next map gesture
                                                 └── (+ Don't ask again ⇒ setting off) ─▶ cleared
```

- `ON_RESUME` with `armed == false` (chooser cancelled, permission dialog, notification
  shade): nothing is shown. The pending entry stays unarmed; it is replaced by the next
  Start, or cleared when it expires, or when the user presses Start again.
- If the chooser is cancelled and the user never leaves the app, the unarmed pending drive
  simply expires; it is never shown.
- After process death the restored entry keeps its `armed` flag; the new process shows the
  prompt on first resume if `armed` and not expired.

## 4. Parsing

### 4.0 Fixture capture N0 (mandatory gate, [needs device], ≤ 1 h)

Add a debug-only log in `shouldOverrideUrlLoading` printing the full intent URL and the
current `webView.url`. Capture at least: (a) current location → named place, (b) named
place → named place, (c) A → B → C (one waypoint), (d) destination = dropped pin (no name),
(e) Directions button, (f) each travel mode (drive/walk/transit/bike). Paste the raw strings
into `app/src/test/resources/nav-fixtures.txt` with the expected parse. **Redact your home
address** from fixtures before committing. Also record (g): does the chooser cancel path
produce `ON_STOP`? (expected: no) — this confirms the arming rule.

N1–N5 do not start before N0's fixtures are committed.

### 4.1 Classification

```kotlin
enum class DynamicLinkKind { Directions, Start, Unknown }

// map/url/DynamicLinkClassifier.kt (same package as MapUrlParser, compose-host.md §6.3)
object DynamicLinkClassifier {
    /** utm_campaign on the outer intent:// URL; fall back to the inner link's utm_campaign. */
    fun classify(intentUrl: String): DynamicLinkKind   // "navnp" → Start, "adir" → Directions
    fun innerLink(intentUrl: String): String?          // Uri.parse(intentUrl).getQueryParameter("link")
}
```

Test `+` vs `%20` and `%2F` inside `link` with fixtures — `Uri.getQueryParameter` decoding
is the only decoding step; don't decode twice.

**Tests need Robolectric.** `android.net.Uri` and `Uri.decode` are stubs in plain JVM
tests ("Method … not mocked", or silently null/0 if `unitTests.returnDefaultValues` is set).
Do **not** switch to `java.net.URI`: it throws `URISyntaxException` on
`intent://…#Intent;…;end` strings that contain unescaped characters.

### 4.2 Route

```kotlin
// LatLon is domain/GeoModels.kt (compose-host.md §6.1); not redefined here.
data class RoutePoint(val label: String?, val latLon: LatLon?) {   // label null + latLon null = "Your location"
    val isCurrentLocation: Boolean get() = label == null && latLon == null
}
enum class TravelMode { DRIVE, WALK, BIKE, TRANSIT, UNKNOWN }   // schema.md §1 (stored by name)
data class RouteInfo(val origin: RoutePoint, val waypoints: List<RoutePoint>, val destination: RoutePoint, val mode: TravelMode) {
    val isSavable: Boolean get() = destination.latLon != null
}

object RouteParser {
    /** Prefer [link]; if it lacks waypoints/coords, merge from [currentPageUrl] when that is a /maps/dir/ URL. */
    fun parse(link: String, currentPageUrl: String?): RouteInfo?
}
```

Rules:
- Path after `/maps/dir/` up to the first segment starting with `@` or `data=`: one segment
  per point, in order. Empty segment = current location. Decode with `Uri.decode`, then
  `+` → space.
- A segment matching `^-?\d+(\.\d+)?,\s*-?\d+(\.\d+)?$` is a coordinate point.
- Coordinates from `data=`: collect all `!1d(-?[\d.]+)!2d(-?[\d.]+)` pairs as
  `LatLon(lat = 2d, lon = 1d)`. Assign them in order to the non-current-location, non-coordinate
  segments **only if the counts match exactly**; otherwise assign none (better unknown than
  wrong). Verify this rule against N0 fixtures; adjust the rule, not the fixtures.
- Travel mode: `!3e0` drive, `!3e1` bike, `!3e2` walk, `!3e3` transit (verify in N0).
- Never read the `@…` camera.

If the destination has a label but no coordinates: hand off as
`geo:0,0?q=<encoded label>` (address search in the nav app — supported by OsmAnd and
Organic Maps; verify in D-N4) and mark the route not savable.

## 5. Launching

```kotlin
// NavTarget is navigation/NavTarget.kt (compose-host.md §6.2):
//   data class NavTarget(val latLon: LatLon?, val label: String?)   // at least one non-null

enum class NavUriStyle { Geo, GoogleNavigation }

object NavIntentFactory {
    fun buildUri(target: NavTarget, style: NavUriStyle): Uri
    // Geo:   geo:<lat>,<lon>?q=<lat>,<lon>(<label>)  |  geo:0,0?q=<label>
    // GoogleNavigation: google.navigation:q=<lat>,<lon>   (starts routing directly in apps that support it)
    fun build(target: NavTarget, style: NavUriStyle, packageName: String?, selfComponent: ComponentName): Intent
}

sealed interface LaunchResult { data object Started : LaunchResult; data object NoApp : LaunchResult }

class NavLauncher @Inject constructor(private val settings: NavSettings) {
    fun launch(activity: Activity, target: NavTarget): LaunchResult
}
```

- `Started` means `startActivity` returned without throwing. It does **not** prove a nav
  app opened (a chooser may be showing); that is why the prompt arms on `ON_STOP` (§3).
- Number formatting: `Locale.ROOT`, 7 decimals. Label: strip `(` `)` and URL-encode.
- `GoogleNavigation` style is an option only; default is `Geo`. **Do not ship the
  `osmand.navigation:` scheme from the TODO** unless D-N5 proves it works — it is not
  documented anywhere we could verify.
- Preferred app (Phase 7) = package name in DataStore. If set: `intent.setPackage(pkg)`;
  on `ActivityNotFoundException` clear the preference and retry without package via
  `Intent.createChooser`. If unset: `createChooser` (Android remembers "always" itself).
  Every `startActivity` is wrapped in `try/catch (ActivityNotFoundException)` → `NoApp`.
- Manifest addition:
  ```xml
  <queries>
      <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="geo" /></intent>
      <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="google.navigation" /></intent>
  </queries>
  ```
- Our own app also handles `geo:` (manifest intent filter). Exclude our component from the
  chooser with `Intent.EXTRA_EXCLUDE_COMPONENTS` — otherwise "navigate" can loop back into
  us. There is **no** "plain `geo:` interim launcher" anywhere: the bubble's Navigate button
  (bubble B8b) depends on N3 + N4 and goes through `NavLauncher`.

Callers: the bubble and drive replay send `MapCommand.Navigate(target)` through
`MapCommands`; `MapScreen`'s collector calls `NavLauncher.launch(activity, target)`
(compose-host.md §4.2). `NavHandoffController` calls `NavLauncher` directly (it already
runs in the Activity-scoped host).

Wiring in `shouldOverrideUrlLoading` (inside `WebViewHost`): keep it thin.

```kotlin
if (url.startsWith("intent://maps.app.goo.gl/?link=")) {
    handoffController.onDynamicLink(url, currentPageUrl = view.url)
    return true
}
```

`NavHandoffController` (Activity-scoped, plain class, constructor-injected collaborators)
does classify → parse → launch → write pending drive. It is unit-testable with fakes for
`NavLauncher`, `MapController` (compose-host.md §4.1; replaces the earlier `WebLoader`
interface), `PendingDriveStore` and `Clock`. It debounces identical intent URLs within 2 s.

## 6. Drive capture (3.4)

### 6.1 Pending drive store

```kotlin
/** DataStore-backed; one entry at most. JSON-encoded DTO in the data layer (domain types are not Parcelable). */
class PendingDriveStore @Inject constructor(dataStore: DataStore<Preferences>, private val clock: Clock) {
    val pending: Flow<PendingDrive?>
    suspend fun put(route: RouteInfo)            // createdAt = clock.millis(), armed = false
    suspend fun arm()                            // no-op if none
    suspend fun clear()
}
data class PendingDrive(val route: RouteInfo, val createdAtMillis: Long, val armed: Boolean)

object DriveCaptureDefaults { const val PROMPT_EXPIRY_MS = 4 * 60 * 60 * 1000L }   // TODO 3.4: 4 h, easy to change
```

- `Clock` is `java.time.Clock` (API 26+), injected; tests use a fixed/offset clock.
- Arming: `MainActivity` overrides `onStop()` and calls `pendingDriveStore.arm()` when
  `!isChangingConfigurations`. Nothing else arms.
- Encoding: `RouteInfoJson.encode/decode` (pure, `org.json`, unit-tested), reusing the
  waypoint shape from schema.md §1.4.

### 6.2 ViewModel

```kotlin
@HiltViewModel
class DriveCaptureViewModel @Inject constructor(
    private val pendingStore: PendingDriveStore,
    private val drives: DriveRepository,
    private val lists: ListRepository,
    private val settings: NavSettings,     // offerSaveDrive: Flow<Boolean>, setOfferSaveDrive(Boolean)
    private val mapState: MapStateRepository,
    private val clock: Clock,
) : ViewModel() {
    val prompt: StateFlow<SaveDrivePrompt?>     // null = hidden
    fun onResumed()                             // called by MapScreen on ON_RESUME (LifecycleEventEffect)
    fun onSave(listId: Long)
    fun onDismiss(dontAskAgain: Boolean)
}
data class SaveDrivePrompt(val title: String, val lists: List<ListOption>, val selectedListId: Long?)
```

- `onResumed`: read `pending`; if null or `!armed` → nothing. If `clock.millis() -
  createdAt > PROMPT_EXPIRY_MS` → `clear()`, nothing shown. Else show the prompt.
- There is no `offer(route)` entry point: the controller writes the store, the VM reads it.
  This answers "how does the Activity-scoped controller reach the VM" without a direct
  reference.
- The VM reports visibility with `mapState.setDrivePromptVisible(prompt != null)`, which
  hides the bubble while the prompt is up (compose-host.md §7.3).
- Save → `drives.insert(route, listId)`, `clear()`. Not now / dismiss → `clear()`;
  with "Don't ask again" also `settings.setOfferSaveDrive(false)`.
- Setting off: the controller never writes a pending drive.

### 6.3 Prompt UI and data

- Schema: `Drive` as defined in `schema.md` §1.4 (nullable `originLat/originLon` = "current
  location at replay time"; waypoints as JSON; `travelMode`).
- Default list = Phase 7 "Default list" or a "Drives" list created on first save
  (`ListRepository.getOrCreateDrivesList()`, found by stored id, not by name).
- Prompt UI: bottom-anchored `ElevatedCard` in the `MapScreen` `Box` (not a `Dialog`),
  z-order per compose-host.md §7.2 (above markers, below the bubble layer, which is hidden
  while the prompt shows). No `navigationBarsPadding()`: the `MapScreen` Box already
  applied and consumed the system-bar insets.
- Dismissal: [Not now], swipe, system Back (priority 2 in compose-host.md §7.4), or the
  **next map gesture** (`MapTouch.GestureStart` or `Tap` from `mapState.touches`). No
  timed auto-dismiss: a 30 s timer can expire before a returning user has read it.
- Safety: the prompt never takes focus, never opens the keyboard, never plays a sound, and
  needs no interaction (a user glancing at the app mid-drive can ignore it; it clears on
  the next map gesture or expiry).
- TalkBack: `paneTitle = "Save drive"`; `liveRegion = Polite` on first show.
- Replay a Drive: `MapCommand.Navigate(NavTarget(LatLon(destLat, destLon), destLabel))`.
  Waypoints are not handed off (`geo:` cannot express them) — show "Waypoints aren't passed
  to the navigation app" once on the Drive detail screen.

## 7. Edge cases

| Case | Handling |
|---|---|
| `utm_campaign` renamed by Google | `Unknown` → today's behaviour + WARN log; nothing breaks |
| Start pressed with "Your location" as destination (unusual) | Destination is current location → no handoff, load link |
| Dropped-pin destination | Segment is coordinates → fine |
| Transit/walk mode | Hand off anyway; `geo:` has no mode. `GoogleNavigation` style can append `&mode=w|b` — only if D-N5 confirms |
| Nav app is our own app | Excluded from chooser |
| User double-taps Start | Debounce in controller: ignore identical intent URLs within 2 s |
| **User cancels the chooser** | No `ON_STOP` → pending stays unarmed → no prompt (D-N10) |
| Location-permission dialog / notification shade / any pause-resume | Same: no `ON_STOP` → no prompt |
| User switches to another app (not the nav app) after Start | `ON_STOP` arms the prompt; it shows on return. Accepted (we cannot tell which app is in front) |
| Process killed while in nav app | Pending drive restored from DataStore with `armed = true`; prompt shows on return if < 4 h |
| Task swiped from recents / reboot | Same as above (DataStore survives) |
| Return after more than 4 h | Pending drive cleared silently |
| User returns mid-drive | Prompt is passive; next map gesture or Not now clears it |
| Activity recreated by a configuration change (rare, compose-host §2.2) | A recreation passes `ON_STOP`; arming therefore checks `!isChangingConfigurations`, so an unarmed entry stays unarmed |
| Activity `onNewIntent`/re-launch with `geo:` from another app | Unrelated path (`onCreate` URL handling); a pending armed drive may still show — accepted |

## 8. Task breakdown

| ID | Task | Tag |
|---|---|---|
| N0 | Capture fixtures (§4.0) — mandatory gate before N1/N2/N5 | [needs device] |
| N1 | `DynamicLinkClassifier` + tests from fixtures | [unit-testable] (**Robolectric** for `android.net.Uri`) |
| N2 | `RouteParser` + tests (all fixture kinds, count-mismatch → no coords, never uses `@`) | [unit-testable] (**Robolectric** for `Uri.decode`) |
| N3 | `NavIntentFactory` + tests (exact URI strings, `Locale.GERMANY` default doesn't change output, label escaping, our component in `EXTRA_EXCLUDE_COMPONENTS`) | [unit-testable] (Robolectric for `Uri`/`Intent`) |
| N4 | `NavLauncher` + `<queries>` + chooser exclusion + `ActivityNotFoundException` handling | [needs device] |
| N5 | `NavHandoffController` + wire into `WebViewHost.shouldOverrideUrlLoading`; tests with fakes for each state-machine branch | [unit-testable] + [needs device] |
| N6 | `NavSettings` (DataStore: preferred package, uri style, offerSaveDrive) | [unit-testable] |
| N7 | `PendingDriveStore` + `RouteInfoJson` + `DriveCaptureViewModel`; tests with a fake `Clock`: arm on stop only, unarmed + resume → no prompt, armed + resume → prompt, 4 h − 1 ms → prompt, 4 h + 1 ms → cleared, survives store re-creation (process death) | [unit-testable] |
| N8 | Save-drive prompt composable + previews + Compose tests (Save calls `onSave(selected)`, checkbox + Not now → `onDismiss(true)`) | [unit-testable] (Compose/Robolectric); previews [needs human review] |
| N9 | Integration (lifecycle arming in `MainActivity`, `LifecycleEventEffect(ON_RESUME)`, gesture dismissal) + device checklist | [needs device] |

N3 and N4 are prerequisites of bubble B8b.

## 9. Acceptance criteria

1. Start opens the nav app at the **destination's** coordinates for every N0 fixture kind
   that has coordinates; never at the camera position.
2. Directions and unknown campaigns behave exactly as today.
3. No crash when no nav app is installed.
4. Our app never appears in the nav chooser.
5. With the setting on, returning from the nav app (within 4 h) shows the prompt once;
   cancelling the chooser never shows it; Save creates a Drive with correct
   origin/destination; "Don't ask again" turns the setting off.
6. All N1–N3, N5–N8 tests pass.

## 10. Manual device checklist

| ID | Check |
|---|---|
| D-N1 | Directions button still loads directions in-app |
| D-N2 | Start with OsmAnd installed → OsmAnd shows the destination (not the map centre: pan the map far away before pressing Start to prove it) |
| D-N3 | Same with Organic Maps; with both installed → chooser without our app in it |
| D-N4 | Destination without coordinates (if N0 produced one) → nav app searches the label |
| D-N5 | Set style to `google.navigation`: does OsmAnd / Organic Maps start routing directly? Record results; remove the option if neither does |
| D-N6 | Uninstall preferred app → Start → chooser appears, no crash |
| D-N7 | Return from nav app → prompt; Save → drive in list; repeat with "Don't ask again" → no prompt next time, setting shows off |
| D-N8 | Enable "Don't keep activities" in developer options → Start → return → prompt still appears |
| D-N9 | No nav app installed (emulator) → Toast, app stays usable |
| D-N10 | Both nav apps installed, no preference → Start → **cancel the chooser** → no prompt, now or after leaving and returning to the app later |
| D-N11 | Start → nav app → wait > 4 h (or temporarily set the constant to 1 min in a debug build) → return → no prompt |
| D-N12 | Prompt visible → pan the map → prompt disappears; bubble reappears |
