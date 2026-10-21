package io.github.einekratzekatze.maps.data.mymaps

import android.util.Xml
import io.github.einekratzekatze.maps.data.exchange.advanceToRoot
import io.github.einekratzekatze.maps.data.exchange.kmlTuples
import io.github.einekratzekatze.maps.data.exchange.localName
import io.github.einekratzekatze.maps.data.exchange.parseKmlTuple
import io.github.einekratzekatze.maps.data.exchange.readAllText
import io.github.einekratzekatze.maps.data.exchange.skipElement
import io.github.einekratzekatze.maps.domain.LatLon
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.io.StringReader

/**
 * Streaming `XmlPullParser` parser for Google My Maps KML exports (TODO 5.1).
 *
 * Rules (documented decisions):
 * - `<Document><name>` is the list name; `<Folder><name>` is a sublist name.
 * - Nested folders are flattened: every folder that directly contains at least one valid
 *   placemark becomes one sublist named by its path ("Outer / Inner"). A folder that only
 *   contains other folders produces no sublist; an empty leaf folder produces an empty sublist
 *   (the layer still exists). Sublists are ordered by the folder's start tag.
 * - Placemarks outside any folder go into one synthetic [ParsedSublist.isImplicit] sublist, created
 *   at the first such placemark (so document order is kept).
 * - Only Point placemarks are kept (inside MultiGeometry the first Point wins). LineString,
 *   Polygon, Track etc. are skipped and counted in [ParsedMyMap.skippedNonPoint]; a placemark
 *   with no usable coordinates is counted in [ParsedMyMap.skippedInvalid]. The bubble only
 *   shows points, so we do not invent a representative point for shapes.
 * - Coordinates are "lon,lat[,alt]" (whitespace-tolerant, locale-independent, '.' decimal);
 *   non-finite or out-of-range values are invalid. Only the first tuple of a Point is used.
 * - Colour: `<Style><IconStyle><color>` (else Poly/LineStyle), reached via the placemark's
 *   `styleUrl` directly or through a `<StyleMap>` "normal" pair. Resolved after the whole file
 *   is read, so style order does not matter.
 * - Malformed XML, an empty document, a document whose root is not `<kml>` (an HTML error page, a
 *   KMZ/zip archive) and any DOCTYPE throw [KmlParseException] (no partial result, so a caller
 *   must never replace a cached list with an empty one). Child markup inside a leaf element such
 *   as an unescaped `<description>a <b>b</b></description>` is flattened to its text.
 * - Memory: the XML pull parser buffers one text node at a time, so peak memory is bounded by the
 *   largest single text node plus the output; it does not grow with the number of placemarks.
 *   Kept text is capped at [TEXT_CAP] chars (description at [MAX_DESCRIPTION] after trimming), and
 *   `<coordinates>` of anything but a Point (line/polygon vertices) are skipped without being read
 *   into a string. A single multi-gigabyte text node is not defended against beyond that.
 * - The skipped counts in [ParsedMyMap] must be surfaced by the UI ("N shapes not imported").
 */
object KmlParser {
    const val MAX_DESCRIPTION = 20_000
    private const val TEXT_CAP = MAX_DESCRIPTION * 2
    private const val MAX_EXTENDED = 50

    private val COLOR = Regex("""[0-9a-fA-F]{8}""")
    private val OTHER_GEOMETRY = setOf("LineString", "Polygon", "LinearRing", "Track", "gx:Track", "MultiTrack", "gx:MultiTrack")

    fun parse(kml: String): ParsedMyMap = parse(StringReader(kml)) { setInput(it) }

    fun parse(input: InputStream): ParsedMyMap = parse(input) { setInput(it, null) }

    private fun <T> parse(source: T, init: XmlPullParser.(T) -> Unit): ParsedMyMap {
        try {
            val p = Xml.newPullParser()
            p.init(source)
            p.advanceToRoot() // rejects DOCTYPE before any entity can be expanded
            return Run(p).run()
        } catch (e: XmlPullParserException) {
            throw KmlParseException("Malformed KML: ${e.message}", e)
        } catch (e: IOException) {
            throw KmlParseException("Cannot read KML: ${e.message}", e)
        }
    }

    private class SublistBuilder(val parentName: String?) {
        var name: String? = parentName
        var isImplicit = false
        var hasChildFolder = false
        val places = ArrayList<ParsedPlacemark>()
        fun setOwnName(raw: String) {
            val own = raw.trim().ifEmpty { null }
            name = listOfNotNull(parentName, own).joinToString(" / ").ifEmpty { null }
        }
    }

    private class PlacemarkBuilder {
        var name: String? = null
        var description: String? = null
        var position: LatLon? = null
        var badCoordinates = false
        var otherGeometry = false
        var styleUrl: String? = null
        var inlineStyle: StyleInfo? = null
        val extended = LinkedHashMap<String, String>()
    }

    private class StyleInfo { var icon: Int? = null; var other: Int? = null }

    private class Run(val p: XmlPullParser) {
        val path = ArrayList<String>()
        var mapName: String? = null
        val builders = ArrayList<SublistBuilder>()
        val folders = ArrayList<SublistBuilder>()
        var implicit: SublistBuilder? = null
        var pm: PlacemarkBuilder? = null
        val styles = HashMap<String, StyleInfo>()
        val styleMaps = HashMap<String, String>()
        val styleMapInline = HashMap<String, StyleInfo>()
        var curStyle: StyleInfo? = null
        var curStyleMapId: String? = null
        var pairKey: String? = null
        var dataName: String? = null
        var skippedNonPoint = 0
        var skippedInvalid = 0
        var sawKml = false

        fun parent(): String? = path.lastOrNull()
        fun inPath(n: String) = path.contains(n)

        fun run(): ParsedMyMap {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (path.isEmpty() && !sawKml) {
                            if (p.localName() != "kml") throw KmlParseException("Not a KML document (root is <${p.name}>)")
                            sawKml = true
                        }
                        start(p.localName())
                    }
                    XmlPullParser.END_TAG -> end(p.localName())
                }
                ev = p.next()
            }
            if (!sawKml) throw KmlParseException("Not a KML document (empty, or a KMZ/zip archive)")
            return finish()
        }

        fun start(tag: String) {
            when (tag) {
                "name", "description", "coordinates", "styleUrl", "color", "key", "value" -> {
                    val parent = parent()
                    if (tag == "coordinates" && parent != "Point") {
                        p.skipElement() // line/polygon vertices are never read into a string
                    } else {
                        leaf(tag, parent, p.readAllText(TEXT_CAP)) // consumes the end tag
                    }
                    return
                }
                "Folder" -> {
                    val b = SublistBuilder(folders.lastOrNull()?.name)
                    folders.lastOrNull()?.hasChildFolder = true
                    folders.add(b); builders.add(b)
                }
                "Placemark" -> if (pm == null) pm = PlacemarkBuilder()
                "Style" -> {
                    curStyle = StyleInfo().also { s ->
                        val id = p.getAttributeValue(null, "id")
                        val mapId = curStyleMapId
                        when {
                            id != null -> styles[id] = s
                            pm != null -> pm!!.inlineStyle = s // inline <Style> of a Placemark
                            mapId != null && pairKey == "normal" -> styleMapInline[mapId] = s
                        }
                    }
                }
                "StyleMap" -> curStyleMapId = p.getAttributeValue(null, "id")
                "Pair" -> pairKey = null
                "Data" -> dataName = p.getAttributeValue(null, "name")
            }
            if (pm != null && tag in OTHER_GEOMETRY) pm!!.otherGeometry = true
            path.add(tag)
        }

        fun end(tag: String) {
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
            when (tag) {
                "Folder" -> if (folders.isNotEmpty()) folders.removeAt(folders.size - 1)
                "Placemark" -> pm?.let { endPlacemark(it); pm = null }
                "Style" -> curStyle = null
                "StyleMap" -> curStyleMapId = null
                "Data" -> dataName = null
            }
        }

        fun leaf(tag: String, parent: String?, text: String) {
            val pm = pm
            when (tag) {
                "name" -> when {
                    parent == "Placemark" && pm != null -> pm.name = text.trim().ifEmpty { null }
                    parent == "Folder" -> folders.lastOrNull()?.setOwnName(text)
                    parent == "Document" && mapName == null -> mapName = text.trim().ifEmpty { null }
                }
                "description" -> if (parent == "Placemark" && pm != null) {
                    pm.description = text.trim().take(MAX_DESCRIPTION).ifEmpty { null }
                }
                "coordinates" -> if (parent == "Point" && pm != null && pm.position == null && !pm.badCoordinates) {
                    val pos = parseCoordinates(text)
                    if (pos == null) pm.badCoordinates = true else pm.position = pos
                }
                "styleUrl" -> when {
                    parent == "Placemark" && pm != null -> pm.styleUrl = text.trim().ifEmpty { null }
                    parent == "Pair" && pairKey == "normal" -> curStyleMapId?.let { styleMaps[it] = text.trim().substringAfterLast('#') }
                }
                "key" -> if (parent == "Pair") pairKey = text.trim()
                "color" -> curStyle?.let { s ->
                    val argb = parseColor(text)
                    if (parent == "IconStyle") s.icon = argb
                    else if (s.other == null && (parent == "PolyStyle" || parent == "LineStyle")) s.other = argb
                }
                "value" -> if (parent == "Data" && pm != null) {
                    val k = dataName
                    if (k != null && pm.extended.size < MAX_EXTENDED) pm.extended[k] = text.trim().take(MAX_DESCRIPTION)
                }
            }
        }

        fun endPlacemark(b: PlacemarkBuilder) {
            val pos = b.position
            if (pos == null) {
                if (b.otherGeometry && !b.badCoordinates) skippedNonPoint++ else skippedInvalid++
                return
            }
            val target = folders.lastOrNull() ?: implicit ?: SublistBuilder(null).also {
                it.isImplicit = true; implicit = it; builders.add(it)
            }
            val inline = b.inlineStyle?.let { it.icon ?: it.other }
            target.places.add(ParsedPlacemark(b.name, b.description, pos, b.styleUrl, inline, b.extended))
        }

        fun colorFor(styleUrl: String?): Int? {
            // `styles.kml#s` and `#s` both resolve by fragment (external files are not fetched).
            var id = styleUrl?.trim()?.substringAfterLast('#') ?: return null
            styleMapInline[id]?.let { return it.icon ?: it.other }
            styleMaps[id]?.let { id = it }
            val s = styles[id] ?: return null
            return s.icon ?: s.other
        }

        fun finish(): ParsedMyMap {
            val subs = builders
                .filter { !(it.places.isEmpty() && it.hasChildFolder) }
                .map { b ->
                    ParsedSublist(b.name, b.isImplicit, b.places.map { it.copy(colorArgb = it.colorArgb ?: colorFor(it.styleUrl)) })
                }
            return ParsedMyMap(mapName, subs, skippedNonPoint, skippedInvalid)
        }
    }

    /** "lon,lat[,alt]" -> LatLon, or null. Only the first whitespace-separated tuple is read. */
    internal fun parseCoordinates(text: String): LatLon? = kmlTuples(text).firstOrNull()?.let(::parseKmlTuple)

    /** KML aabbggrr -> 0xAARRGGBB, or null. */
    internal fun parseColor(text: String): Int? {
        val t = text.trim()
        if (!COLOR.matches(t)) return null
        val v = t.toLong(16)
        val a = (v shr 24 and 0xFF).toInt(); val b = (v shr 16 and 0xFF).toInt()
        val g = (v shr 8 and 0xFF).toInt(); val r = (v and 0xFF).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
