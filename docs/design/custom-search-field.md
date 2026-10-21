# Design: Custom Search Field (TODO Phase 8)

Status: ready for implementation **after DOM spike S0** (and bubble spike B0 for the
viewport bias) · Depends on: Phase 1.1 (TopAppBar), 1.3 (`compose-host.md`:
`MapStateRepository` incl. `touches`/`overlay`, `MapCommands`, `PageScript`/`GmwBridge`,
`MapUrlParser`, `MapUrls`, back priority, `adjustResize`), `schema.md` (DB2 `PlaceDao.search`,
DB4 `RecentSearchDao`), bubble B1 (camera part of `MapUrlParser`)

---

## 0. Corrections to TODO assumptions

1. **`aria-label` selectors are localised.** TODO 8.1 uses `[aria-label="Menü"]` and
   `[aria-label="Löschen"]` — German. GMaps' UI language follows the WebView locale, and
   the app ships 25 translations. Selector priority must be:
   `id` > stable attributes (`name`, `role`, `type`, `jsaction` prefixes) > structure >
   minified class > `aria-label` (last resort, and then one entry per locale).
2. **CSS doesn't need re-applying after SPA re-renders.** A `<style>` element in `<head>`
   applies to elements created later. The `MutationObserver` is only needed (a) to re-insert
   our `<style>` if GMaps removes it and (b) for rules that need JS (e.g. finding an element
   by structure and tagging it with a `data-gmw-hide` attribute that our CSS targets).
3. Today's CSS injection in `onPageFinished` (`MainActivity.kt:273–284`) appends a new
   `<style>` on every page finish (duplicates pile up). Replace it with the single
   idempotent injector below (keep its two existing rules).

## 1. User-facing behaviour

- Top bar shows our search field (pill, "Search here"), with our own **menu** button on the
  left that opens GMaps' side menu, and the settings icon on the right.
- GMaps' own search pill is hidden.
- Focus the field → keyboard + suggestion panel under the top bar:
  - empty query: up to 10 recent searches;
  - typing: "Saved places" (≤ 5 matches), "Recent" (≤ 5 matches), "Google" (≤ 5, only if
    a Places API key is configured).
- Submit → map shows results for the query near the current map area.
- Pick a saved place → map goes to it. Pick a Google prediction → GMaps place page.
- Directions mode (`/maps/dir/`): our field is replaced by a "Directions" title with a back
  arrow (GMaps' directions inputs own that area). Street View: whole top bar hidden.
- When GMaps itself navigates to a search or place (category chip, link), our field shows
  that query/place name.

## 2. State machines

### 2.1 Mode (derived from URL, pure)

There is **no separate `MapModeParser`**. Mode and display text come from the one shared
parser, `MapUrlParser.parse(url): MapUrlState(camera, mode, displayText)` (compose-host.md
§6.3); S1 adds the `mode`/`displayText` rules to that object, next to the camera rules from
bubble B1, with one fixture file. `MapStateRepository.urlState` exposes the result.

`MapMode { Map, Search, Place, Directions, StreetView, NonMap }`:
`/maps/dir/` → Directions; `@…,3a,` → StreetView (derived from the same match as
`CameraMode.StreetView`, so they cannot disagree); `/maps/search/` → Search;
`/maps/place/` → Place; consent page, `/maps/d/`, other hosts, `about:blank` → NonMap;
else Map.

### 2.2 Field

```
 Idle(displayText) ── focus ─▶ Editing(query, suggestions)
      ▲                         │ submit / pick suggestion ─▶ Idle(new text) + LoadUrl
      │                         │ back / map tap / clear focus ─▶ Idle(displayText)
      └─────────────────────────┘
 displayText (when not Editing) = urlState.displayText ?: ""
 mode == Directions ⇒ field replaced by a back/close "Directions" title (Editing cancelled)
 mode == StreetView | NonMap ⇒ field not shown (Editing cancelled)
 IME hidden while Editing ⇒ Idle (clear focus)   — see §7 "Back while editing"
 editing is published with mapState.setSearchEditing(editing)
```

## 3. Components

```kotlin
data class SearchUiState(
    val mode: MapMode,
    val editing: Boolean,
    val query: String,                     // what's in the field
    val sections: List<SuggestionSection>,
    val autocompleteAvailable: Boolean,
    val autocompleteError: Boolean,
)
data class SuggestionSection(val kind: SectionKind, val items: List<Suggestion>)
enum class SectionKind { Saved, Recent, Google }
sealed interface Suggestion {
    data class SavedPlace(val id: Long, val name: String, val address: String?, val lat: Double, val lon: Double, val listColor: Int) : Suggestion
    data class Recent(val id: Long, val query: String) : Suggestion
    data class Prediction(val placeId: String, val primary: String, val secondary: String?) : Suggestion
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mapState: MapStateRepository,       // urlState, touches, overlay (compose-host §5)
    private val commands: MapCommands,              // LoadUrl / OpenGmapsMenu (compose-host §4)
    private val places: PlacesRepository,
    private val recents: RecentSearchRepository,
    private val autocomplete: AutocompleteClient,   // NoOp impl when no key
) : ViewModel() {
    val state: StateFlow<SearchUiState>
    fun onFocusChanged(focused: Boolean)            // also calls mapState.setSearchEditing
    fun onQueryChange(q: String)
    fun onSubmit()
    fun onSuggestion(s: Suggestion)
    fun onDeleteRecent(id: Long)
    fun onMenuClick()                               // commands.send(MapCommand.OpenGmapsMenu)
    fun onBack()                                    // called by MapBackHandler (priority 1)
}

interface AutocompleteClient {
    suspend fun predictions(input: String, bias: LatLon?, sessionToken: String): Result<List<Suggestion.Prediction>>
}
```

Compose:
```
MapTopBar(state, …)            // TopAppBar; navigationIcon = menu IconButton; title = SearchField | DirectionsTitle
SuggestionPanel(state, …)      // in MapScreen Box, aligned TopCenter, z above WebView and bubble,
                               // only composed while editing; Modifier.imePadding(); LazyColumn
```
Not `ExposedDropdownMenuBox`/`DropdownMenu` — they are `Popup`s and fight with IME/focus.
Map taps arrive as `MapTouch.Tap` from `mapState.touches` (collected in the VM; there is no
`onMapTapped` action). Unfocusing needs the focus manager, so the VM exposes
`editing = false` and the composable clears focus/hides the keyboard when it sees that.

URLs — all built by the shared `MapUrls` (compose-host.md §6.3; coordinates `Locale.ROOT`,
`Uri.encode` for text) and sent as `MapCommand.LoadUrl` (a search is a new page anyway):
- Submit: `https://www.google.com/maps/search/<q>/@<lat>,<lon>,<zoom>z` when a camera is known
  (biases results to the visible area), else `https://www.google.com/maps/search/<q>`.
  Spike S0 compares with `?q=` (TODO open question) — default to the path form.
- Saved place: `https://www.google.com/maps/place/<lat>,<lon>/@<lat>,<lon>,17z`
- Prediction: `https://www.google.com/maps/search/?api=1&query=<primary>&query_place_id=<placeId>`
  (documented Maps URLs format).

## 4. Data

- Entities, columns and queries are defined in `schema.md` only: `RecentSearch` (§1.7) and
  `RecentSearchDao.record` (insert-or-replace on the unique `queryNormalized`, then trim to
  the newest 20 in one `@Transaction`; SQLite on API 26 has no `ON CONFLICT DO UPDATE`);
  `Place.nameNormalized`/`addressNormalized` (§1.2) maintained by `PlacesRepository`;
  `PlaceDao.search` (§2.2: `LIKE … ESCAPE '\'`, prefix matches first, then distance to the
  camera centre); `TextNormalizer` (§3). This spec does not amend the schema.
- Debounce: local queries 100 ms; Google 350 ms, minimum 3 characters, `mapLatest` (cancels
  stale requests), one session token (UUID) per focus session.
- Autocomplete: `POST https://places.googleapis.com/v1/places:autocomplete`, headers
  `X-Goog-Api-Key`, and — required for the key's Android-app restriction to work over REST —
  `X-Android-Package: <packageName>` and `X-Android-Cert: <SHA-1 of signing cert, hex, no colons>`.
  Body `{"input":…, "locationBias":{"circle":{"center":{"latitude":…,"longitude":…},"radius":<viewport half-diagonal, max 50000>}}, "sessionToken":…}`.
  Use `HttpURLConnection` + `org.json` (no new deps), on `Dispatchers.IO`, 5 s timeout.
  Errors → section hidden + `autocompleteError = true` (small "Google suggestions unavailable" line). Never block local suggestions.
  Check current Places API pricing before claiming a free tier in UI copy.
- API key: DataStore, entered in Settings; never logged.

## 5. Hiding GMaps' search field (8.1, JS)

### Spike S0 (≤ 2 h, [needs device])
chrome://inspect the mobile GMaps DOM in **two UI languages** (English and German). Record
for the search pill container, the input, the menu button, the category chip row:
id, role, name, jsaction, classes, aria-label. Write the chosen selectors into
`assets/js/selectors.json` with a `stability` field (`id|attr|structure|class|aria-localised`).

### Strategy (decided)
Hide the **whole** GMaps search pill (incl. its burger). Our top bar has its own menu
button; tapping it runs `window.__gmw.openMenu()`, which `.click()`s the hidden GMaps menu
button (programmatic `click()` works on `display:none` elements). This avoids the
"half-width pill" hacks of TODO Strategies 1/2 and gives a clean single top bar. If S0 shows
the side menu does not open via programmatic click, fall back to TODO Strategy 1.

### Injector (`assets/js/ui.js`)
- Provided as a `PageScript` (`@IntoSet`) and installed by the host with
  `WebViewCompat.addDocumentStartJavaScript` (feature-checked; fallback
  `evaluateJavascript` in `onPageStarted`) — compose-host.md §4.3.
- Idempotent: `if (window.__gmw?.uiInstalled) return;`.
- Inserts one `<style id="gmw-style">` with: existing rules
  (`.ml-persistent-promo-banner{display:none!important} #app{top:0!important}`),
  hide rules from `selectors.json`, and `[data-gmw-hide]{display:none!important}`.
- `MutationObserver` on `document.documentElement` (`childList, subtree`), throttled with
  `requestAnimationFrame`: re-insert `#gmw-style` if missing; tag structural targets with
  `data-gmw-hide`.
- **Fail open**: after 5 s, if none of the hide selectors matched anything, post
  `{"type":"searchHideFailed"}` via the `GmwBridge` message bridge (on success post
  `{"type":"searchHidden"}`, which sets `overlay.gmapsSearchHidden` for the bubble's `minY`); Android logs a WARN and keeps our
  field (two fields is ugly but functional; no field would be broken). Debug builds show a
  Toast so the tester notices drift.
- Selectors come from `selectors.json` passed in at injection time (one place to fix drift).

## 6. Keyboard and insets

- `android:windowSoftInputMode="adjustResize"` and `enableEdgeToEdge()` are set by Phase
  1.3a (compose-host.md §2.1); this spec relies on them.
- Do **not** apply `imePadding()` to the WebView while *our* field has focus: the map must
  not resize (resizing makes GMaps re-layout and fire viewport changes). Apply
  `Modifier.imePadding()` to the WebView container **only when our field is not focused**,
  so GMaps' own inputs (directions fields) still get pushed above the keyboard.
- `SuggestionPanel` gets `imePadding()` so its last rows stay reachable.
- Conditional `imePadding()` changes the WebView's size while GMaps' own inputs are
  focused. `MapStateRepository` ignores viewport size reports while the IME is visible
  (compose-host.md §5), so `bounds` (bubble) and pin projection (markers) do not change.
- Bubble hides while IME is visible (bubble spec §5.7).

## 7. Edge cases

| Case | Handling |
|---|---|
| Map tap while editing | `MapTouch.Tap` from `mapState.touches` → `editing = false` → clear focus, hide keyboard (`LocalSoftwareKeyboardController`) |
| Back while editing | The first system Back while the IME is up only hides the keyboard and is **not** dispatched. Rule: when `WindowInsets.isImeVisible` turns false while editing, the field clears focus (`editing = false`). A Back that is dispatched while still editing is handled by `MapBackHandler` priority 1 (compose-host.md §7.4) → `onBack()`. The bubble is hidden while `searchEditing` (bubble §2), so the two never compete. |
| Query with `/`, `#`, `?`, `%` | `Uri.encode` the segment; test fixtures |
| Empty submit | Ignored |
| Same query submitted again | Recent timestamp updated, no duplicate |
| Rotation while editing | Activity not recreated (configChanges); `rememberSaveable` query anyway for process death |
| GMaps changes search DOM | Fail-open path (§5) |
| No API key | Google section never shown; no network calls |
| Invalid key / quota | Error line, local suggestions unaffected |
| RTL | TopAppBar mirrors automatically; verify menu icon side |
| Directions mode opened from our field? | Not possible from our field; mode is URL-driven |

## 8. Task breakdown

| ID | Task | Tag |
|---|---|---|
| S0 | DOM spike in two languages; `selectors.json`; URL format comparison — mandatory gate before S7/S8 | [needs device] |
| S1 | `mode` + `displayText` rules in the shared `MapUrlParser` (compose-host §6.3) + fixtures | [unit-testable] (Robolectric if `Uri.decode` is used) |
| S2 | = schema task DB4 (`RecentSearch` + `RecentSearchDao.record` cap/replace) | [unit-testable] (Robolectric) |
| S3 | = schema `PlaceDao.search` + `TextNormalizer` tests (umlauts, `ß`, `%` input) — part of DB1/DB2 | [unit-testable] (Robolectric) |
| S4 | `AutocompleteClient` real + NoOp; request building and response parsing tests with canned JSON | [unit-testable] |
| S5 | `SearchViewModel` (debounce, sections, effects, focus rules) + Turbine tests | [unit-testable] |
| S6 | `SearchField`, `SuggestionPanel`, `DirectionsTitle` composables + Compose tests (submit via IME action, suggestion click, clear button, delete recent) | [unit-testable] (Compose/Robolectric); previews (empty, typing, all sections, error, RTL, dark, font 2x) [needs human review] |
| S7 | `ui.js` as a `PageScript` (compose-host §4.3) + replace current `onPageFinished` CSS; on success `mapState.setGmapsSearchHidden(true)` | [needs device] |
| S8 | Menu button → `openMenu()`; fail-open message | [needs device] |
| S9 | IME wiring (clear focus on IME hide), search branch of `MapBackHandler`, map-tap unfocus | [needs device] |
| S10 | Settings: API key entry | [needs device] |

## 9. Acceptance criteria

1. In English and German UI, GMaps' search pill is hidden and our menu button opens the GMaps side menu.
2. If selectors break, our field still works and a WARN is logged (simulate by corrupting `selectors.json`).
3. Submitting searches near the visible area; result page loads in the WebView.
4. Saved places match case- and accent-insensitively; recents capped at 20, no duplicates.
5. No network call without an API key; with a key, typing "caf" shows Google predictions within ~1 s.
6. Map does not resize when our keyboard opens; GMaps' directions inputs stay above the keyboard.
7. Tests in S1–S6 pass.

## 10. Manual device checklist

| ID | Check |
|---|---|
| D-S1 | Launch: one search bar (ours), menu left, settings right |
| D-S2 | Menu opens GMaps side menu |
| D-S3 | Navigate around (search, place, back, directions, back) — GMaps' pill never reappears |
| D-S4 | Switch phone language to German, relaunch — same as D-S1..3 |
| D-S5 | Search "pizza" while viewing another city — results in that city |
| D-S6 | Saved place suggestion with umlaut via plain-letter typing ("munchen") |
| D-S7 | Recents: submit 22 searches → 20 kept; swipe one away |
| D-S8 | With key: predictions appear; airplane mode → error line, saved/recent still work |
| D-S9 | Keyboard open: map does not jump; tap map → keyboard closes |
| D-S10 | Directions: our field replaced by title; type in GMaps' destination field → it stays above keyboard |
| D-S11 | Back while editing closes editing only |
| D-S12 | TalkBack: field labelled "Search here", suggestions announced with section headers |
