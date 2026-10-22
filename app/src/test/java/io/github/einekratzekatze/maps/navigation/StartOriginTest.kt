package io.github.einekratzekatze.maps.navigation

import io.github.einekratzekatze.maps.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartOriginTest {
    private val base = "https://www.google.com/maps/dir"

    @Test fun readsNamedOrigin() {
        assertEquals(
            "Brandenburg Gate, Pariser Platz, 10117 Berlin",
            StartOrigin.parse("$base/Brandenburg+Gate,+Pariser+Platz,+10117+Berlin/Alexanderplatz/@52.5,13.4,13z/data=!3e0?x=1"),
        )
    }

    @Test fun readsCoordinateOrigin() {
        assertEquals("52.5, 13.4", StartOrigin.parse("$base/52.5,+13.4/Alexanderplatz/@52.5,13.4,13z"))
    }

    @Test fun missingOrUnusableOriginIsNull() {
        assertNull(StartOrigin.parse(null))
        assertNull(StartOrigin.parse("$base//Alexanderplatz/@52.5,13.4,13z"))
        assertNull(StartOrigin.parse("$base/@52.5,13.4,13z"))
        assertNull(StartOrigin.parse("https://www.google.com/maps/place/Foo/@52.5,13.4,13z"))
        assertNull(StartOrigin.parse("$base/%ZZ/B/"))
    }

    @Test fun routeUrlCarriesOriginAndDestination() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=Brandenburg%20Gate&destination=52.53539%2C13.402343",
            NavIntentBuilder.routeUrl("Brandenburg Gate", NavTarget(LatLon(52.53539, 13.402343), null)),
        )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=A&destination=Alexanderplatz",
            NavIntentBuilder.routeUrl("A", NavTarget(null, "Alexanderplatz")),
        )
    }
}
