package io.github.einekratzekatze.maps.data.mymaps

/**
 * Pure helpers for Google My Maps URLs (TODO 5.1).
 *
 * Accepted input: a user-pasted `google.<tld>/maps/d/[u/N/]<viewer|edit|embed|...>?...mid=<id>`
 * URL, with or without scheme, `www.`, surrounding whitespace and extra query parameters or
 * fragment. Everything else yields `null`.
 */
object MyMapsUrl {
    // Explicit second-level ccTLD combinations, so `google.com.ev` is not accepted as Google.
    private const val CO_TLDS = "uk|jp|in|nz|za|kr|il|th|id|ve|ao|bw|ck|cr|hu|ke|ls|ma|mz|tz|ug|uz|zm|zw"
    private const val COM_TLDS = "au|br|ar|mx|tr|sg|hk|tw|ua|co|pe|ph|pk|my|eg|sa|ng|vn|ec|uy|gt|bd|bh|bo|cu|cy|do|et|fj|gh|gi|jm|kh|kw|lb|ly|mt|ni|np|om|pa|py|qa|sv|tj|tn"

    private val MAP_URL = Regex(
        """^(?:https?://)?(?:www\.)?google\.(?:com|[a-z]{2}|(?:co\.(?:$CO_TLDS)|com\.(?:$COM_TLDS)))/maps/d/(?:u/\d+/)?[A-Za-z0-9_-]+/?\?(?:[^#]*&)?mid=([A-Za-z0-9_-]+)(?:[&#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns the My Maps `mid` of a pasted URL, or null if it is not a My Maps URL. */
    fun extractMapId(pasted: String): String? =
        MAP_URL.matchEntire(pasted.trim())?.groupValues?.get(1)

    /**
     * Public KML endpoint (TODO 5.1: `https://www.google.com/maps/d/kml?mid=<map_id>`).
     * `forcekml=1` makes Google return plain KML instead of a KMZ (zip) archive (owner decision
     * D1); `KmlParser` rejects a zip with a clear error rather than returning an empty map.
     * [mapId] must be a value returned by [extractMapId]; it is not re-validated, but is
     * rejected if it contains characters outside `[A-Za-z0-9_-]`.
     */
    fun kmlUrl(mapId: String): String {
        require(mapId.isNotEmpty() && mapId.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            "Invalid My Maps id"
        }
        return "https://www.google.com/maps/d/kml?mid=$mapId&forcekml=1"
    }
}
