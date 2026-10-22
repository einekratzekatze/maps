package io.github.einekratzekatze.maps.ui.bubble

import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.data.db.ImportantLocation
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import io.github.einekratzekatze.maps.data.db.PlaceDetailRow
import io.github.einekratzekatze.maps.data.prefs.BubblePrefs
import io.github.einekratzekatze.maps.data.repo.PlacesQueries
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.map.MapCommand
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapTouch
import io.github.einekratzekatze.maps.map.NearbyPlacesSource
import io.github.einekratzekatze.maps.map.url.MapUrls
import io.github.einekratzekatze.maps.navigation.NavTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakePlacesQueries : PlacesQueries {
    val rows = MutableStateFlow<List<NearbyPlaceRow>>(emptyList())
    val hasAny = MutableStateFlow(true)
    val important = MutableStateFlow<List<ImportantLocation>>(emptyList())
    val details = mutableMapOf<Long, MutableStateFlow<PlaceDetailRow?>>()
    var inBoundsCalls = 0

    override fun observeInBounds(bounds: GeoBounds, center: LatLon, limit: Int): Flow<List<NearbyPlaceRow>> {
        inBoundsCalls++
        return rows.map { list -> list.filter { bounds.contains(LatLon(it.lat, it.lon)) }.take(limit) }
    }
    override fun observeHasAnyPlaces() = hasAny
    override fun observeDetail(id: Long) = details.getOrPut(id) { MutableStateFlow(null) }
    override fun observeImportant() = important
    override fun observeImportantInBounds(bounds: GeoBounds) =
        important.map { l -> l.filter { LatLon.ofOrNull(it.lat, it.lon)?.let(bounds::contains) == true } }
}

class FakeBubblePrefs : BubblePrefs {
    override val anchor = MutableSharedFlow<BubbleAnchor>(replay = 1)
    override val enabled = MutableStateFlow(true)
    override suspend fun setAnchor(anchor: BubbleAnchor) { this.anchor.emit(anchor) }
    override suspend fun setEnabled(enabled: Boolean) { this.enabled.value = enabled }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesBubbleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val places = FakePlacesQueries()
    private val prefs = FakeBubblePrefs()
    private val mapState = MapStateRepository()
    private val commands = MapCommands()
    private val berlin = "https://www.google.com/maps/@52.52,13.405,14z"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(id: Long, name: String, lat: Double, lon: Double, list: Long = 1) =
        NearbyPlaceRow(id, name, name.lowercase(), lat, lon, list, "List$list", 0xFF00FF00.toInt())

    private fun detail(id: Long, name: String) =
        PlaceDetailRow(id, name, 52.52, 13.41, "Street 1", "notes", 1, "List1", 0, ListType.NORMAL)

    /** Builds the VM, subscribes to uiState and lets the debounce pass. */
    private fun TestScope.vm(url: String? = berlin): PlacesBubbleViewModel {
        mapState.reportUrl(url)
        mapState.reportViewportSize(1080, 2200, 2.625f)
        val source = NearbyPlacesSource(mapState, places, backgroundScope)
        val vm = PlacesBubbleViewModel(places, source, mapState, commands, prefs, dispatcher)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceTimeBy(NearbyPlacesSource.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.sentCommands(): List<MapCommand> {
        val out = mutableListOf<MapCommand>()
        backgroundScope.launch { commands.commands.collect { out += it } }
        return out
    }

    @Test fun badgeLabels() {
        assertEquals("1", BadgeState.Count(1, false).label)
        assertEquals("99", BadgeState.Count(99, false).label)
        assertEquals("99+", BadgeState.Count(100, false).label)
        assertEquals("99+", BadgeState.Count(3, truncated = true).label)
    }

    @Test fun placesInViewAreCountedNearestFirst() = runTest(dispatcher) {
        places.rows.value = listOf(
            row(1, "Far", 52.53, 13.405), row(2, "Near", 52.5201, 13.405),
            row(3, "Near", 52.5201, 13.405, list = 2), row(4, "Outside", 10.0, 10.0),
        )
        val s = vm().uiState.value
        assertEquals(BadgeState.Count(2, truncated = false), s.badge)
        val content = s.content as NearbyContent.Places
        assertEquals(listOf(2L, 1L), content.items.map { it.id })
        assertEquals(1, content.items[0].extraListCount)
        assertEquals(2, content.totalInView)
    }

    @Test fun equalDistanceSortsByName() = runTest(dispatcher) {
        places.rows.value = listOf(row(1, "b", 52.53, 13.405), row(2, "a", 52.53, 13.405, list = 2).copy(nameNormalized = "x"))
        val items = (vm().uiState.value.content as NearbyContent.Places).items
        assertEquals(listOf("a", "b"), items.map { it.name })
    }

    @Test fun truncatedResultShowsAtMostMaxRows() = runTest(dispatcher) {
        places.rows.value = (1..501L).map { row(it, "P$it", 52.52 + it * 1e-5, 13.405) }
        val s = vm().uiState.value
        assertEquals("99+", (s.badge as BadgeState.Count).label)
        val c = s.content as NearbyContent.Places
        assertTrue(c.truncated)
        assertEquals(PlacesBubbleViewModel.MAX_ROWS, c.items.size)
        assertEquals(501, c.totalInView)
    }

    @Test fun emptyStates() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(NearbyContent.NoneInView, vm.uiState.value.content)
        assertEquals(BadgeState.Hidden, vm.uiState.value.badge)
        places.hasAny.value = false
        advanceUntilIdle()
        assertEquals(NearbyContent.NoSavedPlaces, vm.uiState.value.content)
    }

    @Test fun unknownViewport() = runTest(dispatcher) {
        places.rows.value = listOf(row(1, "A", 52.52, 13.405))
        val vm = vm(url = "https://www.google.com/maps")
        assertEquals(NearbyContent.ViewportUnknown, vm.uiState.value.content)
        assertEquals(BadgeState.Hidden, vm.uiState.value.badge)
        assertTrue(vm.uiState.value.bubbleVisible) // Map mode, just no camera
    }

    @Test fun boundsAreDebounced() = runTest(dispatcher) {
        vm()
        val before = places.inBoundsCalls
        repeat(5) { i ->
            mapState.reportUrl("https://www.google.com/maps/@52.5$i,13.405,14z")
            advanceTimeBy(100)
        }
        assertEquals(before, places.inBoundsCalls)
        advanceTimeBy(NearbyPlacesSource.DEBOUNCE_MS)
        advanceUntilIdle()
        assertEquals(before + 1, places.inBoundsCalls)
    }

    @Test fun tapExpandsAndMapTapCollapses() = runTest(dispatcher) {
        val vm = vm()
        vm.onBubbleTap()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.expanded)
        mapState.reportTouch(MapTouch.GestureStart)
        mapState.reportTouch(MapTouch.GestureEnd)
        advanceUntilIdle()
        assertTrue("S8: pans keep the card", vm.uiState.value.expanded)
        mapState.reportTouch(MapTouch.Tap)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.expanded)
    }

    @Test fun closeCollapses() = runTest(dispatcher) {
        val vm = vm()
        vm.onBubbleTap()
        vm.onClose()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.expanded)
    }

    @Test fun imeOrDirectionsCollapseAndHide() = runTest(dispatcher) {
        val vm = vm()
        vm.onBubbleTap()
        mapState.setImeVisible(true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.expanded)
        assertFalse(vm.uiState.value.bubbleVisible)
        mapState.setImeVisible(false)
        mapState.reportUrl("https://www.google.com/maps/dir/A/B/@52.5,13.4,12z")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.cardAllowed)
        mapState.reportUrl(berlin)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.bubbleVisible)
    }

    @Test fun drivePromptAndSearchEditingHideTheBubble() = runTest(dispatcher) {
        val vm = vm()
        mapState.setDrivePromptVisible(true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.bubbleVisible)
        mapState.setDrivePromptVisible(false)
        mapState.setSearchEditing(true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.bubbleVisible)
    }

    @Test fun switchingOffCollapsesButMarkerTapStillOpens() = runTest(dispatcher) {
        places.details[9] = MutableStateFlow(detail(9, "Nine"))
        val vm = vm()
        vm.onBubbleTap()
        advanceUntilIdle()
        prefs.enabled.value = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.expanded)
        assertFalse(vm.uiState.value.bubbleVisible)
        assertTrue(vm.uiState.value.cardAllowed)
        vm.onMarkerTapped(9)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.expanded)
        assertEquals(CardFocus.Place(9), vm.uiState.value.focus)
    }

    @Test fun placeFocusTransitions() = runTest(dispatcher) {
        places.details[1] = MutableStateFlow(detail(1, "One"))
        places.details[2] = MutableStateFlow(detail(2, "Two"))
        val vm = vm()
        vm.onMarkerTapped(1)
        advanceUntilIdle()
        assertEquals("One", vm.uiState.value.placeCard?.name)
        assertTrue(vm.uiState.value.placeCard!!.distanceMeters!! > 0)
        vm.onMarkerTapped(2) // S9b
        advanceUntilIdle()
        assertEquals("Two", vm.uiState.value.placeCard?.name)
        vm.onShowAllNearby() // S9c
        advanceUntilIdle()
        assertEquals(CardFocus.Nearby, vm.uiState.value.focus)
        assertNull(vm.uiState.value.placeCard)
        vm.onMarkerTapped(1)
        advanceUntilIdle()
        places.details.getValue(1).value = null // S9d
        advanceUntilIdle()
        assertEquals(CardFocus.Nearby, vm.uiState.value.focus)
        assertTrue(vm.uiState.value.expanded)
    }

    @Test fun rowTapMovesTheMapAndCollapses() = runTest(dispatcher) {
        places.rows.value = listOf(row(1, "Cafe", 52.5201, 13.405))
        val vm = vm()
        val sent = sentCommands()
        vm.onBubbleTap()
        vm.onPlaceClick(1)
        advanceUntilIdle()
        assertEquals(listOf(MapCommand.NavigateInPage(MapUrls.place(LatLon(52.5201, 13.405), 17.0))), sent)
        assertFalse(vm.uiState.value.expanded)
    }

    @Test fun navigateHandsOffWithoutCollapsing() = runTest(dispatcher) {
        places.rows.value = listOf(row(1, "Cafe", 52.5201, 13.405))
        val vm = vm()
        val sent = sentCommands()
        vm.onBubbleTap()
        vm.onNavigateClick(1)
        vm.onNavigateClick(99) // unknown id: ignored
        advanceUntilIdle()
        assertEquals(listOf(MapCommand.Navigate(NavTarget(LatLon(52.5201, 13.405), "Cafe"))), sent)
        assertTrue(vm.uiState.value.expanded)
    }

    @Test fun importantShortcuts() = runTest(dispatcher) {
        places.important.value = listOf(
            ImportantLocation(1, ImportantKind.HOME, null, 52.0, 13.0, null, 0, 0),
            ImportantLocation(2, ImportantKind.WORK, null, null, null, null, 1, 0),
        )
        val vm = vm()
        val sent = sentCommands()
        val effects = mutableListOf<BubbleEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        assertEquals(listOf(true, false), vm.uiState.value.important.map { it.isSet })
        vm.onBubbleTap()
        vm.onImportantClick(2)
        advanceUntilIdle()
        assertEquals(listOf(BubbleEffect.OpenImportantEditor(2)), effects)
        assertTrue(vm.uiState.value.expanded)
        vm.onImportantClick(1)
        advanceUntilIdle()
        assertEquals(listOf(MapCommand.NavigateInPage(MapUrls.place(LatLon(52.0, 13.0), 17.0))), sent)
        assertFalse(vm.uiState.value.expanded)
    }

    @Test fun anchorIsNullUntilPrefsLoad() = runTest(dispatcher) {
        val vm = vm()
        assertNull(vm.anchor.value)
        vm.onAnchorSettled(BubbleAnchor(EdgeSide.Start, 0.3f))
        advanceUntilIdle()
        assertEquals(BubbleAnchor(EdgeSide.Start, 0.3f), vm.anchor.value)
        assertEquals(BubbleAnchor(EdgeSide.Start, 0.3f), prefs.anchor.first())
    }
}
