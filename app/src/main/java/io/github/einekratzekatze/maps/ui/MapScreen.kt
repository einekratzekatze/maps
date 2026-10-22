package io.github.einekratzekatze.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.einekratzekatze.maps.map.MapWebView
import io.github.einekratzekatze.maps.map.WebViewHost

/**
 * The map destination (compose-host.md §7.1). Insets are applied once here and consumed, so the
 * overlays added by later phases only see what is left (the IME).
 * Z-order (§7.2), bottom to top: WebView < MarkerOverlay < DriveSavePrompt < PlacesBubbleOverlay < SuggestionPanel.
 */
@Composable
fun MapScreen(host: WebViewHost) {
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            MapWebView(host, Modifier.fillMaxSize())
        }
    }
}
