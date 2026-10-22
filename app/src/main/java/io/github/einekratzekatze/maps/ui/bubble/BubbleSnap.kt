package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Logical edge: Start = left in LTR, right in RTL. */
enum class EdgeSide { Start, End }

/** What is persisted: a side and a fraction (0..1) of the allowed y range, so any resize is free. */
data class BubbleAnchor(val side: EdgeSide, val yFraction: Float) {
    companion object {
        val Default = BubbleAnchor(EdgeSide.End, 0.45f)
    }
}

/**
 * Allowed range of the bubble's top-left corner, in px of the overlay (floating-places-bubble.md §4.1).
 * [widthPx]/[heightPx] are the overlay size, [density] converts the dp limits of the card.
 */
data class BubbleBounds(
    val minX: Float, val maxX: Float,
    val minY: Float, val maxY: Float,
    val sizePx: Float,
    val widthPx: Float, val heightPx: Float,
    val density: Float,
) {
    companion object {
        fun from(constraints: Constraints, density: Density, searchHidden: Boolean): BubbleBounds =
            from(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density.density, searchHidden)

        /** Sizes in px. */
        fun from(widthPx: Float, heightPx: Float, density: Float, searchHidden: Boolean): BubbleBounds {
            val size = BubbleDefaults.Size.value * density
            val margin = BubbleDefaults.EdgeMargin.value * density
            val top = (if (searchHidden) BubbleDefaults.TopWithoutGmapsSearch else BubbleDefaults.TopWithGmapsSearch).value * density
            val minX = margin
            val maxX = max(minX, widthPx - margin - size)
            var minY = top
            var maxY = heightPx - BubbleDefaults.BottomReserve.value * density - size
            if (maxY < minY) {
                minY = max(0f, (heightPx - size) / 2)
                maxY = minY
            }
            return BubbleBounds(minX, maxX, minY, maxY, size, widthPx, heightPx, density)
        }
    }
}

/** Where the expanded card goes. [width]/[maxHeight] in dp, [topLeft] in px. */
data class CardPlacement(val topLeft: IntOffset, val width: Dp, val maxHeight: Dp)

private fun isLeft(side: EdgeSide, isRtl: Boolean) = (side == EdgeSide.Start) != isRtl

/**
 * Pure snap target for a released drag (§4.3). [releaseTopLeft] in px, [velocity] in px/s.
 * A fast horizontal fling picks the side by direction; otherwise the side nearer to the
 * projected centre wins, ties go to [EdgeSide.End].
 */
fun computeSnapAnchor(releaseTopLeft: Offset, velocity: Velocity, bounds: BubbleBounds, isRtl: Boolean): BubbleAnchor {
    val projX = releaseTopLeft.x + velocity.x * BubbleDefaults.ProjectionX
    val projY = releaseTopLeft.y + velocity.y * BubbleDefaults.ProjectionY
    val side = when {
        abs(velocity.x) > BubbleDefaults.FlingSideVelocity -> physicalToLogical(left = velocity.x < 0, isRtl)
        else -> {
            val centre = projX + bounds.sizePx / 2
            val half = bounds.widthPx / 2
            if (centre == half) EdgeSide.End else physicalToLogical(left = centre < half, isRtl)
        }
    }
    val y = projY.coerceIn(bounds.minY, bounds.maxY)
    val range = bounds.maxY - bounds.minY
    val fraction = if (range <= 0f) 0.5f else (y - bounds.minY) / range
    return BubbleAnchor(side, fraction)
}

private fun physicalToLogical(left: Boolean, isRtl: Boolean) = if (left != isRtl) EdgeSide.Start else EdgeSide.End

/** Top-left px of the bubble resting at [anchor]. */
fun anchorToOffset(anchor: BubbleAnchor, bounds: BubbleBounds, isRtl: Boolean): Offset {
    val x = if (isLeft(anchor.side, isRtl)) bounds.minX else bounds.maxX
    val y = bounds.minY + anchor.yFraction.coerceIn(0f, 1f) * (bounds.maxY - bounds.minY)
    return Offset(x, y)
}

/**
 * Card size and position next to a resting bubble (§3.3). The top uses the max height, so it
 * does not depend on measured content. In a window too short for the card below the top
 * limit, staying on screen wins over keeping GMaps' search pill free.
 */
fun cardPlacement(restingOffset: Offset, side: EdgeSide, bounds: BubbleBounds, isRtl: Boolean): CardPlacement {
    val d = bounds.density
    val widthDp = bounds.widthPx / d
    val heightDp = bounds.heightPx / d
    val marginDp = BubbleDefaults.EdgeMargin.value
    val compact = widthDp < BubbleDefaults.CompactWidth.value

    var cardW = min(BubbleDefaults.CardMaxWidth.value, widthDp - 2 * marginDp)
    if (compact) cardW = min(cardW, BubbleDefaults.CompactCardWidthFraction * widthDp)
    cardW = max(0f, cardW)

    val cardH = when {
        heightDp < BubbleDefaults.ShortHeight.value -> BubbleDefaults.ShortCardHeightFraction * heightDp
        compact -> min(BubbleDefaults.CardMaxHeight.value, BubbleDefaults.CompactCardHeightFraction * heightDp)
        else -> min(BubbleDefaults.CardMaxHeight.value, BubbleDefaults.LargeCardHeightFraction * heightDp)
    }

    val marginPx = marginDp * d
    val x = if (isLeft(side, isRtl)) marginPx else bounds.widthPx - marginPx - cardW * d
    val bottom = bounds.heightPx - marginPx
    val lowestTop = bottom - cardH * d
    val wanted = restingOffset.y - BubbleDefaults.CardAboveBubble.value * d
    val y = if (lowestTop < bounds.minY) max(0f, lowestTop) else wanted.coerceIn(bounds.minY, lowestTop)
    return CardPlacement(IntOffset(x.roundToInt(), y.roundToInt()), cardW.dp, cardH.dp)
}
