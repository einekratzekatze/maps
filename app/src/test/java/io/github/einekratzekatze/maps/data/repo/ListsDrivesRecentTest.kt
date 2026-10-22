package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.DbTestBase
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.TravelMode as DbMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.RoutePoint
import io.github.einekratzekatze.maps.navigation.TravelMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class MutableClock(var now: Long) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

private class FakeStore(var id: Long? = null) : DrivesListIdStore {
    override suspend fun get() = id
    override suspend fun set(id: Long) { this.id = id }
}

@RunWith(RobolectricTestRunner::class)
class ListsDrivesRecentTest : DbTestBase() {
    private val clock = MutableClock(1000)
    private val store = FakeStore()
    private lateinit var lists: ListRepository
    private lateinit var drives: DriveRepository
    private lateinit var recents: RecentSearchRepository
    private lateinit var places: PlacesRepository

    @Before fun setUp() {
        lists = ListRepository(db, store, Dispatchers.Unconfined, clock)
        drives = DriveRepository(db, Dispatchers.Unconfined, clock)
        recents = RecentSearchRepository(db, Dispatchers.Unconfined, clock)
        places = PlacesRepository(db, Dispatchers.Unconfined, clock)
    }

    private fun draft(mode: TravelMode? = TravelMode.Bike, originLatLon: LatLon? = LatLon(1.0, 2.0)) = DriveDraft(
        origin = RoutePoint("A", originLatLon),
        waypoints = listOf(RoutePoint("W", LatLon(3.0, 4.0)), RoutePoint(null, LatLon(5.0, 6.0))),
        destination = RoutePoint("B", LatLon(7.0, 8.0)),
        destinationLatLon = LatLon(7.0, 8.0),
        mode = mode,
    )

    // ---- lists ----

    @Test fun countsIncludePlacesAndDrivesAndWritableExcludesExternal() = runBlocking {
        val a = lists.create("A", 1)
        val ext = lists.create("Ext", 2, ListType.EXTERNAL, sortOrder = 1)
        places.addPlace(a, "p", LatLon(1.0, 1.0), null, null)
        places.addPlace(a, "q", LatLon(1.0, 1.0), null, null)
        drives.insert(draft(), a)
        val counts = lists.observeAllWithCounts().first()
        assertEquals(listOf("A" to 3, "Ext" to 0), counts.map { it.list.name to it.itemCount })
        assertEquals(listOf(a), lists.observeWritable().first().map { it.id })
        assertEquals(ext, counts[1].list.id)
    }

    @Test fun updateAndDeleteList() = runBlocking {
        val a = lists.create("A", 1)
        lists.update(lists.get(a)!!.copy(name = "B"))
        assertEquals("B", lists.get(a)!!.name)
        lists.delete(a)
        assertNull(lists.get(a))
    }

    @Test fun drivesListCreatedOnceAndRecreatedIfDeleted() = runBlocking {
        val id = lists.getOrCreateDrivesList("Drives", 9)
        assertEquals(id, store.id)
        assertEquals(id, lists.getOrCreateDrivesList("Other name", 9))
        assertEquals(1, lists.observeAllWithCounts().first().size)
        lists.delete(id)
        val id2 = lists.getOrCreateDrivesList("Drives", 9)
        assertNotEquals(id, id2)
        assertEquals(id2, store.id)
    }

    // ---- drives ----

    @Test fun driveRoundTripAndOrdering() = runBlocking {
        val l = lists.create("D", 1)
        clock.now = 10
        val first = drives.insert(draft(), l)
        clock.now = 20
        val second = drives.insert(draft(mode = null, originLatLon = null), l)
        val got = drives.get(first)!!
        assertEquals(draft(), got.route)
        assertEquals(10L, got.createdAt)
        val nullOrigin = drives.get(second)!!
        assertNull(nullOrigin.route.origin.latLon)
        assertNull(nullOrigin.route.mode)
        assertEquals(DbMode.UNKNOWN.name, rawQuery("SELECT travelMode FROM Drive WHERE id = $second") { it.getString(0) }.single())
        assertEquals(listOf(second, first), drives.observeByList(l).first().map { it.id })
        drives.delete(first)
        assertNull(drives.get(first))
    }

    @Test fun driveWithMalformedWaypointsReadsAsNoWaypoints() = runBlocking {
        val l = lists.create("D", 1)
        val id = drives.insert(draft(), l)
        exec("UPDATE Drive SET waypointsJson = 'garbage' WHERE id = $id")
        assertTrue(drives.get(id)!!.route.waypoints.isEmpty())
    }

    // ---- recent searches ----

    @Test fun recentTrimsAndDedupes() = runBlocking {
        clock.now = 1; recents.record("  Café  Einstein ")
        clock.now = 2; recents.record("other")
        clock.now = 3; recents.record("CAFE einstein")
        val r = recents.observeRecent().first()
        assertEquals(listOf("CAFE einstein", "other"), r.map { it.query })
        assertEquals("cafe einstein", r[0].queryNormalized)
        assertEquals(3L, r[0].timestamp)
        assertEquals(1, rawQuery("SELECT COUNT(*) FROM RecentSearch WHERE queryNormalized = 'cafe einstein'") { it.getInt(0) }.single())
    }

    @Test fun recentIgnoresBlankAndStoresTrimmedQuery() = runBlocking {
        recents.record("   ")
        recents.record("")
        assertTrue(recents.observeRecent().first().isEmpty())
        recents.record("  x y  ")
        assertEquals("x y", recents.observeRecent().first().single().query)
    }

    @Test fun recentCappedAt20() = runBlocking {
        for (i in 1..22) { clock.now = i.toLong(); recents.record("q$i") }
        val r = recents.observeRecent(100).first()
        assertEquals(20, r.size)
        assertEquals("q22", r.first().query)
        assertEquals("q3", r.last().query)
    }

    @Test fun recentSearchDeleteAndEscape() = runBlocking {
        clock.now = 1; recents.record("50% off")
        clock.now = 2; recents.record("500 off")
        clock.now = 3; recents.record("a_b")
        assertEquals(listOf("50% off"), recents.search("50%").map { it.query })
        assertEquals(listOf("a_b"), recents.search("a_b").map { it.query })
        assertEquals(listOf("500 off", "50% off"), recents.search("50").map { it.query })
        recents.delete(recents.search("a_b").single().id)
        assertTrue(recents.search("a_b").isEmpty())
    }
}
