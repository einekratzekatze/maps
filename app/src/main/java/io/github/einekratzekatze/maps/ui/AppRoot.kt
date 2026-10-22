package io.github.einekratzekatze.maps.ui

import androidx.compose.runtime.Composable
import io.github.einekratzekatze.maps.map.WebViewHost
import io.github.einekratzekatze.maps.ui.theme.AppTheme

/** Theme and (later) the navigation between the map, settings and list screens. */
@Composable
fun AppRoot(host: WebViewHost) {
    AppTheme {
        MapScreen(host)
    }
}
