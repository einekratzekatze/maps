package io.github.einekratzekatze.maps.ui.search

import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import io.github.einekratzekatze.maps.data.db.RecentSearch
import io.github.einekratzekatze.maps.data.repo.RecentSearches
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.map.MapCommand
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapTouch
import io.github.einekratzekatze.maps.map.url.MapUrls
import io.github.einekratzekatze.maps.search.AutocompleteClient
import io.github.einekratzekatze.maps.search.SectionKind
import io.github.einekratzekatze.maps.search.Suggestion
import io.github.einekratzekatze.maps.ui.bubble.FakePlacesQueries
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeRecents : RecentSearches {
    val rows = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recorded = mutableListOf<String>()
    override suspend fun record(query: String, keep: Int) { recorded += query }
    override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = rows.map { it.take(limit) }
    override suspend fun search(query: String, limit: Int) = rows.value.filter { it.queryNormalized.contains(query.lowercase()) }.take(limit)
    override suspend fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
}

class FakeAutocomplete : AutocompleteClient {
    override val available = MutableStateFlow(false)
    var result: Result<List<Suggestion.Prediction>> = Result.success(emptyList())
    val inputs = mutableListOf<String>()
    override suspend fun predictions(input: String, bias: LatLon?, radiusMeters: Double, sessionToken: String): Result<List<Suggestion.Prediction>> {
        inputs += input
        return result
    }
}

/** Robolectric for android.net.Uri in MapUrls. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mapState = MapStateRepository()
    private val commands = MapCommands()
    private val places = FakePlacesQueries()
    private val recents = FakeRecents()
    private val autocomplete = FakeAutocomplete()
    private val berlin = "https://www.google.com/maps/@52.52,13.405,14z"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(url: String = berlin): SearchViewModel {
        mapState.reportUrl(url)
        mapState.reportViewportSize(1080, 2200, 2.625f)
        val vm = SearchViewModel(mapState, commands, places, recents, autocomplete)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.sent(): List<MapCommand> = mutableListOf<MapCommand>().also { out ->
        backgroundScope.launch { commands.commands.collect { out += it } }
    }

    private fun recent(id: Long, q: String) = RecentSearch(id, q, q.lowercase(), id)

    @Test fun idleShowsThePagesDisplayText() = runTest(dispatcher) {
        val vm = vm("https://www.google.com/maps/search/pizza/@52.52,13.405,14z")
        assertEquals(SearchUiState(mode = MapMode.Search, query = "pizza"), vm.state.value)
    }

    @Test fun focusPublishesEditingAndShowsRecents() = runTest(dispatcher) {
        recents.rows.value = (1L..12L).map { recent(it, "q$it") }
        val vm = vm()
        vm.onFocusChanged(true)
        advanceUntilIdle()
        assertTrue(mapState.overlay.value.searchEditing)
        val section = vm.state.value.sections.single()
        assertEquals(SectionKind.Recent, section.kind)
        assertEquals(10, section.items.size)
    }

    @Test fun typingShowsSavedAndRecentMatchesAfterTheDebounce() = runTest(dispatcher) {
        places.rows.value = listOf(NearbyPlaceRow(1, "Café Einstein", "cafe einstein", 52.5, 13.4, 1, "Food", 0))
        recents.rows.value = listOf(recent(1, "cafe"), recent(2, "museum"))
        val vm = vm()
        vm.onFocusChanged(true)
        vm.onQueryChange("caf")
        advanceTimeBy(SearchViewModel.LOCAL_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(listOf(SectionKind.Saved, SectionKind.Recent), vm.state.value.sections.map { it.kind })
        assertEquals("caf", vm.state.value.query)
    }

    @Test fun localSearchIsDebounced() = runTest(dispatcher) {
        val vm = vm()
        vm.onFocusChanged(true)
        advanceUntilIdle()
        val before = places.searchCalls
        for (q in listOf("c", "ca", "caf", "cafe")) { vm.onQueryChange(q); advanceTimeBy(50) }
        advanceTimeBy(SearchViewModel.LOCAL_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(before + 1, places.searchCalls)
    }

    @Test fun googleOnlyWithKeyAndThreeCharacters() = runTest(dispatcher) {
        autocomplete.result = Result.success(listOf(Suggestion.Prediction("P", "Pizza Place", "Berlin")))
        val vm = vm()
        vm.onFocusChanged(true)
        vm.onQueryChange("pizza")
        advanceUntilIdle()
        assertTrue(autocomplete.inputs.isEmpty())
        vm.onQueryChange("pi")
        advanceUntilIdle()
        autocomplete.available.value = true
        advanceUntilIdle()
        assertTrue(autocomplete.inputs.isEmpty())
        vm.onQueryChange("piz")
        advanceUntilIdle()
        assertEquals(listOf("piz"), autocomplete.inputs)
        assertEquals(SectionKind.Google, vm.state.value.sections.last().kind)
    }

    @Test fun googleErrorKeepsLocalSuggestions() = runTest(dispatcher) {
        recents.rows.value = listOf(recent(1, "pizza"))
        autocomplete.available.value = true
        autocomplete.result = Result.failure(IOException("offline"))
        val vm = vm()
        vm.onFocusChanged(true)
        vm.onQueryChange("pizza")
        advanceUntilIdle()
        assertTrue(vm.state.value.autocompleteError)
        assertEquals(listOf(SectionKind.Recent), vm.state.value.sections.map { it.kind })
    }

    @Test fun submitSearchesNearTheMapAndRecords() = runTest(dispatcher) {
        val vm = vm()
        val sent = sent()
        vm.onFocusChanged(true)
        vm.onQueryChange("  pizza  ")
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(listOf("pizza"), recents.recorded)
        assertEquals(listOf(MapCommand.LoadUrl(MapUrls.search("pizza", mapState.urlState.value.camera))), sent)
        assertFalse(vm.state.value.editing)
        assertFalse(mapState.overlay.value.searchEditing)
    }

    @Test fun emptySubmitIsIgnored() = runTest(dispatcher) {
        val vm = vm()
        val sent = sent()
        vm.onFocusChanged(true)
        vm.onQueryChange("   ")
        vm.onSubmit()
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertTrue(vm.state.value.editing)
    }

    @Test fun suggestionsNavigate() = runTest(dispatcher) {
        val vm = vm()
        val sent = sent()
        vm.onFocusChanged(true)
        vm.onSuggestion(Suggestion.SavedPlace(1, "Café", null, 52.5, 13.4, 0))
        vm.onFocusChanged(true)
        vm.onSuggestion(Suggestion.Prediction("abc", "Pizza Place", null))
        vm.onFocusChanged(true)
        vm.onSuggestion(Suggestion.Recent(3, "museum"))
        advanceUntilIdle()
        assertEquals(
            listOf(
                MapCommand.LoadUrl(MapUrls.place(LatLon(52.5, 13.4), 17.0)),
                MapCommand.LoadUrl(MapUrls.prediction("Pizza Place", "abc")),
                MapCommand.LoadUrl(MapUrls.search("museum", mapState.urlState.value.camera)),
            ),
            sent,
        )
        assertEquals(listOf("Pizza Place", "museum"), recents.recorded)
    }

    @Test fun mapTapImeHideAndBackEndEditing() = runTest(dispatcher) {
        val vm = vm()
        vm.onFocusChanged(true)
        mapState.reportTouch(MapTouch.Tap)
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)

        vm.onFocusChanged(true)
        mapState.setImeVisible(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.editing)
        mapState.setImeVisible(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)

        vm.onFocusChanged(true)
        vm.onBack()
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)
    }

    @Test fun directionsModeEndsEditingAndBlocksFocus() = runTest(dispatcher) {
        val vm = vm()
        vm.onFocusChanged(true)
        mapState.reportUrl("https://www.google.com/maps/dir/A/B/@52.5,13.4,12z")
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)
        assertEquals(MapMode.Directions, vm.state.value.mode)
        vm.onFocusChanged(true)
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)
    }

    @Test fun deleteRecentAndMenu() = runTest(dispatcher) {
        recents.rows.value = listOf(recent(1, "a"), recent(2, "b"))
        val vm = vm()
        val sent = sent()
        vm.onFocusChanged(true)
        vm.onDeleteRecent(1)
        vm.onMenuClick()
        advanceUntilIdle()
        assertEquals(listOf<Suggestion>(Suggestion.Recent(2, "b")), vm.state.value.sections.single().items)
        assertEquals(listOf<MapCommand>(MapCommand.OpenGmapsMenu), sent)
    }
}
