package io.github.einekratzekatze.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import io.github.einekratzekatze.maps.map.OverlayState

/** Overlay back actions, highest priority first (compose-host.md §7.4, items 1-3). */
enum class MapBackAction { ClearSearchFocus, DismissDrivePrompt, CollapseBubble }

/** null: no overlay wants Back, so it falls through to WebView history / finish (items 4-5). */
fun mapBackAction(overlay: OverlayState, bubbleExpanded: Boolean): MapBackAction? = when {
    overlay.searchEditing -> MapBackAction.ClearSearchFocus
    overlay.drivePromptVisible -> MapBackAction.DismissDrivePrompt
    bubbleExpanded -> MapBackAction.CollapseBubble
    else -> null
}

/**
 * The one Compose back handler for the map screen, so priority never depends on composition
 * order. The Activity's history-back callback is registered before `setContent`, so this wins
 * whenever it is enabled.
 */
@Composable
fun MapBackHandler(overlay: OverlayState, bubbleExpanded: Boolean, onAction: (MapBackAction) -> Unit) {
    val action = mapBackAction(overlay, bubbleExpanded)
    BackHandler(enabled = action != null) { action?.let(onAction) }
}
