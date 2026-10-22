package io.github.einekratzekatze.maps.data.db

import android.util.Log
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.RoutePoint
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reads and writes `Drive.waypointsJson` (schema.md 1.4). Malformed input decodes to an empty list. */
object WaypointJson {
    private const val TAG = "WaypointJson"

    fun encode(points: List<RoutePoint>): String {
        val arr = JSONArray()
        for (p in points) {
            val o = JSONObject()
            o.put("label", p.label ?: JSONObject.NULL)
            if (p.latLon != null) {
                o.put("lat", p.latLon.lat)
                o.put("lon", p.latLon.lon)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String): List<RoutePoint> = try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val label = if (o.isNull("label")) null else o.getString("label")
            val hasLat = o.has("lat") && !o.isNull("lat")
            val hasLon = o.has("lon") && !o.isNull("lon")
            // lat/lon are both present or both absent; a lone one counts as absent.
            val latLon = if (hasLat && hasLon) LatLon(o.getDouble("lat"), o.getDouble("lon")) else null
            RoutePoint(label, latLon)
        }
    } catch (e: JSONException) {
        Log.w(TAG, "Malformed waypoints JSON, using empty list", e)
        emptyList()
    }
}
