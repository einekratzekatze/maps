package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.InputStream
import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/**
 * Reads KML 2.2 as written by [KmlWriter], OsmAnd, Organic Maps and Google My Maps style files.
 *
 * - Every `<Folder>` becomes a list (nested folders become separate lists, innermost name).
 * - Placemarks directly under `<Document>`/`<kml>` form an extra list named after the document
 *   (or "" if unnamed), added only if it has content.
 * - `<Point>` -> place; `<LineString>` -> drive. Polygons, `gx:Track` etc. are skipped.
 * - `<description>` -> notes, `<address>` -> address. HTML in descriptions is kept verbatim.
 */
object KmlReader {
    fun read(input: InputStream): ExchangeDocument = withParser(input, null, ::parse)
    fun read(reader: Reader): ExchangeDocument = withParser(null, reader, ::parse)

    private class Builder(var name: String?, val keepIfEmpty: Boolean) {
        val places = ArrayList<ExchangePlace>()
        val drives = ArrayList<ExchangeDrive>()
        fun toList() = ExchangeList(name.orEmpty(), places, drives)
        val isEmpty get() = places.isEmpty() && drives.isEmpty()
    }

    private fun parse(p: XmlPullParser): ExchangeDocument {
        val builders = ArrayList<Builder>()
        val stack = ArrayList<Builder>() // containers: Document / Folder
        var docName: String? = null
        val root = Builder(null, keepIfEmpty = false)
        builders.add(root)
        stack.add(root)
        var sawKml = false
        val elems = ArrayList<String>()

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) {
                val n = p.localName()
                val parent = elems.lastOrNull()
                when {
                    n == "name" && (parent == "Document" || parent == "Folder") -> {
                        val t = p.readAllText().trimmedOrNull()
                        val top = stack.last()
                        if (top === root) { if (docName == null) docName = t } else top.name = t
                    }
                    n == "Placemark" -> readPlacemark(p, stack.last())
                    else -> {
                        if (n == "kml") sawKml = true
                        if (n == "Folder") {
                            val b = Builder(null, keepIfEmpty = true)
                            builders.add(b); stack.add(b)
                        }
                        elems.add(n)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = elems.removeLastOrNull()
                if (n == "Folder" && stack.size > 1) stack.removeAt(stack.size - 1)
            }
        }
        if (elems.isNotEmpty()) throw ExchangeFormatException("Unexpected end of document")
        if (!sawKml) throw ExchangeFormatException("Not a KML document (no <kml> element)")
        root.name = docName
        val lists = builders.filter { it.keepIfEmpty || !it.isEmpty }.map { it.toList() }
        return ExchangeDocument(docName, lists)
    }

    private fun readPlacemark(p: XmlPullParser, target: Builder) {
        var name = ""
        var description: String? = null
        var address: String? = null
        var point: LatLon? = null
        var line: List<LatLon>? = null
        val data = HashMap<String, String>()
        val waypointLabels = ArrayList<String?>()
        val stack = ArrayList<String>()

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw ExchangeFormatException("Unexpected end of document")
            if (ev == XmlPullParser.END_TAG) {
                if (stack.isEmpty()) break
                stack.removeAt(stack.size - 1)
            } else if (ev == XmlPullParser.START_TAG) {
                val n = p.localName()
                val parent = stack.lastOrNull()
                when {
                    n == "name" && parent == null -> name = p.readAllText().trim()
                    n == "description" && parent == null -> description = p.readAllText().trimmedOrNull()
                    n == "address" && parent == null -> address = p.readAllText().trimmedOrNull()
                    n == "coordinates" && (parent == "Point" || parent == "LineString") -> {
                        val pts = parseCoordinates(p.readAllText())
                        if (parent == "Point") { if (point == null && line == null) point = pts.firstOrNull() }
                        else if (line == null && point == null) line = pts
                    }
                    n == "Data" -> {
                        val key = p.getAttributeValue(null, "name")
                        var value: String? = null
                        var depth = 1
                        while (depth > 0) {
                            when (p.next()) {
                                XmlPullParser.START_TAG -> {
                                    if (p.localName() == "value" && depth == 1) value = p.readAllText() else depth++
                                }
                                XmlPullParser.END_TAG -> depth--
                                XmlPullParser.END_DOCUMENT -> throw ExchangeFormatException("Unexpected end of document")
                            }
                        }
                        if (key != null && value != null) {
                            if (key == KmlNames.WAYPOINT_LABEL) waypointLabels.add(value.ifEmpty { null }) else data[key] = value
                        }
                    }
                    else -> stack.add(n)
                }
            }
        }

        val drivePoint = point != null && data[KmlNames.KIND] == KmlNames.KIND_DRIVE
        val ln = line
        val pt = point
        when {
            ln != null && ln.size >= 2 || drivePoint -> {
                val pts = if (drivePoint) listOf(pt!!) else ln!!
                val hasOrigin = !drivePoint && data[KmlNames.HAS_ORIGIN] != "0"
                val first = if (hasOrigin) 1 else 0
                val last = pts.size - 1
                val labelled = pts.mapIndexed { i, ll ->
                    val label = when {
                        hasOrigin && i == 0 -> data[KmlNames.ORIGIN_LABEL]
                        i == last -> data[KmlNames.DEST_LABEL]
                        else -> waypointLabels.getOrNull(i - first)
                    }
                    ll to label
                }
                buildDrive(name, labelled, hasOrigin, data[KmlNames.TRAVEL_MODE])?.let { target.drives.add(it) }
            }
            pt != null -> target.places.add(ExchangePlace(name, pt, address, description))
        }
    }

    /** `lon,lat[,alt]` tuples separated by whitespace; invalid tuples are skipped. */
    internal fun parseCoordinates(s: String): List<LatLon> =
        s.replace(Regex("\\s*,\\s*"), ",").trim().split(Regex("\\s+")).mapNotNull { tuple ->
            if (tuple.isEmpty()) return@mapNotNull null
            val parts = tuple.split(',')
            if (parts.size < 2) null else parseLatLon(parts[1], parts[0])
        }
}
