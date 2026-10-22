package io.github.einekratzekatze.maps.navigation

import android.content.Intent
import android.net.Uri
import java.math.BigDecimal
import java.math.RoundingMode

/** Builds `geo:` VIEW intents for a [NavTarget]. [geoUri] is pure JVM; only [build] touches Android. */
object NavIntentBuilder {
    fun geoUri(target: NavTarget): String {
        val latLon = target.latLon
        if (latLon == null) return "geo:0,0?q=" + encode(requireNotNull(target.label))
        val point = "${number(latLon.lat)},${number(latLon.lon)}"
        val label = target.label?.let { "(" + encode(it) + ")" }.orEmpty()
        return "geo:$point?q=$point$label"
    }

    fun build(target: NavTarget): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(target)))

    /** Google Maps "Maps URLs" route with an explicit origin; `geo:` cannot carry one. */
    fun routeUrl(origin: String, target: NavTarget, mode: TravelMode? = null): String =
        "https://www.google.com/maps/dir/?api=1&origin=" + encode(origin) + "&destination=" + encode(destination(target)) +
            mode?.let { "&travelmode=" + it.apiName }.orEmpty()

    fun buildRoute(origin: String, target: NavTarget, mode: TravelMode?, packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(routeUrl(origin, target, mode))).setPackage(packageName)

    private fun destination(target: NavTarget): String =
        target.latLon?.let { "${number(it.lat)},${number(it.lon)}" } ?: requireNotNull(target.label)

    /** Locale-independent, at most 7 decimals, no exponent, no "-0". */
    private fun number(value: Double): String {
        val decimal = BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP)
        return if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toPlainString()
    }

    /** Percent-encodes everything except RFC 3986 unreserved characters, as UTF-8 (space -> %20). */
    private fun encode(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(java.util.Locale.ROOT, byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}
