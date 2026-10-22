package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.ui.theme.AppTheme

// Previews P1-P14 of floating-places-bubble.md §9.3, for visual review in Android Studio.

private object NoActions : PlacesBubbleActions {
    override fun onBubbleTap() = Unit
    override fun onClose() = Unit
    override fun onPlaceClick(id: Long) = Unit
    override fun onNavigateClick(id: Long) = Unit
    override fun onImportantClick(id: Long) = Unit
    override fun onMarkerTapped(id: Long) = Unit
    override fun onShowAllNearby() = Unit
}

private val important = listOf(
    ImportantShortcut(1, ImportantKind.HOME, null, LatLon(52.5, 13.4)),
    ImportantShortcut(2, ImportantKind.WORK, null, null),
)

private fun places(n: Int, longNames: Boolean = false) = (1..n).map {
    NearbyPlace(
        id = it.toLong(),
        name = if (longNames) "Restaurant with an unusually long name number $it am Alexanderplatz" else "Place $it",
        lat = 52.5, lon = 13.4, listName = if (it % 2 == 0) "Restaurants" else "Museums",
        extraListCount = if (it == 1) 1 else 0,
        listColor = if (it % 2 == 0) 0xFFE53935.toInt() else 0xFF1E88E5.toInt(),
        distanceMeters = it * 130.0,
    )
}

private val base = BubbleUiState(bubbleVisible = true, cardAllowed = true, important = important, content = NearbyContent.NoneInView)

class BubbleUiStateProvider : PreviewParameterProvider<Pair<String, BubbleUiState>> {
    override val values = sequenceOf(
        "P1 collapsed" to base,
        "P2 badge 7" to base.copy(badge = BadgeState.Count(7, false)),
        "P4 expanded 8 places" to base.copy(expanded = true, content = NearbyContent.Places(places(8), 8, false)),
        "P5 no saved places" to base.copy(expanded = true, content = NearbyContent.NoSavedPlaces),
        "P6 none in view" to base.copy(expanded = true, content = NearbyContent.NoneInView),
        "P7 viewport unknown" to base.copy(expanded = true, content = NearbyContent.ViewportUnknown),
        "P8 long names" to base.copy(expanded = true, content = NearbyContent.Places(places(5, longNames = true), 5, false)),
        "P13 truncated" to base.copy(expanded = true, content = NearbyContent.Places(places(100), 501, true)),
        "P14 place card" to base.copy(
            expanded = true, focus = CardFocus.Place(1),
            placeCard = PlaceCardState(1, "Café Einstein", 52.5, 13.4, "Restaurants", 0xFFE53935.toInt(), null,
                "Notes line\n".repeat(10).trim(), 350.0),
        ),
    )
}

@Composable
private fun Frame(state: BubbleUiState, anchor: BubbleAnchor = BubbleAnchor.Default) {
    AppTheme {
        Box(Modifier.size(360.dp, 720.dp).background(Color(0xFFE5E3DF))) {
            PlacesBubbleLayout(state, anchor, {}, NoActions, Modifier.fillMaxSize())
        }
    }
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun BubbleStates(@PreviewParameter(BubbleUiStateProvider::class) state: Pair<String, BubbleUiState>) = Frame(state.second)

@Preview(widthDp = 360, heightDp = 720, name = "P3 99+ left edge")
@Composable
private fun BubbleLeft() = Frame(base.copy(badge = BadgeState.Count(150, false)), BubbleAnchor(EdgeSide.Start, 0.3f))

@Preview(widthDp = 360, heightDp = 720, locale = "ar", name = "P9 RTL")
@Composable
private fun BubbleRtl() = Frame(base.copy(expanded = true, content = NearbyContent.Places(places(8), 8, false)))

@PreviewFontScale
@Composable
private fun BubbleFontScale() = Frame(base.copy(expanded = true, content = NearbyContent.Places(places(8), 8, false)))

@PreviewLightDark
@Composable
private fun BubbleLightDark() = Frame(base.copy(expanded = true, badge = BadgeState.Count(7, false), content = NearbyContent.Places(places(8), 8, false)))

@Preview(device = "spec:width=800dp,height=360dp", name = "P12 landscape")
@Composable
private fun BubbleLandscape() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFFE5E3DF))) {
            PlacesBubbleLayout(base.copy(expanded = true, content = NearbyContent.Places(places(8), 8, false)), BubbleAnchor.Default, {}, NoActions, Modifier.fillMaxSize())
        }
    }
}
