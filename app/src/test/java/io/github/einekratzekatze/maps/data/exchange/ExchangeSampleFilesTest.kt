package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.data.exchange.ExchangeTestData.materialized
import io.github.einekratzekatze.maps.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExchangeSampleFilesTest {
    private fun res(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("exchange/$name")) { name }

    @Test fun osmAndGpx() {
        val doc = GpxReader.read(res("osmand.gpx")).let { it.copy(lists = it.lists.materialized()) }
        assertEquals("favorites", doc.name)
        // Berlin (typed), Rides (typed), and untyped items under the metadata name
        assertEquals(listOf("favorites", "Berlin", "Rides"), doc.lists.map { it.name }.sortedBy { listOf("favorites", "Berlin", "Rides").indexOf(it) })
        val berlin = doc.lists.first { it.name == "Berlin" }.places.single()
        assertEquals(ExchangePlace("Brandenburger Tor", LatLon(52.5163, 13.3777), "Pariser Platz, 10117 Berlin", "Wahrzeichen & Sehenswürdigkeit"), berlin)
        val untyped = doc.lists.first { it.name == "favorites" }
        assertEquals("Tour Eiffel", untyped.places.single().name)
        val planned = untyped.drives.single()
        assertEquals("Planned", planned.name)
        assertEquals(LatLon(50.0, 8.0), planned.origin)
        assertEquals("Start", planned.originLabel)
        assertEquals(LatLon(50.5, 8.5), planned.destination)
        val ride = doc.lists.first { it.name == "Rides" }.drives.single()
        assertEquals(LatLon(52.5, 13.4), ride.origin)
        assertEquals(LatLon(52.52, 13.42), ride.destination)
        assertEquals(listOf(LatLon(52.51, 13.41)), ride.waypoints.map { it.position })
        assertEquals(1, untyped.drives.count()) // single-point track ignored
    }

    @Test fun organicMapsKml() {
        val doc = KmlReader.read(res("organicmaps.kml")).let { it.copy(lists = it.lists.materialized()) }
        assertEquals("Mein Ausflug", doc.name)
        assertEquals(listOf("Cafés & Bars", "Trails", "Leer"), doc.lists.map { it.name })
        val cafes = doc.lists[0].places.toList()
        assertEquals(ExchangePlace("Café Einstein", LatLon(52.52, 13.3889), null, "<b>Gut</b> & günstig"), cafes[0])
        assertEquals(ExchangePlace("Bar \"Zwölf\"", LatLon(51.5074, -0.1278)), cafes[1])
        val trail = doc.lists[1].drives.single()
        assertEquals(LatLon(47.0, 8.0), trail.origin)
        assertEquals(LatLon(47.2, 8.2), trail.destination)
        assertEquals(1, trail.waypoints.size)
        assertEquals(0, doc.lists[1].places.count()) // polygon skipped
        assertEquals(0, doc.lists[2].places.count() + doc.lists[2].drives.count())
    }

    @Test fun looseAndNestedKml() {
        val doc = KmlReader.read(res("mymaps-style.kml")).let { it.copy(lists = it.lists.materialized()) }
        assertEquals("Loose map", doc.name)
        assertEquals(listOf("Loose map", "Outer", "Inner"), doc.lists.map { it.name })
        assertEquals(ExchangePlace("Loose point", LatLon(20.25, 10.5), "Somewhere 1", "top-level"), doc.lists[0].places.single())
        assertEquals(listOf("After inner"), doc.lists[1].places.map { it.name })
        assertEquals(listOf("Deep"), doc.lists[2].places.map { it.name })
        assertNull(doc.lists[1].drives.firstOrNull())
    }
}
