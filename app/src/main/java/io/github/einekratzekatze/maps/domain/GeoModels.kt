package io.github.einekratzekatze.maps.domain

/** Pure Kotlin domain types (compose-host.md §6.1). No Android, no Parcelable. */

data class LatLon(val lat: Double, val lon: Double)

enum class CameraMode { Map, Satellite3dMeters, StreetView, Tilted }

/**
 * [zoom] is the web zoom level, except for [CameraMode.Satellite3dMeters] where it holds the raw
 * span in metres from the URL (`1234m`); [io.github.einekratzekatze.maps.map.WebMercator]
 * converts it using the viewport height.
 */
data class MapCamera(val center: LatLon, val zoom: Double, val mode: CameraMode)

/** [west] > [east] means the box crosses the antimeridian. Longitudes are in [-180, 180]. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val crossesAntimeridian: Boolean get() = west > east

    fun contains(p: LatLon): Boolean {
        if (p.lat.isNaN() || p.lon.isNaN() || p.lat < south || p.lat > north) return false
        val lon = normalizeLon(p.lon)
        return if (crossesAntimeridian) lon >= west || lon <= east else lon in west..east
    }

    private fun normalizeLon(lon: Double): Double {
        if (lon in -180.0..180.0) return lon
        val m = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return m
    }
}

enum class MapMode { Map, Search, Place, Directions, StreetView, NonMap }

data class MapUrlState(
    val camera: MapCamera?, // null = viewport unknown
    val mode: MapMode,
    val displayText: String?, // decoded search query (Search) or place name (Place), else null
) {
    companion object {
        val Unknown = MapUrlState(null, MapMode.NonMap, null)
    }
}
