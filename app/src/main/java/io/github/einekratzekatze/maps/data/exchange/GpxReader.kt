package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.InputStream
import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/**
 * Reads GPX 1.0/1.1 as written by [GpxWriter], OsmAnd and Organic Maps.
 *
 * - `<wpt>` -> place, `<trk>` (all segments concatenated) and `<rte>` -> drive.
 * - Lists: our own files carry `<extensions><maps:list index="n">name</maps:list>` on every item;
 *   that wins and restores list order, empty names and same-named lists exactly. Otherwise lists
 *   are formed from `<type>` (first-seen order), and items without a type go to a list named
 *   after `<metadata><name>` (or ""). Empty lists cannot be represented in GPX and are lost.
 * - A track/route with fewer than two points is skipped (unless it is our own destination-only
 *   drive, flagged `maps:hasOrigin` = 0). A foreign track/route (no `maps:hasOrigin`) keeps its
 *   origin, destination and at most [MAX_FOREIGN_WAYPOINTS] evenly sampled waypoints, see
 *   [ExchangeDocument.simplifiedDrives].
 * - Text is trimmed everywhere; an empty or blank label is the same as no label (null).
 * - A DOCTYPE is rejected ([ExchangeFormatException]) to stop entity-expansion bombs.
 * - Longitude 180 is written as -180 by [GpxWriter] (gpx.xsd excludes 180), so it reads back as
 *   -180: the same meridian.
 * - `<desc>` -> notes; `<cmt>` is used for notes only if `<desc>` is absent. An element named
 *   `address` inside `<extensions>` (ours, OsmAnd's) -> address.
 *
 * @throws ExchangeFormatException for malformed XML or a non-GPX root. A mid-stream
 *   [java.io.IOException] from the input is not wrapped and propagates as is.
 */
object GpxReader {
    fun read(input: InputStream): ExchangeDocument = withParser(input, null, ::parse)
    fun read(reader: Reader): ExchangeDocument = withParser(null, reader, ::parse)

    private class Pt(val pos: LatLon?, var name: String? = null)

    private class Item {
        var name = ""; var desc: String? = null; var cmt: String? = null; var type: String? = null
        var address: String? = null; var hasOrigin = true; var travelMode: String? = null
        var own = false; var listIndex: Int? = null; var listName: String? = null
        var pos: LatLon? = null
        val pts = ArrayList<Pair<LatLon, String?>>()
    }

    private class Group(val index: Int?, val name: String?) { val places = ArrayList<ExchangePlace>(); val drives = ArrayList<ExchangeDrive>() }

    private fun parse(p: XmlPullParser): ExchangeDocument {
        val groups = LinkedHashMap<String, Group>() // "i:<index>", "t:<type>" or "-" (untyped)
        var simplified = 0
        var docName: String? = null
        var sawGpx = false
        val stack = ArrayList<String>()
        var item: Item? = null
        var pt: Pt? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
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
                    parent == "extensions" && item != null && pt == null && n == "hasOrigin" -> {
                        item.own = true
                        item.hasOrigin = p.readAllText().trim() != "0"
                    }
                    parent == "extensions" && item != null && pt == null && n == "list" && p.getAttributeValue(null, "index")?.toIntOrNull() != null -> {
                        item.listIndex = p.getAttributeValue(null, "index")!!.toInt()
                        item.listName = p.readAllText().trim()
                    }
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
                            group(groups, cur).places.add(ExchangePlace(cur.name, pos, cur.address, cur.desc ?: cur.cmt))
                        }
                        item = null
                    }
                    "trk", "rte" -> if (cur != null) {
                        val cap = if (cur.own) Int.MAX_VALUE else MAX_FOREIGN_WAYPOINTS
                        buildDrive(cur.name, cur.pts, cur.hasOrigin, cur.travelMode, cap) { simplified++ }
                            ?.let { d -> group(groups, cur).drives.add(d) }
                        item = null
                    }
                }
            }
            ev = p.next()
        }
        if (stack.isNotEmpty()) throw ExchangeFormatException("Unexpected end of document")
        if (!sawGpx) throw ExchangeFormatException("Not a GPX document (no <gpx> element)")
        // Our own lists (explicit index) first, in index order; foreign ones after, first-seen.
        val ordered = groups.values.filter { it.index != null }.sortedBy { it.index } + groups.values.filter { it.index == null }
        val lists = ordered.map { g -> ExchangeList(g.name ?: docName.orEmpty(), g.places, g.drives) }
        return ExchangeDocument(docName, lists, simplified)
    }

    private fun group(groups: MutableMap<String, Group>, item: Item): Group {
        val idx = item.listIndex
        return when {
            idx != null -> groups.getOrPut("i:$idx") { Group(idx, item.listName) }
            item.type != null -> groups.getOrPut("t:${item.type}") { Group(null, item.type) }
            else -> groups.getOrPut("-") { Group(null, null) }
        }
    }
}
