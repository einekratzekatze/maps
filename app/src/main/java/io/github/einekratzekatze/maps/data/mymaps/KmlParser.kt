package io.github.einekratzekatze.maps.data.mymaps

import android.util.Xml
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
 * - Malformed XML throws [KmlParseException] (no partial result). Description text is capped
 *   at [MAX_DESCRIPTION] chars; memory use is proportional to the output, not the XML input.
 */
object KmlParser {
    const val MAX_DESCRIPTION = 20_000
    private const val MAX_EXTENDED = 50

    private val NUMBER = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")
    private val COLOR = Regex("""[0-9a-fA-F]{8}""")
    private val OTHER_GEOMETRY = setOf("LineString", "Polygon", "LinearRing", "Track", "gx:Track", "MultiTrack", "gx:MultiTrack")

    fun parse(kml: String): ParsedMyMap = parse(StringReader(kml)) { setInput(it) }

    fun parse(input: InputStream): ParsedMyMap = parse(input) { setInput(it, null) }

    private fun <T> parse(source: T, init: XmlPullParser.(T) -> Unit): ParsedMyMap {
        try {
            val p = Xml.newPullParser()
            p.init(source)
            return Run(p).run()
        } catch (e: XmlPullParserException) {
            throw KmlParseException("Malformed KML: ${e.message}", e)
        } catch (e: IOException) {
            throw KmlParseException("Cannot read KML: ${e.message}", e)
        }
    }

    private class SublistBuilder(val parentName: String?) {
        var name: String? = null
        var isImplicit = false
        var hasChildFolder = false
        val places = ArrayList<ParsedPlacemark>()
        fun setOwnName(raw: String) {
            val own = raw.trim().ifEmpty { null }
            name = listOfNotNull(parentName, own).joinToString(" / ").ifEmpty { null }
        }
        init { name = parentName }
    }

    private class PlacemarkBuilder {
        var name: String? = null
        var description: String? = null
        var position: LatLon? = null
        var badCoordinates = false
        var otherGeometry = false
        var styleUrl: String? = null
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
        var curStyle: StyleInfo? = null
        var curStyleMapId: String? = null
        var pairKey: String? = null
        var dataName: String? = null
        var skippedNonPoint = 0
        var skippedInvalid = 0

        fun parent(): String? = path.lastOrNull()
        fun inPath(n: String) = path.contains(n)

        fun run(): ParsedMyMap {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> start(p.name)
                    XmlPullParser.END_TAG -> end(p.name)
                }
                ev = p.next()
            }
            return finish()
        }

        fun start(tag: String) {
            when (tag) {
                "name", "description", "coordinates", "styleUrl", "color", "key", "value" -> {
                    val parent = parent()
                    val text = p.nextText() // consumes the end tag; leaf elements are not pushed
                    leaf(tag, parent, text)
                    return
                }
                "Folder" -> {
                    val b = SublistBuilder(folders.lastOrNull()?.name)
                    folders.lastOrNull()?.hasChildFolder = true
                    folders.add(b); builders.add(b)
                }
                "Placemark" -> if (pm == null) pm = PlacemarkBuilder()
                "Style" -> { curStyle = StyleInfo().also { s -> p.getAttributeValue(null, "id")?.let { styles[it] = s } } }
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
                    parent == "Pair" && pairKey == "normal" -> curStyleMapId?.let { styleMaps[it] = text.trim().removePrefix("#") }
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
            target.places.add(ParsedPlacemark(b.name, b.description, pos, b.styleUrl, null, b.extended))
        }

        fun colorFor(styleUrl: String?): Int? {
            var id = styleUrl?.trim()?.removePrefix("#") ?: return null
            styleMaps[id]?.let { id = it }
            val s = styles[id] ?: return null
            return s.icon ?: s.other
        }

        fun finish(): ParsedMyMap {
            val subs = builders
                .filter { !(it.places.isEmpty() && it.hasChildFolder) }
                .map { b ->
                    ParsedSublist(b.name, b.isImplicit, b.places.map { it.copy(colorArgb = colorFor(it.styleUrl)) })
                }
            return ParsedMyMap(mapName, subs, skippedNonPoint, skippedInvalid)
        }
    }

    /** "lon,lat[,alt]" -> LatLon, or null. Only the first whitespace-separated tuple is read. */
    internal fun parseCoordinates(text: String): LatLon? {
        val tuple = text.trim().replace(Regex("\\s*,\\s*"), ",").split(Regex("\\s+")).firstOrNull() ?: return null
        val parts = tuple.split(',')
        if (parts.size < 2) return null
        if (!NUMBER.matches(parts[0].trim()) || !NUMBER.matches(parts[1].trim())) return null
        // String.toDouble() is locale-independent ('.' decimal separator).
        val lon = parts[0].trim().toDouble()
        val lat = parts[1].trim().toDouble()
        if (!lon.isFinite() || !lat.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

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
