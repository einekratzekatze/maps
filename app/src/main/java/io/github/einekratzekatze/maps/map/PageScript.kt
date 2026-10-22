package io.github.einekratzekatze.maps.map

import android.content.Context

/**
 * A document-start script a feature provides with Hilt `@IntoSet` (compose-host.md §4.3), e.g.
 * ui.js or markers.js. The host installs every one at WebView creation. Pages talk back through
 * the single `GmwBridge` message listener; there is no `addJavascriptInterface`.
 */
interface PageScript {
    val id: String
    /** e.g. setOf("https://www.google.com", "https://maps.google.com") */
    val allowedOrigins: Set<String>
    fun source(context: Context): String
}
