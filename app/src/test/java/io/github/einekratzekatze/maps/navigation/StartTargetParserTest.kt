package io.github.einekratzekatze.maps.navigation

import io.github.einekratzekatze.maps.domain.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartTargetParserTest {
    private val fixtures = javaClass.getResourceAsStream("/nav-fixtures.txt")!!.bufferedReader().readLines()
        .filter { !it.startsWith("#") && it.isNotBlank() }.map { JSONObject(it) }

    private fun starts() = fixtures.filter { it.getString("current").contains("/maps/dir/") }
    private fun https(link: String) = "https://maps.app.goo.gl/?apn=x&link=" + java.net.URLEncoder.encode(link, "UTF-8")
    private fun dir(dest: String, tail: String = "/@52.5,13.4,13z/data=!4m2!3e0") =
        https("https://www.google.com/maps/dir//$dest$tail")

    @Test fun namedPlaceFixturesGiveLabelOnly() {
        val named = starts().filter { it.getString("url").contains("Alexanderplatz") }
        assertEquals(4, named.size)
        named.forEach {
            val h = StartTargetParser.parse(it.getString("url"))!!
            assertEquals(NavTarget(null, "Alexanderplatz, Mitte, 10178 Berlin"), h.target)
            assertEquals(TravelMode.Drive, h.mode)
        }
    }

    @Test fun droppedPinWalkFixtureGivesCoordinatesAndWalk() {
        val pins = starts().filter { it.getString("url").contains("3e2") }
        assertEquals(2, pins.size)
        pins.forEach {
            val h = StartTargetParser.parse(it.getString("url"))!!
            assertEquals(NavTarget(LatLon(52.53539, 13.402343), null), h.target)
            assertEquals(TravelMode.Walk, h.mode)
        }
    }

    @Test fun directionsFixturesStillParseButAreNotStartsSoCallerNeverUsesThem() {
        // Directions events carry the same link shape; isStart is what keeps them away from the launcher.
        fixtures.filter { it.getString("current").contains("/maps/place/") }.forEach {
            assertEquals(false, StartPress.isStart(it.getString("url"), it.getString("current")))
        }
    }

    @Test fun cameraIsNeverTheDestination() {
        val h = StartTargetParser.parse(dir("Foo"))!!
        assertNull(h.target.latLon)
    }

    @Test fun missingOrBadLinkIsNull() {
        assertNull(StartTargetParser.parse("https://maps.app.goo.gl/?apn=x"))
        assertNull(StartTargetParser.parse("https://maps.app.goo.gl/?link="))
        assertNull(StartTargetParser.parse("https://example.com/?link=https%3A%2F%2Fwww.google.com%2Fmaps%2Fdir%2F%2FFoo"))
        assertNull(StartTargetParser.parse(https("https://evil.example/maps/dir//Foo/")))
        assertNull(StartTargetParser.parse(https("https://www.google.com/maps/place/Foo/")))
        assertNull(StartTargetParser.parse(https("https://www.google.com/maps/dir/Origin/Foo/")))
        assertNull(StartTargetParser.parse(https("https://www.google.com/maps/dir///@52.5,13.4,13z")))
        assertNull(StartTargetParser.parse(https("https://www.google.com/maps/dir//@52.5,13.4,13z/data=!3e0")))
        assertNull(StartTargetParser.parse(dir("%ZZ")))
    }

    @Test fun doubleEncodedLinkIsDecodedOnce() {
        val inner = "https://www.google.com/maps/dir//Foo,+Bar/@1,2,3z"
        val twice = java.net.URLEncoder.encode(java.net.URLEncoder.encode(inner, "UTF-8"), "UTF-8")
        val h = StartTargetParser.parse("https://maps.app.goo.gl/?link=$twice")!!
        assertEquals(NavTarget(null, "Foo, Bar"), h.target)
    }

    @Test fun labelWithUnicodeAmpersandAndParens() {
        val h = StartTargetParser.parse(dir("Caf%C3%A9+%26+Bar+(Mitte)%2C+M%C3%BCnchen"))!!
        assertEquals("Café & Bar (Mitte), München", h.target.label)
    }

    @Test fun plusEncodedAsPercentIsLiteralPlus() {
        assertEquals("C++ Cafe", StartTargetParser.parse(dir("C%2B%2B+Cafe"))!!.target.label)
    }

    @Test fun coordinatesAcceptedAndValidated() {
        assertEquals(LatLon(-33.8688, 151.2093), StartTargetParser.parse(dir("-33.8688,+151.2093"))!!.target.latLon)
        assertEquals(LatLon(52.5, 13.4), StartTargetParser.parse(dir("52.5,13.4"))!!.target.latLon)
    }

    @Test fun antimeridian() {
        assertEquals(LatLon(-16.5, 179.9999), StartTargetParser.parse(dir("-16.5,+179.9999"))!!.target.latLon)
        assertEquals(LatLon(-16.5, -180.0), StartTargetParser.parse(dir("-16.5,+-180"))!!.target.latLon)
        assertNull(StartTargetParser.parse(dir("-16.5,+180.0001")))
    }

    @Test fun outOfRangeOrSwappedCoordinatesAreNull() {
        assertNull(StartTargetParser.parse(dir("139.5,+52.5"))) // swapped lon/lat with lat > 90
        assertNull(StartTargetParser.parse(dir("52.5,13.4,7")))
        assertNull(StartTargetParser.parse(dir("999,5")))
    }

    @Test fun dataCoordinatesMustAgreeWithPathAndRefinePrecision() {
        val tail = "/@1,2,3z/data=!4m3!1m1!2m2!1d13.4023429!2d52.53539"
        assertEquals(LatLon(52.53539, 13.4023429), StartTargetParser.parse(dir("52.535390,+13.402343", tail))!!.target.latLon)
        // swapped in data: lon-first order violated
        assertNull(StartTargetParser.parse(dir("52.535390,+13.402343", "/@1,2,3z/data=!1d52.53539!2d13.4023429")))
    }

    @Test fun modes() {
        fun mode(n: String) = StartTargetParser.parse(dir("Foo", "/@1,2,3z/data=!3e$n"))!!.mode
        assertEquals(TravelMode.Drive, mode("0"))
        assertEquals(TravelMode.Bike, mode("1"))
        assertEquals(TravelMode.Walk, mode("2"))
        assertEquals(TravelMode.Transit, mode("3"))
        assertNull(mode("9"))
        assertNull(StartTargetParser.parse(dir("Foo", "/@1,2,3z"))!!.mode)
        assertNotNull(StartTargetParser.parse(dir("Foo", "")))
    }
}
