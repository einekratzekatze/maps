package io.github.einekratzekatze.maps.ui.bubble

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.einekratzekatze.maps.R

/** Stateful entry point wired to [PlacesBubbleViewModel] (§3.2). Renders nothing until the saved anchor has loaded. */
@Composable
fun PlacesBubbleOverlay(viewModel: PlacesBubbleViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAnchor by viewModel.anchor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // TODO(Phase 4/7): open the Home/Work editor once the settings screen exists.
                is BubbleEffect.OpenImportantEditor ->
                    Toast.makeText(context, R.string.important_editor_coming_soon, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val anchor = savedAnchor ?: return
    PlacesBubbleLayout(
        state = state,
        savedAnchor = anchor,
        onAnchorChange = viewModel::onAnchorSettled,
        actions = viewModel,
        modifier = modifier,
    )
}
