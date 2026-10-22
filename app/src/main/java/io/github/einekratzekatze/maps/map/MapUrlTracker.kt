package io.github.einekratzekatze.maps.map

import kotlinx.coroutines.delay

/**
 * Merges the WebView's URL sources into one de-duplicated stream (floating-places-bubble.md §5.1).
 * Unit-testable: takes a URL supplier, not a WebView. The host passes `{ webView.url }` and
 * `mapState::reportUrl`.
 *
 * Spike B0: `doUpdateVisitedHistory` fires for every in-page URL change, and a 250 ms poll saw
 * nothing it missed. The poll stays as a 1 s safety net.
 */
class MapUrlTracker(
    private val currentUrl: () -> String?,
    private val report: (String?) -> Unit,
    private val pollMs: Long = URL_POLL_MS,
) {
    companion object {
        const val URL_POLL_MS = 1000L
    }

    private var last: String? = null
    private var reportedOnce = false
    /** Between onPageStarted and onPageFinished the poll stays quiet: the camera is unknown until the page is up. */
    private var loading = false

    fun onVisitedHistory(url: String) = emit(url)

    /** A full load is starting: the old camera is stale. */
    fun onPageStarted() {
        loading = true
        emit(null)
    }

    fun onPageFinished(url: String) {
        loading = false
        emit(url)
    }

    /** Run inside `repeatOnLifecycle(RESUMED)`; returns only by cancellation. */
    suspend fun pollWhileResumed() {
        while (true) {
            delay(pollMs)
            if (!loading) emit(currentUrl())
        }
    }

    private fun emit(url: String?) {
        if (reportedOnce && url == last) return
        reportedOnce = true
        last = url
        report(url)
    }
}
