package io.github.einekratzekatze.maps.domain

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distances for display (floating-places-bubble.md §5.5). */
object Distance {
    /** Mean Earth radius in metres (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Haversine distance in metres. Longitudes may wrap (179.9 to -179.9 is ~11 km at the equator). */
    fun meters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /**
     * Short visible label: under 1 km in 10 m steps ("350 m"), under 10 km with one decimal
     * ("1.2 km", "1,2 km" in German), else whole kilometres ("12 km"). [locale] picks the
     * decimal separator; it is passed explicitly so the result never depends on the default.
     * Negative or non-finite input gives "–".
     */
    fun format(meters: Double, locale: Locale): String {
        if (!meters.isFinite() || meters < 0) return "–"
        val roundedM = (meters / 10).roundToLong() * 10
        if (roundedM < 1000) return "$roundedM m"
        val km = meters / 1000
        val tenths = (km * 10).roundToLong()
        if (tenths < 100) return String.format(locale, "%.1f km", tenths / 10.0)
        return String.format(locale, "%,d km", km.roundToLong())
    }
}
