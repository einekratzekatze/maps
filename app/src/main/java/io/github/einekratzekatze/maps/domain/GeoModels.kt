package io.github.einekratzekatze.maps.domain

/** Pure Kotlin domain types (compose-host.md §6.1). No Android, no Parcelable. */

data class LatLon(val lat: Double, val lon: Double) {
    companion object {
        /** The one coordinate validator: finite, lat in [-90, 90], lon in [-180, 180]; else null. */
        fun ofOrNull(lat: Double?, lon: Double?): LatLon? {
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return LatLon(lat, lon)
        }
    }
}

enum class CameraMode { Map, Satellite3dMeters, StreetView, Tilted }

/**
 * [zoom] is the web zoom level, except for [CameraMode.Satellite3dMeters] where it holds the raw
 * span in metres from the URL (`1234m`); [io.github.einekratzekatze.maps.map.WebMercator]
 * converts it using the viewport height.
 */
data class MapCamera(
    val center: LatLon,
    val zoom: Double,
    val mode: CameraMode,
    /**
     * True when the URL carried a tilt/heading. [CameraMode.Tilted] implies it; it is needed for
     * [CameraMode.Satellite3dMeters] (`3000m,45t`), which cannot say "metres and tilted" by mode.
     */
    val tilted: Boolean = false,
)

/**
 * [west] > [east] means the box crosses the antimeridian. Longitudes are in [-180, 180].
 * Latitudes beyond +-85.0511 (the Web Mercator limit) never occur inside bounds computed by
 * `WebMercator`, so such places are never contained.
 */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val crossesAntimeridian: Boolean get() = west > east

    fun contains(p: LatLon): Boolean {
        if (p.lat.isNaN() || p.lon.isNaN() || p.lat < south || p.lat > north) return false
        val lon = normalizeLon(p.lon)
        if (containsLon(lon)) return true
        // +-180 are the same meridian; normalizeLon maps 180 to -180, so try the other spelling.
        return lon == -180.0 && containsLon(180.0)
    }

    private fun containsLon(lon: Double): Boolean =
        if (crossesAntimeridian) lon >= west || lon <= east else lon in west..east
}

/** Normalises any longitude into [-180, 180) (so 180 becomes -180). Shared by domain and map code. */
internal fun normalizeLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

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
