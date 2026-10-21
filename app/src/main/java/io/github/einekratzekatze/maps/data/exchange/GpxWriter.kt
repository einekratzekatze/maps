package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon
import java.io.OutputStream

/**
 * GPX 1.1 export. One `<wpt>` per place, one `<trk>` (single `<trkseg>`) per drive. GPX has no
 * folders, so the list name goes into `<type>` (OsmAnd reads it as the group/category), and each
 * item also carries `<maps:list index="n">name</maps:list>` so [GpxReader] restores list order,
 * empty list names and same-named lists exactly.
 *
 * Documented round-trip limitations (accepted, owner decision D4):
 * - An empty list leaves no trace in a GPX file (no items to carry it).
 * - Leading/trailing whitespace is trimmed on read from names, notes, addresses and labels (also
 *   in KML); an empty or blank label reads back as null. CR/CRLF read back as LF.
 * - Characters invalid in XML 1.0 (control chars, lone surrogates) are dropped on write.
 * - Coordinates are written with at most 7 decimals (about 1 cm).
 * - Longitude 180 is written as -180 (gpx.xsd's longitudeType excludes 180): same meridian.
 * - Lists are iterated once; each list's places and drives are iterated once.
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
        // `lists` is consumed once; only the (cheap) headers are kept, since gpx.xsd needs every
        // <wpt> before the first <trk>. The lazy places/drives inside are still iterated once.
        val all = lists.toList()
        all.forEachIndexed { i, list -> for (p in list.places) writePlace(x, i, list.name, p) }
        all.forEachIndexed { i, list -> for (d in list.drives) writeDrive(x, i, list.name, d) }
        x.raw("</gpx>\n")
    }

    /** UTF-8, buffered; flushes but does not close [out]. */
    fun write(out: OutputStream, lists: Iterable<ExchangeList>, documentName: String? = null) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        write(w, lists, documentName)
        w.flush()
    }

    /** gpx.xsd's longitudeType is [-180, 180): 180 (also after 7-decimal rounding) becomes -180. */
    private fun lon(v: Double): String = formatCoord(v).let { if (it == "180") "-180" else it }

    private fun listExt(x: XmlOut, index: Int, list: String) {
        x.raw("<maps:list index=\"$index\">")
        x.text(list)
        x.raw("</maps:list>")
    }

    private fun point(x: XmlOut, tag: String, p: LatLon, name: String?) {
        x.raw("<$tag lat=\"${formatCoord(p.lat)}\" lon=\"${lon(p.lon)}\"")
        if (name.isNullOrEmpty()) x.raw("/>\n") else { x.raw(">"); x.element("name", name); x.raw("</$tag>\n") }
    }

    private fun writePlace(x: XmlOut, index: Int, list: String, p: ExchangePlace) {
        x.raw("<wpt lat=\"${formatCoord(p.position.lat)}\" lon=\"${lon(p.position.lon)}\">\n")
        x.element("name", p.name)
        x.element("desc", p.notes)
        x.element("type", list.ifEmpty { null })
        x.raw("<extensions>")
        listExt(x, index, list)
        x.element("maps:address", p.address)
        x.raw("</extensions>\n</wpt>\n")
    }

    private fun writeDrive(x: XmlOut, index: Int, list: String, d: ExchangeDrive) {
        x.raw("<trk>\n")
        x.element("name", d.name)
        x.element("type", list.ifEmpty { null })
        x.raw("<extensions>")
        listExt(x, index, list)
        x.element("maps:hasOrigin", if (d.origin != null) "1" else "0")
        x.element("maps:travelMode", d.travelMode)
        x.raw("</extensions>\n<trkseg>\n")
        d.origin?.let { point(x, "trkpt", it, d.originLabel) }
        for (w in d.waypoints) point(x, "trkpt", w.position, w.label)
        point(x, "trkpt", d.destination, d.destinationLabel)
        x.raw("</trkseg>\n</trk>\n")
    }
}
