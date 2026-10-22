package io.github.einekratzekatze.maps.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaTest : DbTestBase() {
    private fun newList(type: ListType = ListType.NORMAL) = runBlocking {
        db.placeListDao().insert(PlaceList(name = "L", color = 1, type = type, createdAt = 1))
    }

    @Test fun seedsHomeAndWork() = runBlocking {
        val rows = db.importantLocationDao().observeAll().first()
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertEquals(listOf(ImportantKind.HOME, ImportantKind.WORK), rows.map { it.kind })
        rows.forEach { assertNull(it.label); assertNull(it.lat); assertNull(it.lon); assertNull(it.address) }
        assertEquals(listOf(0, 1), rows.map { it.sortOrder })
        assertEquals(0, db.placeListDao().observeAllWithCounts().first().size) // no list seeded
    }

    @Test fun enumsStoredByName() {
        newList(ListType.EXTERNAL)
        assertEquals(listOf("EXTERNAL"), rawQuery("SELECT type FROM PlaceList") { it.getString(0) })
        runBlocking {
            db.driveDao().insert(Drive(listId = newList(), destLat = 1.0, destLon = 2.0, travelMode = TravelMode.BIKE, createdAt = 1))
        }
        assertEquals(listOf("BIKE"), rawQuery("SELECT travelMode FROM Drive") { it.getString(0) })
        assertEquals(listOf("HOME", "WORK"), rawQuery("SELECT kind FROM ImportantLocation ORDER BY id") { it.getString(0) })
    }

    @Test fun defaultsApplied() {
        val l = newList()
        runBlocking { db.driveDao().insert(Drive(listId = l, destLat = 1.0, destLon = 2.0, createdAt = 1)) }
        assertEquals(listOf("[]"), rawQuery("SELECT waypointsJson FROM Drive") { it.getString(0) })
        assertEquals(listOf("UNKNOWN"), rawQuery("SELECT travelMode FROM Drive") { it.getString(0) })
    }

    @Test fun cascadeExternalListDeletesSublistsAndPlaces() {
        val l = newList(ListType.EXTERNAL)
        exec("INSERT INTO ExternalList(id, listId, mapId, sourceUrl) VALUES (1, $l, 'm', 'u')")
        exec("INSERT INTO ExternalSublist(id, externalListId, name, visible, sortOrder) VALUES (1, 1, 's', 1, 0)")
        runBlocking {
            db.placeDao().insert(Place(listId = l, externalSublistId = 1, name = "a", nameNormalized = "a", lat = 1.0, lon = 1.0, createdAt = 1))
            db.placeListDao().delete(l)
        }
        assertEquals(0, rawQuery("SELECT 1 FROM ExternalList") { 1 }.size)
        assertEquals(0, rawQuery("SELECT 1 FROM ExternalSublist") { 1 }.size)
        assertEquals(0, rawQuery("SELECT 1 FROM Place") { 1 }.size)
    }

    @Test fun cascadeSublistDeletesItsPlacesOnly() {
        val l = newList(ListType.EXTERNAL)
        exec("INSERT INTO ExternalList(id, listId, mapId, sourceUrl) VALUES (1, $l, 'm', 'u')")
        exec("INSERT INTO ExternalSublist(id, externalListId, name, visible, sortOrder) VALUES (1, 1, 's', 1, 0)")
        runBlocking {
            db.placeDao().insert(Place(listId = l, externalSublistId = 1, name = "a", nameNormalized = "a", lat = 1.0, lon = 1.0, createdAt = 1))
            db.placeDao().insert(Place(listId = l, name = "b", nameNormalized = "b", lat = 1.0, lon = 1.0, createdAt = 1))
        }
        exec("DELETE FROM ExternalSublist WHERE id = 1")
        assertEquals(listOf("b"), rawQuery("SELECT name FROM Place") { it.getString(0) })
    }

    @Test fun cascadeListDeletesPlacesAndDrives() = runBlocking {
        val l = newList()
        val other = newList()
        db.placeDao().insert(Place(listId = l, name = "a", nameNormalized = "a", lat = 1.0, lon = 1.0, createdAt = 1))
        db.placeDao().insert(Place(listId = other, name = "b", nameNormalized = "b", lat = 1.0, lon = 1.0, createdAt = 1))
        db.driveDao().insert(Drive(listId = l, destLat = 1.0, destLon = 2.0, createdAt = 1))
        db.driveDao().insert(Drive(listId = other, destLat = 1.0, destLon = 2.0, createdAt = 1))
        db.placeListDao().delete(l)
        assertEquals(listOf("b"), rawQuery("SELECT name FROM Place") { it.getString(0) })
        assertEquals(listOf(other), rawQuery("SELECT listId FROM Drive") { it.getLong(0) })
    }

    @Test fun uniqueMapId() {
        val a = newList(ListType.EXTERNAL)
        val b = newList(ListType.EXTERNAL)
        exec("INSERT INTO ExternalList(listId, mapId, sourceUrl) VALUES ($a, 'm', 'u')")
        val r = runCatching { exec("INSERT INTO ExternalList(listId, mapId, sourceUrl) VALUES ($b, 'm', 'u')") }
        assertEquals(true, r.isFailure)
    }
}
