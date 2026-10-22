package io.github.einekratzekatze.maps.data

import android.util.Log
import androidx.room.withTransaction
import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.ExternalList
import io.github.einekratzekatze.maps.data.db.ExternalSublistContent
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.Place
import io.github.einekratzekatze.maps.data.db.PlaceList
import io.github.einekratzekatze.maps.data.db.TextNormalizer
import io.github.einekratzekatze.maps.data.repo.PlacesRepository
import io.github.einekratzekatze.maps.domain.LatLon
import java.time.Clock
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds only: test data for the device checks (floating-places-bubble.md §9.4), since
 * there is no list UI yet. Start with
 * `adb shell am start -n io.github.einekratzekatze.maps.debug/io.github.einekratzekatze.maps.MainActivity --ez debug_seed true [--es debug_seed_at 48.137,11.575]`.
 */
class DebugSeeder @Inject constructor(
    private val db: AppDatabase,
    private val places: PlacesRepository,
    private val clock: Clock,
) {
    companion object {
        const val EXTRA_SEED = "debug_seed"
        const val EXTRA_AT = "debug_seed_at"
        private const val TAG = "DebugSeeder"
        private val Berlin = LatLon(52.52, 13.405)

        fun parseAt(raw: String?): LatLon {
            val parts = raw?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            return parts?.takeIf { it.size == 2 }?.let { LatLon.ofOrNull(it[0], it[1]) } ?: Berlin
        }
    }

    suspend fun seed(center: LatLon) {
        val now = clock.millis()
        val food = db.placeListDao().insert(PlaceList(name = "Debug Food", color = 0xFFE53935.toInt(), sortOrder = 90, type = ListType.NORMAL, createdAt = now))
        val sights = db.placeListDao().insert(PlaceList(name = "Debug Sights", color = 0xFF1E88E5.toInt(), sortOrder = 91, type = ListType.NORMAL, createdAt = now))
        // 20 places on a spiral from about 100 m to 5 km around the centre.
        for (i in 0 until 20) {
            val distM = 100.0 + i * 250.0
            val angle = i * 2.4
            val p = offset(center, distM * cos(angle), distM * sin(angle))
            places.addPlace(if (i % 2 == 0) food else sights, "Debug place ${i + 1}", p, "Street ${i + 1}", if (i == 0) "Notes line\n".repeat(8) else null)
        }
        // The same place in two lists: one row, "Debug Food +1", counted once.
        val twice = offset(center, 300.0, -200.0)
        places.addPlace(food, "Saved twice", twice, null, null)
        places.addPlace(sights, "Saved twice", twice, null, null)
        // Antimeridian check (D17).
        places.addPlace(sights, "Fiji (Taveuni)", LatLon(-16.79, 179.98), null, null)
        // A place in a hidden My Maps layer must never show.
        db.withTransaction {
            val listId = db.placeListDao().insert(PlaceList(name = "Debug My Maps", color = 0xFF43A047.toInt(), sortOrder = 92, type = ListType.EXTERNAL, createdAt = now))
            val extId = db.externalListDao().insertExternal(ExternalList(listId = listId, mapId = "debug-$now", sourceUrl = "debug"))
            val hiddenSpot = offset(center, -250.0, 250.0)
            val shown = offset(center, 250.0, 250.0)
            fun place(name: String, at: LatLon) = Place(listId = listId, name = name, nameNormalized = TextNormalizer.normalize(name), lat = at.lat, lon = at.lon, createdAt = now)
            db.externalListDao().replaceContent(
                extId, listId,
                listOf(ExternalSublistContent("Visible layer", listOf(place("Visible layer place", shown))), ExternalSublistContent("Hidden layer", listOf(place("HIDDEN - must not show", hiddenSpot)))),
                null, now,
            )
            db.externalListDao().getSublists(extId).single { it.name == "Hidden layer" }.let { db.externalListDao().setSublistVisible(it.id, false) }
        }
        // Home set, Work left unset.
        places.setImportant(1, offset(center, -800.0, -600.0), "Debug home")
        Log.i(TAG, "Seeded debug places around $center")
    }

    /** Small offsets in metres (east, north) on a flat approximation; fine for a few km. */
    private fun offset(c: LatLon, eastM: Double, northM: Double): LatLon {
        val dLat = northM / 111_320.0
        val dLon = eastM / (111_320.0 * cos(Math.toRadians(c.lat)))
        return LatLon(c.lat + dLat, c.lon + dLon)
    }
}
