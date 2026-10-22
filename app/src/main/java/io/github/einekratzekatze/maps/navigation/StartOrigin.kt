package io.github.einekratzekatze.maps.navigation

import java.net.URLDecoder

/**
 * The route origin of a GMaps page `/maps/dir/<origin>/<destination>/...`. The Start handoff `link`
 * never carries it (spike N0 #4), so it is read from the page URL at press time. Returns null when
 * missing or unreadable; the origin is free text (a name or `lat,lon`), passed on as-is.
 */
object StartOrigin {
    private const val MAX_LABEL = 300

    fun parse(currentUrl: String?): String? {
        val path = currentUrl?.substringBefore('?')?.substringBefore('#') ?: return null
        val segments = path.substringAfter("/maps/dir/", "").split('/')
        val raw = segments.firstOrNull()?.takeIf { it.isNotEmpty() && segments.size > 1 && !it.startsWith("@") } ?: return null
        return try {
            URLDecoder.decode(raw, "UTF-8").trim().takeIf { it.isNotEmpty() && it.length <= MAX_LABEL && it.none(Char::isISOControl) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
