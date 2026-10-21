package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon

/**
 * Pure input/output models for KML/GPX import and export. They deliberately mirror the
 * `PlaceList` / `Place` / `Drive` entities of docs/design/schema.md without depending on Room;
 * repositories map between the two.
 *
 * [ExchangeList.places] and [ExchangeList.drives] are [Iterable]s (not [List]s) so an exporter can
 * pass a lazily-loaded, cursor-backed sequence; writers iterate each of them exactly once per
 * list and never hold more than one element.
 */
data class ExchangeList(
    val name: String,
    val places: Iterable<ExchangePlace> = emptyList(),
    val drives: Iterable<ExchangeDrive> = emptyList(),
)

data class ExchangePlace(
    val name: String,
    val position: LatLon,
    val address: String? = null,
    val notes: String? = null,
)

data class ExchangeWaypoint(val label: String?, val position: LatLon)

/**
 * A saved route. [origin] == null means "current location at replay time" (schema.md 1.4); it
 * is preserved through a round-trip, not replaced by a coordinate.
 * [travelMode] is the `TravelMode.name` string, kept as a string to avoid a dependency on the
 * data layer; unknown values are passed through untouched.
 */
data class ExchangeDrive(
    val name: String,
    val origin: LatLon?,
    val originLabel: String? = null,
    val destination: LatLon,
    val destinationLabel: String? = null,
    val waypoints: List<ExchangeWaypoint> = emptyList(),
    val travelMode: String? = null,
)

/** Result of reading a file. [name] is the document / metadata name when present. */
data class ExchangeDocument(val name: String?, val lists: List<ExchangeList>)

class ExchangeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
