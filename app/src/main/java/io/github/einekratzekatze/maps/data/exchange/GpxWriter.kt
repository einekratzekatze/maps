package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.OutputStream

/**
 * GPX 1.1 export. One `<wpt>` per place, one `<trk>` (single `<trkseg>`) per drive. GPX has no
 * folders, so the list name goes into `<type>` (OsmAnd reads it as the group/category) and
 * the reader regroups by it. Consequences: an empty list leaves no trace in a GPX file, and
 * two lists with the same name merge on re-import.
 *
 * Everything not in the GPX schema (address, unset origin, travel mode) lives in `<extensions>`
 * under the `maps:` namespace. Element order follows gpx.xsd (wpt before trk; within a wpt/trk
 * the xsd sequence).
 */
object GpxWriter {
    const val NAMESPACE = "http://www.topografix.com/GPX/1/1"
    const val EXTENSION_NAMESPACE = "https://github.com/einekratzekatze/maps/gpx/1"
    const val CREATOR = "Maps (io.github.einekratzekatze.maps)"

    fun write(out: Appendable, lists: Iterable<ExchangeList>, documentName: String? = null) {
        val x = XmlOut(out)
        x.raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        x.raw("<gpx version=\"1.1\" creator=\"$CREATOR\" xmlns=\"$NAMESPACE\" ")
        x.raw("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:maps=\"$EXTENSION_NAMESPACE\" ")
        x.raw("xsi:schemaLocation=\"$NAMESPACE http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        if (documentName != null) {
            x.raw("<metadata>")
            x.element("name", documentName)
            x.raw("</metadata>\n")
        }
        for (list in lists) for (p in list.places) writePlace(x, list.name, p)
        for (list in lists) for (d in list.drives) writeDrive(x, list.name, d)
        x.raw("</gpx>\n")
    }

    /** UTF-8, buffered; flushes but does not close [out]. */
    fun write(out: OutputStream, lists: Iterable<ExchangeList>, documentName: String? = null) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        write(w, lists, documentName)
        w.flush()
    }

    private fun point(x: XmlOut, tag: String, p: LatLon, name: String?) {
        x.raw("<$tag lat=\"${formatCoord(p.lat)}\" lon=\"${formatCoord(p.lon)}\"")
        if (name.isNullOrEmpty()) x.raw("/>\n") else { x.raw(">"); x.element("name", name); x.raw("</$tag>\n") }
    }

    private fun writePlace(x: XmlOut, list: String, p: ExchangePlace) {
        x.raw("<wpt lat=\"${formatCoord(p.position.lat)}\" lon=\"${formatCoord(p.position.lon)}\">\n")
        x.element("name", p.name)
        x.element("desc", p.notes)
        x.element("type", list.ifEmpty { null })
        if (p.address != null) {
            x.raw("<extensions>")
            x.element("maps:address", p.address)
            x.raw("</extensions>\n")
        }
        x.raw("</wpt>\n")
    }

    private fun writeDrive(x: XmlOut, list: String, d: ExchangeDrive) {
        x.raw("<trk>\n")
        x.element("name", d.name)
        x.element("type", list.ifEmpty { null })
        x.raw("<extensions>")
        x.element("maps:hasOrigin", if (d.origin != null) "1" else "0")
        x.element("maps:travelMode", d.travelMode)
        x.raw("</extensions>\n<trkseg>\n")
        d.origin?.let { point(x, "trkpt", it, d.originLabel) }
        for (w in d.waypoints) point(x, "trkpt", w.position, w.label)
        point(x, "trkpt", d.destination, d.destinationLabel)
        x.raw("</trkseg>\n</trk>\n")
    }
}
