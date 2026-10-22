package io.github.einekratzekatze.maps.navigation

/**
 * Detects the Start button of the GMaps web route screen (spike N0, docs/design/spike-results.md).
 *
 * A press navigates to `https://maps.app.goo.gl/?...&link=` and then to `intent://maps.app.goo.gl/?link=`.
 * The `link` only carries the destination (`/maps/dir//<dest>`), never the origin, so loading it would
 * replace the route page. `utm_campaign` does not tell Start from Directions; the page URL does:
 * Start fires on `/maps/dir/<origin>/<dest>`, Directions on a place page. With "Your location" as the
 * origin the page is `/maps/dir//<dest>/.../data=...!1m1!4e2...` (empty origin, `!4e2` marker; log of 2026-10-22).
 */
object StartPress {
    private val handoffPrefixes = listOf("https://maps.app.goo.gl/", "intent://maps.app.goo.gl/")
    private val completeRoutePath = Regex("^/maps/dir/[^/]+/[^/]+")
    private val myLocationRoutePath = Regex("^/maps/dir//[^/]+/")
    private val myLocationMarker = "!4e2"

    fun isStart(navigatedUrl: String, currentUrl: String?): Boolean {
        if (currentUrl == null || handoffPrefixes.none { navigatedUrl.startsWith(it) }) return false
        val path = currentUrl.removePrefix("https://www.google.com").takeIf { it.length != currentUrl.length }
            ?: return false
        val pathOnly = path.substringBefore('?')
        return completeRoutePath.containsMatchIn(pathOnly) ||
            (myLocationRoutePath.containsMatchIn(pathOnly) && pathOnly.contains(myLocationMarker))
    }
}
