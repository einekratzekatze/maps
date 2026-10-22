package io.github.einekratzekatze.maps.map.url

import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapCamera
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.domain.MapUrlState
import java.net.URLDecoder

/** The one page-URL parser (compose-host.md §6.3, bubble spec §5.2). Pure JVM, never throws. */
object MapUrlParser {
    private val URL_REGEX = Regex("""^(https?)://([^/?#]*)([^?#]*)""", RegexOption.IGNORE_CASE)
    private val CAMERA_REGEX = Regex(
        """@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(\d+(?:\.\d+)?)([zma])((?:,-?\d+(?:\.\d+)?[yht])*)""",
    )
    private val SUFFIX_REGEX = Regex("""(-?\d+(?:\.\d+)?)([yht])""")
    private val COORD_TEXT = Regex("""-?\d+(?:\.\d+)?,\s*-?\d+(?:\.\d+)?""")
    private val HOSTS = setOf("www.google.com", "maps.google.com")

    fun parse(url: String?): MapUrlState {
        if (url == null) return MapUrlState.Unknown
        val m = URL_REGEX.find(url.trim()) ?: return MapUrlState.Unknown
        // WHATWG treats '\' as a path separator, so `evil.com\@www.google.com` has host evil.com.
        if ('\\' in m.groupValues[2]) return MapUrlState.Unknown
        val host = m.groupValues[2].substringAfterLast('@').substringBefore(':').lowercase()
        val path = m.groupValues[3]
        if (host !in HOSTS) return MapUrlState.Unknown
        if (path != "/maps" && !path.startsWith("/maps/")) return MapUrlState.Unknown
        if (path == "/maps/d" || path.startsWith("/maps/d/")) return MapUrlState.Unknown

        val camera = parseCamera(path)
        val mode = when {
            camera?.mode == CameraMode.StreetView -> MapMode.StreetView
            path.startsWith("/maps/dir/") || path == "/maps/dir" -> MapMode.Directions
            path.startsWith("/maps/search/") -> MapMode.Search
            path.startsWith("/maps/place/") -> MapMode.Place
            else -> MapMode.Map
        }
        val text = when (mode) {
            MapMode.Search -> firstSegment(path, "/maps/search/")
            MapMode.Place -> firstSegment(path, "/maps/place/")
            else -> null
        }
        return MapUrlState(camera, mode, text)
    }

    private fun parseCamera(path: String): MapCamera? {
        val m = CAMERA_REGEX.find(path) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        val value = m.groupValues[3].toDoubleOrNull() ?: return null
        val center = LatLon.ofOrNull(lat, lon) ?: return null
        if (!value.isFinite()) return null
        val unit = m.groupValues[4]
        val extras = SUFFIX_REGEX.findAll(m.groupValues[5]).associate { it.groupValues[2] to it.groupValues[1].toDouble() }
        val tilted = (extras["t"] ?: 0.0) > 0.0 || (extras["h"] ?: 0.0) != 0.0
        val mode = when (unit) {
            // TODO(B0 spike): `...a` is also the altitude (metres) of desktop 3D/tilted satellite
            // views (`@40.689,-74.044,346a,35y,315.97h,72.88t`), which this classifies as Street
            // View. Settle the rule once B0 (d) has real URLs; see REVIEW-batch1 SF-2.
            "a" -> CameraMode.StreetView
            "m" -> if (value > 0.0) CameraMode.Satellite3dMeters else return null
            else -> if (tilted) CameraMode.Tilted else CameraMode.Map
        }
        return MapCamera(center, value, mode, tilted = tilted && unit == "m")
    }

    private fun firstSegment(path: String, prefix: String): String? {
        val seg = path.removePrefix(prefix).substringBefore('/')
        if (seg.isEmpty() || seg.startsWith("@")) return null
        val decoded = decode(seg).trim()
        if (decoded.isEmpty() || COORD_TEXT.matches(decoded)) return null
        return decoded
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8") // the Charset overload needs API 33
        } catch (_: IllegalArgumentException) {
            s.replace('+', ' ')
        }
}
