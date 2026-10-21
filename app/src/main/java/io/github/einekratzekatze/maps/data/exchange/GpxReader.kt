package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.InputStream
import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/**
 * Reads GPX 1.0/1.1 as written by [GpxWriter], OsmAnd and Organic Maps.
 *
 * - `<wpt>` -> place, `<trk>` (all segments concatenated) and `<rte>` -> drive.
 * - Lists are formed from `<type>` (first-seen order). Items without a type go to a list named
 *   after `<metadata><name>` (or "").
 * - A track/route with fewer than two points is skipped (unless it is our own destination-only
 *   drive, flagged `maps:hasOrigin` = 0).
 * - `<desc>` -> notes; `<cmt>` is used for notes only if `<desc>` is absent. An element named
 *   `address` inside `<extensions>` (ours, OsmAnd's) -> address.
 */
object GpxReader {
    fun read(input: InputStream): ExchangeDocument = withParser(input, null, ::parse)
    fun read(reader: Reader): ExchangeDocument = withParser(null, reader, ::parse)

    private class Pt(val pos: LatLon?, var name: String? = null)

    private class Item {
        var name = ""; var desc: String? = null; var cmt: String? = null; var type: String? = null
        var address: String? = null; var hasOrigin = true; var travelMode: String? = null
        var pos: LatLon? = null
        val pts = ArrayList<Pair<LatLon, String?>>()
    }

    private class Group { val places = ArrayList<ExchangePlace>(); val drives = ArrayList<ExchangeDrive>() }

    private fun parse(p: XmlPullParser): ExchangeDocument {
        val groups = LinkedHashMap<String?, Group>() // null key = untyped
        var docName: String? = null
        var sawGpx = false
        val stack = ArrayList<String>()
        var item: Item? = null
        var pt: Pt? = null

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) {
                val n = p.localName()
                val parent = stack.lastOrNull()
                val container = parent == "wpt" || parent == "trk" || parent == "rte" ||
                    parent == "trkpt" || parent == "rtept" || parent == "metadata" || parent == "extensions"
                when {
                    n == "gpx" && parent == null -> { sawGpx = true; stack.add(n) }
                    n == "wpt" || n == "trk" || n == "rte" -> {
                        item = Item().also { if (n == "wpt") it.pos = parseLatLon(p.getAttributeValue(null, "lat"), p.getAttributeValue(null, "lon")) }
                        stack.add(n)
                    }
                    (n == "trkpt" || n == "rtept") && item != null -> {
                        pt = Pt(parseLatLon(p.getAttributeValue(null, "lat"), p.getAttributeValue(null, "lon")))
                        stack.add(n)
                    }
                    container && n == "name" -> {
                        val t = p.readAllText().trimmedOrNull()
                        when (parent) {
                            "metadata" -> if (docName == null) docName = t
                            "trkpt", "rtept" -> pt?.name = t
                            "wpt", "trk", "rte" -> item?.name = t.orEmpty()
                        }
                    }
                    container && (n == "desc" || n == "cmt" || n == "type") && item != null &&
                        (parent == "wpt" || parent == "trk" || parent == "rte") -> {
                        val t = p.readAllText().trimmedOrNull()
                        when (n) { "desc" -> item.desc = t; "cmt" -> item.cmt = t; else -> item.type = t }
                    }
                    parent == "extensions" && item != null && pt == null && n == "address" -> item.address = p.readAllText().trimmedOrNull()
                    parent == "extensions" && item != null && pt == null && n == "hasOrigin" -> item.hasOrigin = p.readAllText().trim() != "0"
                    parent == "extensions" && item != null && pt == null && n == "travelMode" -> item.travelMode = p.readAllText().trimmedOrNull()
                    else -> stack.add(n)
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = stack.removeLastOrNull()
                val cur = item
                when (n) {
                    "trkpt", "rtept" -> { pt?.pos?.let { pos -> cur?.pts?.add(pos to pt!!.name) }; pt = null }
                    "wpt" -> if (cur != null) {
                        cur.pos?.let { pos ->
                            groups.getOrPut(cur.type) { Group() }.places.add(ExchangePlace(cur.name, pos, cur.address, cur.desc ?: cur.cmt))
                        }
                        item = null
                    }
                    "trk", "rte" -> if (cur != null) {
                        buildDrive(cur.name, cur.pts, cur.hasOrigin, cur.travelMode)?.let { d -> groups.getOrPut(cur.type) { Group() }.drives.add(d) }
                        item = null
                    }
                }
            }
        }
        if (stack.isNotEmpty()) throw ExchangeFormatException("Unexpected end of document")
        if (!sawGpx) throw ExchangeFormatException("Not a GPX document (no <gpx> element)")
        val lists = groups.map { (type, g) -> ExchangeList(type ?: docName.orEmpty(), g.places, g.drives) }
        return ExchangeDocument(docName, lists)
    }
}
