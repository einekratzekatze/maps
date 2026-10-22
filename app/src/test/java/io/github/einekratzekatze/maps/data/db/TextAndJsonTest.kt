package io.github.einekratzekatze.maps.data.db

import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.RoutePoint
import io.github.einekratzekatze.maps.navigation.TravelMode as NavMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextAndJsonTest {
    @Test fun normalize() {
        assertEquals("munchen", TextNormalizer.normalize("MÜNCHEN"))
        assertEquals("strasse", TextNormalizer.normalize("Straße"))
        assertEquals("cafe einstein", TextNormalizer.normalize("Café  Einstein "))
        assertEquals("i", TextNormalizer.normalize("İ"))
        assertEquals("", TextNormalizer.normalize("   "))
    }

    @Test fun escapeLike() {
        assertEquals("50\\%", TextNormalizer.escapeLike("50%"))
        assertEquals("a\\_b", TextNormalizer.escapeLike("a_b"))
        assertEquals("a\\\\b", TextNormalizer.escapeLike("a\\b"))
        assertEquals("plain", TextNormalizer.escapeLike("plain"))
    }

    @Test fun waypointRoundTrip() {
        val pts = listOf(
            RoutePoint("Café Einstein", LatLon(52.5, 13.4)),
            RoutePoint(null, LatLon(52.6, 13.5)),
            RoutePoint("label only", null),
        )
        assertEquals(pts, WaypointJson.decode(WaypointJson.encode(pts)))
        assertEquals("[]", WaypointJson.encode(emptyList()))
    }

    @Test fun waypointMalformedIsEmpty() {
        assertEquals(emptyList<RoutePoint>(), WaypointJson.decode("not json"))
        assertEquals(emptyList<RoutePoint>(), WaypointJson.decode("{\"a\":1}"))
        assertEquals(emptyList<RoutePoint>(), WaypointJson.decode("[1,2]"))
        assertEquals(emptyList<RoutePoint>(), WaypointJson.decode(""))
    }

    @Test fun waypointLoneCoordinateIsAbsent() {
        val r = WaypointJson.decode("""[{"label":"x","lat":1.0}]""")
        assertEquals(listOf(RoutePoint("x", null)), r)
    }

    @Test fun enumConverters() {
        val c = EnumConverters()
        assertEquals("EXTERNAL", c.listTypeToString(ListType.EXTERNAL))
        assertEquals(ListType.NORMAL, c.stringToListType("NORMAL"))
        assertEquals(ImportantKind.CUSTOM, c.stringToImportantKind("CUSTOM"))
        assertEquals("TRANSIT", c.travelModeToString(TravelMode.TRANSIT))
        assertEquals(TravelMode.BIKE, c.stringToTravelMode("BIKE"))
    }

    @Test fun unknownEnumNames() {
        val c = EnumConverters()
        assertEquals(TravelMode.UNKNOWN, c.stringToTravelMode("HOVERCRAFT"))
        assertEquals(TravelMode.UNKNOWN, c.stringToTravelMode("drive")) // case-sensitive
        assertThrows(IllegalArgumentException::class.java) { c.stringToListType("BOGUS") }
        assertThrows(IllegalArgumentException::class.java) { c.stringToImportantKind("BOGUS") }
    }

    @Test fun travelModeMapping() {
        assertEquals(TravelMode.DRIVE, NavMode.Drive.toDb())
        assertEquals(TravelMode.BIKE, NavMode.Bike.toDb())
        assertEquals(TravelMode.WALK, NavMode.Walk.toDb())
        assertEquals(TravelMode.TRANSIT, NavMode.Transit.toDb())
        assertEquals(TravelMode.UNKNOWN, (null as NavMode?).toDb())
        NavMode.entries.forEach { assertEquals(it, it.toDb().toNavigation()) }
        assertEquals(null, TravelMode.UNKNOWN.toNavigation())
    }
}
