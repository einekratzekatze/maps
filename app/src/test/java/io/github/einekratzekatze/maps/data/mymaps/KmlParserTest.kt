package io.github.einekratzekatze.maps.data.mymaps

import io.github.einekratzekatze.maps.domain.LatLon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class KmlParserTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream("mymaps/$name")!!

    @Test fun realisticExport() {
        val map = res("berlin_mymap.kml").use { KmlParser.parse(it) }
        assertEquals("Berlin Trip", map.name)
        assertEquals(listOf("Cafes", "Routes", "Empty layer"), map.sublists.map { it.name })
        assertTrue(map.sublists.none { it.isImplicit })

        val cafes = map.sublists[0].placemarks
        assertEquals(3, cafes.size)
        val einstein = cafes[0]
        assertEquals("Café Einstein", einstein.name)
        assertEquals("Kaffee &amp; Kuchen<br>Unter den Linden 42", einstein.description)
        // KML is lon,lat: 13.3889 is the longitude.
        assertEquals(LatLon(lat = 52.5163, lon = 13.3889), einstein.position)
        assertEquals("#icon-1899-0288D1", einstein.styleUrl)
        assertEquals(0xFF0288D1.toInt(), einstein.colorArgb) // ffd18802 = aabbggrr
        assertEquals(mapOf("gx_media_links" to "https://example.org/a.jpg"), einstein.extendedData)

        assertEquals("Bonanza & Co", cafes[1].name)
        assertNull(cafes[2].name) // empty <name>
        assertNull(cafes[2].colorArgb)
        assertEquals(LatLon(52.6, 13.5), cafes[2].position) // no altitude

        // CDATA content is kept verbatim (HTML entities stay).
        // Line, polygon skipped; broken point counted as invalid.
        assertEquals(0, map.sublists[1].placemarks.size)
        assertEquals(2, map.skippedNonPoint)
        assertEquals(1, map.skippedInvalid)
        assertEquals(0, map.sublists[2].placemarks.size) // empty layer kept
        assertEquals(3, map.placemarkCount)
    }

    @Test fun nestedFoldersAndRootPlacemarks() {
        val map = res("nested_and_root.kml").use { KmlParser.parse(it) }
        assertEquals("Nested", map.name)
        // Order: implicit root (first root placemark), Outer / Inner, Outer (unnamed child).
        assertEquals(listOf(null, "Outer / Inner", "Outer"), map.sublists.map { it.name })
        assertTrue(map.sublists[0].isImplicit)
        assertEquals(listOf("Root A", "Root B"), map.sublists[0].placemarks.map { it.name })
        assertEquals(LatLon(49.0, 9.0), map.sublists[0].placemarks[1].position) // Point inside MultiGeometry
        assertEquals(LatLon(51.0, 11.0), map.sublists[1].placemarks[0].position)
        assertFalse(map.sublists[1].isImplicit)
        assertEquals("Unnamed child", map.sublists[2].placemarks[0].name)
    }

    @Test fun stringInputAndCdataAndEntities() {
        val map = KmlParser.parse(
            """<kml><Document><name>A &amp; B</name><Folder><name><![CDATA[F <1>]]></name>
            <Placemark><name>P&#233;</name><description>x &lt; y</description>
            <Point><coordinates>-122.5,37.75</coordinates></Point></Placemark></Folder></Document></kml>""",
        )
        assertEquals("A & B", map.name)
        assertEquals("F <1>", map.sublists[0].name)
        val p = map.sublists[0].placemarks[0]
        assertEquals("Pé", p.name)
        assertEquals("x < y", p.description)
        assertEquals(LatLon(37.75, -122.5), p.position)
    }

    @Test fun coordinateEdgeCases() {
        assertEquals(LatLon(2.0, 1.0), KmlParser.parseCoordinates(" 1 , 2 ,3\n"))
        assertEquals(LatLon(2.5, 1.5), KmlParser.parseCoordinates("1.5,2.5 9,9"))
        assertEquals(LatLon(-0.5, -1e1), KmlParser.parseCoordinates("-1e1,-.5"))
        assertNull(KmlParser.parseCoordinates(""))
        assertNull(KmlParser.parseCoordinates("1"))
        assertNull(KmlParser.parseCoordinates("1,"))
        assertNull(KmlParser.parseCoordinates("1,2f"))
        assertNull(KmlParser.parseCoordinates("NaN,1"))
        assertNull(KmlParser.parseCoordinates("Infinity,1"))
        assertNull(KmlParser.parseCoordinates("1,,2"))
        assertNull(KmlParser.parseCoordinates("181,0"))
        assertNull(KmlParser.parseCoordinates("0,91"))
        // Comma decimal separators (German style) are rejected, never mis-read.
        assertNull(KmlParser.parseCoordinates("13,5;52,5"))
        // Swapped order is not auto-corrected: lat 100 (lon,lat = 10,100) is out of range.
        assertNull(KmlParser.parseCoordinates("10,100"))
    }

    @Test fun colorConversion() {
        assertEquals(0x80112233.toInt(), KmlParser.parseColor("80332211"))
        assertNull(KmlParser.parseColor("zz"))
        assertNull(KmlParser.parseColor("fff"))
    }

    @Test fun emptyAndMissing() {
        val map = KmlParser.parse("<kml><Document/></kml>")
        assertNull(map.name)
        assertTrue(map.sublists.isEmpty())
        assertTrue(KmlParser.parse("<kml/>").sublists.isEmpty())
    }

    @Test(expected = KmlParseException::class)
    fun malformedXmlThrows() { KmlParser.parse("<kml><Document><name>x</Document>") }

    @Test(expected = KmlParseException::class)
    fun notXmlThrows() { KmlParser.parse("hello") }

    @Test fun hugeFileStreams() {
        val n = 50_000
        val sb = StringBuilder("<kml><Document><name>Big</name><Folder><name>F</name>")
        for (i in 0 until n) {
            sb.append("<Placemark><name>P").append(i).append("</name><Point><coordinates>")
                .append(10 + (i % 100) / 100.0).append(',').append(50 + (i % 50) / 50.0)
                .append(",0</coordinates></Point></Placemark>")
        }
        sb.append("</Folder></Document></kml>")
        val map = KmlParser.parse(sb.toString().byteInputStream())
        assertEquals(n, map.placemarkCount)
        assertEquals("P49999", map.sublists[0].placemarks.last().name)
    }

    @Test fun longDescriptionIsCapped() {
        val d = "x".repeat(KmlParser.MAX_DESCRIPTION + 500)
        val map = KmlParser.parse("<kml><Document><Placemark><description>$d</description><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>")
        assertEquals(KmlParser.MAX_DESCRIPTION, map.sublists[0].placemarks[0].description!!.length)
    }
}
