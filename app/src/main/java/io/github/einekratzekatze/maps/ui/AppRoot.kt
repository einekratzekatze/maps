package io.github.einekratzekatze.maps.ui

import androidx.compose.runtime.Composable
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.WebViewHost
import io.github.einekratzekatze.maps.ui.theme.AppTheme

/** Theme and (later) the navigation between the map, settings and list screens. */
@Composable
fun AppRoot(host: WebViewHost, mapState: MapStateRepository, commands: MapCommands) {
    AppTheme {
        MapScreen(host, mapState, commands)
    }
}
