package io.github.einekratzekatze.maps.data.mymaps

/**
 * Pure helpers for Google My Maps URLs (TODO 5.1).
 *
 * Accepted input: a user-pasted `google.<tld>/maps/d/[u/N/]<viewer|edit|embed|...>?...mid=<id>`
 * URL, with or without scheme, `www.`, surrounding whitespace and extra query parameters or
 * fragment. Everything else yields `null`.
 */
object MyMapsUrl {
    private val MAP_URL = Regex(
        """^(?:https?://)?(?:www\.)?google\.[a-z]{2,3}(?:\.[a-z]{2})?/maps/d/(?:u/\d+/)?[A-Za-z0-9_-]+/?\?(?:[^#]*&)?mid=([A-Za-z0-9_-]+)(?:[&#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns the My Maps `mid` of a pasted URL, or null if it is not a My Maps URL. */
    fun extractMapId(pasted: String): String? =
        MAP_URL.matchEntire(pasted.trim())?.groupValues?.get(1)

    /**
     * Public KML endpoint (TODO 5.1: `https://www.google.com/maps/d/kml?mid=<map_id>`).
     * `forcekml=1` is deliberately NOT added: neither TODO nor PROJECT_GOALS mentions it.
     * [mapId] must be a value returned by [extractMapId]; it is not re-validated, but is
     * rejected if it contains characters outside `[A-Za-z0-9_-]`.
     */
    fun kmlUrl(mapId: String): String {
        require(mapId.isNotEmpty() && mapId.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            "Invalid My Maps id"
        }
        return "https://www.google.com/maps/d/kml?mid=$mapId"
    }
}
