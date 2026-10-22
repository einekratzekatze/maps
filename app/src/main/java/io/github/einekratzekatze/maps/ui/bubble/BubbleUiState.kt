package io.github.einekratzekatze.maps.ui.bubble

import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.domain.LatLon

/** Everything the bubble and its card render (floating-places-bubble.md §5.6). */
data class BubbleUiState(
    /** enabled && cardAllowed (§2). */
    val bubbleVisible: Boolean = false,
    val cardAllowed: Boolean = false,
    /** GMaps' search pill hidden by custom search; lowers the top limit (§4.1). */
    val searchHidden: Boolean = false,
    val expanded: Boolean = false,
    val focus: CardFocus = CardFocus.Nearby,
    val badge: BadgeState = BadgeState.Hidden,
    val important: List<ImportantShortcut> = emptyList(),
    val content: NearbyContent = NearbyContent.Loading,
    /** Non-null iff [focus] is a [CardFocus.Place] whose detail has loaded (§5.8). */
    val placeCard: PlaceCardState? = null,
)

sealed interface CardFocus {
    data object Nearby : CardFocus
    data class Place(val placeId: Long) : CardFocus
}

sealed interface BadgeState {
    data object Hidden : BadgeState
    data class Count(val n: Int, val truncated: Boolean) : BadgeState {
        val label: String get() = if (truncated || n > 99) "99+" else n.toString()
    }
}

sealed interface NearbyContent {
    /** Before the first database emission. */
    data object Loading : NearbyContent
    data object ViewportUnknown : NearbyContent
    /** The database has no places at all. */
    data object NoSavedPlaces : NearbyContent
    /** Places exist, none inside the viewport. */
    data object NoneInView : NearbyContent
    /** [items] are the nearest [PlacesBubbleViewModel.MAX_ROWS]; [totalInView] counts distinct places. */
    data class Places(val items: List<NearbyPlace>, val totalInView: Int, val truncated: Boolean) : NearbyContent
}

data class NearbyPlace(
    val id: Long, val name: String, val lat: Double, val lon: Double,
    /** First list; [extraListCount] more lists give "Restaurants +1". */
    val listName: String, val extraListCount: Int,
    /** ARGB */
    val listColor: Int,
    val distanceMeters: Double,
)

data class ImportantShortcut(
    /** ImportantLocation.id (HOME = 1, WORK = 2). */
    val id: Long,
    val kind: ImportantKind,
    /** The custom label; null for HOME/WORK, whose labels come from resources in the UI. */
    val label: String?,
    /** null = not set. */
    val latLon: LatLon?,
) {
    val isSet: Boolean get() = latLon != null
}

data class PlaceCardState(
    val id: Long, val name: String, val lat: Double, val lon: Double,
    val listName: String, val listColor: Int,
    val address: String?, val notes: String?,
    /** From the map centre; null while the viewport is unknown. */
    val distanceMeters: Double?,
)

interface PlacesBubbleActions {
    fun onBubbleTap()
    fun onClose()
    /** Move the map there and collapse. */
    fun onPlaceClick(id: Long)
    /** Hand the place to a navigation app. */
    fun onNavigateClick(id: Long)
    fun onImportantClick(id: Long)
    /** Phase 6.3 marker tap. */
    fun onMarkerTapped(id: Long)
    /** Place card back-chevron (§5.8). */
    fun onShowAllNearby()
}

/** UI-only one-shot effects; anything that drives the WebView goes through MapCommands. */
sealed interface BubbleEffect {
    data class OpenImportantEditor(val id: Long) : BubbleEffect
}
