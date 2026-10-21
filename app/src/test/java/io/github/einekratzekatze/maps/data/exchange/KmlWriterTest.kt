package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Plain JVM: the writers do not touch android.*; the DOM parser here only validates well-formedness. */
class KmlWriterTest {
    private lateinit var saved: Locale

    @Before fun setLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun restoreLocale() { Locale.setDefault(saved) }

    private fun render(lists: List<ExchangeList>, name: String? = null) = buildString { KmlWriter.write(this, lists, name) }

    private fun dom(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test fun outputIsWellFormedWithEscapingEmojiAndUmlauts() {
        val xml = render(ExchangeTestData.sample, "Doc & <name>")
        val d = dom(xml)
        assertEquals("kml", d.documentElement.localName)
        assertEquals(KmlWriter.NAMESPACE, d.documentElement.namespaceURI)
        val names = d.getElementsByTagNameNS("*", "name")
        val all = (0 until names.length).map { names.item(it).textContent }
        assertTrue(all.contains(ExchangeTestData.nastyName))
        assertTrue(all.contains("Doc & <name>"))
        assertFalse(xml.contains("<Söhne>"))
    }

    @Test fun oneFolderPerListAndCoordinatesAreLonLatAlt() {
        val d = dom(render(ExchangeTestData.sample))
        val folders = d.getElementsByTagNameNS("*", "Folder")
        assertEquals(3, folders.length)
        val first = folders.item(0) as Element
        assertEquals(6, first.getElementsByTagNameNS("*", "Placemark").length)
        val coords = first.getElementsByTagNameNS("*", "coordinates").item(0).textContent
        assertEquals("13.3777,52.5163,0", coords)
        assertEquals(0, (folders.item(1) as Element).getElementsByTagNameNS("*", "Placemark").length)
    }

    @Test fun numbersIgnoreDefaultLocaleAndNeverUseExponent() {
        val xml = render(listOf(ExchangeList("l", places = listOf(
            ExchangePlace("a", LatLon(0.00001234, -0.0000001)),
            ExchangePlace("b", LatLon(-0.0, 1234567.0).copy(lat = 12.5, lon = 100.0)),
        ))))
        assertTrue(xml, xml.contains("<coordinates>-0.0000001,0.0000123,0</coordinates>"))
        assertTrue(xml, xml.contains("<coordinates>100,12.5,0</coordinates>"))
        assertFalse(xml.contains("E-"))
    }

    @Test fun driveLineStringHasOriginWaypointsDestinationInOrder() {
        val xml = render(ExchangeTestData.sample)
        assertTrue(xml, xml.contains("<coordinates>13.4,52.5,0 13.45,52.55,0 13.46,52.56,0 13.47,52.57,0 13.5,52.6,0</coordinates>"))
        // unset origin: no origin point
        assertTrue(xml, xml.contains("<coordinates>11,49,0 11.5755,48.1374,0</coordinates>"))
    }

    @Test fun destinationOnlyDriveIsMarkedPointNotDegenerateLineString() {
        val xml = render(listOf(ExchangeList("l", drives = listOf(ExchangeDrive("d", null, destination = LatLon(1.0, 2.0))))))
        assertFalse(xml.contains("LineString"))
        assertTrue(xml.contains("<Point><coordinates>2,1,0</coordinates></Point>"))
    }

    @Test fun invalidXmlCharactersAreDroppedButSurrogatePairsKept() {
        val xml = render(listOf(ExchangeList("a\u0000b\u0001c\uD800d😀", places = emptyList())))
        val d = dom(xml)
        assertEquals("abcd😀", d.getElementsByTagNameNS("*", "name").item(0).textContent)
    }

    @Test fun emptyInputAndNoDocumentName() {
        val d = dom(render(emptyList()))
        assertEquals(0, d.getElementsByTagNameNS("*", "Folder").length)
        assertEquals(0, d.getElementsByTagNameNS("*", "name").length)
    }

    @Test fun largeListStreamsWithoutBuffering() {
        var chars = 0L
        var maxChunk = 0
        val sink = object : Appendable {
            override fun append(csq: CharSequence?): Appendable { chars += csq!!.length; maxChunk = maxOf(maxChunk, csq.length); return this }
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable { chars += end - start; return this }
            override fun append(c: Char): Appendable { chars++; return this }
        }
        val places = generateSequence(0) { it + 1 }.take(200_000).map { ExchangePlace("Place $it", LatLon(it % 90.0, it % 180.0)) }
        val list = ExchangeList("big", places = Iterable { places.iterator() })
        KmlWriter.write(sink, listOf(list))
        assertTrue(chars > 10_000_000)
        assertTrue(maxChunk < 1000)
    }
}
