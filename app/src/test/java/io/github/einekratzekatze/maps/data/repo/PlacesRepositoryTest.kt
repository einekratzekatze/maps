package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.DbTestBase
import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.Place
import io.github.einekratzekatze.maps.data.db.PlaceList
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlacesRepositoryTest : DbTestBase() {
    private lateinit var repo: PlacesRepository
    private var listId = 0L
    private val clock = Clock.fixed(Instant.ofEpochMilli(12345), ZoneOffset.UTC)

    @Before fun setUp() = runBlocking {
        repo = PlacesRepository(db, Dispatchers.Unconfined, clock)
        listId = db.placeListDao().insert(PlaceList(name = "L", color = 7, type = ListType.NORMAL, createdAt = 1))
        Unit
    }

    private suspend fun add(name: String, lat: Double, lon: Double, address: String? = null) =
        repo.addPlace(listId, name, LatLon(lat, lon), address, null)

    private fun bounds(s: Double, w: Double, n: Double, e: Double) = GeoBounds(s, w, n, e)

    @Test fun addPlaceWritesNormalisedColumnsAndWrapsLongitude() = runBlocking {
        val id = add("MÜNCHEN Straße", 48.0, 190.0, "Café  Allee")
        val p = db.placeDao().observeByList(listId).first().single()
        assertEquals(id, p.id)
        assertEquals("munchen strasse", p.nameNormalized)
        assertEquals("cafe allee", p.addressNormalized)
        assertEquals(-170.0, p.lon, 1e-9)
        assertEquals(12345L, p.createdAt)
        add("noaddr", 1.0, 1.0)
        assertNull(db.placeDao().observeByList(listId).first().first { it.name == "noaddr" }.addressNormalized)
    }

    @Test fun addPlaceRejectsBadLatitude() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { add("x", 91.0, 0.0) } }
    }

    @Test fun inBoundsFiltersAndHiddenSublist() = runBlocking {
        add("in", 10.0, 10.0)
        add("out-lat", 20.0, 10.0)
        add("out-lon", 10.0, 20.0)
        exec("INSERT INTO ExternalList(id, listId, mapId, sourceUrl) VALUES (1, $listId, 'm', 'u')")
        exec("INSERT INTO ExternalSublist(id, externalListId, name, visible, sortOrder) VALUES (1, 1, 'vis', 1, 0), (2, 1, 'hid', 0, 1)")
        db.placeDao().insert(Place(listId = listId, externalSublistId = 1, name = "vis", nameNormalized = "vis", lat = 10.1, lon = 10.1, createdAt = 1))
        db.placeDao().insert(Place(listId = listId, externalSublistId = 2, name = "hid", nameNormalized = "hid", lat = 10.2, lon = 10.2, createdAt = 1))
        val rows = repo.observeInBounds(bounds(5.0, 5.0, 15.0, 15.0), LatLon(10.0, 10.0), 100).first()
        assertEquals(listOf("in", "vis"), rows.map { it.name })
        assertEquals(7, rows[0].listColor)
        assertEquals("L", rows[0].listName)
    }

    @Test fun inBoundsAntimeridianOrderUsesWrappedDistance() = runBlocking {
        add("wrapped-near", 0.0, -179.99) // wrapped distance 0.06 from the centre
        add("plain-far", 0.0, 179.80)     // distance 0.15; without the wrap -179.99 would be 359.94 away
        add("middle", 0.0, 0.0)
        val b = bounds(-1.0, 179.0, 1.0, -179.0)
        val rows = repo.observeInBounds(b, LatLon(0.0, 179.95), 100).first()
        assertEquals(listOf("wrapped-near", "plain-far"), rows.map { it.name })
        val rows2 = repo.observeInBounds(b, LatLon(0.0, -179.95), 100).first()
        assertEquals(listOf("wrapped-near", "plain-far"), rows2.map { it.name })
    }

    @Test fun inBoundsEastEdge180MatchesPlaceStoredAtMinus180() = runBlocking {
        val id = add("edge", 0.0, 180.0) // stored as -180
        assertEquals(-180.0, db.placeDao().observeByList(listId).first().single().lon, 0.0)
        val rows = repo.observeInBounds(bounds(-1.0, 170.0, 1.0, 180.0), LatLon(0.0, 175.0), 10).first()
        assertEquals(listOf(id), rows.map { it.id })
        repo.setImportant(1, LatLon(0.0, 180.0), null)
        assertEquals(listOf(1L), repo.observeImportantInBounds(bounds(-1.0, 170.0, 1.0, 180.0)).first().map { it.id })
        assertTrue(repo.observeImportantInBounds(bounds(-1.0, 100.0, 1.0, 170.0)).first().isEmpty())
    }

    @Test fun inBoundsOrderingAndCap() = runBlocking {
        val cLat = 50.0; val cLon = 8.0
        val rnd = java.util.Random(42)
        val all = (0 until 600).map { i ->
            val lat = cLat + (rnd.nextDouble() - 0.5) * 0.2
            val lon = cLon + (rnd.nextDouble() - 0.5) * 0.2
            i to LatLon(lat, lon)
        }
        db.placeDao().insertAll(all.map { (i, p) ->
            Place(listId = listId, name = "p$i", nameNormalized = "p$i", lat = p.lat, lon = p.lon, createdAt = 1)
        })
        val cos = cos(Math.toRadians(cLat))
        val expected = all.sortedBy { (_, p) ->
            val dLat = p.lat - cLat; val dLon = (p.lon - cLon) * cos
            dLat * dLat + dLon * dLon
        }.take(501).map { "p${it.first}" }
        val rows = repo.observeInBounds(bounds(49.0, 7.0, 51.0, 9.0), LatLon(cLat, cLon), 501).first()
        assertEquals(501, rows.size)
        assertEquals(expected, rows.map { it.name })
        assertEquals(expected.first(), rows.first().name)
    }

    @Test fun inBoundsTiesByIdAndLimit() = runBlocking {
        // Equidistant from (1,1); index order is by lat (b before a), id order is a before b.
        val a = add("a", 1.5, 1.0)
        val b = add("b", 0.5, 1.0)
        add("far", 3.0, 1.0)
        val rows = repo.observeInBounds(bounds(0.0, 0.0, 4.0, 3.0), LatLon(1.0, 1.0), 2).first()
        assertEquals(listOf(a, b), rows.map { it.id })
    }

    @Test fun addPlaceRejectsExternalOrMissingList() {
        val ext = runBlocking { db.placeListDao().insert(PlaceList(name = "E", color = 1, type = ListType.EXTERNAL, createdAt = 1)) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addPlace(ext, "x", LatLon(0.0, 0.0), null, null) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addPlace(9999, "x", LatLon(0.0, 0.0), null, null) } }
    }

    @Test fun limitMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { repo.observeInBounds(bounds(0.0, 0.0, 1.0, 1.0), LatLon(0.0, 0.0), 0) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.search("a", null, -1) } }
    }

    @Test fun customSortOrderIsAssignedInsideTransactionConcurrently() = runBlocking {
        val ids = (1..5).map { i -> this.async(Dispatchers.Default) { repo.addCustomImportant("c$i", null, null) } }
            .map { it.await() }
        val orders = repo.observeImportant().first().filter { it.id in ids }.map { it.sortOrder }
        assertEquals(listOf(10, 11, 12, 13, 14), orders.sorted())
    }

    @Test fun hasAnyPlacesAndDetail() = runBlocking {
        assertFalse(repo.observeHasAnyPlaces().first())
        val id = add("x", 1.0, 2.0, "addr")
        assertTrue(repo.observeHasAnyPlaces().first())
        val d = repo.observeDetail(id).first()!!
        assertEquals("addr", d.address)
        assertEquals(ListType.NORMAL, d.listType)
        assertNull(repo.observeDetail(999).first())
    }

    @Test fun searchAccentCaseInsensitiveAndPrefixFirst() = runBlocking {
        add("Alt MÜNCHEN", 0.0, 0.0)
        add("München Mitte", 5.0, 5.0)
        add("Elsewhere", 1.0, 1.0, "Bei München")
        add("Hamburg", 2.0, 2.0)
        val r = repo.search("munchen", null, 10)
        assertEquals(listOf("München Mitte", "Alt MÜNCHEN", "Elsewhere"), r.map { it.name })
        assertEquals(listOf("München Mitte", "Alt MÜNCHEN", "Elsewhere"), repo.search("MÜNCHEN", null, 10).map { it.name })
        assertEquals(1, repo.search("munchen", null, 1).size)
        assertTrue(repo.search("   ", null, 10).isEmpty())
    }

    @Test fun searchDistanceOrderWithCenter() = runBlocking {
        add("Cafe far", 10.0, 10.0)
        add("Cafe near", 1.0, 1.0)
        assertEquals(listOf("Cafe near", "Cafe far"), repo.search("cafe", LatLon(0.0, 0.0), 10).map { it.name })
        assertEquals(listOf("Cafe far", "Cafe near"), repo.search("cafe", LatLon(10.0, 10.0), 10).map { it.name })
    }

    @Test fun searchEscapesWildcards() = runBlocking {
        add("50% off", 0.0, 0.0)
        add("500 things", 0.0, 0.0)
        add("a_b", 0.0, 0.0)
        add("axb", 0.0, 0.0)
        assertEquals(listOf("50% off"), repo.search("50%", null, 10).map { it.name })
        assertEquals(listOf("a_b"), repo.search("a_b", null, 10).map { it.name })
    }

    @Test fun searchExcludesHiddenSublist() = runBlocking {
        exec("INSERT INTO ExternalList(id, listId, mapId, sourceUrl) VALUES (1, $listId, 'm', 'u')")
        exec("INSERT INTO ExternalSublist(id, externalListId, name, visible, sortOrder) VALUES (1, 1, 'hid', 0, 0)")
        db.placeDao().insert(Place(listId = listId, externalSublistId = 1, name = "secret", nameNormalized = "secret", lat = 0.0, lon = 0.0, createdAt = 1))
        assertTrue(repo.search("secret", null, 10).isEmpty())
    }

    // ---- ImportantLocation ----

    @Test fun setImportantSetsAndClears() = runBlocking {
        repo.setImportant(1, LatLon(52.5, 13.4), "Somewhere 1")
        var home = repo.observeImportant().first().first { it.id == 1L }
        assertEquals(52.5, home.lat!!, 0.0); assertEquals(13.4, home.lon!!, 0.0)
        assertEquals("Somewhere 1", home.address); assertEquals(12345L, home.updatedAt)
        repo.setImportant(1, null, "ignored")
        home = repo.observeImportant().first().first { it.id == 1L }
        assertNull(home.lat); assertNull(home.lon); assertNull(home.address)
        assertEquals(ImportantKind.HOME, home.kind)
    }

    @Test fun importantCoordsAlwaysBothOrNeither() = runBlocking {
        repo.setImportant(2, LatLon(1.0, 190.0), null)
        repo.addCustomImportant("Gym", LatLon(3.0, 4.0), null)
        repo.addCustomImportant("Nowhere", null, "addr")
        repo.observeImportant().first().forEach { assertEquals(it.lat == null, it.lon == null) }
        assertEquals(-170.0, repo.observeImportant().first().first { it.id == 2L }.lon!!, 1e-9)
    }

    @Test fun customImportantRules() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addCustomImportant("  ", null, null) } }
        val a = repo.addCustomImportant(" Gym ", LatLon(1.0, 1.0), null)
        val b = repo.addCustomImportant("Mum", null, null)
        val rows = repo.observeImportant().first()
        assertEquals(listOf(1L, 2L, a, b), rows.map { it.id })
        assertEquals(listOf(0, 1, 10, 11), rows.map { it.sortOrder })
        assertEquals("Gym", rows[2].label)
        assertEquals(ImportantKind.CUSTOM, rows[2].kind)
    }

    @Test fun deleteCustomRefusesNonCustom() = runBlocking {
        assertEquals(0, db.importantLocationDao().deleteCustom(1))
        assertEquals(0, db.importantLocationDao().deleteCustom(2))
        assertFalse(repo.deleteCustomImportant(1))
        val c = repo.addCustomImportant("Gym", null, null)
        assertTrue(repo.deleteCustomImportant(c))
        assertEquals(listOf(1L, 2L), repo.observeImportant().first().map { it.id })
    }

    @Test fun deletingEveryListKeepsImportantAndPlaceDeleteKeepsHome() = runBlocking {
        val pid = add("p", 1.0, 1.0)
        repo.setImportant(1, LatLon(1.0, 1.0), "a")
        repo.deletePlace(pid)
        db.placeListDao().delete(listId)
        val rows = repo.observeImportant().first()
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertEquals(1.0, rows[0].lat!!, 0.0)
    }

    @Test fun importantInBounds() = runBlocking {
        repo.setImportant(1, LatLon(0.0, 179.9), null)
        repo.setImportant(2, LatLon(0.0, 0.0), null)
        val c = repo.addCustomImportant("W", LatLon(0.0, -179.9), null)
        repo.addCustomImportant("unset", null, null)
        val wrap = repo.observeImportantInBounds(bounds(-1.0, 179.0, 1.0, -179.0)).first()
        assertEquals(listOf(1L, c), wrap.map { it.id })
        val plain = repo.observeImportantInBounds(bounds(-1.0, -1.0, 1.0, 1.0)).first()
        assertEquals(listOf(2L), plain.map { it.id })
        assertTrue(repo.observeImportantInBounds(bounds(10.0, 10.0, 11.0, 11.0)).first().isEmpty())
    }
}
