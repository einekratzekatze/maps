package io.github.einekratzekatze.maps.map.url

import android.net.Uri
import io.github.einekratzekatze.maps.domain.CameraMode
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapCamera
import java.util.Locale

/** The one URL builder. Numbers use [Locale.ROOT] (never the default locale), text `Uri.encode`. */
object MapUrls {
    private const val BASE = "https://www.google.com/maps"

    fun camera(latLon: LatLon, zoom: Double): String = "$BASE/@${at(latLon, zoom)}"

    fun place(latLon: LatLon, zoom: Double): String =
        "$BASE/place/${coord(latLon.lat)},${coord(latLon.lon)}/@${at(latLon, zoom)}"

    /** The camera biases the search only when it is a plain web-zoom camera. */
    fun search(query: String, camera: MapCamera? = null): String {
        val q = Uri.encode(query)
        val usable = camera != null && (camera.mode == CameraMode.Map || camera.mode == CameraMode.Tilted)
        return if (usable) "$BASE/search/$q/@${at(camera!!.center, camera.zoom)}" else "$BASE/search/$q"
    }

    fun prediction(primary: String, placeId: String): String =
        "$BASE/search/?api=1&query=${Uri.encode(primary)}&query_place_id=${Uri.encode(placeId)}"

    private fun at(p: LatLon, zoom: Double) = "${coord(p.lat)},${coord(p.lon)},${zoom(zoom)}z"

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.7f", v)

    /** `17.0` -> `17`, `14.5` -> `14.5`, up to 2 decimals. */
    private fun zoom(z: Double): String {
        val s = String.format(Locale.ROOT, "%.2f", z)
        return s.trimEnd('0').trimEnd('.')
    }
}
