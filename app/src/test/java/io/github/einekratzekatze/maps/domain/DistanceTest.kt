package io.github.einekratzekatze.maps.domain

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DistanceTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }
    @After fun tearDown() { Locale.setDefault(saved) }

    @Test fun haversineKnownDistances() {
        // Brandenburger Tor -> Alexanderplatz; flat-earth check: sqrt(627.5² + 2402.9²) ≈ 2483 m
        assertEquals(2483.5, Distance.meters(LatLon(52.5162746, 13.3777041), LatLon(52.521918, 13.413215)), 2.0)
        // One degree of latitude
        assertEquals(111_195.0, Distance.meters(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1.0)
        assertEquals(0.0, Distance.meters(LatLon(48.1, 11.5), LatLon(48.1, 11.5)), 0.0)
    }

    @Test fun haversineAcrossTheAntimeridian() {
        assertEquals(22_239.0, Distance.meters(LatLon(0.0, 179.9), LatLon(0.0, -179.9)), 1.0)
    }

    @Test fun antipodesDoNotProduceNaN() {
        assertEquals(Math.PI * Distance.EARTH_RADIUS_M, Distance.meters(LatLon(0.0, 0.0), LatLon(0.0, 180.0)), 1.0)
    }

    @Test fun formatSteps() {
        val en = Locale.US
        assertEquals("0 m", Distance.format(4.0, en))
        assertEquals("350 m", Distance.format(347.0, en))
        assertEquals("990 m", Distance.format(994.9, en))
        assertEquals("1.0 km", Distance.format(995.0, en))
        assertEquals("1.2 km", Distance.format(1234.0, en))
        assertEquals("10 km", Distance.format(9960.0, en))
        assertEquals("12 km", Distance.format(12_400.0, en))
        assertEquals("1,234 km", Distance.format(1_234_000.0, en))
    }

    @Test fun formatUsesTheGivenLocaleNotTheDefault() {
        assertEquals("1,2 km", Distance.format(1234.0, Locale.GERMANY))
        assertEquals("1.234 km", Distance.format(1_234_000.0, Locale.GERMANY))
        assertEquals("1.2 km", Distance.format(1234.0, Locale.US)) // default is GERMANY here
    }

    @Test fun formatRejectsInvalidInput() {
        assertEquals("–", Distance.format(-1.0, Locale.US))
        assertEquals("–", Distance.format(Double.NaN, Locale.US))
    }
}
