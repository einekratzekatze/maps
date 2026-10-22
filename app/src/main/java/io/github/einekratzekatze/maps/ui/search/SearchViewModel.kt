package io.github.einekratzekatze.maps.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.einekratzekatze.maps.data.repo.PlacesQueries
import io.github.einekratzekatze.maps.data.repo.RecentSearches
import io.github.einekratzekatze.maps.domain.Distance
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.map.MapCommand
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapTouch
import io.github.einekratzekatze.maps.map.WebMercator
import io.github.einekratzekatze.maps.map.url.MapUrls
import io.github.einekratzekatze.maps.search.AutocompleteClient
import io.github.einekratzekatze.maps.search.SectionKind
import io.github.einekratzekatze.maps.search.Suggestion
import io.github.einekratzekatze.maps.search.SuggestionSection
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val mode: MapMode = MapMode.NonMap,
    val editing: Boolean = false,
    /** What the field shows: the typed query while editing, else the page's search query or place name. */
    val query: String = "",
    val sections: List<SuggestionSection> = emptyList(),
    val autocompleteAvailable: Boolean = false,
    val autocompleteError: Boolean = false,
)

/** Our search field (custom-search-field.md §2.2, §3). WebView actions go through [MapCommands]. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mapState: MapStateRepository,
    private val commands: MapCommands,
    private val places: PlacesQueries,
    private val recents: RecentSearches,
    private val autocomplete: AutocompleteClient,
) : ViewModel() {

    companion object {
        const val LOCAL_DEBOUNCE_MS = 100L
        const val GOOGLE_DEBOUNCE_MS = 350L
        const val GOOGLE_MIN_CHARS = 3
        const val SECTION_LIMIT = 5
        const val RECENT_EMPTY_LIMIT = 10
        const val PLACE_ZOOM = 17.0
        private val FIELD_MODES = setOf(MapMode.Map, MapMode.Search, MapMode.Place)
    }

    private val editing = MutableStateFlow(false)
    private val typed = MutableStateFlow("")
    private var sessionToken = UUID.randomUUID().toString()

    private data class Google(val items: List<Suggestion.Prediction>, val error: Boolean)

    private val local: Flow<List<SuggestionSection>> = combine(editing, typed.debounce(LOCAL_DEBOUNCE_MS)) { e, q -> e to q }
        .distinctUntilChanged()
        .flatMapLatest { (e, q) ->
            when {
                !e -> flowOf(emptyList())
                q.isBlank() -> recents.observeRecent(RECENT_EMPTY_LIMIT).map { rows ->
                    listOfNotNull(section(SectionKind.Recent, rows.map { Suggestion.Recent(it.id, it.query) }))
                }
                else -> flow {
                    val centre = mapState.urlState.value.camera?.center
                    val saved = places.search(q, centre, SECTION_LIMIT).map {
                        Suggestion.SavedPlace(it.id, it.name, null, it.lat, it.lon, it.listColor)
                    }
                    val recent = recents.search(q, SECTION_LIMIT).map { Suggestion.Recent(it.id, it.query) }
                    emit(listOfNotNull(section(SectionKind.Saved, saved), section(SectionKind.Recent, recent)))
                }
            }
        }

    private val google: Flow<Google> = combine(editing, typed.debounce(GOOGLE_DEBOUNCE_MS), autocomplete.available) { e, q, a -> Triple(e, q.trim(), a) }
        .distinctUntilChanged()
        .mapLatest { (e, q, available) ->
            if (!e || !available || q.length < GOOGLE_MIN_CHARS) return@mapLatest Google(emptyList(), false)
            val (centre, radius) = bias()
            autocomplete.predictions(q, centre, radius, sessionToken).fold(
                onSuccess = { Google(it, false) },
                onFailure = { Google(emptyList(), true) },
            )
        }
        .onStart { emit(Google(emptyList(), false)) }

    val state: StateFlow<SearchUiState> = combine(
        mapState.urlState, editing, typed, combine(local.onStart { emit(emptyList()) }, google) { l, g -> l to g }, autocomplete.available,
    ) { url, e, q, (l, g), available ->
        SearchUiState(
            mode = url.mode,
            editing = e,
            query = if (e) q else url.displayText.orEmpty(),
            sections = if (e) l + listOfNotNull(section(SectionKind.Google, g.items)) else emptyList(),
            autocompleteAvailable = available,
            autocompleteError = e && g.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        // A single tap on the map ends editing (§7).
        viewModelScope.launch { mapState.touches.collect { if (it == MapTouch.Tap) stopEditing() } }
        // The keyboard closing (first Back is not dispatched) ends editing (§7).
        viewModelScope.launch {
            var wasVisible = false
            mapState.overlay.map { it.imeVisible }.distinctUntilChanged().collect { visible ->
                if (wasVisible && !visible) stopEditing()
                wasVisible = visible
            }
        }
        // Directions, Street View and non-map pages have no field (§2.2).
        viewModelScope.launch { mapState.urlState.collect { if (it.mode !in FIELD_MODES) stopEditing() } }
    }

    fun onFocusChanged(focused: Boolean) {
        if (focused == editing.value) return
        if (focused) {
            if (mapState.urlState.value.mode !in FIELD_MODES) return
            sessionToken = UUID.randomUUID().toString()
            typed.value = mapState.urlState.value.displayText.orEmpty()
            setEditing(true)
        } else {
            stopEditing()
        }
    }

    fun onQueryChange(q: String) {
        if (editing.value) typed.value = q
    }

    fun onSubmit() = submit(typed.value)

    fun onSuggestion(s: Suggestion) {
        when (s) {
            is Suggestion.SavedPlace -> {
                commands.send(MapCommand.LoadUrl(MapUrls.place(LatLon(s.lat, s.lon), PLACE_ZOOM)))
                stopEditing()
            }
            is Suggestion.Recent -> submit(s.query)
            is Suggestion.Prediction -> {
                viewModelScope.launch { recents.record(s.primary) }
                commands.send(MapCommand.LoadUrl(MapUrls.prediction(s.primary, s.placeId)))
                stopEditing()
            }
        }
    }

    fun onDeleteRecent(id: Long) {
        viewModelScope.launch { recents.delete(id) }
    }

    fun onMenuClick() {
        commands.send(MapCommand.OpenGmapsMenu)
    }

    /** MapBackHandler priority 1 (compose-host.md §7.4). */
    fun onBack() = stopEditing()

    private fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { recents.record(q) }
        commands.send(MapCommand.LoadUrl(MapUrls.search(q, mapState.urlState.value.camera)))
        stopEditing()
    }

    private fun stopEditing() {
        if (!editing.value) return
        setEditing(false)
    }

    private fun setEditing(value: Boolean) {
        editing.value = value
        mapState.setSearchEditing(value)
    }

    /** Map centre and half the viewport diagonal (metres), capped by the API. */
    private fun bias(): Pair<LatLon?, Double> {
        val camera = mapState.urlState.value.camera ?: return null to 0.0
        val size = mapState.viewportCssPx.value ?: return camera.center to 0.0
        val b = WebMercator.boundsOrNull(camera, size) ?: return camera.center to 0.0
        return camera.center to Distance.meters(camera.center, LatLon(b.north, b.east))
    }

    private fun section(kind: SectionKind, items: List<Suggestion>) = items.takeIf { it.isNotEmpty() }?.let { SuggestionSection(kind, it) }
}
