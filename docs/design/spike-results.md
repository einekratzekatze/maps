# Device spike results

Run 2026-10-22 on a Pixel 8a (app spoofs a Pixel 7 / Chrome 135 user agent), debug build `452919d`,
driven over adb (`input swipe/tap`, `logcat -s SPIKE`) and Chrome DevTools over
`adb forward ... localabstract:webview_devtools_remote_<pid>` (JS probes). Coordinates in the
captured URLs are Google's default Berlin viewport, not the owner's location.

Status: **M0 done. B0 and S0 partly done. N0 not started** (needs the owner to press Directions/Start).

## B0 — URL and viewport behaviour

| # | Question | Result |
|---|---|---|
| 1 | Does `@lat,lng,zoomz` update on pan? | **Yes**, in place (no navigation). |
| 2 | Which hook sees it? | `doUpdateVisitedHistory` fires for every in-page URL change. `shouldOverrideUrlLoading` and `onPageStarted` do **not** fire during pan/zoom. |
| 3 | Latency | **Pan: the new centre arrives about 5.6 s after the pan ends** (a no-change history event at +0.6 s, then the real update at +5.6 s; repeatable). **Double-tap zoom: about 1.4 s.** The URL is a laggy viewport source. |
| 4 | Polling fallback needed? | The 250 ms poll of `webView.url` saw nothing that history did not (≤ 0.2 s later). Keep a 1 s poll only as a safety net. |
| 5 | CSS viewport | `innerWidth × innerHeight = 411 × 844`, `devicePixelRatio = 2.625` (WebView area under the status bar, no top bar yet). |
| 6 | URL shape | `https://www.google.com/maps/@52.5204675,13.3292937,12z?g_ep=...` (an extra `g_ep` param after the camera). `?hl=de` is kept in the query. Search URL after navigation: `/maps/search/<q>/@lat,lng,17z?g_ep=...`. |
| 7 | In-page navigation by synthetic link click | **Does not work as SPA navigation**: a clicked `<a href="/maps/...">` caused a full page navigation (`shouldOverrideUrlLoading` + `onPageStarted`, JS globals lost). Bubble rows must use `loadUrl` (about 0.6 s to `onPageFinished`); the "in-page first" default in the bubble spec reduces to the fallback. |
| 8 | Real URLs per suffix (`z`, `m`, `a`, `y`, `t`, `h`), non-map URLs | **Not captured.** Needs the owner to tilt, rotate, switch to satellite/3D and open a place photo, side menu, and consent page. |
| 9 | Load-time redirect | At page load GMaps asks for an app hand-off: `intent://www.google.com/maps?entry=ml&utm_campaign=ml-ardi&coh=...#Intent;scheme=https;package=com.google.android.apps.maps;S.browser_fallback_url=...;end` (blocked by the existing non-https rule; harmless, but classify it as `NonMap`/ignore in the Dynamic Link classifier). |

**Consequences for the specs**
- Badge, nearby list and search viewport bias must tolerate a **~5.6 s lag after a pan**. Decide: accept (badge updates when the map has settled), or reduce perceived lag by hiding/greying the badge while the map is moving (touches are known) and refreshing on the next history event.
- Marker renderer P (projected native overlay) cannot follow the map during a pan using the URL alone. See M0.

## M0 — marker injection

- `window.google` is **undefined** (also `google.maps`). Wrapping `google.maps.Map` is **not possible**: mobile GMaps does not use the public Maps JS API.
- `window` has only `mapslite` (`START_PERF`, `__jsload__`) and `_F_toggles__` as app-specific globals; there are no iframes and no shadow roots.
- `document.querySelectorAll('canvas')` returned an empty list, so the map surface was not a plain `<canvas>` the probe could find (the console prints "WebGPU is experimental..." at load). What it is exactly was not investigated; either way there is no DOM marker layer to extend.
- **Decision: Renderer P** (native Compose overlay projecting saved places with `WebMercator`). Because the URL lags a pan by ~5.6 s (B0 #3), P must hide pins while the map is moving (any touch on the map) and re-project after the next history event. Map markers drawn inside GMaps are out. Update `map-markers-js.md` §0 accordingly and drop the JS-injection tasks M1–M3.

## S0 — search DOM

- The GMaps search "pill" is **not an `<input>`**: it is `div.bVzuaf` (class stable across en and de) containing a `div[role=button]` and children with the text "Find a place" / "Ort finden". There are no `<input>` elements on the map screen at all.
- Tapping the pill opens a full-screen search overlay with `input[role=combobox][autocomplete=off]` (no id, no name, no aria-label, no class). The on-screen keyboard opens immediately.
- `aria-label`s are fully localised: en `Menu`, `Clear`, `Sign in`, `Back`, `Close`, `Re-center map to your location`, `Expand quick actions`; de `Menü`, `Löschen`, `Anmelden`, `Zurück`, `Schließen`, `Deinen Standort wieder auf der Karte zentrieren`, `Schnellaktionen maximieren`. Menu is the first `BUTTON` in the pill in both languages: prefer a structural selector (e.g. `.bVzuaf button:first-of-type`, **untested guess**, verify in S1) with a locale-independent fallback and keep the German/English label table.
- `?hl=de` switches the UI language (`<html lang>` becomes `de`); `navigator.language` stays `en-DE`.
- Hiding the pill with CSS is feasible via `.bVzuaf` but the class name is minified and will drift: keep the spec's fallback (leave GMaps' field visible when the selector does not match).
- **Not yet done:** search URL form comparison (`/maps/search/<q>` vs `/maps?q=`), `selectors.json` capture, behaviour of the directions screen search fields.

## N0 — Start/Directions intent fixtures

Not started. Needs the owner to run flows (a) to (f) in `nav-handoff.md` §4.0 on the phone while the SPIKE logs are captured. Case (g) (chooser-cancel lifecycle) cannot be tested until the nav intent launcher exists.

## Other observations (affect the bubble and compose-host specs)

- With the Directions entry point on the map screen, GMaps shows a **bottom sheet about one third of the screen high** (Drive / Walk / Bike / Public transport) plus a "Your location" button at the lower right above it. The bubble's default anchor and card height must not collide with it; the bubble should avoid the bottom third by default (check the anchor bounds in `floating-places-bubble.md`).
- The page is dark by default (system dark mode). Bubble and card colours need to follow system dark/light.
- First launch while the phone was asleep produced `net::ERR_SOCKET_NOT_CONNECTED` and a stuck error page: the app has no retry or reload affordance. Add a "tap to retry" state to compose-host (error page).
- Phone shows a navigation gesture bar; edge-to-edge insets matter at the bottom.
