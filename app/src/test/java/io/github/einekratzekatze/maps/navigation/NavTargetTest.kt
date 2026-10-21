package io.github.einekratzekatze.maps.navigation

import io.github.einekratzekatze.maps.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class NavTargetTest {
    @Test fun acceptsEitherOrBoth() {
        NavTarget(LatLon(1.0, 2.0), null)
        NavTarget(null, "Home")
        assertEquals("x", NavTarget(LatLon(1.0, 2.0), "x").label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmpty() {
        NavTarget(null, null)
    }
}
