package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.ImportantLocation
import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import io.github.einekratzekatze.maps.data.db.PlaceDetailRow
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import kotlinx.coroutines.flow.Flow

/** The read side of [PlacesRepository] that the map features observe; an interface so their tests can use fakes. */
interface PlacesQueries {
    fun observeInBounds(bounds: GeoBounds, center: LatLon, limit: Int): Flow<List<NearbyPlaceRow>>
    fun observeHasAnyPlaces(): Flow<Boolean>
    fun observeDetail(id: Long): Flow<PlaceDetailRow?>
    fun observeImportant(): Flow<List<ImportantLocation>>
    fun observeImportantInBounds(bounds: GeoBounds): Flow<List<ImportantLocation>>
    /** Name/address matches, prefix matches first, then nearest to [center]. Blank returns nothing. */
    suspend fun search(query: String, center: LatLon?, limit: Int): List<NearbyPlaceRow>
}
