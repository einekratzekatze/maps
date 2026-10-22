package io.github.einekratzekatze.maps.map

/** The one way to drive the WebView (compose-host.md §4.1). [WebViewHost] implements it. */
interface MapController {
    /** Full page load. Cold-starts the GMaps SPA (seconds on mid-range phones), adds a history entry. */
    fun loadUrl(url: String)

    /**
     * Meant to prefer GMaps' in-page (SPA) navigation. Spike B0 #7 showed that a synthetic link
     * click causes a full page navigation anyway, so this is just [loadUrl]. Callers keep using
     * it so the in-page path can return if a working one is found.
     */
    fun navigateInPage(url: String)

    fun evaluateJs(script: String, callback: ((String?) -> Unit)? = null)

    /** Android -> page message. Uses the reply proxy of the GmwBridge listener (§4.3). */
    fun postMessage(json: String)

    fun canGoBack(): Boolean
    fun goBack()
}
