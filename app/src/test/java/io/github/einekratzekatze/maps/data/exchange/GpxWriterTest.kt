package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class GpxWriterTest {
    private lateinit var saved: Locale

    @Before fun setLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun restoreLocale() { Locale.setDefault(saved) }

    private fun render(lists: List<ExchangeList>, name: String? = null) = buildString { GpxWriter.write(this, lists, name) }
    private fun dom(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun children(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    @Test fun rootIsGpx11WithNamespaceAndSchemaLocation() {
        val root = dom(render(ExchangeTestData.sample, "Export")).documentElement
        assertEquals("gpx", root.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue(root.getAttribute("creator").isNotEmpty())
        assertTrue(root.getAttribute("xsi:schemaLocation").endsWith("gpx.xsd"))
    }

    @Test fun elementOrderFollowsSchemaMetadataWptTrk() {
        val root = dom(render(ExchangeTestData.sample, "Export")).documentElement
        val names = children(root).map { it.localName }
        assertEquals("metadata", names.first())
        val firstTrk = names.indexOf("trk")
        assertTrue(names.indexOf("wpt") < firstTrk)
        assertTrue(names.subList(firstTrk, names.size).all { it == "trk" })
        // wpt child order: name, desc, type, extensions
        val wpt = children(root).first { it.localName == "wpt" }
        assertEquals(listOf("name", "desc", "type", "extensions"), children(wpt).map { it.localName })
        // trk child order: name, type, extensions, trkseg
        val trk = children(root).first { it.localName == "trk" }
        assertEquals(listOf("name", "type", "extensions", "trkseg"), children(trk).map { it.localName })
    }

    @Test fun waypointsAndTracksCountAndAttributesAreLocaleIndependent() {
        val root = dom(render(ExchangeTestData.sample)).documentElement
        val wpts = children(root).filter { it.localName == "wpt" }
        assertEquals(4, wpts.size)
        assertEquals("52.5163", wpts[0].getAttribute("lat"))
        assertEquals("13.3777", wpts[0].getAttribute("lon"))
        val trks = children(root).filter { it.localName == "trk" }
        assertEquals(3, trks.size)
        val pts = trks[0].getElementsByTagNameNS("*", "trkpt")
        assertEquals(5, pts.length)
        assertEquals("52.5", (pts.item(0) as Element).getAttribute("lat"))
        assertEquals("52.6", (pts.item(4) as Element).getAttribute("lat"))
        assertEquals(2, trks[1].getElementsByTagNameNS("*", "trkpt").length) // unset origin: waypoint + dest
    }

    @Test fun escapingAndEmptyInput() {
        val xml = render(listOf(ExchangeList("l", places = listOf(ExchangePlace(ExchangeTestData.nastyName, LatLon(1.0, 2.0))))))
        val d = dom(xml)
        assertEquals(ExchangeTestData.nastyName, d.getElementsByTagNameNS("*", "name").item(0).textContent)
        val empty = dom(render(emptyList())).documentElement
        assertEquals(0, children(empty).size)
    }
}
