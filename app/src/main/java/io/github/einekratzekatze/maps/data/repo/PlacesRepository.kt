package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.data.db.ImportantLocation
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import io.github.einekratzekatze.maps.data.db.Place
import io.github.einekratzekatze.maps.data.db.PlaceDetailRow
import io.github.einekratzekatze.maps.data.db.TextNormalizer
import io.github.einekratzekatze.maps.di.IoDispatcher
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.normalizeLon
import java.time.Clock
import javax.inject.Inject
import androidx.room.withTransaction
import kotlin.math.cos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Normalises the longitude into [-180, 180) on write (schema.md section 0); rejects an invalid latitude. */
internal fun LatLon.normalizedForStorage(): LatLon {
    require(lat.isFinite() && lat in -90.0..90.0) { "lat out of range: $lat" }
    require(lon.isFinite()) { "lon not finite: $lon" }
    // Leave in-range values bit-exact; normalizeLon's modulo arithmetic would add rounding noise.
    return LatLon(lat, if (lon >= -180.0 && lon < 180.0) lon else normalizeLon(lon))
}

/** Like [normalizedForStorage] but returns null instead of throwing (for optional points such as waypoints). */
internal fun LatLon.normalizedOrNull(): LatLon? =
    if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0) normalizedForStorage() else null

private fun cosLat(lat: Double): Double = cos(Math.toRadians(lat))

class PlacesRepository @Inject constructor(
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val places get() = db.placeDao()
    private val important get() = db.importantLocationDao()

    fun observeInBounds(bounds: GeoBounds, center: LatLon, limit: Int): Flow<List<NearbyPlaceRow>> {
        require(limit > 0) { "limit must be positive" }
        return places.observeInBounds(
            bounds.south, bounds.west, bounds.north, bounds.east,
            center.lat, center.lon, cosLat(center.lat), limit,
        )
    }

    fun observeHasAnyPlaces(): Flow<Boolean> = places.observeHasAnyPlaces()
    fun observeDetail(id: Long): Flow<PlaceDetailRow?> = places.observeDetail(id)
    fun observeImportant(): Flow<List<ImportantLocation>> = important.observeAll()
    fun observeImportantInBounds(bounds: GeoBounds): Flow<List<ImportantLocation>> =
        important.observeInBounds(bounds.south, bounds.west, bounds.north, bounds.east)

    /** A blank query returns no places (suggestions need input; recents cover the empty field). */
    suspend fun search(query: String, center: LatLon?, limit: Int): List<NearbyPlaceRow> {
        require(limit > 0) { "limit must be positive" }
        val n = TextNormalizer.normalize(query)
        if (n.isEmpty()) return emptyList()
        val esc = TextNormalizer.escapeLike(n)
        return places.search(
            contains = "%$esc%", prefix = "$esc%",
            hasCenter = center != null,
            cLat = center?.lat ?: 0.0, cLon = center?.lon ?: 0.0,
            cosLat = center?.let { cosLat(it.lat) } ?: 1.0,
            limit = limit,
        )
    }

    suspend fun addPlace(listId: Long, name: String, latLon: LatLon, address: String?, notes: String?): Long =
        withContext(io) {
            val p = latLon.normalizedForStorage()
            val list = requireNotNull(db.placeListDao().get(listId)) { "no such list: $listId" }
            require(list.type == ListType.NORMAL) { "EXTERNAL lists are read-only" }
            places.insert(
                Place(
                    listId = listId, name = name, nameNormalized = TextNormalizer.normalize(name),
                    lat = p.lat, lon = p.lon,
                    address = address, addressNormalized = address?.let(TextNormalizer::normalize),
                    notes = notes, createdAt = clock.millis(),
                ),
            )
        }

    suspend fun deletePlace(id: Long) = withContext(io) { places.delete(id) }

    /** Sets or clears (latLon == null) Home/Work/custom coordinates. Lat and lon are always both set or both null. */
    suspend fun setImportant(id: Long, latLon: LatLon?, address: String?) = withContext(io) {
        val p = latLon?.normalizedForStorage()
        // Clearing coordinates also clears the address (an address without a point is meaningless).
        important.setLocation(id, p?.lat, p?.lon, if (p == null) null else address, clock.millis())
    }

    /** Adds a CUSTOM important location; [label] must not be blank. */
    suspend fun addCustomImportant(label: String, latLon: LatLon?, address: String?): Long = withContext(io) {
        require(label.isNotBlank()) { "custom important location needs a label" }
        val p = latLon?.normalizedForStorage()
        val now = clock.millis()
        db.withTransaction {
            important.insertCustom(
                ImportantLocation(
                    kind = ImportantKind.CUSTOM, label = label.trim(), lat = p?.lat, lon = p?.lon,
                    address = if (p == null) null else address,
                    sortOrder = important.nextCustomSortOrder(), updatedAt = now,
                ),
            )
        }
    }

    /** True if a CUSTOM row was deleted; HOME/WORK are never deleted. */
    suspend fun deleteCustomImportant(id: Long): Boolean = withContext(io) { important.deleteCustom(id) > 0 }
}
