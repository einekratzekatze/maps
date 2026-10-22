package io.github.einekratzekatze.maps.ui.bubble

/** Test tags of the bubble UI (floating-places-bubble.md §9.2). */
object BubbleTestTags {
    const val Bubble = "bubble"
    const val Badge = "bubble_badge"
    const val NearbyCard = "nearby_card"
    const val NearbyCardClose = "nearby_card_close"
    const val NearbyList = "nearby_list"
    const val EmptyState = "empty_state"
    const val PlaceCard = "place_card"
    const val PlaceCardBack = "place_card_back"
    fun placeRow(id: Long) = "place_row_$id"
    fun placeRowNav(id: Long) = "place_row_nav_$id"
    fun importantChip(id: Long) = "important_chip_$id"
}
