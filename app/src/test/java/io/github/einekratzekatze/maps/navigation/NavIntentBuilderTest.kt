package io.github.einekratzekatze.maps.navigation

import io.github.einekratzekatze.maps.domain.LatLon
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NavIntentBuilderTest {
    @Test fun coordinatesOnly() {
        assertEquals("geo:52.53539,13.402343?q=52.53539,13.402343",
            NavIntentBuilder.geoUri(NavTarget(LatLon(52.53539, 13.402343), null)))
    }

    @Test fun coordinatesWithLabelEncoded() {
        assertEquals("geo:52.5,13.4?q=52.5,13.4(Caf%C3%A9%20%26%20Bar%20%28Mitte%29%2C%20100%25)",
            NavIntentBuilder.geoUri(NavTarget(LatLon(52.5, 13.4), "Café & Bar (Mitte), 100%")))
    }

    @Test fun nameOnly() {
        assertEquals("geo:0,0?q=Alexanderplatz%2C%20Mitte%2C%2010178%20Berlin",
            NavIntentBuilder.geoUri(NavTarget(null, "Alexanderplatz, Mitte, 10178 Berlin")))
    }

    @Test fun numbersIgnoreDefaultLocaleAndNegativeZero() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("geo:-33.8688,151.2093?q=-33.8688,151.2093",
                NavIntentBuilder.geoUri(NavTarget(LatLon(-33.8688, 151.2093), null)))
            assertEquals("geo:0,0?q=0,0", NavIntentBuilder.geoUri(NavTarget(LatLon(-0.0, 0.0), null)))
            assertEquals("geo:0.00001,-180?q=0.00001,-180", NavIntentBuilder.geoUri(NavTarget(LatLon(1e-5, -180.0), null)))
        } finally {
            Locale.setDefault(old)
        }
    }
}
