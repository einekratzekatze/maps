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
        p.advanceToRoot()
        return block(p)
    } catch (e: XmlPullParserException) {
        throw ExchangeFormatException("Malformed XML: ${e.message}", e)
    }
}

/**
 * Moves to the root START_TAG (skipping prolog, comments, processing instructions) and rejects any
 * DOCTYPE on the way, before the parser can expand internal entities ("billion laughs", SF-4).
 * Afterwards [XmlPullParser.getEventType] is START_TAG, or END_DOCUMENT for an empty document;
 * callers continue the event loop from there. Throws [XmlPullParserException] on a DOCTYPE.
 */
internal fun XmlPullParser.advanceToRoot() {
    var t = nextToken()
    while (t != XmlPullParser.START_TAG && t != XmlPullParser.END_DOCUMENT) {
        if (t == XmlPullParser.DOCDECL) throw XmlPullParserException("DOCTYPE is not allowed", this, null)
        t = nextToken()
    }
}

internal fun XmlPullParser.localName(): String = name.substringAfter(':')

/**
 * Concatenated text of the current element including nested elements; consumes its end tag.
 * At most [maxChars] characters are kept (the rest is consumed and dropped).
 */
internal fun XmlPullParser.readAllText(maxChars: Int = Int.MAX_VALUE): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                val room = maxChars - sb.length
                if (room > 0) sb.append(text, 0, minOf(room, text.length))
            }
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document", this, null)
        }
    }
    return sb.toString()
}

/** Consumes the current element and its children without materialising any text. */
internal fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document", this, null)
        }
    }
}

internal fun String.trimmedOrNull(): String? = trim().ifEmpty { null }

private val DECIMAL = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

/** Strict decimal: no `f`/`d` suffixes, hex floats, NaN or Infinity (SIM-2/N-9). */
internal fun parseDecimal(s: String?): Double? {
    val t = s?.trim() ?: return null
    return if (DECIMAL.matches(t)) t.toDouble() else null
}

internal fun parseLatLon(lat: String?, lon: String?): LatLon? = LatLon.ofOrNull(parseDecimal(lat), parseDecimal(lon))

private val COMMA_SPACES = Regex("\\s*,\\s*")
private val WHITESPACE = Regex("\\s+")

/** KML `lon,lat[,alt]` tuples (whitespace-separated, whitespace around commas tolerated). */
internal fun kmlTuples(text: String): List<String> =
    text.trim().replace(COMMA_SPACES, ",").split(WHITESPACE).filter { it.isNotEmpty() }

/** One KML `lon,lat[,alt]` tuple -> [LatLon] (note the KML order), or null if invalid. */
internal fun parseKmlTuple(tuple: String): LatLon? {
    val parts = tuple.split(',')
    return if (parts.size < 2) null else parseLatLon(parts[1], parts[0])
}

/** Maximum intermediate waypoints kept from a foreign (non-Maps) KML/GPX track or route (D3). */
internal const val MAX_FOREIGN_WAYPOINTS = 25

/**
 * Builds a drive from ordered points; shared by KML LineStrings and GPX tracks/routes. More than
 * [maxWaypoints] intermediate points are reduced to [maxWaypoints] evenly sampled ones (origin and
 * destination are always kept) and [onSimplified] is called.
 */
internal fun buildDrive(
    name: String,
    points: List<Pair<LatLon, String?>>,
    hasOrigin: Boolean,
    travelMode: String?,
    maxWaypoints: Int = Int.MAX_VALUE,
    onSimplified: () -> Unit = {},
): ExchangeDrive? {
    val minPoints = if (hasOrigin) 2 else 1
    if (points.size < minPoints) return null
    val first = if (hasOrigin) 1 else 0
    val last = points.size - 1
    var middle = (first until last).toList()
    if (middle.size > maxWaypoints) {
        val m = middle.size
        middle = if (maxWaypoints <= 0) emptyList() else if (maxWaypoints == 1) listOf(middle[m / 2]) else
            (0 until maxWaypoints).map { k -> middle[Math.round(k * (m - 1).toDouble() / (maxWaypoints - 1)).toInt()] }
        onSimplified()
    }
    val waypoints = middle.map { ExchangeWaypoint(points[it].second, points[it].first) }
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
