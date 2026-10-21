package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.data.exchange.ExchangeTestData.materialized
import io.github.einekratzekatze.maps.domain.LatLon
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because the readers use android.util.Xml.newPullParser(). */
@RunWith(RobolectricTestRunner::class)
class ExchangeRoundTripTest {
    private lateinit var saved: Locale

    @Before fun setLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun restoreLocale() { Locale.setDefault(saved) }

    @Test fun kmlRoundTripIsLossless() {
        val out = ByteArrayOutputStream()
        KmlWriter.write(out, ExchangeTestData.sample, "My export")
        val doc = KmlReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("My export", doc.name)
        assertEquals(ExchangeTestData.sample.materialized(), doc.lists.materialized())
    }

    @Test fun gpxRoundTripKeepsEverythingExceptEmptyLists() {
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, ExchangeTestData.sample, "My export")
        val doc = GpxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("My export", doc.name)
        assertEquals(ExchangeTestData.sample.materialized().filter { it.name != "Empty list" }, doc.lists.materialized())
    }

    @Test fun unsetOriginSurvivesBothFormats() {
        val lists = listOf(ExchangeList("Drives", drives = listOf(ExchangeDrive("x", null, destination = LatLon(5.0, 6.0)))))
        val kml = buildString { KmlWriter.write(this, lists) }
        val gpx = buildString { GpxWriter.write(this, lists) }
        assertNull(KmlReader.read(kml.reader()).lists.single().drives.single().origin)
        assertNull(GpxReader.read(gpx.reader()).lists.single().drives.single().origin)
    }

    @Test fun nonUtf8DeclaredInputIsHonoured() {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><kml><Document><Placemark><name>Zürich</name><Point><coordinates>8.5,47.4</coordinates></Point></Placemark></Document></kml>"
        val doc = KmlReader.read(ByteArrayInputStream(xml.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("Zürich", doc.lists.single().places.single().name)
    }

    @Test(expected = ExchangeFormatException::class) fun malformedXmlThrowsFormatException() {
        KmlReader.read("<kml><Document>".reader())
    }

    @Test(expected = ExchangeFormatException::class) fun wrongRootThrows() {
        GpxReader.read("<html/>".reader())
    }
}
