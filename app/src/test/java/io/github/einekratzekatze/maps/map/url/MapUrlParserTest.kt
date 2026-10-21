package io.github.einekratzekatze.maps.map.url

import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapCamera
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.domain.MapUrlState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class MapUrlParserTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private fun check(url: String?, mode: MapMode, cam: MapCamera?, text: String? = null) {
        assertEquals(url, MapUrlState(cam, mode, text), MapUrlParser.parse(url))
    }

    private fun c(lat: Double, lon: Double, z: Double, m: CameraMode = CameraMode.Map) =
        MapCamera(LatLon(lat, lon), z, m)

    @Test fun specFixtures() {
        check("https://www.google.com/maps/@52.5200066,13.404954,15z", MapMode.Map, c(52.5200066, 13.404954, 15.0))
        check("https://www.google.com/maps/@52.52,13.40,14.5z?entry=ttu", MapMode.Map, c(52.52, 13.40, 14.5))
        check(
            "https://www.google.com/maps/place/Brandenburger+Tor/@52.5162746,13.3755154,17z/data=!3m1!4b1",
            MapMode.Place, c(52.5162746, 13.3755154, 17.0), "Brandenburger Tor",
        )
        check("https://www.google.com/maps/search/cafe/@48.13,11.57,13z", MapMode.Search, c(48.13, 11.57, 13.0), "cafe")
        check("https://www.google.com/maps/dir/A/B/@52.5,13.4,12z", MapMode.Directions, c(52.5, 13.4, 12.0))
        check(
            "https://www.google.com/maps/@52.5,13.4,3000m/data=!3m1!1e3",
            MapMode.Map, c(52.5, 13.4, 3000.0, CameraMode.Satellite3dMeters),
        )
        check(
            "https://www.google.com/maps/@52.51,13.37,3a,75y,90h,80t/data=!3m6",
            MapMode.StreetView, c(52.51, 13.37, 3.0, CameraMode.StreetView),
        )
        check("https://www.google.com/maps", MapMode.Map, null)
        check("https://www.google.com/maps/@-33.86,151.20,12z", MapMode.Map, c(-33.86, 151.20, 12.0))
        check("https://www.google.com/maps/d/edit?mid=abc", MapMode.NonMap, null)
        check("https://consent.google.com/ml?continue=https://www.google.com/maps", MapMode.NonMap, null)
        check("about:blank", MapMode.NonMap, null)
        assertEquals(MapUrlState.Unknown, MapUrlParser.parse(null))
    }

    @Test fun tilted() {
        check("https://www.google.com/maps/@52.5,13.4,15z,45t", MapMode.Map, c(52.5, 13.4, 15.0, CameraMode.Tilted))
        check("https://www.google.com/maps/@52.5,13.4,15z,0h,60t", MapMode.Map, c(52.5, 13.4, 15.0, CameraMode.Tilted))
        check("https://www.google.com/maps/@52.5,13.4,15z,90h", MapMode.Map, c(52.5, 13.4, 15.0, CameraMode.Tilted))
        check("https://www.google.com/maps/@52.5,13.4,15z,0t,0h", MapMode.Map, c(52.5, 13.4, 15.0))
    }

    @Test fun zoomFractions() {
        assertEquals(15.25, MapUrlParser.parse("https://www.google.com/maps/@1,2,15.25z").camera!!.zoom, 0.0)
        assertEquals(0.0, MapUrlParser.parse("https://www.google.com/maps/@1,2,0z").camera!!.zoom, 0.0)
        assertEquals(21.5, MapUrlParser.parse("https://www.google.com/maps/@1,2,21.5z").camera!!.zoom, 0.0)
    }

    @Test fun otherHostAndSchemeRules() {
        check("https://maps.google.com/maps/@1.5,2.5,10z", MapMode.Map, c(1.5, 2.5, 10.0))
        check("http://WWW.GOOGLE.COM/maps/@1.5,2.5,10z", MapMode.Map, c(1.5, 2.5, 10.0))
        check("https://maps.google.com/maps?q=Marienplatz", MapMode.Map, null)
        check("https://www.google.com/search?q=maps", MapMode.NonMap, null)
        check("https://www.google.com/mapsfoo/@1,2,3z", MapMode.NonMap, null)
        check("https://www.google.com.evil.com/maps/@1,2,3z", MapMode.NonMap, null)
        check("https://example.com/maps/@1,2,3z", MapMode.NonMap, null)
        check("https://www.google.com/maps/d/viewer?mid=1", MapMode.NonMap, null)
        check("https://www.google.com/maps/d", MapMode.NonMap, null)
        check("ftp://www.google.com/maps", MapMode.NonMap, null)
    }

    @Test fun cameraInQueryOrFragmentIsIgnored() {
        check("https://www.google.com/maps?x=@1,2,3z", MapMode.Map, null)
        check("https://www.google.com/maps#@1,2,3z", MapMode.Map, null)
    }

    @Test fun malformedUrls() {
        for (u in listOf("", " ", "not a url", "https://", "https:///maps", "://", "%%%", "https://www.google.com/maps/@,,z",
            "https://www.google.com/maps/@52.5,13.4,z", "https://www.google.com/maps/@52.5,,15z",
            "https://www.google.com/maps/@abc,def,15z", "https://www.google.com/maps/@52,5,13,4,15z",
            "https://[::1/maps")) {
            val s = MapUrlParser.parse(u)
            assertNull(u, s.camera)
        }
        assertEquals(MapUrlState.Unknown, MapUrlParser.parse(""))
        assertEquals(MapUrlState.Unknown, MapUrlParser.parse("%%%"))
    }

    @Test fun outOfRangeCoordinatesGiveNoCamera() {
        check("https://www.google.com/maps/@91,10,10z", MapMode.Map, null)
        check("https://www.google.com/maps/@10,181,10z", MapMode.Map, null)
        check("https://www.google.com/maps/@-90,-180,2z", MapMode.Map, c(-90.0, -180.0, 2.0))
        check("https://www.google.com/maps/@10,10,0m", MapMode.Map, null)
    }

    @Test fun germanCommaDecimalsAreNotAccepted() {
        check("https://www.google.com/maps/@52,5,13,4,15z", MapMode.Map, null)
    }

    @Test fun displayTextDecoding() {
        assertEquals("café & bar", MapUrlParser.parse("https://www.google.com/maps/search/caf%C3%A9+%26+bar/@1,2,3z").displayText)
        assertEquals("München Hbf", MapUrlParser.parse("https://www.google.com/maps/place/M%C3%BCnchen+Hbf/@48.1,11.5,17z/data=x").displayText)
        assertEquals("100% sure", MapUrlParser.parse("https://www.google.com/maps/search/100%25+sure").displayText)
        assertEquals("bad%zz x", MapUrlParser.parse("https://www.google.com/maps/search/bad%zz+x").displayText)
        assertNull(MapUrlParser.parse("https://www.google.com/maps/search/?api=1&query=x").displayText)
        assertNull(MapUrlParser.parse("https://www.google.com/maps/search/@1,2,3z").displayText)
        assertNull(MapUrlParser.parse("https://www.google.com/maps/place/52.5,13.4/@52.5,13.4,17z").displayText)
        assertNull(MapUrlParser.parse("https://www.google.com/maps/@1,2,3z").displayText)
        assertNull(MapUrlParser.parse("https://www.google.com/maps/dir/A/B/@1,2,3z").displayText)
    }

    @Test fun placeWithoutCameraAndSearchWithoutCamera() {
        check("https://www.google.com/maps/place/Foo", MapMode.Place, null, "Foo")
        check("https://www.google.com/maps/search/bar", MapMode.Search, null, "bar")
    }

    @Test fun streetViewWithPlaceInPath() {
        val s = MapUrlParser.parse("https://www.google.com/maps/place/X/@1,2,3a,75y/data=1")
        assertEquals(MapMode.StreetView, s.mode)
        assertEquals(CameraMode.StreetView, s.camera!!.mode)
    }
}
