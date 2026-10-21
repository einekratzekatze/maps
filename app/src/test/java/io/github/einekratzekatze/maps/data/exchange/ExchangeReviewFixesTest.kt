package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.data.exchange.ExchangeTestData.materialized
import io.github.einekratzekatze.maps.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression tests for REVIEW-batch1 (SF-4, SF-7..SF-11, D3, D4). */
@RunWith(RobolectricTestRunner::class)
class ExchangeReviewFixesTest {
    private fun gpx(lists: List<ExchangeList>, name: String? = null) = buildString { GpxWriter.write(this, lists, name) }
    private fun kml(lists: List<ExchangeList>, name: String? = null) = buildString { KmlWriter.write(this, lists, name) }

    private val bomb = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">" +
        "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]>"

    // SF-4
    @Test(expected = ExchangeFormatException::class)
    fun kmlReaderRejectsDoctype() { KmlReader.read((bomb + "<kml><Document><name>&c;</name></Document></kml>").reader()) }

    @Test(expected = ExchangeFormatException::class)
    fun gpxReaderRejectsDoctype() { GpxReader.read((bomb + "<gpx><metadata><name>&c;</name></metadata></gpx>").reader()) }

    // SF-5 for the exchange reader stays fine
    @Test fun htmlInDescriptionIsTolerated() {
        val doc = KmlReader.read("<kml><Document><Placemark><name>n</name><description>a <b>b</b></description><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>".reader())
        assertEquals("a b", doc.lists.single().places.single().notes)
    }

    // SF-7 / D3
    private fun longLine(n: Int) = (0 until n).joinToString(" ") { "${10.0 + it / 10000.0},${50.0 + it / 10000.0},0" }

    @Test fun foreignKmlLineStringIsSampled() {
        val xml = "<kml><Document><Placemark><name>t</name><LineString><coordinates>${longLine(5000)}</coordinates></LineString></Placemark></Document></kml>"
        val doc = KmlReader.read(xml.reader())
        val d = doc.lists.single().drives.single()
        assertEquals(MAX_FOREIGN_WAYPOINTS, d.waypoints.size)
        assertEquals(25, MAX_FOREIGN_WAYPOINTS)
        assertEquals(LatLon(50.0, 10.0), d.origin)
        assertEquals(LatLon(50.4999, 10.4999), d.destination)
        assertEquals(1, doc.simplifiedDrives)
        // evenly sampled, strictly increasing
        val lons = d.waypoints.map { it.position.lon }
        assertEquals(lons.sorted(), lons)
        assertEquals(lons.size, lons.toSet().size)
    }

    @Test fun foreignGpxTrackIsSampled() {
        val pts = (0 until 5000).joinToString("") { "<trkpt lat=\"50.$it\" lon=\"10.$it\"/>" }
        val doc = GpxReader.read("<gpx><trk><name>t</name><trkseg>$pts</trkseg></trk></gpx>".reader())
        assertEquals(MAX_FOREIGN_WAYPOINTS, doc.lists.single().drives.single().waypoints.size)
        assertEquals(1, doc.simplifiedDrives)
    }

    @Test fun shortForeignTrackIsUntouched() {
        val pts = (0 until 10).joinToString("") { "<trkpt lat=\"50.$it\" lon=\"10.$it\"/>" }
        val doc = GpxReader.read("<gpx><trk><name>t</name><trkseg>$pts</trkseg></trk></gpx>".reader())
        assertEquals(8, doc.lists.single().drives.single().waypoints.size)
        assertEquals(0, doc.simplifiedDrives)
    }

    @Test fun ownDrivesKeepAllWaypoints() {
        val wps = (0 until 60).map { ExchangeWaypoint("w$it", LatLon(1.0 + it / 100.0, 2.0)) }
        val lists = listOf(ExchangeList("l", drives = listOf(ExchangeDrive("big", LatLon(0.0, 0.0), destination = LatLon(5.0, 5.0), waypoints = wps))))
        for (doc in listOf(KmlReader.read(kml(lists).reader()), GpxReader.read(gpx(lists).reader()))) {
            assertEquals(60, doc.lists.single().drives.single().waypoints.size)
            assertEquals(0, doc.simplifiedDrives)
        }
    }

    // SF-8 item 1, 2 and same-named lists
    @Test fun gpxKeepsListOrderEmptyNamesAndSameNamedLists() {
        val d = ExchangeDrive("d", null, destination = LatLon(1.0, 1.0))
        val p = ExchangePlace("p", LatLon(2.0, 2.0))
        val lists = listOf(
            ExchangeList("DrivesOnly", drives = listOf(d)),
            ExchangeList("Places", places = listOf(p)),
            ExchangeList("", places = listOf(p.copy(name = "in unnamed"))),
            ExchangeList("Places", places = listOf(p.copy(name = "second Places"))),
        )
        val back = GpxReader.read(gpx(lists, "Doc").reader()).lists.materialized()
        assertEquals(lists.materialized(), back)
    }

    // SF-8 item 3/4: empty and padded labels behave the same in both formats
    @Test fun labelsAreTrimmedAndEmptyIsNullInBothFormats() {
        val drive = ExchangeDrive(
            "d", LatLon(1.0, 1.0), originLabel = "", destination = LatLon(2.0, 2.0), destinationLabel = "  dest ",
            waypoints = listOf(ExchangeWaypoint("", LatLon(1.5, 1.5)), ExchangeWaypoint(" w ", LatLon(1.6, 1.6))),
        )
        val lists = listOf(ExchangeList("l", drives = listOf(drive)))
        for (doc in listOf(KmlReader.read(kml(lists).reader()), GpxReader.read(gpx(lists).reader()))) {
            val d = doc.lists.single().drives.single()
            assertNull(d.originLabel)
            assertEquals("dest", d.destinationLabel)
            assertEquals(listOf(null, "w"), d.waypoints.map { it.label })
        }
    }

    // SF-9
    @Test fun gpxWriterIteratesListsAndItemsOnce() {
        var listPasses = 0
        var placePasses = 0
        var drivePasses = 0
        val list = ExchangeList(
            "l",
            places = Iterable { placePasses++; listOf(ExchangePlace("p", LatLon(1.0, 1.0))).iterator() },
            drives = Iterable { drivePasses++; listOf(ExchangeDrive("d", null, destination = LatLon(1.0, 1.0))).iterator() },
        )
        val once = Iterable { listPasses++; check(listPasses == 1) { "lists iterated twice" }; listOf(list).iterator() }
        GpxWriter.write(StringBuilder(), once)
        assertEquals(1, listPasses)
        assertEquals(1, placePasses)
        assertEquals(1, drivePasses)
    }

    // SF-10
    @Test fun gpxNeverWritesLongitude180() {
        val lists = listOf(ExchangeList("l", places = listOf(
            ExchangePlace("a", LatLon(0.0, 180.0)), ExchangePlace("b", LatLon(0.0, 179.99999999)),
        ), drives = listOf(ExchangeDrive("d", LatLon(1.0, 180.0), destination = LatLon(2.0, -180.0)))))
        val xml = gpx(lists)
        assertFalse(xml, Regex("""lon="180"""").containsMatchIn(xml))
        assertTrue(xml, xml.contains("""lon="-180""""))
        val back = GpxReader.read(xml.reader()).lists.single()
        assertEquals(listOf(-180.0, -180.0), back.places.map { it.position.lon })
    }

    // SF-11
    @Test fun nestedFoldersAreNamedByPathAndContainerOnlyFoldersDropped() {
        val xml = "<kml><Document><Folder><name>Outer</name><Folder><name>Inner</name><Placemark><name>p</name><Point><coordinates>1,2</coordinates></Point></Placemark></Folder>" +
            "<Folder><name>Empty leaf</name></Folder></Folder></Document></kml>"
        val doc = KmlReader.read(xml.reader())
        assertEquals(listOf("Outer / Inner", "Outer / Empty leaf"), doc.lists.map { it.name })
    }

    // N-9
    @Test fun lenientNumberSuffixesAreRejected() {
        assertTrue(KmlReader.parseCoordinates("1.5f,2d").isEmpty())
        assertEquals(listOf(LatLon(2.0, 1.0)), KmlReader.parseCoordinates(" 1 , 2 , 3 "))
    }

    @Test fun emptyDocumentIsNotKml() {
        try { KmlReader.read("".reader()); org.junit.Assert.fail() } catch (_: ExchangeFormatException) {}
        try { GpxReader.read("".reader()); org.junit.Assert.fail() } catch (_: ExchangeFormatException) {}
    }
}
