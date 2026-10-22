package io.github.einekratzekatze.maps.navigation

import android.net.Uri
import io.github.einekratzekatze.maps.domain.LatLon
import java.net.URLDecoder
import kotlin.math.abs

/**
 * Travel mode from `!3e<N>` in the GMaps `data` blob. The 0/1/2/3 = drive/bike/walk/transit
 * convention is UNVERIFIED (spike N0 only saw 0, 2 and 3). Not acted on yet.
 */
enum class TravelMode(val apiName: String) {
    Drive("driving"), Bike("bicycling"), Walk("walking"), Transit("transit");

    companion object {
        fun fromCode(code: Int): TravelMode? = entries.getOrNull(code)
    }
}

/** What a Start press hands off: the destination only (spike N0 #4: the origin is never in `link`). */
data class StartHandoff(val target: NavTarget, val mode: TravelMode?)

/**
 * Parses the destination from the `link` parameter of the `https://maps.app.goo.gl/?link=` or
 * `intent://maps.app.goo.gl/?link=` URL fired by Start (docs/design/spike-results.md N0).
 * Expected link path: `/maps/dir//<destination>/@camera/data=...`. The `@lat,lng` is the camera,
 * never the destination. Returns null for anything unrecognised.
 */
object StartTargetParser {
    private val handoffPrefixes = listOf("https://maps.app.goo.gl/", "intent://maps.app.goo.gl/")
    private val mapsHosts = setOf("www.google.com", "google.com", "maps.google.com")
    private val number = "[+-]?\\d{1,3}(?:\\.\\d+)?"
    private val coordinatePair = Regex("^($number)\\s*,\\s*($number)$")
    private val looksNumeric = Regex("^[\\d\\s.,+-]+$")
    private val dataCoordinates = Regex("!1d([+-]?\\d+(?:\\.\\d+)?)!2d([+-]?\\d+(?:\\.\\d+)?)")
    private val modeCode = Regex("!3e(\\d+)")
    private const val MAX_LABEL = 300
    private const val DATA_TOLERANCE = 1e-4

    fun parse(navigatedUrl: String): StartHandoff? {
        if (handoffPrefixes.none { navigatedUrl.startsWith(it) }) return null
        return try {
            parseLink(extractLink(navigatedUrl) ?: return null)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun extractLink(url: String): String? {
        var link = Uri.parse(url).getQueryParameter("link")?.takeIf { it.isNotBlank() } ?: return null
        if (!link.startsWith("https://", ignoreCase = true) && link.contains('%')) {
            link = URLDecoder.decode(link, "UTF-8") // double-encoded
        }
        return link
    }

    private fun parseLink(link: String): StartHandoff? {
        val rest = link.removePrefixIgnoreCase("https://") ?: return null
        val host = rest.substringBefore('/')
        if (host !in mapsHosts) return null
        val path = ("/" + rest.substringAfter('/', "")).substringBefore('?').substringBefore('#')
        val segments = path.split('/')
        // ["", "maps", "dir", "", "<destination>", "@lat,lng,zoom", "data=..."]
        if (segments.size < 5 || segments[1] != "maps" || segments[2] != "dir" || segments[3].isNotEmpty()) return null
        val rawDestination = segments[4]
        if (rawDestination.isEmpty() || rawDestination.startsWith("@") || rawDestination.startsWith("data=")) return null
        val destination = URLDecoder.decode(rawDestination, "UTF-8").trim()
        if (destination.isEmpty() || destination.any { it.isISOControl() }) return null

        val target = coordinateTarget(destination, link) ?: nameTarget(destination) ?: return null
        val mode = modeCode.find(link)?.groupValues?.get(1)?.toIntOrNull()?.let(TravelMode::fromCode)
        return StartHandoff(target, mode)
    }

    private fun coordinateTarget(destination: String, data: String): NavTarget? {
        val match = coordinatePair.matchEntire(destination)
        if (match == null) {
            if (looksNumeric.matches(destination)) throw IllegalArgumentException("malformed coordinates")
            return null
        }
        val latLon = LatLon.ofOrNull(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
            ?: throw IllegalArgumentException("coordinates out of range")
        // `!1d<lon>!2d<lat>` (lon first) is more precise; a mismatch means we misread the shape.
        val fromData = dataCoordinates.find(data)?.let {
            LatLon.ofOrNull(it.groupValues[2].toDouble(), it.groupValues[1].toDouble())
        }
        if (fromData == null) return NavTarget(latLon, null)
        if (abs(fromData.lat - latLon.lat) > DATA_TOLERANCE || abs(fromData.lon - latLon.lon) > DATA_TOLERANCE) {
            throw IllegalArgumentException("path and data coordinates disagree")
        }
        return NavTarget(fromData, null)
    }

    private fun nameTarget(destination: String): NavTarget? =
        if (destination.length > MAX_LABEL) null else NavTarget(null, destination)

    private fun String.removePrefixIgnoreCase(prefix: String): String? =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else null
}
