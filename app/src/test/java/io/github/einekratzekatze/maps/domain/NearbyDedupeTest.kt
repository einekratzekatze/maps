package io.github.einekratzekatze.maps.domain

import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyDedupeTest {
    private fun row(id: Long, name: String, lat: Double, lon: Double, list: Long) =
        NearbyPlaceRow(id, name, name.lowercase(), lat, lon, list, "L$list", list.toInt())

    @Test fun samePlaceInTwoListsIsOneGroup() {
        val g = dedupeNearby(listOf(row(1, "Cafe", 52.5, 13.4, 1), row(2, "Cafe", 52.500001, 13.400004, 2)))
        assertEquals(1, g.size)
        assertEquals(1L, g[0].nearest.id)
        assertEquals(listOf("L1", "L2"), g[0].lists.map { it.name })
    }

    @Test fun differentCoordinatesOrNamesStaySeparate() {
        val g = dedupeNearby(listOf(row(1, "Cafe", 52.5, 13.4, 1), row(2, "Cafe", 52.50002, 13.4, 1), row(3, "Bar", 52.5, 13.4, 1)))
        assertEquals(listOf(1L, 2L, 3L), g.map { it.nearest.id })
    }

    @Test fun sameListTwiceIsListedOnce() {
        val g = dedupeNearby(listOf(row(1, "Cafe", 1.0, 2.0, 7), row(2, "Cafe", 1.0, 2.0, 7)))
        assertEquals(1, g.single().lists.size)
    }

    @Test fun firstOccurrenceOrderIsKept() {
        val g = dedupeNearby(listOf(row(5, "B", 1.0, 1.0, 1), row(6, "A", 2.0, 2.0, 1), row(7, "B", 1.0, 1.0, 2)))
        assertEquals(listOf(5L, 6L), g.map { it.nearest.id })
    }
}
