package io.github.einekratzekatze.maps.map

import androidx.compose.ui.unit.IntSize
import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.MapCamera
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Pure camera + viewport -> bounds (floating-places-bubble.md §5.3). */
object WebMercator {
    const val TILE = 256.0
    const val MAX_LAT = 85.05112878
    const val TILT_EXPAND = 1.5
    const val METERS_CONSTANT = 156543.034

    /**
     * Bounds for a flat or tilted/satellite camera. Not defined for [CameraMode.StreetView]
     * (throws [IllegalArgumentException]); use [boundsOrNull] when the mode is not known to be
     * a map mode.
     */
    fun bounds(camera: MapCamera, viewportCssPx: IntSize): GeoBounds {
        require(camera.mode != CameraMode.StreetView) { "No viewport bounds in Street View" }
        return boundsOrNull(camera, viewportCssPx)
            ?: throw IllegalArgumentException("Invalid camera or viewport: $camera $viewportCssPx")
    }

    /** null for Street View, empty/negative viewport, non-finite input or unconvertible metres. */
    fun boundsOrNull(camera: MapCamera, viewportCssPx: IntSize): GeoBounds? {
        if (camera.mode == CameraMode.StreetView) return null
        if (viewportCssPx.width <= 0 || viewportCssPx.height <= 0) return null
        val lat = camera.center.lat
        val lon = camera.center.lon
        if (!lat.isFinite() || !lon.isFinite() || !camera.zoom.isFinite()) return null

        val clampedLat = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val zoom = when (camera.mode) {
            CameraMode.Satellite3dMeters -> {
                if (camera.zoom <= 0.0) return null
                log2(METERS_CONSTANT * cos(Math.toRadians(clampedLat)) * viewportCssPx.height / camera.zoom)
            }
            else -> camera.zoom
        }
        if (!zoom.isFinite()) return null

        val scale = if (camera.mode == CameraMode.Tilted) TILT_EXPAND else 1.0
        val w = viewportCssPx.width * scale
        val h = viewportCssPx.height * scale
        val worldSize = TILE * 2.0.pow(zoom.coerceIn(0.0, 30.0))

        val cy = latToY(clampedLat, worldSize)
        val north = yToLat(cy - h / 2, worldSize)
        val south = yToLat(cy + h / 2, worldSize)

        if (w >= worldSize) return GeoBounds(south, -180.0, north, 180.0)
        val cx = (lon + 180.0) / 360.0 * worldSize
        val west = normalizeWest((cx - w / 2) / worldSize * 360.0 - 180.0)
        val east = normalizeEast((cx + w / 2) / worldSize * 360.0 - 180.0)
        return GeoBounds(south, west, north, east)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun latToY(lat: Double, worldSize: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * worldSize
    }

    private fun yToLat(y: Double, worldSize: Double): Double {
        val n = PI * (1 - 2 * y / worldSize)
        return Math.toDegrees(atan(sinh(n))).coerceIn(-MAX_LAT, MAX_LAT)
    }

    /** Into [-180, 180). */
    private fun normalizeWest(lon: Double): Double {
        val m = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return m
    }

    /** Into (-180, 180], so a box ending exactly on the antimeridian does not look wrapped. */
    private fun normalizeEast(lon: Double): Double {
        val m = 180.0 - ((180.0 - lon) % 360.0 + 360.0) % 360.0
        return m
    }
}
