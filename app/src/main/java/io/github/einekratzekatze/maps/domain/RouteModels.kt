package io.github.einekratzekatze.maps.domain

import io.github.einekratzekatze.maps.navigation.TravelMode

/** A route point. label null and latLon null = "Your location" (nav-handoff.md 4.2). */
data class RoutePoint(val label: String?, val latLon: LatLon?) {
    val isCurrentLocation: Boolean get() = label == null && latLon == null
}

/**
 * A route as captured from Maps (nav-handoff.md 4.2). [mode] null = unknown (stored as UNKNOWN).
 * The storage enum `data.db.TravelMode` is mapped in the repository.
 */
data class RouteInfo(
    val origin: RoutePoint,
    val waypoints: List<RoutePoint>,
    val destination: RoutePoint,
    val mode: TravelMode?,
) {
    val isSavable: Boolean get() = destination.latLon != null
}
