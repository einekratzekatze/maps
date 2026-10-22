package io.github.einekratzekatze.maps.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.einekratzekatze.maps.MainActivity
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapWebView
import io.github.einekratzekatze.maps.map.WebViewHost
import io.github.einekratzekatze.maps.map.dispatchMapCommand
import io.github.einekratzekatze.maps.navigation.NavLauncher
import io.github.einekratzekatze.maps.ui.bubble.PlacesBubbleOverlay
import io.github.einekratzekatze.maps.ui.bubble.PlacesBubbleViewModel

/**
 * The map destination (compose-host.md §7.1). Insets are applied once here and consumed, so the
 * overlays added by later phases only see what is left (the IME).
 * Z-order (§7.2), bottom to top: WebView < MarkerOverlay < DriveSavePrompt < PlacesBubbleOverlay < SuggestionPanel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(host: WebViewHost, mapState: MapStateRepository, commands: MapCommands) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlay by mapState.overlay.collectAsStateWithLifecycle()
    val bubble: PlacesBubbleViewModel = hiltViewModel()
    val bubbleState by bubble.uiState.collectAsStateWithLifecycle()
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(imeVisible) { mapState.setImeVisible(imeVisible) }

    // The single collector of MapCommands (§4.2); commands sent while stopped wait in the buffer.
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            commands.commands.collect { cmd ->
                dispatchMapCommand(cmd, host) { target ->
                    NavLauncher.launch(context, target, ComponentName(context, MainActivity::class.java))
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            MapWebView(host, Modifier.fillMaxSize())
            // MarkerOverlay and DriveSavePrompt go here (§7.2), below the bubble.
            PlacesBubbleOverlay(bubble, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            // TODO(custom search, nav-handoff): real actions for the search and drive-prompt branches.
            MapBackHandler(overlay, bubbleExpanded = bubbleState.expanded && bubbleState.cardAllowed) { action ->
                when (action) {
                    MapBackAction.ClearSearchFocus -> mapState.setSearchEditing(false)
                    MapBackAction.DismissDrivePrompt -> mapState.setDrivePromptVisible(false)
                    MapBackAction.CollapseBubble -> bubble.onClose()
                }
            }
        }
    }
}
