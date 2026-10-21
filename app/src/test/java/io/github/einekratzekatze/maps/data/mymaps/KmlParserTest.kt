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

    /** Generates [count] placemarks on demand; the whole document never exists in memory. */
    private class GeneratedKml(private val count: Int) : java.io.InputStream() {
        private var next = 0
        private var buf = "<kml><Document><name>Big</name><Folder><name>F</name>".toByteArray()
        private var pos = 0
        private var footerDone = false
        var maxBuffered = 0L
        var produced = 0L

        override fun read(): Int {
            while (pos >= buf.size) {
                buf = when {
                    next < count -> {
                        val i = next++
                        ("<Placemark><name>P$i</name><Point><coordinates>" + (10 + (i % 100) / 100.0) + "," +
                            (50 + (i % 50) / 50.0) + ",0</coordinates></Point></Placemark>").toByteArray()
                    }
                    !footerDone -> { footerDone = true; "</Folder></Document></kml>".toByteArray() }
                    else -> return -1
                }
                pos = 0
            }
            produced++
            return buf[pos++].toInt() and 0xFF
        }
    }

    @Test fun hugeInputIsReadFromAGeneratingStream() {
        val n = 100_000
        val input = GeneratedKml(n)
        val map = KmlParser.parse(input)
        assertEquals(n, map.placemarkCount)
        assertEquals("P${n - 1}", map.sublists[0].placemarks.last().name)
        assertTrue("produced ${input.produced} bytes", input.produced > 8_000_000)
    }

    @Test fun lineStringVerticesAreNotKept() {
        val vertices = (0 until 50_000).joinToString(" ") { "10.$it,50.$it,0" }
        val map = KmlParser.parse("<kml><Document><Placemark><LineString><coordinates>$vertices</coordinates></LineString></Placemark></Document></kml>")
        assertEquals(1, map.skippedNonPoint)
        assertTrue(map.sublists.isEmpty())
    }

    // BL-1: input that is not KML must fail instead of yielding an empty map (which would wipe a cache).
    @Test(expected = KmlParseException::class)
    fun emptyInputThrows() { KmlParser.parse("") }

    @Test(expected = KmlParseException::class)
    fun emptyStreamThrows() { KmlParser.parse(java.io.ByteArrayInputStream(ByteArray(0))) }

    @Test(expected = KmlParseException::class)
    fun htmlPageThrows() { KmlParser.parse("<html><body>Sign in</body></html>") }

    @Test(expected = KmlParseException::class)
    fun wellFormedNonKmlRootThrows() { KmlParser.parse("<html/>") }

    @Test(expected = KmlParseException::class)
    fun kmzZipThrows() {
        KmlParser.parse(java.io.ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00, 0, 0, 0, 0)))
    }

    @Test fun commentsAndDeclarationBeforeRootAreFine() {
        val map = KmlParser.parse("<?xml version=\"1.0\"?><!-- c --><kml><Document><name>x</name></Document></kml>")
        assertEquals("x", map.name)
    }

    // SF-4: entity bomb.
    @Test fun doctypeIsRejected() {
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE kml [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">" +
            "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]><kml><Document><name>&c;</name></Document></kml>"
        try {
            KmlParser.parse(bomb)
            org.junit.Assert.fail("DOCTYPE must be rejected")
        } catch (e: KmlParseException) {
            assertTrue(e.message!!, e.message!!.contains("DOCTYPE"))
        }
    }

    // SF-5: HTML inside a leaf element must not abort the import.
    @Test fun childMarkupInDescriptionIsFlattened() {
        val map = KmlParser.parse("<kml><Document><Placemark><name>n</name><description>a <b>b</b> c</description><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>")
        assertEquals("a b c", map.sublists.single().placemarks.single().description)
    }

    // SF-12
    @Test fun styleUrlWithFilePrefixAndInlineStyles() {
        val kml = """<kml><Document>
            <Style id="s"><IconStyle><color>ff0000ff</color></IconStyle></Style>
            <StyleMap id="m"><Pair><key>normal</key><Style><IconStyle><color>ff00ff00</color></IconStyle></Style></Pair></StyleMap>
            <Placemark><name>a</name><styleUrl>styles.kml#s</styleUrl><Point><coordinates>1,2</coordinates></Point></Placemark>
            <Placemark><name>b</name><Style><IconStyle><color>ffff0000</color></IconStyle></Style><Point><coordinates>1,2</coordinates></Point></Placemark>
            <Placemark><name>c</name><styleUrl>#m</styleUrl><Point><coordinates>1,2</coordinates></Point></Placemark>
            </Document></kml>"""
        val pms = KmlParser.parse(kml).sublists.single().placemarks
        assertEquals(0xFFFF0000.toInt(), pms[0].colorArgb) // aabbggrr ff0000ff = red
        assertEquals(0xFF0000FF.toInt(), pms[1].colorArgb) // inline ffff0000 = blue
        assertEquals(0xFF00FF00.toInt(), pms[2].colorArgb) // inline Style in StyleMap Pair
    }

    @Test fun sharedCoordinateValidatorRejectsSuffixes() {
        assertNull(KmlParser.parseCoordinates("1.5f,2d"))
    }

    @Test fun longDescriptionIsCapped() {
        val d = "x".repeat(KmlParser.MAX_DESCRIPTION + 500)
        val map = KmlParser.parse("<kml><Document><Placemark><description>$d</description><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>")
        assertEquals(KmlParser.MAX_DESCRIPTION, map.sublists[0].placemarks[0].description!!.length)
    }
}
