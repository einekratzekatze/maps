# Code review: batch 1 (shared geo types, URL parser/builder, Web Mercator, My Maps, KML/GPX)

Reviewed 2026-10-21: `git diff ac9a08b..HEAD` (`b43dc7a`, `499ed9f`, `aecbcb8` and merges, HEAD `edd14bd`).
Specs: compose-host.md §6, floating-places-bubble.md (FPB) §5.2–5.3, schema.md, TODO.md 3.5/5.1, PROJECT_GOALS.md.
`./gradlew testDebugUnitTest` passes on HEAD. Claims marked **[verified]** were checked with a throwaway Robolectric
test in a scratchpad clone (real libcore `KXmlParser`, the same parser `android.util.Xml` returns on a device).
Severity: **blocker** = the feature breaks when integrated; **should-fix** = a real bug or gap that will cost
rework; **nit** = polish. Counts: 2 blockers, 12 should-fix, 12 nits.

Paths are abbreviated. `main/` = `app/src/main/java/io/github/einekratzekatze/maps/`, `test/` = the same under
`app/src/test/java`.

## 1. Blockers

### BL-1: `KmlParser` returns an empty map, without an error, for input that is not KML
`main/data/mymaps/KmlParser.kt:101-110` never checks for a `<kml>` root. `parse("")` and
`parse("<html><body>Sign in</body></html>")` both return `ParsedMyMap(name=null, sublists=[])` **[verified]**.
When a map is private, deleted, rate-limited or behind a consent interstitial, the KML endpoint returns HTML or an
empty body. Phase 5.1 passes the result to `ExternalListDao.replaceContent`, which "replaces all sublists and places
atomically" (schema.md §2.6). A refresh would therefore **delete the user's cached list** without any error. The
KDoc says the opposite ("Malformed XML throws ... no partial result", line 31). `KmlReader` already does the right
thing (`KmlReader.kt:66`).
**Fix:** track `sawKml` (first START_TAG local name == `kml`) and throw `KmlParseException("Not a KML document")`
otherwise. Throw on an empty document as well. Add tests for `""`, `"<html/>"` and a well-formed non-KML root.

### BL-2: `MyMapsUrl.kmlUrl` probably fetches KMZ (a zip), not KML
`main/data/mymaps/MyMapsUrl.kt:21-30` deliberately leaves out `forcekml=1`. As far as I know (and as many third-party
tools depend on), `/maps/d/kml?mid=…` returns a **KMZ** archive unless `forcekml=1` is present. Fed a KMZ,
`KmlParser.parse(InputStream)` throws `KmlParseException` on every import. I could not check this offline because I
have no public `mid`. **Verify first:**
`curl -sI 'https://www.google.com/maps/d/kml?mid=<public id>'` and look at `Content-Type`/`Content-Disposition`.
**Fix:** if it is KMZ, either append `&forcekml=1` and update TODO 5.1 to match, or unzip `doc.kml` from the
stream with `ZipInputStream`. Unzipping is more robust, because Google could drop `forcekml` and the same code
path then covers user-shared `.kmz` files (TODO 3.5 share target).

## 2. Should-fix: correctness

### SF-1: Tilted bounds miss visible places when the map is rotated (FPB §5.2 promises "nothing visible is missed")
`main/map/WebMercator.kt:51-53` scales width and height by 1.5 separately. `MapCamera` has no heading, so a
heading of 90° on a portrait viewport is not covered. With a 400×800 viewport at z=10, the computed half-width is
0.412° and the visibly needed half-width is 0.549° **[verified]**. Separately, 1.5× is not conservative at high
tilt either: near the horizon the visible ground runs out far beyond 1.5× of the flat footprint.
**Fix:** for `Tilted`, use a square of side `max(w, h) * TILT_EXPAND` (or `hypot(w, h) * TILT_EXPAND`, which covers
any rotation). Either cap the tilt case (for example, mark the badge as approximate) or carry `t` in `MapCamera`
and expand by `1/cos(t)`. Amend the FPB §5.2 wording.

### SF-2: Every `…a` camera is classified as Street View, including tilted 3D satellite views
`main/map/url/MapUrlParser.kt:54`. Desktop GMaps writes 3D/tilted satellite views as
`@40.689,-74.044,346a,35y,315.97h,72.88t/data=!3m1!1e3`, where `a` is altitude in metres. That URL is parsed as
`StreetView`, so the bubble is hidden **[verified parser behaviour; URL shape not yet confirmed on mobile]**. The
spec table (FPB §5.2) has the same blind spot.
**Fix:** wait for B0 (d). The likely rule is: Street View iff the `data=` part has a pano id (`!1s…`) or
`!2e0`/`!2e10`, or iff the value is exactly `3a`. Otherwise treat `a` as altitude metres and return `Tilted`.
That needs a second field, or a metres flag, because `zoom` is overloaded.

### SF-3: `m` camera with tilt/heading loses the tilt
`MapUrlParser.kt:55`. `3000m,45t` is parsed as `Satellite3dMeters`, so the 1.5× expansion is skipped
**[verified]**. FPB §5.2 says that `,Nt` alongside `z` **or `m`** means `Tilted`. `MapCamera(zoom, mode)` cannot
express "metres + tilted".
**Fix:** add `tilted: Boolean` (or `tiltDeg`/`headingDeg`) to `MapCamera`, which SF-1 needs anyway. Alternatively,
amend the spec to say that `m` always counts as flat.

### SF-4: An entity-expansion bomb ("billion laughs") is expanded by every reader
`android.util.Xml.newPullParser()` (libcore KXmlParser) expands internal DTD entities. A six-level bomb produced a
1,000,000-char `<name>` in both `KmlParser` and `KmlReader` **[verified]**, and nine levels means about 1 GB, which
is an OOM crash. The share-target import (TODO 3.5) takes untrusted files. External entities are *not* resolved
(XXE returned null) **[verified]**.
**Fix:** in `withParser` (`main/data/exchange/XmlReading.kt:15`) and `KmlParser.parse` (`KmlParser.kt:46`),
advance with `nextToken()` until the first START_TAG and throw on `DOCDECL`. Then continue with `next()`. Add a
test with a small bomb.

### SF-5: `KmlParser` aborts the whole import when a leaf element contains child markup
`KmlParser.kt:117` uses `p.nextText()`, which throws on a child element.
`<description>a <b>b</b></description>` makes the whole map fail with `KmlParseException` **[verified]**. Unescaped
HTML in `<description>` is common in hand-made and Earth-exported KML. `KmlReader` copes with the same input
because it uses `readAllText()` (`XmlReading.kt:28`) **[verified]**.
**Fix:** use `readAllText()`. Item SIM-1 removes the duplication anyway.

### SF-6: The memory claim in `KmlParser` is false
`KmlParser.kt:32` says "memory use is proportional to the output, not the XML input". `nextText()` is called for
**every** `<coordinates>`, `<description>` and `<value>`, including LineString/Polygon coordinates that are then
thrown away (line 117 runs before the `parent == "Point"` check at line 158). The 20,000-char cap applies only after
the full string has been built. Memory is therefore proportional to the largest text node, and for SF-4 it is
unbounded. The test `hugeFileStreams` (`test/data/mymaps/KmlParserTest.kt:121`) builds a 5 MB `String` first, so it
cannot show streaming.
**Fix:** correct the KDoc ("bounded by the largest text node"). Skip non-Point `<coordinates>` with a depth-skip
instead of `nextText()`. Feed the streaming test from a generating `InputStream`.

### SF-7: A foreign track becomes a "Drive" with thousands of waypoints
`main/data/exchange/KmlReader.kt:126` and `GpxReader.kt:90`. Any LineString, `<trk>` or `<rte>` with ≥2 points is
imported as a drive. A 5000-vertex OsmAnd track gives 4998 waypoints **[verified]**. Replay hands this to a
navigation app or a GMaps URL that accepts about 10 stops, and `waypointsJson` grows without limit.
**Fix:** import full geometry only from our own files (`maps:hasOrigin`/`kind=drive` present). For foreign
tracks, keep the first and last point and skip or cap the intermediate ones (for example, `MAX_WAYPOINTS = 8`),
and return a skipped/simplified count in `ExchangeDocument` so the UI can say so. Owner decision D3.

### SF-8: The GPX round trip loses data silently (task question 3). Yes, it does, beyond the documented cases
`GpxWriter` KDoc (`GpxWriter.kt:7-10`) documents only "empty lists vanish" and "same-named lists merge". Further
losses **[verified]**:
1. **List order changes.** All `<wpt>` come before all `<trk>` (GPX xsd requires this) and the reader groups lists
   by first-seen `<type>` (`GpxReader.kt:85,90`). Writing `[DrivesOnly, Places]` reads back as `[Places, DrivesOnly]`.
2. **A list named `""` is renamed to the document name.** `type` is omitted for `""` (`GpxWriter.kt:53,65`) and
   untyped items go to `docName` (`GpxReader.kt:98`). It also merges with any list named like the document.
3. **`""` labels become `null`** (origin, destination, waypoint: `GpxWriter.kt:46`). KML keeps `""` for
   origin/destination but also maps `""` waypoint labels to null (`KmlReader.kt:114`).
4. **Leading/trailing whitespace is trimmed** from names, notes, address and labels in both formats (except KML
   `<Data>` values, which are *not* trimmed, so the two formats disagree).
5. CR is normalised to LF by the parser, invalid XML 1.0 chars are dropped by `XmlOut.text`, and coordinates are
   rounded to 7 decimals (about 1 cm). These are acceptable but undocumented.
The round-trip test sidesteps item 3 (`ExchangeTestData.kt:146`, `"".copy(label = null)`) and item 1 (the first
list has places).
**Fix:** state all five in the `GpxWriter`/`KmlWriter` KDoc and test them explicitly. For items 1 and 2, write
`<maps:list index="n">name</maps:list>` in each item's `<extensions>` and prefer it over `<type>` when reading.
Owner decision D4.

### SF-9: `GpxWriter` iterates `lists` twice; the streaming contract is not consistent
`GpxWriter.kt:32-33`. With a `constrainOnce()` iterable this throws `IllegalStateException` **[verified]**. The
KDoc of `ExchangeList` says that the writers iterate *places/drives* once, which is true, but `lists` itself is an
`Iterable` too.
**Fix:** take `lists: List<ExchangeList>` in both writers, or document "re-iterable".

### SF-10: GPX writes `lon="180"`, which is invalid against gpx.xsd
GPX `longitudeType` is `[-180, 180)` (maxExclusive). `GpxWriter.kt:45,50` writes `180` **[verified]**. Strict
validators (and some importers) reject the file.
**Fix:** map `lon == 180.0` to `-180.0` in GPX output only.

### SF-11: Nested folders mean different things in the two KML readers
`KmlParser` produces `["Outer / Inner"]` and drops container-only folders. `KmlReader` produces `["Outer",
"Inner"]` and keeps the empty `Outer` as an empty list (`KmlReader.kt:53-56`, `keepIfEmpty = true`)
**[verified]**. So an OsmAnd/Earth file with category folders imports an empty list per parent folder.
**Fix:** choose one rule (the "Outer / Inner" path and dropping container-only folders seem better for both) and
share it (SIM-1).

### SF-12: The `KmlParser` style resolution misses common KML shapes
`KmlParser.kt:192`. `styleUrl` values like `styles.kml#s` keep the file prefix, so the lookup fails. Inline
`<Style>` inside a `<Placemark>` or inside a `<StyleMap><Pair>` has no id and is discarded (`:127`). Both give
`colorArgb = null` **[verified]**. My Maps itself uses shared `#id` styles, so this matters only for non-My-Maps
input.
**Fix:** `substringAfterLast('#')`, and attach an id-less `<Style>` to the enclosing placemark or Pair.

## 3. Spec conformance

- Signatures match compose-host §6.1–6.3 and FPB §5.3 (`GeoModels`, `NavTarget`, `MapUrlParser.parse`, `MapUrls`
  four builders, `WebMercator.bounds`). The additions (`boundsOrNull`, constants) are fine.
- **N-1 (nit):** schema.md §2.6 has `replaceContent(..., content: ParsedKml, ...)`, but the code type is
  `ParsedMyMap` (`main/data/mymaps/MyMapsModels.kt:11`). Rename one of the two.
- **N-2 (nit):** schema.md §1.6 has `ExternalSublist.name` as non-null and "visibility carried over by **name**".
  `ParsedSublist.name` is null for the implicit sublist and for blank folders, and duplicate folder names are kept
  separate (`[A, A]` **[verified]**). The repository has to pick fallbacks, and visibility carry-over becomes
  ambiguous for duplicates. Specify a fallback (for example, the localised "Untitled layer") and carry over by
  `(name, occurrence index)`.
- **N-3 (nit):** TODO 3.5 says "`XmlPullParser`-based writer". The writer is hand-rolled (`XmlWriting.kt:5-9`
  explains why). The deviation is reasonable; update the TODO.
- **N-4 (nit):** the FPB §5.2 suffix regex has no `-?` in the `y/h/t` group, but the code accepts negative values
  (`MapUrlParser.kt:14`). This is a harmless superset; align the spec.
- **(1) The `WebMercator.METERS_CONSTANT` is uncalibrated.** `156543.034` (`WebMercator.kt:20,45`) is the
  theoretical metres/px at z0 for 256-px tiles, and the formula matches FPB §5.2. It is **not calibrated**: the
  FPB says to calibrate on a device (B2b), and B0 (d) has not run, so we do not know whether `1234m` is the
  vertical span, the diagonal or the camera altitude. `satelliteMetersConvertsViaViewportHeight`
  (`test/map/WebMercatorTest.kt:105`) derives its input from the same constant, so it only proves internal
  consistency. Keep a `// TODO(B2b): calibrate` comment on the constant until then.
- **(2) My Maps skips LineString/Polygon.** This is **not a spec gap for 5.1**. PROJECT_GOALS ("places within each
  layer → list items") and schema.md have no way to store a shape or a route in an external list: `Drive` has no
  `externalSublistId`, and `ExternalSublist` holds only `Place` rows. My Maps "routes" are also geometry
  (hundreds of vertices), not origin/destination/waypoints, so converting them to `Drive` would be the SF-7
  problem again. The real gap is UX: `skippedNonPoint` is counted, but no spec says that the user is told.
  Owner decision D2.

## 4. Should-fix / nit: URLs

- **N-5 (nit) `MapUrlParser` host check:** `https://evil.com\@www.google.com/maps/@1,2,3z` is accepted as Google
  (`MapUrlParser.kt:12,23`) **[verified]**. WHATWG parsing treats `\` as a path separator, so the real host is
  `evil.com`. The input normally comes from WebView-normalised URLs and the result only drives UI, so the impact
  is low. Reject `\` in the authority, or parse with `java.net.URI` and use `.host`.
- **N-6 (nit) `MyMapsUrl` host:** `google\.[a-z]{2,3}(?:\.[a-z]{2})?` accepts `google.com.ev` **[verified]**.
  `kmlUrl` always uses www.google.com, so the fetch is safe, but `sourceUrl` is stored and opened by "Edit source"
  (schema.md §1.5). Use an explicit pattern for Google ccTLDs (`com|[a-z]{2}|com?\.[a-z]{2}`) followed by `/`.
  `maps.google.com/maps/d/...` is rejected **[verified]**; accept it if pasted links of that form exist.
- ReDoS: none found. Both URL regexes stay linear: 40k-char adversarial inputs took 2–13 ms **[verified]**.
- **N-7 (nit):** `GeoBounds.contains` treats lon 180 and -180 as different (`GeoBounds(0,-180,10,-170)` does
  not contain lon 180) **[verified]**. Normalise 180 → -180 in `normalizeLon` (`GeoModels.kt:26-30`). Places at
  |lat| > 85.0511 can never be inside bounds; document this.

## 5. Should-fix / nit: XML details

- Escaping is correct: `& < > " '` are escaped, surrogate pairs are kept, lone surrogates and C0 controls are
  dropped (`XmlWriting.kt:13-50`). Locale safety holds: all numbers go through `Locale.ROOT`, and the tests run
  under `Locale.GERMANY`.
- The GPX 1.1 element order is correct: `metadata, wpt*, trk*`; wpt `name, desc, type, extensions`; trk `name,
  type, extensions, trkseg`; `extensions` content is in a foreign namespace. The KML Feature order is correct:
  `name, address, description, ExtendedData, Geometry`.
- **N-8 (nit):** the exception contracts disagree. `KmlParser` → `KmlParseException`, while the exchange readers →
  `ExchangeFormatException`. An `IOException` at `setInput` is wrapped by KXmlParser **[verified]**, but a mid-stream
  `IOException` escapes `withParser` (`XmlReading.kt:20`) raw. Document "@throws IOException" or wrap it.
- **N-9 (nit):** `parseLatLon` uses `toDoubleOrNull` (`XmlReading.kt:44`), which accepts `1.5f`, `2d` and hex
  floats (`KmlReader` reads `1.5f,2d` as a point; `KmlParser` rejects it) **[verified]**. This is harmless but
  inconsistent; see SIM-2.
- **N-10 (nit):** `Regex("\\s*,\\s*")` and `Regex("\\s+")` are compiled on every coordinate call
  (`KmlParser.kt:210`, `KmlReader.kt:147`). Hoist them to constants.

## 6. Test quality

- Good: the fixtures are the spec fixtures, locale is forced to `GERMANY`, the antimeridian, poles and the
  exact-180 east edge are covered, writer output is checked through a namespace-aware DOM, and the writer
  streaming test uses a counting `Appendable` (a real check).
- **T-1:** missing tests for BL-1 (non-KML/empty input), SF-4 (DOCTYPE), SF-5 (child markup), SF-1 (rotation
  coverage), SF-8 items 1–4 and SF-10.
- **T-2:** the GPX/KML writer tests check element order by hand. Bundle `gpx.xsd` (and `ogckml22.xsd` if
  feasible) under `test/resources` and validate with `javax.xml.validation`; this would have caught SF-10.
- **T-3:** `osmand.gpx` / `organicmaps.kml` are hand-written imitations. Replace them with real exports
  (anonymised) when available, and add a real My Maps export once B0 (g)/BL-2 has been resolved.
- **T-4:** `hugeFileStreams` does not test streaming (SF-6). `satelliteMetersConverts…` is circular (point 1
  above).
- **T-5 (nit):** `ExchangeSampleFilesTest.osmAndGpx` sorts the list names before comparing (line 19), which
  hides any ordering regression. Assert the actual order.

## 7. Simplification / duplication

- **SIM-1: there are two KML readers.** `data/mymaps/KmlParser` (230 lines) and `data/exchange/KmlReader` (152
  lines) each walk Document/Folder/Placemark/Point/ExtendedData with different rules (SF-5, SF-11, root check,
  name trimming, exception type). Build one low-level reader, `KmlReader`, that emits folders (with path),
  placemarks (name, description, address, styleUrl, first Point, LineString, ExtendedData) and styles. Make
  `MyMapsKml` a roughly 50-line adapter that resolves colours and maps to `ParsedMyMap`. This removes about 150
  lines and fixes SF-5/SF-11 once.
- **SIM-2: there are three coordinate validators.** `KmlParser.parseCoordinates` (strict regex),
  `KmlReader.parseCoordinates` (lenient), `makeLatLon`, plus the range checks in `MapUrlParser.kt:50`. Add
  `LatLon.ofOrNull(lat, lon)` in `domain/GeoModels.kt` and one `parseKmlTuple` in the shared KML reader.
- **SIM-3:** longitude normalisation is duplicated (`GeoModels.kt:26-30` and `WebMercator.kt:80-83` use the same
  formula). Expose one `normalizeLon` in `domain`.
- **SIM-4 (nit):** `KmlParseException` and `ExchangeFormatException` could become one `GeoFileFormatException`.
- **N-11 (nit):** `MapUrls.coord` (fixed 7 decimals) and `formatCoord` (trimmed) differ on purpose, but both should
  call one `Locale.ROOT` helper.
- **N-12 (nit):** `KmlParser.SublistBuilder` sets `name` in `init` and in `setOwnName`; a constructor default
  would read more simply.

## 8. Owner decisions

- **D1 (BL-2):** fetch My Maps with `&forcekml=1` or unzip KMZ? Unzipping is recommended, because it also handles
  `.kmz` shares. One `curl` against a public map settles whether this is needed at all.
- **D2:** My Maps lines/polygons: skip them (current, consistent with schema), and should the import result show
  "N shapes not imported"? Later, a representative point (centroid or first vertex) instead?
- **D3 (SF-7):** foreign tracks/routes on import: skip, keep endpoints only, or cap waypoints at N?
- **D4 (SF-8):** accept GPX order/name losses as documented limitations, or add `maps:list` extensions so that
  our own GPX round-trips exactly?
- **D5 (SF-1/SF-3):** add `tilt`/`heading` to `MapCamera` (an API change to compose-host §6.1), or use a
  rotation-safe square expansion and accept over-inclusion?
- **D6 (SF-2):** wait for B0 (d) real `a`-suffix URLs before settling the Street View rule. Until then, should the
  parser treat non-`3a` values as tilted satellite?
