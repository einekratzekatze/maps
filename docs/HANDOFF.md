# Handoff: phone testing and open decisions (2026-10-22)

Everything that can be built and tested on the JVM is done up to the point where a phone or
an owner decision is needed. This file says what to check on the phone, in which order, and
which decisions block the next code.

State at `a1197aa`: `./gradlew assembleDebug testDebugUnitTest lint` passes, 306 unit tests
(Robolectric included), 0 lint errors. Nothing below has run on a device yet.

## 1. What changed in this session

| Area | Commits | On screen? |
|---|---|---|
| Compose host 1.3a/1.3b: `WebViewHost`, edge-to-edge, `MapController`/`MapCommands`, `MapStateRepository`, touch observer, `GmwBridge`, back handler | `9c51203`, `28f581f` | yes (whole app) |
| Bubble B1, B2a, B2b, B4-B8b, B9 (bubble semantics) | `1fdd95d` ... `e0720c5` | yes |
| Search S4-S6 + top bar composable | `515e037`, `7cd70ef`, `27b9471` | **no**, waits for S7 |
| My Maps import/refresh (DB5, 5.1) | `792a680` | no UI yet |
| Debug seeder | `a1197aa` | debug builds via adb |

Deviations from the specs are written down in `compose-host.md` §11 and in the commit
messages. The main ones: `navigateInPage` is `loadUrl` (spike B0 #7); the drag release
position comes from the gesture, not the animated offset (the animation can lag pending
drag steps); `PlaceCardState` carries lat/lon.

## 2. Phone session (about 2 h)

Build and install the debug APK, then seed test data around your city:

```
./gradlew installDebug
adb shell am start -n io.github.einekratzekatze.maps.debug/io.github.einekratzekatze.maps.MainActivity \
    --ez debug_seed true --es debug_seed_at 52.52,13.405
```

The seeder adds 20 places on a spiral (100 m to 5 km), "Saved twice" in two lists, a Fiji
place, a place in a hidden My Maps layer (it must **never** appear) and Home. Run it only
once per install (it adds, it does not replace). Useful logs:
`adb logcat -s CAMERA SPIKE GMapsWV MapCommands`.

### 2.1 Host (compose-host.md §10) - do this first, everything else depends on it

| ID | Check |
|---|---|
| D-H1 | Status bar and navigation bar do not cover GMaps' search pill or bottom sheet (gesture nav **and** 3-button nav). Screenshots please. |
| D-H2 | Rotate: no reload, consent banner does not come back |
| D-H3 | Change font size and display size with the app open: no reload |
| D-H4 | Change system language: no reload |
| D-H5 | Toggle dark mode: no reload, the map darkens/lightens |
| D-H6 | Back: history-back as before; at the start page the app closes |
| D-H7 | Swipe the app away, reopen: cookies wiped, start URL |
| D-H8 | `adb logcat` while tapping: single tap, double-tap zoom and pan behave as in §10 (add a temporary `Log.d` on `touches` if needed) |

Also check: the WebView still keeps the screen on (moved from the deleted layout to the
WebView), and the location prompt still works.

### 2.2 Bubble (floating-places-bubble.md §9.4)

Run D1-D27. The most important ones, in order:
- **D3** map gestures work everywhere outside the bubble/card (collapsed and expanded).
- **D7/D8** card opens, list updates after panning. Expect about **5-6 s** delay after a pan
  (spike B0 #3: the URL lags). Tell me if that feels broken; the spec allows hiding the badge
  while the map moves.
- **D9/D10** a tap on the map closes the card; Back closes the card before history-back.
- **D4/D21** position limits and card size: the bubble avoids the top 72 dp and bottom
  136 dp. Spike B0 saw a Directions bottom sheet about one third of the screen high; the
  bubble is hidden in Directions, but check place pages too.
- **D5** gesture-nav back does not fire when dragging from the edge.
- **D17** zoomed out over the Pacific, the Fiji place counts; "HIDDEN - must not show" never appears.
- **B2b calibration**: the magenta crosshair (debug builds) must sit on the map centre
  within about 10 px at zoom 12+. If it is off by a constant amount, note the offset.
- **D14/D15** TalkBack: bubble reads "Saved places, N in view"; Actions menu has
  "Move to left edge" etc. Not done yet: moving TalkBack focus to the card title on open
  (B9 focus handoff), so record what TalkBack focuses after opening the card.
- Tapping an unset Work chip shows a "comes with the settings screen" toast (expected).

### 2.3 Spike leftovers (only needed for later tasks)

- **N0**: transit and bike Start presses, and case (g): does cancelling the nav-app chooser
  produce `ON_STOP`? (Expected: no.)
- **S0 / S7**: in chrome://inspect, check that `.bVzuaf` is still the pill and whether
  `document.querySelector('.bVzuaf button')?.click()` opens the side menu while the pill is
  hidden with `display:none`. This decides S7/S8 (see 3.3).

## 3. Decisions needed from you

### 3.1 Saving drives (nav-handoff N6-N9) is blocked by missing coordinates
The N0 fixtures show that for a **named** destination neither the Start link nor the page
URL has coordinates, only a Google place id (`!1s0x...:0x...`). Only dropped pins carry
`!1d<lon>!2d<lat>`. `Drive.destLat/destLon` are NOT NULL, so "Save this drive?" would
almost never appear. Options:
1. Allow label-only drives (schema v2: nullable destination coordinates; replay with
   `geo:0,0?q=<label>`). Simple, but replay depends on the nav app's address search.
2. Read the coordinates from the page (JS on the directions page). Needs a device spike.
3. Drop drive capture for now.

### 3.2 Map markers (Renderer P): ship or not?
The markers spec says: if the URL lags a pan by more than 2 s, ask the owner. B0 measured
about 5.6 s. With Renderer P, pins disappear on every touch and come back about 5-6 s after
the map stops. My suggestion: skip markers for the first release; the bubble already lists
what is in view.

### 3.3 Search field: when to put it on screen
The top bar with our search field is built and tested but not on the map screen, because
GMaps' own pill is only hidden once S7 (the `ui.js` page script) works on a device. Putting
it in before that shows two search fields. If you want it earlier anyway (the spec accepts
two fields as the fail-open state), say so. The settings screen (Phase 7: bubble on/off,
Places API key, Home/Work, refresh interval) also needs the top bar as its entry point.

## 4. Build environment notes (cloud sessions)

- Android SDK: `dl.google.com` must be allowed in the environment's network settings.
- Maven Central rate-limits Gradle's parallel downloads (HTTP 429). The session used a
  user-level Gradle init script (`~/.gradle/init.d/central-mirror.gradle`, not in the repo)
  that points Maven Central to `https://maven-central.storage-download.googleapis.com/maven2/`.
  Robolectric downloads its Android runtime jar from Maven Central by itself; a retry fixes
  a 429 there.
- `local.properties` with `sdk.dir` is git-ignored and must be created per machine.
