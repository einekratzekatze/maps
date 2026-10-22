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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        val scope = rememberCoroutineScope()
        val position = remember { BubblePositionState(savedAnchor, bounds, isRtl) }
        val currentOnAnchorChange by rememberUpdatedState(onAnchorChange)
        // Persist first, then animate: an interrupted spring must not lose the target (S6, T-C14).
        val settle: (BubbleAnchor, Velocity) -> Unit = { target, velocity ->
            currentOnAnchorChange(target)
            scope.launch { position.animateTo(target, velocity) }
        }

        LaunchedEffect(bounds, isRtl) {
            position.bounds = bounds
            position.isRtl = isRtl
            if (!position.isDragging) position.snapToAnchor(position.target)
        }
        // A DataStore echo of our own settle equals the target and is ignored; anything else (first load) is applied.
        LaunchedEffect(savedAnchor) {
            if (savedAnchor != position.target && !position.isDragging && !position.isSettling) position.snapToAnchor(savedAnchor)
        }
        LaunchedEffect(state.bubbleVisible) {
            if (!state.bubbleVisible) position.snapToAnchor(position.target)
        }
        val onRight = (position.side == EdgeSide.End) != isRtl

        // Two siblings, each positioned by its OWN outer modifier: an offset inside
        // AnimatedVisibility content would be clipped (T-C13).
        AnimatedVisibility(
            visible = state.bubbleVisible && !state.expanded,
            modifier = Modifier.offset { position.offset.round() },
            enter = fadeIn(tween(90)),
            exit = fadeOut(tween(90)),
        ) {
            PlacesBubble(
                badge = state.badge,
                badgeOnLeft = onRight,
                dragging = position.isDragging,
                modifier = Modifier
                    .bubbleSemantics(state.badge, state.content, position, settle)
                    .systemGestureExclusion() // API 29+: the back gesture must not steal drags at the edge (D5)
                    .bubbleDrag(position, scope, settle)
                    .clickable(onClickLabel = stringResource(R.string.bubble_open), role = Role.Button) { actions.onBubbleTap() },
            )
        }

        val resting = position.restingOffset
        val placement = remember(resting, position.side, bounds, isRtl) { cardPlacement(resting, position.side, bounds, isRtl) }
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

/** §4.5: drag with velocity, settle on release, cancel or when the gesture coroutine dies mid-drag. */
private fun Modifier.bubbleDrag(
    position: BubblePositionState,
    scope: CoroutineScope,
    settle: (BubbleAnchor, Velocity) -> Unit,
): Modifier = pointerInput(Unit) { // keyed on Unit: a bounds change must NOT restart the gesture
    val tracker = VelocityTracker()
    var dragging = false
    var startPos = Offset.Zero
    var accumulated = Offset.Zero
    fun end(velocity: Velocity) {
        if (!dragging) return
        dragging = false
        settle(position.computeTarget(startPos + accumulated, velocity), velocity)
    }
    try {
        detectDragGestures(
            onDragStart = {
                dragging = true
                tracker.resetTracking()
                startPos = position.offset
                accumulated = Offset.Zero
                scope.launch { position.startDrag() }
            },
            onDrag = { change, delta ->
                change.consume()
                accumulated += delta
                // Overlay space (start + sum of deltas), not the local pointer position, which barely moves.
                tracker.addPosition(change.uptimeMillis, startPos + accumulated)
                scope.launch { position.dragBy(delta) }
            },
            onDragEnd = { end(tracker.calculateVelocity()) },
            onDragCancel = { end(Velocity.Zero) },
        )
    } finally {
        // detectDragGestures does not call onDragCancel when this coroutine is cancelled.
        end(Velocity.Zero)
    }
}

/** §7: one merged node with a state description and accessible alternatives to dragging. */
@Composable
private fun Modifier.bubbleSemantics(
    badge: BadgeState,
    content: NearbyContent,
    position: BubblePositionState,
    settle: (BubbleAnchor, Velocity) -> Unit,
): Modifier {
    val description = stringResource(R.string.bubble_description)
    val stateText = when {
        badge is BadgeState.Count && badge.label == "99+" -> stringResource(R.string.bubble_state_many_in_view)
        badge is BadgeState.Count -> pluralStringResource(R.plurals.bubble_state_in_view, badge.n, badge.n)
        content == NearbyContent.ViewportUnknown -> stringResource(R.string.bubble_state_unknown)
        else -> stringResource(R.string.bubble_state_none)
    }
    val left = stringResource(R.string.bubble_move_left)
    val right = stringResource(R.string.bubble_move_right)
    val up = stringResource(R.string.bubble_move_up)
    val down = stringResource(R.string.bubble_move_down)
    return semantics(mergeDescendants = true) {
        contentDescription = description
        stateDescription = stateText
        customActions = listOf(
            CustomAccessibilityAction(left) { moveTo(position, settle, left = true); true },
            CustomAccessibilityAction(right) { moveTo(position, settle, left = false); true },
            CustomAccessibilityAction(up) { nudge(position, settle, -A11Y_STEP); true },
            CustomAccessibilityAction(down) { nudge(position, settle, A11Y_STEP); true },
        )
    }
}

private const val A11Y_STEP = 0.2f

private fun moveTo(position: BubblePositionState, settle: (BubbleAnchor, Velocity) -> Unit, left: Boolean) {
    val side = if (left != position.isRtl) EdgeSide.Start else EdgeSide.End
    settle(position.target.copy(side = side), Velocity.Zero)
}

private fun nudge(position: BubblePositionState, settle: (BubbleAnchor, Velocity) -> Unit, delta: Float) {
    val t = position.target
    settle(t.copy(yFraction = (t.yFraction + delta).coerceIn(0f, 1f)), Velocity.Zero)
}

