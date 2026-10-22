# Code review: batch 2 (Room schema v1, DAOs, repositories: TODO 3.1 DB1-DB4)

Reviewed 2026-10-22: `git diff f477025..95cb886` (`54c3403` DB1, `fb8ace6` DB2, `82d4958` DB3, `95cb886` DB4).
Specs: `schema.md` (all sections), TODO.md 3.1 and 8.3, `REVIEW.md` §7 Resolution (BL-3, SF-15, SF-16, N-5),
`nav-handoff.md` §4.2/§6 (for the Drive API).

The gate (`./gradlew assembleDebug testDebugUnitTest lint`) passes on `95cb886` (49 new tests, 0 failures). After the
build, `git status` is clean, so the committed `app/schemas/.../1.json` matches the entities. Claims marked
**[verified]** were checked with throwaway probe tests and one mutation in a scratchpad clone (since deleted). Nothing
in the repository was changed.

Severity: **blocker** = the feature breaks when integrated; **should-fix** = a real bug or gap that will cost rework;
**nit** = polish. Counts: 0 blockers, 4 should-fix, 10 nits.

Paths are abbreviated. `main/` = `app/src/main/java/io/github/einekratzekatze/maps/`, `test/` = the same under
`app/src/test/java`.

## 0. Spec conformance (no findings)

Checked against `1.json` and the entities, table by table:

- **Columns, nullability, defaults:** they match §1.1-1.7 exactly. That includes the nullable `externalSublistId`,
  `addressNormalized`, `ImportantLocation.label/lat/lon/address`, `Drive.originLat/originLon`, `lastFetchedAt` and
  `rawKml`, plus the defaults `sortOrder 0`, `visible 1`, `waypointsJson '[]'` and `travelMode 'UNKNOWN'`.
- **Indices:** they match every "Indices:" line. `ExternalList.listId` and `mapId` are unique, as is
  `RecentSearch.queryNormalized`. The composite index is `(lat, lon)`.
- **Foreign keys:** every FK is `ON DELETE CASCADE` and every FK column has its own index. `ImportantLocation` has no
  FK.
- **Enums:** they are stored by `name` (`main/data/db/Enums.kt:30-43`). An unknown `TravelMode` maps to `UNKNOWN`. An
  unknown `ListType` or `ImportantKind` throws, which the spec allows ("else throws") and `TextAndJsonTest` covers.
- **Seeding:** the SQL is byte-identical to §1.8 and runs in `onCreate`. No list is seeded. `AUTOINCREMENT` plus the
  explicit ids 1/2 means customs start at 3. The test checks this through the ids.
- **DAO SQL:** it is verbatim from §2. Nothing is newer than SQLite 3.18: no upsert, window functions or `RETURNING`.
  `NOT IN (… LIMIT)` and `EXISTS` are old features.
- **`TextNormalizer`:** it follows the §3 pipeline in order, and every example in §3 is tested.
- **Normalised columns:** they are written only by `PlacesRepository` and `RecentSearchRepository`, and the
  repository tests assert them.
- **Longitude:** it is normalised on write for places, Important Locations and drive origin/destination
  (`normalizedForStorage`, `main/data/repo/PlacesRepository.kt:22-27`). The exception is waypoints (SF-4).
- **`schemaLocation`:** set with a KSP arg (`app/build.gradle:52-53`). `fallbackToDestructiveMigration` is not used.
- **Clock and dispatcher:** `Clock` is injected everywhere and `@IoDispatcher` is used for one-shot writes. Room
  `Flow`s are main-safe.

## 1. Blockers

None.

## 2. Should-fix

### SF-1: `getOrCreateDrivesList` is not atomic, so two concurrent saves create two "Drives" lists
`main/data/repo/ListRepository.kt:47-55` reads the store, inserts a list and then writes the store, with no lock.
Two callers that overlap both see `null` and both insert. **[verified]** With a store whose `get()` suspends for
50 ms, two concurrent calls returned ids `[2, 1]` and left 2 lists. The first drive then lands in a "Drives" list
that the store no longer points to.

This happens in practice when Save is double-tapped, or when the save prompt and a list picker race on first use.
It also happens on any first save that coincides with a retry. A crash between `insert` and `set` leaves a similar
orphan.

**Fix:** guard the method with a `Mutex` held by the `@Singleton`-scoped repository. Mark `ListRepository` `@Singleton`
so that every caller shares one mutex, or move the mutex into the store. Add a concurrency test like the probe
above.

### SF-2: Two `observeInBounds` tests cannot fail on the behaviour they name
`test/data/repo/PlacesRepositoryTest.kt:74-85` (`inBoundsAntimeridian`). With the centre at 179.95, the unwrapped
distance to `west` (-179.9) is 359.85, which is larger than the 0.05 to `east`. The order is therefore the same with
or without the wrap, and the mirrored assertion has the same problem.

`:109-115` (`inBoundsTiesByIdAndLimitSmall`). SQLite feeds rows to the sorter in `(lat, lon, rowid)` index order, so
`a, b` comes out even without `, id`.

**[verified]** I removed both the `CASE` wrap and the `, id` tiebreak from `PlaceDao.observeInBounds`. All 17 tests
in `PlacesRepositoryTest` still passed. The wrapped `dLon` is the SF-15 fix from `REVIEW.md`, and it currently has no
test protecting it.

**Fix:**
- **Wrap test:** put the centre at 179.95 with places at -179.99 (wrapped distance 0.06) and 179.80 (0.15). The
  expected order is `[-179.99, 179.80]`. Without the wrap it flips.
- **Ties test:** insert the tied rows in descending id order, for example by using `insertAll` with explicit ids
  `[5, 4]` at the same point. Alternatively, make the tie visible by giving the two rows different `lat` but equal
  distance, such as `lat ± d` around the centre, so that index order disagrees with id order.

### SF-3: `DriveDraft` duplicates the destination and diverges from `nav-handoff.md` `RouteInfo`
`main/data/repo/DriveRepository.kt:20-26, 37-50, 60-69`.

- **Duplicated destination:** `DriveDraft` carries `destination: RoutePoint` (whose `latLon` is nullable) *and*
  `destinationLatLon: LatLon`. `insert` ignores `destination.latLon` completely. A draft with
  `destination = RoutePoint("B", null)` and `destinationLatLon = X` reads back with `destination.latLon = X`, so it
  does not round-trip. A draft where the two disagree is accepted silently.
- **Parallel API:** schema §4 says `DriveRepository.insert(RouteInfo, listId)`. `nav-handoff.md` §4.2 defines
  `RouteInfo(origin, waypoints, destination, mode)` with `isSavable = destination.latLon != null`, and §6.2 calls
  `drives.insert(route, listId)`. When N-tasks add `RouteInfo`, `DriveDraft` becomes a second, parallel type with a
  mapping to maintain. `PendingDriveStore` already needs `RouteInfoJson`.

**Fix:** drop `destinationLatLon`. In `insert`, `require(destination.latLon != null)`, which is the `isSavable`
contract. Better still, name the type `RouteInfo` now, in `domain/RouteModels.kt`, with the fields from nav-handoff
§4.2. A thin interim type is acceptable if it is documented, but the redundant field is not.

### SF-4: `WaypointJson` crashes on encode for non-finite values and accepts invalid coordinates on decode
`main/data/db/WaypointJson.kt:14-26, 36`.

- **Encode:** `JSONObject.put("lat", NaN)` throws `JSONException("Forbidden numeric value: NaN")` **[verified]**. The
  exception escapes `DriveRepository.insert`, so Save crashes. `RoutePoint`/`LatLon` have public constructors with no
  validation, so a single bad waypoint from a future parser path takes the whole save down.
- **Waypoints not normalised:** they are written without `normalizedForStorage`, unlike origin and destination.
- **Decode:** `[{"lat":999,"lon":5000}]` decodes to `LatLon(999.0, 5000.0)` **[verified]**. That point is later fed to
  `NavTarget` and the URL builder.

**Fix:**
- In `DriveRepository.insert`, map each waypoint through
  `latLon?.let { LatLon.ofOrNull(it.lat, it.lon)?.normalizedForStorage() }`, dropping or nulling invalid ones.
- In `decode`, use `LatLon.ofOrNull(...)` instead of the constructor.
- Add NaN and out-of-range tests.

## 3. Nits

- **N-1: A place on the antimeridian is missed when the box ends at +180.** `WebMercator.kt:95-97` deliberately
  returns `east = 180.0` for a box that ends on the antimeridian. Storage turns `lon = 180` into `-180`, and
  `Daos.kt:30` (`p.lon BETWEEN :west AND 180`) does not match `-180`. `GeoBounds.contains` handles this case
  (`GeoModels.kt:47`); the SQL does not. **[verified]** In that case SQL returned 0 rows while `contains` returned
  true. The same gap is in `ImportantLocationDao.observeInBounds`. Real places almost never sit at exactly ±180, so
  this is a nit. Fix: add `OR (:east = 180 AND p.lon = -180)`, and also amend schema §2.2/§2.3 (spec-first rule).
- **N-2: `addCustomImportant` reads `MAX(sortOrder)` with raw SQL.** `PlacesRepository.kt:92-94` runs the query
  through `db.openHelper.readableDatabase`, outside the DAO and outside a transaction with the insert. Two
  concurrent adds get the same `sortOrder`. That is harmless, because `ORDER BY sortOrder, id` stays deterministic,
  but the read is untyped SQL that the Room compiler never checks. Fix: add a `@Query` for the next `sortOrder` to
  `ImportantLocationDao`, and wrap the read and the insert in `db.withTransaction`. Also amend schema §2.3.
- **N-3: `DriveRepository.observeByList` decodes JSON on the collector thread** (`DriveRepository.kt:52-53`), which
  is the main thread for UI collectors. Fix: add `.flowOn(io)`.
- **N-4: The two repository `search` methods treat a blank query differently and are dispatched inconsistently.**
  `PlacesRepository.search` (`:51-62`) is the only one-shot method not wrapped in `withContext(io)`. That is safe,
  because Room suspend DAOs are main-safe, but it is inconsistent. A blank query returns `[]` from
  `PlacesRepository.search` but *all* recents (`LIKE '%%'`) from `RecentSearchRepository.search` (`:30-32`). Either
  behaviour may be what the UI wants; state it in KDoc and test it.
- **N-5: The `limit`/`keep` arguments are not validated.** A negative `limit` becomes `LIMIT -1`, which returns all
  rows; `observeInBounds` was meant to be capped. `record(keep = 0)` deletes the whole history. Fix:
  `require(limit > 0)` and `require(keep > 0)` in the repositories.
- **N-6: Nothing guards the read-only nature of `EXTERNAL` lists.**
  - `ListRepository.create(type = EXTERNAL)` makes an `EXTERNAL` list with no `ExternalList` row, which is DB5's job.
  - `update(list)` can flip a list's `type`.
  - `PlacesRepository.addPlace` accepts an `EXTERNAL` `listId`. A place added that way would be wiped by DB5's
    `replaceContent`.

  The UI uses `observeWritable`, so this is defensive only. Consider making `create` NORMAL-only and refusing
  `addPlace` on `EXTERNAL` lists.
- **N-7: There are two `TravelMode` enums, and `RoutePoint` differs from the spec.** One is `data.db.TravelMode`
  (schema §1); the other is `navigation.TravelMode`, from the earlier batch. `toDb`/`toNavigation` bridge them and are
  tested, which is fine for now. `nav-handoff.md` §4.2 expects one enum; pick one when `RouteInfo` lands.
  `RoutePoint` (`domain/RouteModels.kt:4`) lacks `isCurrentLocation` from nav-handoff §4.2. The `DriveDraft` KDoc
  ("origin.latLon null = current location") also contradicts nav-handoff, where only `label == null && latLon == null`
  means current location.
- **N-8: The DataStore has its own `drives` file.** `DataStoreDrivesListIdStore.kt:12` creates a `drives` preferences
  file. `nav-handoff.md` §6.1 injects one `DataStore<Preferences>` into `PendingDriveStore`/`NavSettings`. Decide now
  whether there is one app DataStore binding in `di/` or one file per feature, before a second delegate gets the same
  name, which crashes at runtime with "multiple DataStores active for the same file".
- **N-9: The seeding test exercises the test's own builder.** `DbTestBase.kt:14-17` adds `SeedCallback` itself, so
  the test would still pass if `DatabaseModule.database` (`di/DatabaseModule.kt:26-28`) dropped `.addCallback(...)`.
  Fix: expose one `AppDatabase.configure(builder)` (or a `build(context, inMemory)` helper) and use it in both places.
- **N-10: Several edge cases from the spec and the code are untested.**
  - Hidden-sublist rows do not consume the `observeInBounds` limit.
  - `getOrCreateDrivesList` when the stored id points to an `EXTERNAL` list. The code handles this at `:49`.
  - `DataStoreDrivesListIdStore` has no test; a Robolectric test with a temp file is cheap.
  - `RecentSearch.search` ordering by timestamp.
  - The DAO `@Provides` in `DatabaseModule.kt:31-35` are unused. They are harmless and can stay for later ViewModels.

## 4. Scope creep (judged acceptable unless noted)

- **`addCustomImportant` / `deleteCustomImportant`:** acceptable. Schema §2.3 says that "repository asserts kind ==
  CUSTOM, label != null" for `insertCustom`, which implies a repository wrapper, and TODO 4 needs customs. See N-2 for
  the implementation.
- **`deletePlace`:** acceptable. It is a thin wrapper over the spec's `PlaceDao.delete`, and Phase 3.3 needs it.
- **`RoutePoint` in `domain/`:** acceptable. It is the nav-handoff §4.2 type, apart from N-7.
- **`DriveDraft` / `SavedDrive`:** not acceptable as is (SF-3). `SavedDrive` (id, listId, createdAt plus the route)
  is a reasonable read model once the route type is `RouteInfo`.
- **`Clock` in the repository constructors:** it goes beyond the §4 signatures, but it is correct, because tests need
  deterministic `createdAt`/`updatedAt`. Amend schema §4 to match.
- **`setImportant` clearing the address when `latLon == null`:** this matches §1.3 ("Clear Home sets lat/lon/address
  to null"). Supplying an address without coordinates is dropped silently, which is documented only in a code
  comment.

TODO.md: DB1-DB4 are ticked correctly and DB5 is left open. The 8.3 boxes are correctly left open, because they are
UI behaviour.

## 5. Verdict

**Accept with fixes.**
- The schema is a faithful, complete implementation of `schema.md` v1, the SQL is portable to SQLite 3.18, and the
  gate is green.
- **Fix before work that depends on this:** SF-1 (Drives-list race) and SF-3 (`DriveDraft` → `RouteInfo`) before
  Phase 3.4 drive capture starts; SF-4 at the same time.
- **Cheap, and it protects the SF-15 fix:** SF-2, the test rewrites.
- **Batch the nits** with DB5. N-1 and N-2 require a small `schema.md` amendment first.

**Not verified:**
- Behaviour on a device's SQLite. Robolectric uses host SQLite, as §1.2 and D23 note.
- Lint output was checked only for errors (the gate passed); individual warnings in the new files were not reviewed.
- That the Hilt graph resolves the repositories at runtime. Nothing injects them yet, so Hilt has not validated those
  bindings beyond compiling the modules.
