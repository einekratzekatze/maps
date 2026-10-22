package io.github.einekratzekatze.maps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    /**
     * Places inside the bbox, nearest to (cLat, cLon) first, at most [limit] rows.
     * cosLat = cos(toRadians(cLat)), precomputed by the caller. dLon is wrapped into [-180, 180].
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
                 OR (:east = 180 AND p.lon = -180)
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
     * Saved-place suggestions. [contains] = "%<escaped>%", [prefix] = "<escaped>%", both already
     * normalised and escaped with '\'. Order: name-prefix matches, then (if hasCenter) distance, then name.
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

@Dao
interface ImportantLocationDao {
    @Query("SELECT * FROM ImportantLocation ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ImportantLocation>>

    /** Only rows with coordinates, inside the bbox (same wrap rule as [PlaceDao.observeInBounds]). */
    @Query("""
        SELECT * FROM ImportantLocation
        WHERE lat IS NOT NULL AND lon IS NOT NULL
          AND lat BETWEEN :south AND :north
          AND ( (:west <= :east AND lon BETWEEN :west AND :east)
             OR (:east = 180 AND lon = -180)
             OR (:west >  :east AND (lon >= :west OR lon <= :east)) )
        ORDER BY sortOrder, id
    """)
    fun observeInBounds(south: Double, west: Double, north: Double, east: Double): Flow<List<ImportantLocation>>

    @Query("UPDATE ImportantLocation SET lat = :lat, lon = :lon, address = :address, updatedAt = :now WHERE id = :id")
    suspend fun setLocation(id: Long, lat: Double?, lon: Double?, address: String?, now: Long)

    @Insert suspend fun insertCustom(row: ImportantLocation): Long

    /** Next sortOrder for a CUSTOM row: customs start at 10. */
    @Query("SELECT COALESCE(MAX(sortOrder), 9) + 1 FROM ImportantLocation WHERE kind = 'CUSTOM'")
    suspend fun nextCustomSortOrder(): Int

    @Query("DELETE FROM ImportantLocation WHERE id = :id AND kind = 'CUSTOM'")
    suspend fun deleteCustom(id: Long): Int
}

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
    fun observeWritable(): Flow<List<PlaceList>>

    @Query("SELECT * FROM PlaceList WHERE id = :id") suspend fun get(id: Long): PlaceList?
    @Insert suspend fun insert(list: PlaceList): Long
    @Update suspend fun update(list: PlaceList)
    @Query("DELETE FROM PlaceList WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface DriveDao {
    @Insert suspend fun insert(drive: Drive): Long
    @Query("SELECT * FROM Drive WHERE listId = :listId ORDER BY createdAt DESC, id DESC")
    fun observeByList(listId: Long): Flow<List<Drive>>
    @Query("SELECT * FROM Drive WHERE id = :id") suspend fun get(id: Long): Drive?
    @Query("DELETE FROM Drive WHERE id = :id") suspend fun delete(id: Long)
}

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

/** One sublist of a refreshed external list; [places] get their listId/externalSublistId set by [ExternalListDao.replaceContent]. */
data class ExternalSublistContent(val name: String, val places: List<Place>)

@Dao
interface ExternalListDao {
    @Insert suspend fun insertExternal(row: ExternalList): Long
    @Query("SELECT * FROM ExternalList WHERE listId = :listId") suspend fun getByList(listId: Long): ExternalList?
    @Query("SELECT * FROM ExternalList WHERE mapId = :mapId") suspend fun getByMapId(mapId: String): ExternalList?
    @Query("SELECT * FROM ExternalList ORDER BY id") suspend fun getAll(): List<ExternalList>

    @Query("SELECT * FROM ExternalSublist WHERE externalListId = :id ORDER BY sortOrder, id")
    fun observeSublists(id: Long): Flow<List<ExternalSublist>>
    @Query("SELECT * FROM ExternalSublist WHERE externalListId = :id ORDER BY sortOrder, id")
    suspend fun getSublists(id: Long): List<ExternalSublist>
    @Query("UPDATE ExternalSublist SET visible = :visible WHERE id = :id")
    suspend fun setSublistVisible(id: Long, visible: Boolean)
    /** Drives the "Remove this list?" prompt (TODO 5.2). */
    @Query("SELECT NOT EXISTS(SELECT 1 FROM ExternalSublist WHERE externalListId = :id AND visible = 1)")
    fun observeAllHidden(id: Long): Flow<Boolean>

    @Query("DELETE FROM Place WHERE listId = :listId") suspend fun deletePlacesOfList(listId: Long)
    @Query("DELETE FROM ExternalSublist WHERE externalListId = :id") suspend fun deleteSublists(id: Long)
    @Insert suspend fun insertSublist(row: ExternalSublist): Long
    @Insert suspend fun insertPlaces(rows: List<Place>)
    @Query("UPDATE ExternalList SET lastFetchedAt = :now, rawKml = :rawKml WHERE id = :id")
    suspend fun setFetched(id: Long, rawKml: String?, now: Long)

    /**
     * Refresh: replaces all sublists and places of one external list atomically (schema.md §2.6).
     * Sublist visibility is carried over by name (the first sublist of a name wins). Place ids
     * change on refresh.
     */
    @Transaction
    suspend fun replaceContent(externalListId: Long, listId: Long, content: List<ExternalSublistContent>, rawKml: String?, now: Long) {
        val visibleByName = getSublists(externalListId).reversed().associate { it.name to it.visible }
        deletePlacesOfList(listId)
        deleteSublists(externalListId)
        content.forEachIndexed { index, sub ->
            val sublistId = insertSublist(
                ExternalSublist(externalListId = externalListId, name = sub.name, visible = visibleByName[sub.name] ?: true, sortOrder = index),
            )
            insertPlaces(sub.places.map { it.copy(id = 0, listId = listId, externalSublistId = sublistId) })
        }
        setFetched(externalListId, rawKml, now)
    }
}

