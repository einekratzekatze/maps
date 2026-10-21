package io.github.einekratzekatze.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoModelsTest {
    @Test fun plainBoundsContain() {
        val b = GeoBounds(south = 10.0, west = 20.0, north = 30.0, east = 40.0)
        assertFalse(b.crossesAntimeridian)
        assertTrue(b.contains(LatLon(20.0, 30.0)))
        assertTrue(b.contains(LatLon(10.0, 20.0)))
        assertTrue(b.contains(LatLon(30.0, 40.0)))
        assertFalse(b.contains(LatLon(9.99, 30.0)))
        assertFalse(b.contains(LatLon(20.0, 40.01)))
    }

    @Test fun antimeridianBoundsContain() {
        val b = GeoBounds(-10.0, 170.0, 10.0, -170.0)
        assertTrue(b.crossesAntimeridian)
        assertTrue(b.contains(LatLon(0.0, 175.0)))
        assertTrue(b.contains(LatLon(0.0, -175.0)))
        assertTrue(b.contains(LatLon(0.0, 180.0)))
        assertTrue(b.contains(LatLon(0.0, -180.0)))
        assertFalse(b.contains(LatLon(0.0, 0.0)))
        assertFalse(b.contains(LatLon(0.0, 169.0)))
    }

    @Test fun outOfRangeLongitudeIsNormalised() {
        val b = GeoBounds(-10.0, 170.0, 10.0, -170.0)
        assertTrue(b.contains(LatLon(0.0, 185.0)))
        assertFalse(b.contains(LatLon(0.0, 360.0)))
    }

    @Test fun lon180And180AreTheSameMeridian() {
        val b = GeoBounds(-10.0, -180.0, 10.0, -170.0)
        assertTrue(b.contains(LatLon(0.0, 180.0)))
        assertTrue(GeoBounds(-10.0, 170.0, 10.0, 180.0).contains(LatLon(0.0, -180.0)))
        assertFalse(b.contains(LatLon(0.0, 0.0)))
    }

    @Test fun sharedValidatorAndNormalisation() {
        assertEquals(LatLon(1.0, 2.0), LatLon.ofOrNull(1.0, 2.0))
        assertEquals(null, LatLon.ofOrNull(91.0, 0.0))
        assertEquals(null, LatLon.ofOrNull(0.0, 180.5))
        assertEquals(null, LatLon.ofOrNull(Double.NaN, 0.0))
        assertEquals(null, LatLon.ofOrNull(null, 0.0))
        assertEquals(-180.0, normalizeLon(180.0), 0.0)
        assertEquals(-175.0, normalizeLon(185.0), 0.0)
        assertEquals(10.0, normalizeLon(-350.0), 1e-9)
    }

    @Test fun nanIsNeverContained() {
        assertFalse(GeoBounds(-90.0, -180.0, 90.0, 180.0).contains(LatLon(Double.NaN, 0.0)))
    }

    @Test fun unknownState() {
        assertEquals(MapMode.NonMap, MapUrlState.Unknown.mode)
        assertEquals(null, MapUrlState.Unknown.camera)
    }
}
