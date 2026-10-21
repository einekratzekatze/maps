package io.github.einekratzekatze.maps.map.url

import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapCamera
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class MapUrlsTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private val berlin = LatLon(52.52, 13.405)

    @Test fun cameraUsesRootLocale() {
        assertEquals("https://www.google.com/maps/@52.5200000,13.4050000,15z", MapUrls.camera(berlin, 15.0))
        assertEquals("https://www.google.com/maps/@52.5200000,13.4050000,14.5z", MapUrls.camera(berlin, 14.5))
        assertEquals("https://www.google.com/maps/@52.5200000,13.4050000,14.25z", MapUrls.camera(berlin, 14.25))
    }

    @Test fun placeNegativeAndRounding() {
        assertEquals(
            "https://www.google.com/maps/place/-33.8600000,151.2000001/@-33.8600000,151.2000001,17z",
            MapUrls.place(LatLon(-33.86, 151.20000012), 17.0),
        )
    }

    @Test fun searchWithAndWithoutCamera() {
        assertEquals("https://www.google.com/maps/search/caf%C3%A9%20%26%20bar%2Fx", MapUrls.search("café & bar/x"))
        val cam = MapCamera(berlin, 13.0, CameraMode.Map)
        assertEquals("https://www.google.com/maps/search/cafe/@52.5200000,13.4050000,13z", MapUrls.search("cafe", cam))
        assertEquals("https://www.google.com/maps/search/cafe", MapUrls.search("cafe", cam.copy(mode = CameraMode.StreetView)))
        assertEquals("https://www.google.com/maps/search/cafe", MapUrls.search("cafe", cam.copy(mode = CameraMode.Satellite3dMeters)))
    }

    @Test fun prediction() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Caf%C3%A9%20Einstein%20%26%20Co&query_place_id=ChIJ_abc-1",
            MapUrls.prediction("Café Einstein & Co", "ChIJ_abc-1"),
        )
    }

    @Test fun roundTripThroughParser() {
        val cam = MapUrlParser.parse(MapUrls.camera(berlin, 14.25)).camera!!
        assertEquals(berlin, cam.center)
        assertEquals(14.25, cam.zoom, 0.0)
        val s = MapUrlParser.parse(MapUrls.search("Brandenburger Tor", MapCamera(berlin, 13.0, CameraMode.Map)))
        assertEquals("Brandenburger Tor", s.displayText)
        assertEquals(13.0, s.camera!!.zoom, 0.0)
        val p = MapUrlParser.parse(MapUrls.place(berlin, 17.0))
        assertEquals(17.0, p.camera!!.zoom, 0.0)
    }
}
