package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.DbTestBase
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.mymaps.KmlFetcher
import io.github.einekratzekatze.maps.data.mymaps.KmlParseException
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalListRepositoryTest : DbTestBase() {
    private class FakeFetcher : KmlFetcher {
        var body: String = ""
        var error: IOException? = null
        val urls = mutableListOf<String>()
        override suspend fun fetch(url: String): String {
            urls += url
            error?.let { throw it }
            return body
        }
    }

    private val fetcher = FakeFetcher()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private lateinit var repo: ExternalListRepository
    private lateinit var places: PlacesRepository
    private val url = "https://www.google.com/maps/d/u/0/viewer?mid=1AbC_d-9&ll=52,13"

    private fun kml(vararg folders: Pair<String, List<Pair<String?, String>>>) = buildString {
        append("<kml><Document><name>Trip</name>")
        for ((name, pms) in folders) {
            append("<Folder><name>$name</name>")
            for ((pm, coords) in pms) {
                append("<Placemark>")
                if (pm != null) append("<name>$pm</name>")
                append("<Point><coordinates>$coords</coordinates></Point></Placemark>")
            }
            append("</Folder>")
        }
        append("</Document></kml>")
    }

    @Before fun setUpRepo() {
        repo = ExternalListRepository(db, fetcher, ExternalListLabels("List", "Layer", "Place"), Dispatchers.Unconfined, clock)
        places = PlacesRepository(db, Dispatchers.Unconfined, clock)
    }

    private fun inView(): List<String> = runBlocking {
        places.observeInBounds(GeoBounds(-90.0, -180.0, 90.0, 180.0), LatLon(52.5, 13.4), 501).first().map { it.name }.sorted()
    }

    @Test fun importCreatesAReadOnlyListWithSublistsAndPlaces() = runBlocking {
        fetcher.body = kml("Food" to listOf("Cafe" to "13.4,52.5", null to "13.41,52.51"), "Museums" to listOf("Pergamon" to "13.39,52.52"))
        val result = repo.import("  $url  ", color = 0xFF00FF00.toInt()) as ImportResult.Imported
        assertEquals(3, result.placeCount)
        assertEquals(listOf("https://www.google.com/maps/d/kml?mid=1AbC_d-9&forcekml=1"), fetcher.urls)
        val list = db.placeListDao().get(result.listId)!!
        assertEquals("Trip", list.name)
        assertEquals(ListType.EXTERNAL, list.type)
        val external = repo.getByList(result.listId)!!
        assertEquals(url, external.sourceUrl)
        assertEquals(1_000L, external.lastFetchedAt)
        assertEquals(listOf("Food", "Museums"), repo.observeSublists(external.id).first().map { it.name })
        assertEquals(listOf("Cafe", "Pergamon", "Place"), inView())
    }

    @Test fun sameMapTwiceAndNonMyMapsUrls() = runBlocking {
        fetcher.body = kml("A" to listOf("x" to "1,2"))
        val first = repo.import(url, 0) as ImportResult.Imported
        assertEquals(ImportResult.AlreadyImported(first.listId), repo.import(url, 0))
        assertEquals(ImportResult.NotAMyMapsUrl, repo.import("https://example.com/?mid=1", 0))
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun failuresLeaveNothingBehind() = runBlocking {
        fetcher.error = IOException("offline")
        assertThrows(IOException::class.java) { runBlocking { repo.import(url, 0) } }
        fetcher.error = null
        fetcher.body = "not kml"
        assertThrows(KmlParseException::class.java) { runBlocking { repo.import(url, 0) } }
        assertTrue(db.placeListDao().observeAllWithCounts().first().isEmpty())
        Unit
    }

    @Test fun refreshReplacesContentAndKeepsVisibilityByName() = runBlocking {
        fetcher.body = kml("Food" to listOf("Cafe" to "13.4,52.5"), "Bars" to listOf("Pub" to "13.4,52.5"))
        val listId = (repo.import(url, 0) as ImportResult.Imported).listId
        val externalId = repo.getByList(listId)!!.id
        val bars = repo.observeSublists(externalId).first().single { it.name == "Bars" }
        repo.setSublistVisible(bars.id, false)
        assertEquals(listOf("Cafe"), inView())

        fetcher.body = kml("Bars" to listOf("Pub" to "13.4,52.5", "Club" to "13.4,52.5"), "Food" to listOf("Bistro" to "13.4,52.5"), "New" to listOf("Zoo" to "13.4,52.5"))
        assertEquals(4, repo.refresh(listId))
        val after = repo.observeSublists(externalId).first()
        assertEquals(listOf("Bars" to false, "Food" to true, "New" to true), after.map { it.name to it.visible })
        assertEquals(listOf("Bistro", "Zoo"), inView())
        assertFalse(repo.observeAllHidden(externalId).first())
    }

    @Test fun allHiddenAndDeleteCascades() = runBlocking {
        fetcher.body = kml("Only" to listOf("x" to "13.4,52.5"))
        val listId = (repo.import(url, 0) as ImportResult.Imported).listId
        val externalId = repo.getByList(listId)!!.id
        repo.setSublistVisible(repo.observeSublists(externalId).first().single().id, false)
        assertTrue(repo.observeAllHidden(externalId).first())
        db.placeListDao().delete(listId)
        assertEquals(null, repo.getByList(listId))
        assertTrue(repo.observeSublists(externalId).first().isEmpty())
        assertTrue(inView().isEmpty())
    }

    @Test fun refreshOfUnknownListThrows() {
        assertThrows(NoSuchElementException::class.java) { runBlocking { repo.refresh(42) } }
    }

    @Test fun externalListsAreReadOnlyForAddPlace() = runBlocking {
        fetcher.body = kml("A" to listOf("x" to "1,2"))
        val listId = (repo.import(url, 0) as ImportResult.Imported).listId
        assertThrows(IllegalArgumentException::class.java) { runBlocking { places.addPlace(listId, "y", LatLon(1.0, 2.0), null, null) } }
        Unit
    }
}
