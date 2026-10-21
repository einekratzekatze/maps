package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.OutputStream

/**
 * KML 2.2 export. One `<Folder>` per list; each place is a `<Placemark>` with a `<Point>`
 * (`lon,lat,0`), each drive a `<Placemark>` with a `<LineString>` of origin (if set), waypoints,
 * destination. Drive details KML cannot express (unset origin, labels, travel mode) go into
 * `<ExtendedData>` so our own files round-trip losslessly; other apps ignore it.
 */
object KmlWriter {
    const val NAMESPACE = "http://www.opengis.net/kml/2.2"

    /** Streams to [out]; nothing is accumulated. The caller flushes/closes [out]. */
    fun write(out: Appendable, lists: Iterable<ExchangeList>, documentName: String? = null) {
        val x = XmlOut(out)
        x.raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"$NAMESPACE\">\n<Document>\n")
        x.element("name", documentName)
        for (list in lists) {
            x.raw("<Folder>\n")
            x.element("name", list.name)
            for (p in list.places) writePlace(x, p)
            for (d in list.drives) writeDrive(x, d)
            x.raw("</Folder>\n")
        }
        x.raw("</Document>\n</kml>\n")
    }

    /** UTF-8, buffered; flushes but does not close [out]. */
    fun write(out: OutputStream, lists: Iterable<ExchangeList>, documentName: String? = null) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        write(w, lists, documentName)
        w.flush()
    }

    private fun coord(p: LatLon) = "${formatCoord(p.lon)},${formatCoord(p.lat)},0"

    private fun writePlace(x: XmlOut, p: ExchangePlace) {
        x.raw("<Placemark>\n")
        x.element("name", p.name)
        x.element("address", p.address)
        x.element("description", p.notes)
        x.raw("<Point><coordinates>${coord(p.position)}</coordinates></Point>\n</Placemark>\n")
    }

    private fun data(x: XmlOut, name: String, value: String?) {
        if (value == null) return
        x.raw("<Data name=\"$name\">")
        x.element("value", value)
        x.raw("</Data>\n")
    }

    private fun writeDrive(x: XmlOut, d: ExchangeDrive) {
        x.raw("<Placemark>\n")
        x.element("name", d.name)
        x.raw("<ExtendedData>\n")
        data(x, KmlNames.KIND, KmlNames.KIND_DRIVE)
        data(x, KmlNames.HAS_ORIGIN, if (d.origin != null) "1" else "0")
        data(x, KmlNames.ORIGIN_LABEL, d.originLabel)
        data(x, KmlNames.DEST_LABEL, d.destinationLabel)
        data(x, KmlNames.TRAVEL_MODE, d.travelMode)
        // Index-aligned with the waypoints; empty string stands for "no label".
        for (w in d.waypoints) data(x, KmlNames.WAYPOINT_LABEL, w.label.orEmpty())
        x.raw("</ExtendedData>\n")
        val pts = buildList {
            d.origin?.let { add(it) }
            d.waypoints.forEach { add(it.position) }
            add(d.destination)
        }
        if (pts.size >= 2) {
            x.raw("<LineString><tessellate>1</tessellate><coordinates>")
            pts.forEachIndexed { i, p -> if (i > 0) x.raw(" "); x.raw(coord(p)) }
            x.raw("</coordinates></LineString>\n")
        } else {
            // A LineString needs >= 2 points; a destination-only drive is a marked Point.
            x.raw("<Point><coordinates>${coord(pts[0])}</coordinates></Point>\n")
        }
        x.raw("</Placemark>\n")
    }
}

internal object KmlNames {
    const val KIND = "kind"
    const val KIND_DRIVE = "drive"
    const val HAS_ORIGIN = "hasOrigin"
    const val ORIGIN_LABEL = "originLabel"
    const val DEST_LABEL = "destLabel"
    const val TRAVEL_MODE = "travelMode"
    const val WAYPOINT_LABEL = "waypointLabel"
}
