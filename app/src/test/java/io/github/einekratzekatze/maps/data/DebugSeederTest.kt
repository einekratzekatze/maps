package io.github.einekratzekatze.maps.data

import io.github.einekratzekatze.maps.data.db.DbTestBase
import io.github.einekratzekatze.maps.data.repo.PlacesRepository
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugSeederTest : DbTestBase() {
    @Test fun seedsWhatTheDeviceChecksNeed() = runBlocking {
        val places = PlacesRepository(db, Dispatchers.Unconfined, Clock.systemUTC())
        DebugSeeder(db, places, Clock.systemUTC()).seed(LatLon(48.137, 11.575))
        val all = places.observeInBounds(GeoBounds(-90.0, -180.0, 90.0, 180.0), LatLon(48.137, 11.575), 501).first().map { it.name }
        assertEquals(20, all.count { it.startsWith("Debug place") })
        assertEquals(2, all.count { it == "Saved twice" })
        assertTrue("Fiji (Taveuni)" in all)
        assertTrue("Visible layer place" in all)
        assertFalse(all.any { it.startsWith("HIDDEN") })
        assertNotNull(places.observeImportant().first().single { it.id == 1L }.lat)
    }

    @Test fun parseAt() {
        assertEquals(LatLon(48.1, 11.5), DebugSeeder.parseAt("48.1, 11.5"))
        assertEquals(LatLon(52.52, 13.405), DebugSeeder.parseAt(null))
        assertEquals(LatLon(52.52, 13.405), DebugSeeder.parseAt("999,1"))
    }
}
