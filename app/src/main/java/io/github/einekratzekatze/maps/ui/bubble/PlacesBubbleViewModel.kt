package io.github.einekratzekatze.maps.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.einekratzekatze.maps.data.db.PlaceDetailRow
import io.github.einekratzekatze.maps.data.prefs.BubblePrefs
import io.github.einekratzekatze.maps.data.repo.PlacesQueries
import io.github.einekratzekatze.maps.di.DefaultDispatcher
import io.github.einekratzekatze.maps.domain.Distance
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.domain.dedupeNearby
import io.github.einekratzekatze.maps.map.MapCommand
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapTouch
import io.github.einekratzekatze.maps.map.NearbyPlacesSource
import io.github.einekratzekatze.maps.map.NearbyResult
import io.github.einekratzekatze.maps.map.url.MapUrls
import io.github.einekratzekatze.maps.navigation.NavTarget
import java.text.Collator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presentation state of the floating places bubble (floating-places-bubble.md §2, §5.6).
 * Map taps, the IME and overlay state come from [MapStateRepository], not from the composable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlacesBubbleViewModel @Inject constructor(
    private val places: PlacesQueries,
    nearby: NearbyPlacesSource,
    private val mapState: MapStateRepository,
    private val commands: MapCommands,
    private val prefs: BubblePrefs,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel(), PlacesBubbleActions {

    companion object {
        /** Rows shown in the card; the rest is summarised in the footer. */
        const val MAX_ROWS = 100
        /** More rows than this from the query means the result was cut. */
        const val TRUNCATED_ABOVE = 500
        const val PLACE_ZOOM = 17.0
        private val CARD_MODES = setOf(MapMode.Map, MapMode.Search, MapMode.Place)
    }

    private data class Presentation(val expanded: Boolean = false, val focus: CardFocus = CardFocus.Nearby)
    private data class Gates(val cardAllowed: Boolean, val enabled: Boolean, val searchHidden: Boolean)

    private val presentation = MutableStateFlow(Presentation())
    private val effectChannel = Channel<BubbleEffect>(Channel.BUFFERED)
    val effects: Flow<BubbleEffect> = effectChannel.receiveAsFlow()

    /** null until DataStore has loaded (§4.6): the overlay renders nothing until then. */
    val anchor: StateFlow<BubbleAnchor?> = prefs.anchor.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val cardAllowed: Flow<Boolean> = combine(mapState.overlay, mapState.urlState) { o, u ->
        !o.imeVisible && !o.searchEditing && !o.drivePromptVisible && u.mode in CARD_MODES
    }.distinctUntilChanged()

    private val gates: Flow<Gates> = combine(cardAllowed, prefs.enabled, mapState.overlay) { allowed, enabled, o ->
        Gates(allowed, enabled, o.gmapsSearchHidden)
    }

    private val content: Flow<Pair<NearbyContent, BadgeState>> =
        combine(nearby.inView, places.observeHasAnyPlaces()) { result, any -> buildContent(result, any) }
            .flowOn(default)
            .onStart { emit(NearbyContent.Loading to BadgeState.Hidden) }

    private val important: Flow<List<ImportantShortcut>> = places.observeImportant()
        .map { rows -> rows.map { ImportantShortcut(it.id, it.kind, it.label, LatLon.ofOrNull(it.lat, it.lon)) } }
        .onStart { emit(emptyList()) }

    private val focusedPlace: Flow<CardFocus> = presentation.map { it.focus }.distinctUntilChanged()

    private val placeCard: Flow<PlaceCardState?> = focusedPlace.flatMapLatest { focus ->
        if (focus !is CardFocus.Place) flowOf(null)
        else places.observeDetail(focus.placeId).map { row -> row?.toCard() }
    }.onStart { emit(null) }

    val uiState: StateFlow<BubbleUiState> =
        combine(presentation, gates, content, important, placeCard) { p, g, c, imp, card ->
            BubbleUiState(
                bubbleVisible = g.enabled && g.cardAllowed,
                cardAllowed = g.cardAllowed,
                searchHidden = g.searchHidden,
                expanded = p.expanded,
                focus = p.focus,
                badge = c.second,
                important = imp,
                content = c.first,
                placeCard = card?.takeIf { (p.focus as? CardFocus.Place)?.placeId == it.id },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BubbleUiState())

    init {
        // S7: a single tap on the map closes the card.
        viewModelScope.launch { mapState.touches.collect { if (it == MapTouch.Tap) collapse() } }
        // S10: whatever hides the card collapses it.
        viewModelScope.launch { cardAllowed.collect { if (!it) collapse() } }
        // S15: switching the bubble off collapses (a marker tap can still open the card afterwards).
        viewModelScope.launch {
            var last: Boolean? = null
            prefs.enabled.collect { now ->
                if (last == true && !now) collapse()
                last = now
            }
        }
        // S9d: the focused place was deleted (or its external list refreshed away).
        viewModelScope.launch {
            focusedPlace.flatMapLatest { focus ->
                if (focus !is CardFocus.Place) emptyFlow()
                else places.observeDetail(focus.placeId).filter { it == null }.map { focus }
            }.collect { gone -> presentation.update { if (it.focus == gone) it.copy(focus = CardFocus.Nearby) else it } }
        }
    }

    fun onAnchorSettled(anchor: BubbleAnchor) {
        viewModelScope.launch { prefs.setAnchor(anchor) }
    }

    override fun onBubbleTap() = presentation.update { Presentation(expanded = true, focus = CardFocus.Nearby) }

    override fun onClose() = collapse()

    override fun onPlaceClick(id: Long) {
        val target = findPlace(id) ?: return
        commands.send(MapCommand.NavigateInPage(MapUrls.place(target.first, PLACE_ZOOM)))
        collapse()
    }

    override fun onNavigateClick(id: Long) {
        val target = findPlace(id) ?: return
        commands.send(MapCommand.Navigate(NavTarget(target.first, target.second)))
    }

    override fun onImportantClick(id: Long) {
        val shortcut = uiState.value.important.firstOrNull { it.id == id } ?: return
        val latLon = shortcut.latLon
        if (latLon == null) {
            effectChannel.trySend(BubbleEffect.OpenImportantEditor(id))
            return
        }
        commands.send(MapCommand.NavigateInPage(MapUrls.place(latLon, PLACE_ZOOM)))
        collapse()
    }

    override fun onMarkerTapped(id: Long) = presentation.update { Presentation(expanded = true, focus = CardFocus.Place(id)) }

    override fun onShowAllNearby() = presentation.update { it.copy(focus = CardFocus.Nearby) }

    private fun collapse() = presentation.update { if (it.expanded) it.copy(expanded = false) else it }

    /** Coordinates and name of a place shown in the list or the place card. */
    private fun findPlace(id: Long): Pair<LatLon, String>? {
        val state = uiState.value
        state.placeCard?.takeIf { it.id == id }?.let { return LatLon(it.lat, it.lon) to it.name }
        val items = (state.content as? NearbyContent.Places)?.items ?: return null
        return items.firstOrNull { it.id == id }?.let { LatLon(it.lat, it.lon) to it.name }
    }

    private fun PlaceDetailRow.toCard(): PlaceCardState {
        val centre = mapState.urlState.value.camera?.center
        return PlaceCardState(
            id = id, name = name, lat = lat, lon = lon, listName = listName, listColor = listColor,
            address = address, notes = notes,
            distanceMeters = centre?.let { Distance.meters(it, LatLon(lat, lon)) },
        )
    }

    private fun buildContent(result: NearbyResult?, hasAnyPlaces: Boolean): Pair<NearbyContent, BadgeState> {
        if (!hasAnyPlaces) return NearbyContent.NoSavedPlaces to BadgeState.Hidden
        if (result == null) return NearbyContent.ViewportUnknown to BadgeState.Hidden
        val groups = dedupeNearby(result.rows)
        if (groups.isEmpty()) return NearbyContent.NoneInView to BadgeState.Hidden
        val truncated = result.rows.size > TRUNCATED_ABOVE
        val centre = result.camera.center
        val collator = Collator.getInstance()
        val items = groups.map { g ->
            val row = g.nearest
            NearbyPlace(
                id = row.id, name = row.name, lat = row.lat, lon = row.lon,
                listName = g.lists.first().name, extraListCount = g.lists.size - 1, listColor = g.lists.first().color,
                distanceMeters = Distance.meters(centre, LatLon(row.lat, row.lon)),
            )
        }.sortedWith(compareBy<NearbyPlace> { it.distanceMeters }.thenComparator { a, b -> collator.compare(a.name, b.name) })
            .take(MAX_ROWS)
        return NearbyContent.Places(items, groups.size, truncated) to BadgeState.Count(groups.size, truncated)
    }
}
