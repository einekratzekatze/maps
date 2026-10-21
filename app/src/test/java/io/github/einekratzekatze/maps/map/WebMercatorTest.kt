package io.github.einekratzekatze.maps.map

import androidx.compose.ui.unit.IntSize
import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapCamera
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class WebMercatorTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private fun cam(lat: Double, lon: Double, z: Double, mode: CameraMode = CameraMode.Map) =
        MapCamera(LatLon(lat, lon), z, mode)

    @Test fun specTestVector() {
        val b = WebMercator.bounds(cam(0.0, 0.0, 1.0), IntSize(256, 256))
        assertEquals(-90.0, b.west, 1e-9)
        assertEquals(90.0, b.east, 1e-9)
        assertEquals(66.5133, b.north, 1e-4)
        assertEquals(-66.5133, b.south, 1e-4)
        assertFalse(b.crossesAntimeridian)
    }

    @Test fun zoomZeroIsWholeWorld() {
        val b = WebMercator.bounds(cam(10.0, 20.0, 0.0), IntSize(400, 800))
        assertEquals(-180.0, b.west, 0.0)
        assertEquals(180.0, b.east, 0.0)
        assertEquals(WebMercator.MAX_LAT, b.north, 1e-9)
        assertEquals(-WebMercator.MAX_LAT, b.south, 1e-9)
    }

    @Test fun widthEqualToWorldIsWholeWorld() {
        val b = WebMercator.bounds(cam(0.0, 100.0, 1.0), IntSize(512, 100))
        assertEquals(-180.0, b.west, 0.0)
        assertEquals(180.0, b.east, 0.0)
    }

    @Test fun fractionalZoomIsBetweenIntegers() {
        val vp = IntSize(360, 640)
        val w14 = WebMercator.bounds(cam(52.5, 13.4, 14.0), vp).let { it.east - it.west }
        val w145 = WebMercator.bounds(cam(52.5, 13.4, 14.5), vp).let { it.east - it.west }
        val w15 = WebMercator.bounds(cam(52.5, 13.4, 15.0), vp).let { it.east - it.west }
        assertEquals(w14 / 2, w15, 1e-9)
        assertEquals(w14 / Math.sqrt(2.0), w145, 1e-9)
    }

    @Test fun centreIsInsideAndSymmetricInLongitude() {
        val b = WebMercator.bounds(cam(52.52, 13.405, 15.25), IntSize(360, 640))
        assertTrue(b.contains(LatLon(52.52, 13.405)))
        assertEquals(13.405, (b.west + b.east) / 2, 1e-9)
    }

    @Test fun antimeridianCrossing() {
        val b = WebMercator.bounds(cam(0.0, 179.9, 5.0), IntSize(400, 400))
        assertTrue(b.crossesAntimeridian)
        assertTrue(b.west in 170.0..180.0)
        assertTrue(b.east in -180.0..-170.0)
        assertTrue(b.contains(LatLon(0.0, 179.95)))
        assertTrue(b.contains(LatLon(0.0, -179.95)))
        assertFalse(b.contains(LatLon(0.0, 0.0)))
    }

    @Test fun antimeridianCrossingWest() {
        val b = WebMercator.bounds(cam(0.0, -179.9, 5.0), IntSize(400, 400))
        assertTrue(b.crossesAntimeridian)
        assertTrue(b.contains(LatLon(0.0, 179.9)))
    }

    @Test fun eastEdgeExactlyOnAntimeridianDoesNotWrap() {
        // z=1 -> W=512; centre lon 90 with width 256 -> east = 180 exactly
        val b = WebMercator.bounds(cam(0.0, 90.0, 1.0), IntSize(256, 256))
        assertEquals(0.0, b.west, 1e-9)
        assertEquals(180.0, b.east, 1e-9)
        assertFalse(b.crossesAntimeridian)
    }

    @Test fun polarLatitudesAreClamped() {
        val b = WebMercator.bounds(cam(89.9, 0.0, 3.0), IntSize(400, 800))
        assertEquals(WebMercator.MAX_LAT, b.north, 1e-9)
        assertTrue(b.south < b.north)
        assertTrue(b.south in 60.0..80.0)
        val s = WebMercator.bounds(cam(-90.0, 0.0, 3.0), IntSize(400, 800))
        assertEquals(-WebMercator.MAX_LAT, s.south, 1e-9)
        assertTrue(s.north < -60.0 && s.north > s.south)
    }

    @Test fun tiltedIsExpandedOneAndAHalf() {
        val vp = IntSize(360, 640)
        val flat = WebMercator.bounds(cam(10.0, 10.0, 12.0), vp)
        val tilted = WebMercator.bounds(cam(10.0, 10.0, 12.0, CameraMode.Tilted), vp)
        assertEquals(1.5 * (flat.east - flat.west), tilted.east - tilted.west, 1e-9)
        assertTrue(tilted.north > flat.north && tilted.south < flat.south)
    }

    @Test fun satelliteMetersConvertsViaViewportHeight() {
        val vp = IntSize(360, 640)
        // metres chosen so that the equivalent zoom is exactly 15 at the equator
        val meters = WebMercator.METERS_CONSTANT * 640 / Math.pow(2.0, 15.0)
        val sat = WebMercator.bounds(cam(0.0, 0.0, meters, CameraMode.Satellite3dMeters), vp)
        val flat = WebMercator.bounds(cam(0.0, 0.0, 15.0), vp)
        assertEquals(flat.west, sat.west, 1e-9)
        assertEquals(flat.north, sat.north, 1e-9)
    }

    @Test fun invalidInputsGiveNull() {
        assertNull(WebMercator.boundsOrNull(cam(0.0, 0.0, 5.0, CameraMode.StreetView), IntSize(10, 10)))
        assertNull(WebMercator.boundsOrNull(cam(0.0, 0.0, 5.0), IntSize(0, 10)))
        assertNull(WebMercator.boundsOrNull(cam(0.0, 0.0, 0.0, CameraMode.Satellite3dMeters), IntSize(10, 10)))
        assertNull(WebMercator.boundsOrNull(cam(Double.NaN, 0.0, 5.0), IntSize(10, 10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun streetViewBoundsThrows() {
        WebMercator.bounds(cam(0.0, 0.0, 3.0, CameraMode.StreetView), IntSize(10, 10))
    }
}
