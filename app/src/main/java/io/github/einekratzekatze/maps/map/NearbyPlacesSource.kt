package io.github.einekratzekatze.maps.map

import dagger.hilt.android.scopes.ActivityRetainedScoped
import io.github.einekratzekatze.maps.data.db.ImportantLocation
import io.github.einekratzekatze.maps.data.db.NearbyPlaceRow
import io.github.einekratzekatze.maps.data.repo.PlacesQueries
import io.github.einekratzekatze.maps.di.RetainedScope
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.MapCamera
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Saved places in the current viewport. [rows] are nearest to [camera] first, at most [NearbyPlacesSource.LIMIT]. */
data class NearbyResult(val camera: MapCamera, val bounds: GeoBounds, val rows: List<NearbyPlaceRow>)

/**
 * The one shared "saved places in view" pipeline (floating-places-bubble.md §5.1), used by the
 * bubble and the map markers. Bounds are debounced so a settling map costs about one query.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@ActivityRetainedScoped
class NearbyPlacesSource @Inject constructor(
    mapState: MapStateRepository,
    places: PlacesQueries,
    @RetainedScope scope: CoroutineScope,
) {
    companion object {
        /** 500 shown at most, one more to know the result was cut (schema.md §2.2). */
        const val LIMIT = 501
        const val DEBOUNCE_MS = 250L
    }

    private val viewport: Flow<Pair<MapCamera, GeoBounds>?> =
        combine(mapState.urlState, mapState.viewportCssPx) { state, size ->
            val camera = state.camera ?: return@combine null
            if (size == null) return@combine null
            WebMercator.boundsOrNull(camera, size)?.let { camera to it }
        }.distinctUntilChanged().debounce(DEBOUNCE_MS)

    /** null = viewport unknown. */
    val inView: SharedFlow<NearbyResult?> = viewport.flatMapLatest { v ->
        if (v == null) flowOf(null)
        else places.observeInBounds(v.second, v.first.center, LIMIT).map { NearbyResult(v.first, v.second, it) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Important locations with coordinates inside the viewport (markers draw Home/Work). */
    val importantInView: SharedFlow<List<ImportantLocation>> = viewport.flatMapLatest { v ->
        if (v == null) flowOf(emptyList()) else places.observeImportantInBounds(v.second)
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
}
