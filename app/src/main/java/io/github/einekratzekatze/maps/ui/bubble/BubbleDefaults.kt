package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.ui.unit.dp

/** Every tunable number of the bubble in one place (floating-places-bubble.md §3.3, §4). */
object BubbleDefaults {
    val Size = 56.dp
    /** Gap between the bubble/card and the side edge (after insets). */
    val EdgeMargin = 12.dp
    /** Top limit while GMaps' own search pill is visible. */
    val TopWithGmapsSearch = 72.dp
    /** Top limit once custom search hides GMaps' pill. */
    val TopWithoutGmapsSearch = 12.dp
    /** Kept free at the bottom for GMaps' "my location" button and collapsed sheet. */
    val BottomReserve = 136.dp

    /** Above this horizontal release speed (px/s) the fling direction picks the side. */
    const val FlingSideVelocity = 1000f
    /** Fling projection factors (s): projected = position + velocity * factor. */
    const val ProjectionX = 0.15f
    const val ProjectionY = 0.10f

    val CardMaxWidth = 360.dp
    val CardMaxHeight = 480.dp
    /** Windows narrower than this are "compact phones": the card leaves a strip of map beside it. */
    val CompactWidth = 600.dp
    /** Windows shorter than this are "short" (landscape phone, split screen). */
    val ShortHeight = 480.dp
    const val CompactCardWidthFraction = 0.85f
    const val CompactCardHeightFraction = 0.5f
    const val LargeCardHeightFraction = 0.6f
    const val ShortCardHeightFraction = 0.9f
    /** The card's top sits this far above the bubble's top, then is clamped. */
    val CardAboveBubble = 24.dp
}
