package io.github.einekratzekatze.maps.data.mymaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyMapsUrlTest {
    private val id = "1AbC_dEf-GhIjKlMnOpQrStUvWxY"

    @Test fun viewerWithUserSegment() =
        assertEquals(id, MyMapsUrl.extractMapId("https://www.google.com/maps/d/u/0/viewer?mid=$id"))

    @Test fun editWithoutUserSegment() =
        assertEquals(id, MyMapsUrl.extractMapId("https://www.google.com/maps/d/edit?mid=$id"))

    @Test fun extraParamsBeforeAndAfter() {
        assertEquals(id, MyMapsUrl.extractMapId("https://www.google.com/maps/d/u/1/viewer?mid=$id&ll=52.5%2C13.4&z=12"))
        assertEquals(id, MyMapsUrl.extractMapId("https://www.google.com/maps/d/viewer?hl=de&mid=$id"))
        assertEquals(id, MyMapsUrl.extractMapId("https://www.google.com/maps/d/viewer?mid=$id#frag"))
    }

    @Test fun whitespaceAndMissingScheme() {
        assertEquals(id, MyMapsUrl.extractMapId("  \n google.com/maps/d/u/0/viewer?mid=$id \t"))
        assertEquals(id, MyMapsUrl.extractMapId("www.google.de/maps/d/viewer?mid=$id"))
        assertEquals(id, MyMapsUrl.extractMapId("HTTP://WWW.GOOGLE.COM/maps/d/edit?mid=$id"))
    }

    @Test fun nonMatching() {
        assertNull(MyMapsUrl.extractMapId(""))
        assertNull(MyMapsUrl.extractMapId("not a url"))
        assertNull(MyMapsUrl.extractMapId("https://www.google.com/maps/@52.5,13.4,12z"))
        assertNull(MyMapsUrl.extractMapId("https://www.google.com/maps/d/viewer"))
        assertNull(MyMapsUrl.extractMapId("https://www.google.com/maps/d/viewer?mid="))
        assertNull(MyMapsUrl.extractMapId("https://evil.example/maps/d/viewer?mid=$id"))
        assertNull(MyMapsUrl.extractMapId("https://www.google.com.evil.example/maps/d/viewer?mid=$id"))
        assertNull(MyMapsUrl.extractMapId("https://www.google.com/maps/d/viewer?xmid=$id"))
    }

    @Test fun kmlUrl() {
        assertEquals("https://www.google.com/maps/d/kml?mid=$id", MyMapsUrl.kmlUrl(id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun kmlUrlRejectsInjection() { MyMapsUrl.kmlUrl("abc&x=1") }
}
