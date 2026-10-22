package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.Drive
import io.github.einekratzekatze.maps.data.db.WaypointJson
import io.github.einekratzekatze.maps.data.db.toDb
import io.github.einekratzekatze.maps.data.db.toNavigation
import io.github.einekratzekatze.maps.di.IoDispatcher
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.RoutePoint
import io.github.einekratzekatze.maps.navigation.TravelMode
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What gets saved for a drive. [origin].latLon null = "current location at replay time". */
data class DriveDraft(
    val origin: RoutePoint,
    val waypoints: List<RoutePoint>,
    val destination: RoutePoint,
    val destinationLatLon: LatLon,
    val mode: TravelMode?,
)

data class SavedDrive(val id: Long, val listId: Long, val route: DriveDraft, val createdAt: Long)

class DriveRepository @Inject constructor(
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val drives get() = db.driveDao()

    suspend fun insert(route: DriveDraft, listId: Long): Long = withContext(io) {
        val dest = route.destinationLatLon.normalizedForStorage()
        val origin = route.origin.latLon?.normalizedForStorage()
        drives.insert(
            Drive(
                listId = listId,
                originLabel = route.origin.label, originLat = origin?.lat, originLon = origin?.lon,
                destLabel = route.destination.label, destLat = dest.lat, destLon = dest.lon,
                waypointsJson = WaypointJson.encode(route.waypoints),
                travelMode = route.mode.toDb(),
                createdAt = clock.millis(),
            ),
        )
    }

    fun observeByList(listId: Long): Flow<List<SavedDrive>> =
        drives.observeByList(listId).map { rows -> rows.map { it.toSaved() } }

    /** Replay lookup. */
    suspend fun get(id: Long): SavedDrive? = withContext(io) { drives.get(id)?.toSaved() }

    suspend fun delete(id: Long) = withContext(io) { drives.delete(id) }

    private fun Drive.toSaved() = SavedDrive(
        id = id, listId = listId, createdAt = createdAt,
        route = DriveDraft(
            origin = RoutePoint(originLabel, LatLon.ofOrNull(originLat, originLon)),
            waypoints = WaypointJson.decode(waypointsJson),
            destination = RoutePoint(destLabel, LatLon(destLat, destLon)),
            destinationLatLon = LatLon(destLat, destLon),
            mode = travelMode.toNavigation(),
        ),
    )
}
