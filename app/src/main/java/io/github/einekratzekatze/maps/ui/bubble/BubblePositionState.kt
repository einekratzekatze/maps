package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity

/**
 * Bubble position (floating-places-bubble.md §4.4). One [Animatable] holds the top-left in px;
 * a new drag stops a running settle (S6). Settling is split into [computeTarget] (persisted by
 * the caller first) and [animateTo], so a cancelled spring never skips persistence.
 */
@Stable
class BubblePositionState(initialAnchor: BubbleAnchor, bounds: BubbleBounds, isRtl: Boolean) {
    /** Updated from composition; read at gesture time, never a pointerInput key. */
    var bounds: BubbleBounds = bounds
    var isRtl: Boolean = isRtl

    private val animatable = Animatable(anchorToOffset(initialAnchor, bounds, isRtl), Offset.VectorConverter)

    /** Current top-left in px; read it in `Modifier.offset { }`. */
    val offset: Offset get() = animatable.value
    var isDragging by mutableStateOf(false)
        private set
    /** The anchor the bubble rests at or is settling to. */
    var target by mutableStateOf(initialAnchor)
        private set
    val side: EdgeSide get() = target.side
    val isSettling: Boolean get() = animatable.isRunning
    val restingOffset: Offset get() = anchorToOffset(target, bounds, isRtl)

    suspend fun startDrag() {
        isDragging = true
        animatable.stop()
    }

    /** 1:1 with the finger, clamped to the overlay (not to the resting range; the user may drag over the top band). */
    suspend fun dragBy(delta: Offset) {
        animatable.snapTo(clampToOverlay(animatable.value + delta))
    }

    fun clampToOverlay(p: Offset): Offset {
        val maxX = (bounds.widthPx - bounds.sizePx).coerceAtLeast(0f)
        val maxY = (bounds.heightPx - bounds.sizePx).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(0f, maxX), p.y.coerceIn(0f, maxY))
    }

    /**
     * [releaseTopLeft] comes from the gesture (start + sum of deltas), not from [offset]: the
     * launched [dragBy] calls can still be pending when the finger lifts.
     */
    fun computeTarget(releaseTopLeft: Offset, velocity: Velocity): BubbleAnchor =
        computeSnapAnchor(clampToOverlay(releaseTopLeft), velocity, bounds, isRtl)

    /** Ends the drag and springs to [anchor]; may be cancelled by a new touch. */
    suspend fun animateTo(anchor: BubbleAnchor, velocity: Velocity = Velocity.Zero) {
        isDragging = false
        target = anchor
        animatable.animateTo(
            anchorToOffset(anchor, bounds, isRtl),
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            initialVelocity = Offset(velocity.x, velocity.y),
        )
    }

    /** No animation: bounds changed, the bubble was hidden, or the saved anchor loaded. */
    suspend fun snapToAnchor(anchor: BubbleAnchor) {
        isDragging = false
        target = anchor
        animatable.snapTo(anchorToOffset(anchor, bounds, isRtl))
    }
}
