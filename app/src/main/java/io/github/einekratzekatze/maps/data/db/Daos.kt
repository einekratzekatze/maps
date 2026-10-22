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
             OR (:west >  :east AND (lon >= :west OR lon <= :east)) )
        ORDER BY sortOrder, id
    """)
    fun observeInBounds(south: Double, west: Double, north: Double, east: Double): Flow<List<ImportantLocation>>

    @Query("UPDATE ImportantLocation SET lat = :lat, lon = :lon, address = :address, updatedAt = :now WHERE id = :id")
    suspend fun setLocation(id: Long, lat: Double?, lon: Double?, address: String?, now: Long)

    @Insert suspend fun insertCustom(row: ImportantLocation): Long

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
