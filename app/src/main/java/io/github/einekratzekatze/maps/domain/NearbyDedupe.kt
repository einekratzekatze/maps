package io.github.einekratzekatze.maps.domain

import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import kotlin.math.roundToLong

/** One distinct place in view; [nearest] is the first (nearest) row, [lists] every list it is saved in, in row order. */
data class NearbyGroup(val nearest: NearbyPlaceRow, val lists: List<ListRef>) {
    data class ListRef(val id: Long, val name: String, val color: Int)
}

/**
 * Groups rows that are the same place (floating-places-bubble.md §5.5): equal `nameNormalized`
 * and both coordinates equal after rounding to 5 decimals (about 1 m). Keeps the input order
 * of first occurrences, so nearest-first input gives nearest-first groups.
 */
fun dedupeNearby(rows: List<NearbyPlaceRow>): List<NearbyGroup> {
    val groups = LinkedHashMap<Triple<String, Long, Long>, MutableList<NearbyPlaceRow>>()
    for (row in rows) {
        val key = Triple(row.nameNormalized, (row.lat * 1e5).roundToLong(), (row.lon * 1e5).roundToLong())
        groups.getOrPut(key) { mutableListOf() } += row
    }
    return groups.values.map { members ->
        NearbyGroup(
            nearest = members.first(),
            lists = members.distinctBy { it.listId }.map { NearbyGroup.ListRef(it.listId, it.listName, it.listColor) },
        )
    }
}
