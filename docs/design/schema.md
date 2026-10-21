# Design: Room schema (TODO Phase 3.1, 4, 5, 8.3)

Status: ready for implementation once Phase 0.2/0.3 build (Room, KSP, Hilt) is green ·
Owner: TBD · Supersedes: the entity lists in TODO 3.1, the "schema additions" in
`floating-places-bubble.md` §5.4, `nav-handoff.md` §6 and `custom-search-field.md` §4.

This file is the **only** place the database is defined. The feature specs reference the
DAO methods below by name. A feature that needs a new column or query changes this file
first, in its own PR, and bumps nothing else: we are at schema version 1 until the first
release, so there is no migration yet (see §7).

---

## 0. Rules that apply to every table

| Rule | Why |
|---|---|
| `@Database(version = 1, exportSchema = true)`; the schema JSON is committed under `app/schemas/` | Reviewable diffs; the first real migration needs the v1 JSON. How `room.schemaLocation` is passed is the Gradle agent's call (KSP arg or Room Gradle plugin). |
| Enums are stored as their **`name`** (`TEXT`), via `@TypeConverters(EnumConverters::class)`; never by ordinal | SQL in this file compares string literals (`kind = 'HOME'`). An ordinal converter would break them silently, and reordering an enum would corrupt data. Reading an unknown name maps to the enum's `UNKNOWN`/fallback value where one exists, else throws (covered by a test). |
| Booleans are `INTEGER` 0/1 (Room default) | |
| Timestamps are `INTEGER` epoch milliseconds (UTC), field names end in `At` | |
| Colours are `INTEGER` ARGB | |
| Coordinates are `REAL` WGS84 degrees, `lat` in [-90, 90], `lon` in [-180, 180) (normalised on write) | The bbox query assumes normalised longitudes. |
| Foreign keys are declared with `ForeignKey(..., onDelete = CASCADE)` and every FK column has its own `Index` | Room enables `PRAGMA foreign_keys` itself; an FK without an index triggers a Room warning and full scans on delete. |
| No SQL newer than **SQLite 3.18** (Android 8.0, our `minSdk 26`) | No `ON CONFLICT … DO UPDATE` (3.24), no window functions (3.25), no `RETURNING` (3.35). |
| Entities live in `data/db/`; domain types (`LatLon`, `MapCamera`, …) stay in `domain/GeoModels.kt` and are **not** Room entities or `Parcelable` | Domain stays free of Android and Room; mapping happens in repositories. |
| Text that is searched has a `…Normalized` twin column maintained by the repository on every write (§3) | SQLite `LIKE` is case-insensitive for ASCII only. |

---

## 1. Entities

```kotlin
enum class ListType { NORMAL, EXTERNAL }
enum class ImportantKind { HOME, WORK, CUSTOM }
enum class TravelMode { DRIVE, WALK, BIKE, TRANSIT, UNKNOWN }
```

There is **no** `IMPORTANT_LOCATION` list type any more: Important Locations have their own
table (§1.3). This matches PROJECT_GOALS ("stored separately; survive list clears") and lets
Home/Work exist with no coordinates.

### 1.1 `PlaceList`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `name` | TEXT | no | User-visible. Never used as a key. |
| `color` | INTEGER | no | ARGB |
| `sortOrder` | INTEGER | no | default 0 |
| `type` | TEXT (`ListType`) | no | `NORMAL` or `EXTERNAL` |
| `createdAt` | INTEGER | no | |

Indices: `sortOrder`.

A list holds Places and Drives. The "Drives" list that drive capture creates on first save is
an ordinary `NORMAL` list; the repository finds it by id stored in DataStore
(`drives_default_list_id`), not by name (names are localised and user-editable).

### 1.2 `Place`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `listId` | INTEGER FK → `PlaceList.id` CASCADE | no | |
| `externalSublistId` | INTEGER FK → `ExternalSublist.id` CASCADE | **yes** | Non-null only for places imported from My Maps. Used to hide places of invisible sublists (TODO 5.2). |
| `name` | TEXT | no | |
| `nameNormalized` | TEXT | no | §3; maintained by `PlacesRepository` |
| `lat` | REAL | no | |
| `lon` | REAL | no | |
| `address` | TEXT | yes | |
| `addressNormalized` | TEXT | yes | §3; null iff `address` is null |
| `notes` | TEXT | yes | |
| `createdAt` | INTEGER | no | |

Indices: `listId`, `externalSublistId`, `(lat, lon)`, `nameNormalized`.

The composite `(lat, lon)` index gives a range scan on `lat` only; `lon` is filtered per row.
That is acceptable for the expected size (≤ 10 000 places). Measure on a device (D23 in the
bubble spec), not under Robolectric, which uses host SQLite.

### 1.3 `ImportantLocation`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | Home is always id **1**, Work id **2** (seeded, §1.8) |
| `kind` | TEXT (`ImportantKind`) | no | `HOME`, `WORK` or `CUSTOM` |
| `label` | TEXT | yes | **null for HOME/WORK** (UI shows the localised "Home"/"Work" string resource); required for CUSTOM (enforced in repository) |
| `lat` | REAL | **yes** | null = not set yet |
| `lon` | REAL | **yes** | both null or both non-null (repository invariant, tested) |
| `address` | TEXT | yes | Used for the directions pre-fill (TODO 4) |
| `sortOrder` | INTEGER | no | HOME 0, WORK 1, customs from 10 up |
| `updatedAt` | INTEGER | no | |

Indices: `kind`, `sortOrder`.

- HOME and WORK rows are never deleted. "Clear Home" sets `lat/lon/address` to null.
  `deleteCustom` refuses non-CUSTOM rows in SQL (`AND kind = 'CUSTOM'`).
- "Survive list clears": no FK to `PlaceList`, so deleting any list leaves these rows alone.
- "Set as Home" from a place copies `lat/lon/address` (no FK to `Place`: deleting the place
  must not unset Home).

### 1.4 `Drive`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `listId` | INTEGER FK → `PlaceList.id` CASCADE | no | |
| `originLabel` | TEXT | yes | |
| `originLat` | REAL | **yes** | `originLat/Lon` both null = "current location at replay time" |
| `originLon` | REAL | **yes** | |
| `destLabel` | TEXT | yes | |
| `destLat` | REAL | no | A route without destination coordinates is not savable (nav-handoff §4.2) |
| `destLon` | REAL | no | |
| `waypointsJson` | TEXT | no | default `'[]'`; format below |
| `travelMode` | TEXT (`TravelMode`) | no | default `'UNKNOWN'` |
| `createdAt` | INTEGER | no | |

Indices: `listId`.

Waypoint JSON (written and read only by `WaypointJson.encode/decode`, pure Kotlin with
`org.json`, unit-tested; malformed JSON decodes to an empty list and logs WARN):

```json
[{"label": "Café Einstein", "lat": 52.5, "lon": 13.4}, {"label": null, "lat": 52.6, "lon": 13.5}]
```

`label` may be null; `lat`/`lon` are both present or both absent. Order = route order,
origin and destination excluded.

### 1.5 `ExternalList`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `listId` | INTEGER FK → `PlaceList.id` CASCADE, **unique** | no | 1:1 with a `PlaceList` of type `EXTERNAL` |
| `mapId` | TEXT, **unique** | no | the My Maps `mid`; prevents importing the same map twice |
| `sourceUrl` | TEXT | no | the URL the user pasted ("Edit source", TODO 5.3) |
| `lastFetchedAt` | INTEGER | yes | null until the first successful fetch |
| `rawKml` | TEXT | yes | Last fetched KML. Can be large; if a device test shows cursor-window problems (> 2 MB row), move it to a file in `filesDir` and store the path instead. |

Indices: `listId` (unique), `mapId` (unique).

### 1.6 `ExternalSublist`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `externalListId` | INTEGER FK → `ExternalList.id` CASCADE | no | |
| `name` | TEXT | no | KML `<Folder><name>` |
| `visible` | INTEGER (Boolean) | no | default 1 |
| `sortOrder` | INTEGER | no | KML document order |

Indices: `externalListId`.

Deleting the `PlaceList` cascades: `ExternalList` → `ExternalSublist` → `Place` (through
`externalSublistId`), and `Place` also through `listId`.

### 1.7 `RecentSearch`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | INTEGER PK autoGenerate | no | |
| `query` | TEXT | no | as typed (trimmed) |
| `queryNormalized` | TEXT, **unique** | no | §3 |
| `timestamp` | INTEGER | no | |

Indices: `queryNormalized` (unique), `timestamp`.

### 1.8 Seeding

`RoomDatabase.Callback.onCreate` (runs once, when the file is created) inserts:

```sql
INSERT INTO ImportantLocation(id, kind, label, lat, lon, address, sortOrder, updatedAt) VALUES (1, 'HOME', NULL, NULL, NULL, NULL, 0, 0);
INSERT INTO ImportantLocation(id, kind, label, lat, lon, address, sortOrder, updatedAt) VALUES (2, 'WORK', NULL, NULL, NULL, NULL, 1, 0);
```

No `PlaceList` is seeded.

---

## 2. DAO queries

All `observe…` methods return `Flow` and are main-safe. One-shot methods are `suspend`.
Multi-statement writes are `@Transaction` methods on the DAO (or `withTransaction` in the
repository).

### 2.1 Row types

```kotlin
data class NearbyPlaceRow(
    val id: Long, val name: String, val nameNormalized: String,
    val lat: Double, val lon: Double,
    val listId: Long, val listName: String, val listColor: Int,
)

data class PlaceDetailRow(          // focused-place card (bubble §5.8) and place detail (3.3)
    val id: Long, val name: String, val lat: Double, val lon: Double,
    val address: String?, val notes: String?,
    val listId: Long, val listName: String, val listColor: Int, val listType: ListType,
)

data class ListWithCount(@Embedded val list: PlaceList, val itemCount: Int)
```

### 2.2 `PlaceDao`

```kotlin
@Dao
interface PlaceDao {
    /**
     * Places inside the bbox, nearest to (cLat, cLon) first, at most [limit] rows.
     * cosLat = cos(toRadians(cLat)), precomputed by the caller (SQLite has no cos()).
     * The ORDER BY is what makes "first 100 = 100 nearest" true when more than [limit]
     * rows match; without it SQLite returns an arbitrary subset.
     * Distance is an equirectangular approximation: fine for ordering at viewport scale.
     * dLon is wrapped into [-180, 180] so the antimeridian case orders correctly.
     */
    @Query("""
        SELECT id, name, nameNormalized, lat, lon, listId, listName, listColor FROM (
            SELECT p.id, p.name, p.nameNormalized, p.lat, p.lon,
                   l.id AS listId, l.name AS listName, l.color AS listColor,
                   (p.lat - :cLat) AS dLat,
                   (CASE WHEN p.lon - :cLon > 180  THEN p.lon - :cLon - 360
                         WHEN p.lon - :cLon < -180 THEN p.lon - :cLon + 360
                         ELSE p.lon - :cLon END) * :cosLat AS dLonScaled
            FROM Place p
            JOIN PlaceList l ON l.id = p.listId
            LEFT JOIN ExternalSublist s ON s.id = p.externalSublistId
            WHERE (s.id IS NULL OR s.visible = 1)
              AND p.lat BETWEEN :south AND :north
              AND ( (:west <= :east AND p.lon BETWEEN :west AND :east)
                 OR (:west >  :east AND (p.lon >= :west OR p.lon <= :east)) )
        )
        ORDER BY dLat * dLat + dLonScaled * dLonScaled, id
        LIMIT :limit
    """)
    fun observeInBounds(
        south: Double, west: Double, north: Double, east: Double,
        cLat: Double, cLon: Double, cosLat: Double, limit: Int,
    ): Flow<List<NearbyPlaceRow>>

    @Query("SELECT EXISTS(SELECT 1 FROM Place)")
    fun observeHasAnyPlaces(): Flow<Boolean>

    @Query("""
        SELECT p.id, p.name, p.lat, p.lon, p.address, p.notes,
               l.id AS listId, l.name AS listName, l.color AS listColor, l.type AS listType
        FROM Place p JOIN PlaceList l ON l.id = p.listId WHERE p.id = :id
    """)
    fun observeDetail(id: Long): Flow<PlaceDetailRow?>

    @Query("SELECT * FROM Place WHERE listId = :listId ORDER BY nameNormalized, id")
    fun observeByList(listId: Long): Flow<List<Place>>

    /**
     * Saved-place suggestions (custom search). [contains] = "%<escaped>%", [prefix] = "<escaped>%",
     * both already normalised (§3) and escaped with '\' for %, _ and \.
     * Order: name-prefix matches first, then (if hasCenter) distance, then name.
     */
    @Query("""
        SELECT p.id, p.name, p.nameNormalized, p.lat, p.lon,
               l.id AS listId, l.name AS listName, l.color AS listColor
        FROM Place p
        JOIN PlaceList l ON l.id = p.listId
        LEFT JOIN ExternalSublist s ON s.id = p.externalSublistId
        WHERE (s.id IS NULL OR s.visible = 1)
          AND (p.nameNormalized LIKE :contains ESCAPE '\' OR p.addressNormalized LIKE :contains ESCAPE '\')
        ORDER BY CASE WHEN p.nameNormalized LIKE :prefix ESCAPE '\' THEN 0 ELSE 1 END,
                 CASE WHEN :hasCenter THEN (p.lat - :cLat) * (p.lat - :cLat)
                                         + (p.lon - :cLon) * (p.lon - :cLon) * :cosLat * :cosLat
                      ELSE 0 END,
                 p.nameNormalized, p.id
        LIMIT :limit
    """)
    suspend fun search(
        contains: String, prefix: String,
        hasCenter: Boolean, cLat: Double, cLon: Double, cosLat: Double, limit: Int,
    ): List<NearbyPlaceRow>

    @Insert suspend fun insert(place: Place): Long
    @Insert suspend fun insertAll(places: List<Place>)
    @Update suspend fun update(place: Place)
    @Query("DELETE FROM Place WHERE id = :id") suspend fun delete(id: Long)
}
```

The address column is not returned by `search` (the suggestion row shows the list); if the
UI needs it later, add it to a separate row type rather than to `NearbyPlaceRow`.
`search` does not wrap `dLon` at the antimeridian: suggestions only need a rough order.

### 2.3 `ImportantLocationDao`

```kotlin
@Dao
interface ImportantLocationDao {
    @Query("SELECT * FROM ImportantLocation ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ImportantLocation>>             // = PlacesRepository.observeImportant()

    /** Only rows with coordinates, inside the bbox (same wrap rule as observeInBounds). For markers. */
    @Query("""
        SELECT * FROM ImportantLocation
        WHERE lat IS NOT NULL AND lon IS NOT NULL
          AND lat BETWEEN :south AND :north
          AND ( (:west <= :east AND lon BETWEEN :west AND :east)
             OR (:west >  :east AND (lon >= :west OR lon <= :east)) )
        ORDER BY sortOrder, id
    """)
    fun observeInBounds(south: Double, west: Double, north: Double, east: Double): Flow<List<ImportantLocation>>

    @Query("UPDATE ImportantLocation SET lat = :lat, lon = :lon, address = :address, updatedAt = :now WHERE id = :id")
    suspend fun setLocation(id: Long, lat: Double?, lon: Double?, address: String?, now: Long)

    @Insert suspend fun insertCustom(row: ImportantLocation): Long     // repository asserts kind == CUSTOM, label != null

    @Query("DELETE FROM ImportantLocation WHERE id = :id AND kind = 'CUSTOM'")
    suspend fun deleteCustom(id: Long): Int
}
```

### 2.4 `PlaceListDao`

```kotlin
@Dao
interface PlaceListDao {
    @Query("""
        SELECT l.*,
               (SELECT COUNT(*) FROM Place p WHERE p.listId = l.id)
             + (SELECT COUNT(*) FROM Drive d WHERE d.listId = l.id) AS itemCount
        FROM PlaceList l ORDER BY l.sortOrder, l.id
    """)
    fun observeAllWithCounts(): Flow<List<ListWithCount>>

    @Query("SELECT * FROM PlaceList WHERE type = 'NORMAL' ORDER BY sortOrder, id")
    fun observeWritable(): Flow<List<PlaceList>>               // list pickers (save drive, save place)

    @Query("SELECT * FROM PlaceList WHERE id = :id") suspend fun get(id: Long): PlaceList?
    @Insert suspend fun insert(list: PlaceList): Long
    @Update suspend fun update(list: PlaceList)
    @Query("DELETE FROM PlaceList WHERE id = :id") suspend fun delete(id: Long)
}
```

### 2.5 `DriveDao`

```kotlin
@Dao
interface DriveDao {
    @Insert suspend fun insert(drive: Drive): Long
    @Query("SELECT * FROM Drive WHERE listId = :listId ORDER BY createdAt DESC, id DESC")
    fun observeByList(listId: Long): Flow<List<Drive>>
    @Query("SELECT * FROM Drive WHERE id = :id") suspend fun get(id: Long): Drive?
    @Query("DELETE FROM Drive WHERE id = :id") suspend fun delete(id: Long)
}
```

### 2.6 `ExternalListDao`

```kotlin
@Dao
interface ExternalListDao {
    @Insert suspend fun insertExternal(row: ExternalList): Long
    @Query("SELECT * FROM ExternalList WHERE listId = :listId") suspend fun getByList(listId: Long): ExternalList?
    @Query("SELECT * FROM ExternalSublist WHERE externalListId = :id ORDER BY sortOrder, id")
    fun observeSublists(id: Long): Flow<List<ExternalSublist>>
    @Query("UPDATE ExternalSublist SET visible = :visible WHERE id = :id")
    suspend fun setSublistVisible(id: Long, visible: Boolean)
    @Query("SELECT NOT EXISTS(SELECT 1 FROM ExternalSublist WHERE externalListId = :id AND visible = 1)")
    fun observeAllHidden(id: Long): Flow<Boolean>            // drives the "Remove this list?" prompt (TODO 5.2)

    /**
     * Refresh: replaces all sublists and places of one external list atomically.
     * Sublist visibility is carried over by name. Place ids change on refresh
     * (a focused-place card on an external place falls back to Nearby, bubble §5.8).
     */
    @Transaction
    suspend fun replaceContent(externalListId: Long, listId: Long, content: ParsedKml, now: Long)
}
```

### 2.7 `RecentSearchDao`

```kotlin
@Dao
interface RecentSearchDao {
    /** REPLACE on the unique queryNormalized: re-submitting moves the entry to the top (new id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOrReplace(row: RecentSearch)

    @Query("""
        DELETE FROM RecentSearch WHERE id NOT IN
            (SELECT id FROM RecentSearch ORDER BY timestamp DESC, id DESC LIMIT :keep)
    """)
    suspend fun trimTo(keep: Int)

    @Transaction suspend fun record(row: RecentSearch, keep: Int = 20) { insertOrReplace(row); trimTo(keep) }

    @Query("SELECT * FROM RecentSearch ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSearch>>

    @Query("""
        SELECT * FROM RecentSearch WHERE queryNormalized LIKE :contains ESCAPE '\'
        ORDER BY timestamp DESC, id DESC LIMIT :limit
    """)
    suspend fun search(contains: String, limit: Int): List<RecentSearch>

    @Query("DELETE FROM RecentSearch WHERE id = :id") suspend fun delete(id: Long)
}
```

(`ON CONFLICT … DO UPDATE` would keep the id but needs SQLite 3.24; see §0.)

---

## 3. Text normalisation

```kotlin
object TextNormalizer {
    /** Pure, [unit-testable]. */
    fun normalize(s: String): String
    /** Escapes \, % and _ with '\' for use with LIKE … ESCAPE '\'. */
    fun escapeLike(s: String): String
}
```

`normalize`: `Normalizer.normalize(s, NFD)` → remove `\p{Mn}` → `lowercase(Locale.ROOT)` →
`ß` → `ss` → collapse whitespace runs to one space → trim. Tests: "MÜNCHEN" = "munchen",
"Straße" = "strasse", "Café  Einstein " = "cafe einstein", Turkish "İ" does not crash and
yields "i", `%`/`_`/`\` escaped.

`PlacesRepository` and `RecentSearchRepository` are the only writers of `…Normalized`
columns. A Robolectric test inserts through the repository and asserts the columns.

---

## 4. Repositories (public surface used by the feature specs)

```kotlin
class PlacesRepository @Inject constructor(db: AppDatabase, @IoDispatcher io: CoroutineDispatcher) {
    fun observeInBounds(bounds: GeoBounds, center: LatLon, limit: Int): Flow<List<NearbyPlaceRow>>
    fun observeHasAnyPlaces(): Flow<Boolean>
    fun observeDetail(id: Long): Flow<PlaceDetailRow?>
    fun observeImportant(): Flow<List<ImportantLocation>>
    fun observeImportantInBounds(bounds: GeoBounds): Flow<List<ImportantLocation>>
    suspend fun search(query: String, center: LatLon?, limit: Int): List<NearbyPlaceRow>
    suspend fun addPlace(listId: Long, name: String, latLon: LatLon, address: String?, notes: String?): Long
    suspend fun setImportant(id: Long, latLon: LatLon?, address: String?)
}
class ListRepository   // lists, counts, getOrCreateDrivesList()
class DriveRepository  // insert(RouteInfo, listId), replay lookup
class RecentSearchRepository
class ExternalListRepository
```

`observeInBounds` computes `cosLat = cos(toRadians(center.lat))` and passes it to the DAO.

---

## 5. Tests (`PlaceDaoTest`, `ImportantLocationDaoTest`, … — Robolectric + in-memory Room) [unit-testable]

- `observeInBounds`: inside/outside; hidden sublist excluded; antimeridian (lon 179.9 and
  -179.9, `west > east`); **ordering**: 600 rows in bbox, limit 501 → the returned rows are
  the 501 nearest (compare with a Kotlin sort), and row 0 is the nearest; ties by id.
- `ImportantLocation`: seeded HOME (id 1) and WORK (id 2) exist with null coords on a fresh
  DB; `setLocation(1, null, null, null, …)` works; `deleteCustom(1)` returns 0; deleting
  every `PlaceList` leaves HOME/WORK intact.
- Cascades: delete an `EXTERNAL` list → its sublists and places are gone; delete a sublist
  → its places are gone.
- Enum storage: insert a list with `ListType.EXTERNAL`; raw `SELECT type FROM PlaceList`
  returns `"EXTERNAL"`.
- `RecentSearchDao.record`: 22 inserts → 20 rows; same query twice → 1 row, newest timestamp.
- `search`: "munchen" finds "MÜNCHEN"; input `50%` matches only a literal `50%`.
- `WaypointJson`: round trip; malformed → empty list.
- Timing is **not** measured here (host SQLite). See bubble D23.

## 6. Tasks

| ID | Task | Tag |
|---|---|---|
| DB1 | Entities, converters, `AppDatabase` v1 + seeding callback + `TextNormalizer` + `WaypointJson`; schema JSON committed | [unit-testable] (Robolectric) |
| DB2 | `PlaceDao` + `ImportantLocationDao` + `PlacesRepository` | [unit-testable] (Robolectric) |
| DB3 | `PlaceListDao`, `DriveDao`, `ListRepository`, `DriveRepository` | [unit-testable] (Robolectric) |
| DB4 | `RecentSearchDao` + repository | [unit-testable] (Robolectric) |
| DB5 | `ExternalListDao` + `replaceContent` (with Phase 5.1) | [unit-testable] (Robolectric) |

DB1 → DB2 → (DB3, DB4 in parallel); DB5 with Phase 5. Bubble B3 = DB2.

## 7. Versioning

Version 1 until the first public release. Before that, schema changes edit this file and the
entities in place and reinstall (`fallbackToDestructiveMigration` is **not** used in release
builds; in debug builds it is acceptable until release). After release every change needs a
`Migration` and a `MigrationTestHelper` test.
