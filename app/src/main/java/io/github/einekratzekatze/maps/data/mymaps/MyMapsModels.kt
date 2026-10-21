package io.github.einekratzekatze.maps.data.mymaps

import io.github.einekratzekatze.maps.domain.LatLon

/**
 * Pure parse result of a My Maps KML export (TODO 5.1). Mapping to Room (later, by the caller):
 * [ParsedMyMap.name] -> PlaceList name, [ParsedSublist] -> ExternalSublist (sortOrder = index),
 * [ParsedPlacemark] -> Place. Names are null when missing/blank; the caller picks a fallback
 * (schema.md has non-null name columns).
 */
data class ParsedMyMap(
    /** `<Document><name>`. */
    val name: String?,
    /** In KML document order. */
    val sublists: List<ParsedSublist>,
    /** Placemarks skipped because they are lines/polygons/tracks (no Point geometry). */
    val skippedNonPoint: Int = 0,
    /** Placemarks skipped because their coordinates were missing or malformed. */
    val skippedInvalid: Int = 0,
) {
    val placemarkCount: Int get() = sublists.sumOf { it.placemarks.size }
}

data class ParsedSublist(
    /** `<Folder><name>`; nested folders are joined as "Outer / Inner". Null for blank names. */
    val name: String?,
    /** True for the synthetic sublist holding placemarks that sit directly under Document/kml. */
    val isImplicit: Boolean,
    val placemarks: List<ParsedPlacemark>,
)

data class ParsedPlacemark(
    val name: String?,
    val description: String?,
    /** KML order is lon,lat; this is already converted to lat/lon. Altitude is dropped. */
    val position: LatLon,
    /** Raw `<styleUrl>`, e.g. "#icon-1899-0288D1-nodesc". */
    val styleUrl: String?,
    /** Resolved icon colour as 0xAARRGGBB (KML's aabbggrr converted), or null if unavailable. */
    val colorArgb: Int?,
    /** `<ExtendedData><Data name=..><value>` pairs (capped), in order. */
    val extendedData: Map<String, String> = emptyMap(),
)

class KmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
