package io.github.einekratzekatze.maps.map

import androidx.compose.ui.unit.IntSize
import dagger.hilt.android.scopes.ActivityRetainedScoped
import io.github.einekratzekatze.maps.domain.GeoBounds
import io.github.einekratzekatze.maps.domain.MapUrlState
import io.github.einekratzekatze.maps.map.url.MapUrlParser
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** One page-to-app event from the GmwBridge listener (§4.3). [json] is the whole message. */
data class PageMessage(val type: String, val json: String)

data class OverlayState(
    val imeVisible: Boolean = false,
    val searchEditing: Boolean = false,
    val drivePromptVisible: Boolean = false,
    val gmapsSearchHidden: Boolean = false,
)

/**
 * The one way state gets out of the WebView (compose-host.md §5). Survives configuration changes.
 * [urlState] is derived synchronously in [reportUrl] rather than with `stateIn`, so readers never
 * see a URL and a stale parse at the same time.
 */
@ActivityRetainedScoped
class MapStateRepository @Inject constructor() {
    private val _url = MutableStateFlow<String?>(null)
    private val _urlState = MutableStateFlow(MapUrlState.Unknown)
    private val _viewport = MutableStateFlow<IntSize?>(null)
    private val _touches = MutableSharedFlow<MapTouch>(extraBufferCapacity = 16)
    private val _pageMessages = MutableSharedFlow<PageMessage>(extraBufferCapacity = 16)
    private val _overlay = MutableStateFlow(OverlayState())

    /** null while a full page load is starting and before the first page. */
    val url: StateFlow<String?> = _url.asStateFlow()
    val urlState: StateFlow<MapUrlState> = _urlState.asStateFlow()
    val viewportCssPx: StateFlow<IntSize?> = _viewport.asStateFlow()
    val touches: SharedFlow<MapTouch> = _touches.asSharedFlow()
    val pageMessages: SharedFlow<PageMessage> = _pageMessages.asSharedFlow()
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    /** null when the camera or the viewport is unknown, or in Street View. */
    val bounds: Flow<GeoBounds?> = combine(_urlState, _viewport) { state, size ->
        val camera = state.camera
        if (camera == null || size == null) null else WebMercator.boundsOrNull(camera, size)
    }.distinctUntilChanged()

    fun reportUrl(url: String?) {
        if (url == _url.value) return
        _urlState.value = MapUrlParser.parse(url)
        _url.value = url
    }

    /** Ignored while the IME is visible: the keyboard shrinks the WebView, not the map (REVIEW N-6). */
    fun reportViewportSize(widthPx: Int, heightPx: Int, density: Float) {
        if (_overlay.value.imeVisible) return
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return
        _viewport.value = IntSize((widthPx / density).roundToInt(), (heightPx / density).roundToInt())
    }

    fun reportTouch(t: MapTouch) {
        _touches.tryEmit(t)
    }

    fun reportPageMessage(m: PageMessage) {
        _pageMessages.tryEmit(m)
    }

    fun setImeVisible(v: Boolean) = _overlay.update { it.copy(imeVisible = v) }
    fun setSearchEditing(v: Boolean) = _overlay.update { it.copy(searchEditing = v) }
    fun setDrivePromptVisible(v: Boolean) = _overlay.update { it.copy(drivePromptVisible = v) }
    fun setGmapsSearchHidden(v: Boolean) = _overlay.update { it.copy(gmapsSearchHidden = v) }
}
