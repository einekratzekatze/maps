package io.github.einekratzekatze.maps.domain

/** A route point (origin, waypoint, destination). [latLon] null = unknown / "current location". */
data class RoutePoint(val label: String?, val latLon: LatLon?)
