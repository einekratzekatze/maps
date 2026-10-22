package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import io.github.einekratzekatze.maps.R

/**
 * Stateless bubble + card layout (§3.2); previews and Compose tests target this.
 * The full-size root has no background, click or pointer input, so touches outside the bubble
 * and the card fall through to the WebView (T-C9).
 */
@Composable
fun PlacesBubbleLayout(
    state: BubbleUiState,
    savedAnchor: BubbleAnchor,
    onAnchorChange: (BubbleAnchor) -> Unit,
    actions: PlacesBubbleActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val bounds = remember(constraints, density, state.searchHidden) {
            BubbleBounds.from(constraints, density, state.searchHidden)
        }
        val resting = anchorToOffset(savedAnchor, bounds, isRtl)
        val onRight = (savedAnchor.side == EdgeSide.End) != isRtl

        // Two siblings, each positioned by its OWN outer modifier: an offset inside
        // AnimatedVisibility content would be clipped (T-C13).
        AnimatedVisibility(
            visible = state.bubbleVisible && !state.expanded,
            modifier = Modifier.offset { resting.round() },
            enter = fadeIn(tween(90)),
            exit = fadeOut(tween(90)),
        ) {
            PlacesBubble(
                badge = state.badge,
                badgeOnLeft = onRight,
                dragging = false,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.bubble_open), role = Role.Button) { actions.onBubbleTap() },
            )
        }

        val placement = remember(resting, savedAnchor.side, bounds, isRtl) { cardPlacement(resting, savedAnchor.side, bounds, isRtl) }
        val origin = TransformOrigin(if (onRight) 1f else 0f, 0.1f)
        AnimatedVisibility(
            visible = state.expanded && state.cardAllowed,
            modifier = Modifier.offset { placement.topLeft },
            enter = scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.6f, transformOrigin = origin) + fadeIn(tween(220)),
            exit = scaleOut(tween(160), targetScale = 0.6f, transformOrigin = origin) + fadeOut(tween(160)),
        ) {
            val cardModifier = Modifier.width(placement.width).heightIn(max = placement.maxHeight)
            when (state.focus) {
                CardFocus.Nearby -> NearbyCard(state, actions, cardModifier)
                is CardFocus.Place -> PlaceCard(state.placeCard, actions, cardModifier)
            }
        }
    }
}

