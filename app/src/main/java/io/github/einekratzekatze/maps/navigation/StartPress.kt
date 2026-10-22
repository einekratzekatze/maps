package io.github.einekratzekatze.maps.navigation

/**
 * Detects the Start button of the GMaps web route screen (spike N0, docs/design/spike-results.md).
 *
 * A press navigates to `https://maps.app.goo.gl/?...&link=` and then to `intent://maps.app.goo.gl/?link=`.
 * The `link` only carries the destination (`/maps/dir//<dest>`), never the origin, so loading it would
 * replace the route page. `utm_campaign` does not tell Start from Directions; the page URL does:
 * Start fires on `/maps/dir/<origin>/<dest>`, Directions on a place page.
 */
object StartPress {
    private val handoffPrefixes = listOf("https://maps.app.goo.gl/", "intent://maps.app.goo.gl/")
    private val completeRoutePath = Regex("^/maps/dir/[^/]+/[^/]+")

    fun isStart(navigatedUrl: String, currentUrl: String?): Boolean {
        if (currentUrl == null || handoffPrefixes.none { navigatedUrl.startsWith(it) }) return false
        val path = currentUrl.removePrefix("https://www.google.com").takeIf { it.length != currentUrl.length }
            ?: return false
        return completeRoutePath.containsMatchIn(path.substringBefore('?'))
    }
}
