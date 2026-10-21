package io.github.einekratzekatze.maps.data.exchange

import android.util.Xml
import io.github.einekratzekatze.maps.domain.LatLon
import java.io.InputStream
import java.io.Reader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Readers use `android.util.Xml.newPullParser()` (KXmlParser): the only XmlPullParser on
 * Android, and it works under Robolectric, which the project already uses. It is
 * namespace-unaware, so prefixed names are compared by [localName].
 */
internal inline fun <T> withParser(input: InputStream?, reader: Reader?, block: (XmlPullParser) -> T): T {
    val p = Xml.newPullParser()
    try {
        if (reader != null) p.setInput(reader) else p.setInput(input, null)
        return block(p)
    } catch (e: XmlPullParserException) {
        throw ExchangeFormatException("Malformed XML: ${e.message}", e)
    }
}

internal fun XmlPullParser.localName(): String = name.substringAfter(':')

/** Concatenated text of the current element including nested elements; consumes its end tag. */
internal fun XmlPullParser.readAllText(): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(text)
            XmlPullParser.END_DOCUMENT -> throw ExchangeFormatException("Unexpected end of document")
        }
    }
    return sb.toString()
}

internal fun String.trimmedOrNull(): String? = trim().ifEmpty { null }

internal fun parseLatLon(lat: String?, lon: String?): LatLon? = makeLatLon(lat?.trim()?.toDoubleOrNull(), lon?.trim()?.toDoubleOrNull())

internal fun makeLatLon(lat: Double?, lon: Double?): LatLon? {
    if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
    return LatLon(lat, lon)
}

/** Builds a drive from ordered points; shared by KML LineStrings and GPX tracks/routes. */
internal fun buildDrive(
    name: String,
    points: List<Pair<LatLon, String?>>,
    hasOrigin: Boolean,
    travelMode: String?,
): ExchangeDrive? {
    val minPoints = if (hasOrigin) 2 else 1
    if (points.size < minPoints) return null
    fun label(i: Int): String? = points[i].second
    val first = if (hasOrigin) 1 else 0
    val last = points.size - 1
    val waypoints = (first until last).map { ExchangeWaypoint(label(it), points[it].first) }
    return ExchangeDrive(
        name = name,
        origin = if (hasOrigin) points[0].first else null,
        originLabel = if (hasOrigin) points[0].second else null,
        destination = points[last].first,
        destinationLabel = points[last].second,
        waypoints = waypoints,
        travelMode = travelMode,
    )
}
