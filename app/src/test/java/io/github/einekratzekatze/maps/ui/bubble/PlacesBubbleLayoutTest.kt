package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class RecordingActions : PlacesBubbleActions {
    val calls = mutableListOf<String>()
    override fun onBubbleTap() { calls += "tap" }
    override fun onClose() { calls += "close" }
    override fun onPlaceClick(id: Long) { calls += "place $id" }
    override fun onNavigateClick(id: Long) { calls += "nav $id" }
    override fun onImportantClick(id: Long) { calls += "important $id" }
    override fun onMarkerTapped(id: Long) { calls += "marker $id" }
    override fun onShowAllNearby() { calls += "all" }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacesBubbleLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val actions = RecordingActions()
    private val important = listOf(
        ImportantShortcut(1, ImportantKind.HOME, null, LatLon(52.0, 13.0)),
        ImportantShortcut(2, ImportantKind.WORK, null, null),
    )
    private val visible = BubbleUiState(bubbleVisible = true, cardAllowed = true, important = important, content = NearbyContent.NoneInView)

    private fun places(n: Int) = NearbyContent.Places(
        (1..n).map { NearbyPlace(it.toLong(), "Place $it", 52.0, 13.0, "Food", if (it == 1) 1 else 0, 0xFFFF0000.toInt(), it * 100.0) },
        totalInView = n, truncated = false,
    )

    private var state by mutableStateOf(visible)
    private val anchors = mutableListOf<BubbleAnchor>()

    private fun show(initial: BubbleUiState, anchor: BubbleAnchor = BubbleAnchor.Default, under: (() -> Unit)? = null) {
        state = initial
        rule.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    if (under != null) Box(Modifier.fillMaxSize().testTag("under").clickable { under() })
                    PlacesBubbleLayout(state, anchor, { anchors += it }, actions, Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test fun badgeLabels() { // T-C1
        show(visible.copy(badge = BadgeState.Count(7, false)))
        // The badge is merged into the bubble's semantics, so look in the unmerged tree.
        rule.onNodeWithTag(BubbleTestTags.Badge, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("7", useUnmergedTree = true).assertIsDisplayed()
        state = visible.copy(badge = BadgeState.Count(150, false))
        rule.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        state = visible.copy(badge = BadgeState.Hidden)
        rule.onNodeWithTag(BubbleTestTags.Badge, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun tapExpandsIntoTheCard() { // T-C2
        show(visible)
        rule.onNodeWithTag(BubbleTestTags.Bubble).performClick()
        assertEquals(listOf("tap"), actions.calls)
        state = visible.copy(expanded = true)
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag(BubbleTestTags.NearbyCard).assertIsDisplayed()
        rule.onNodeWithTag(BubbleTestTags.Bubble).assertDoesNotExist()
    }

    @Test fun closeButton() { // T-C3
        show(visible.copy(expanded = true))
        rule.onNodeWithTag(BubbleTestTags.NearbyCardClose).performClick()
        assertEquals(listOf("close"), actions.calls)
    }

    @Test fun everyContentStateRenders() { // T-C4 + T-C6
        show(visible.copy(expanded = true, content = NearbyContent.Loading))
        for (c in listOf(NearbyContent.Loading, NearbyContent.ViewportUnknown, NearbyContent.NoSavedPlaces, NearbyContent.NoneInView)) {
            state = visible.copy(expanded = true, content = c)
            rule.onNodeWithTag(BubbleTestTags.EmptyState).assertIsDisplayed()
            rule.onNodeWithTag(BubbleTestTags.importantChip(1)).assertIsDisplayed()
            rule.onNodeWithTag(BubbleTestTags.importantChip(2)).assertIsDisplayed()
        }
        rule.onNodeWithText("Set Work").assertIsDisplayed()
        state = visible.copy(expanded = true, content = places(100))
        rule.onNodeWithTag(BubbleTestTags.EmptyState).assertDoesNotExist()
        // Row text is replaced by one spoken description (§7)
        rule.onNodeWithContentDescription("Place 1, Food +1, 100 metres").assertIsDisplayed()
        rule.onNodeWithTag(BubbleTestTags.NearbyList).performScrollToNode(hasTestTag(BubbleTestTags.placeRow(99)))
        rule.onNodeWithTag(BubbleTestTags.importantChip(1)).assertIsDisplayed()
    }

    @Test fun rowAndNavigateAreSeparate() { // T-C5
        show(visible.copy(expanded = true, content = places(3)))
        rule.onNodeWithTag(BubbleTestTags.placeRow(2)).performClick()
        rule.onNodeWithTag(BubbleTestTags.placeRowNav(3)).performClick()
        assertEquals(listOf("place 2", "nav 3"), actions.calls)
    }

    @Test fun touchesOutsideFallThrough() { // T-C9
        var hits = 0
        show(visible, under = { hits++ })
        rule.onNodeWithTag("under").performTouchInput { click(Offset(width / 2f, height * 0.1f)) }
        assertEquals(1, hits)
        state = visible.copy(expanded = true)
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("under").performTouchInput { click(Offset(width / 2f, height - 10f)) }
        assertEquals(2, hits)
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun touchTargets() { // T-C10
        show(visible.copy(expanded = true, content = places(1)))
        // M3 icon buttons are 40 dp inside a 48 dp touch area
        rule.onNodeWithTag(BubbleTestTags.NearbyCardClose).assertTouchHeightIsEqualTo(48.dp)
        rule.onNodeWithTag(BubbleTestTags.placeRowNav(1)).assertTouchHeightIsEqualTo(48.dp)
        rule.onNodeWithTag(BubbleTestTags.placeRow(1)).assertHeightIsAtLeast(56.dp)
        state = visible
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag(BubbleTestTags.Bubble).assertHeightIsAtLeast(48.dp)
    }

    @Test fun lowRightAnchorIsVisibleAndTappable() { // T-C13
        show(visible, anchor = BubbleAnchor(EdgeSide.End, 0.9f))
        val node = rule.onNodeWithTag(BubbleTestTags.Bubble)
        node.assertIsDisplayed()
        val b = node.getBoundsInRoot()
        assertEquals((360 - 12).toFloat(), b.right.value, 1f)
        node.performClick()
        assertEquals(listOf("tap"), actions.calls)
        state = visible.copy(expanded = true)
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag(BubbleTestTags.NearbyCardClose).assertIsDisplayed().performClick()
        assertEquals(listOf("tap", "close"), actions.calls)
    }

    @Test fun placeCard() { // T-C15
        val card = PlaceCardState(1, "Café Einstein", 52.5, 13.4, "Food", 0, "Unter den Linden 42", null, 350.0)
        show(visible.copy(expanded = true, focus = CardFocus.Place(1), placeCard = card))
        rule.onNodeWithText("Café Einstein").assertIsDisplayed()
        rule.onNodeWithTag(BubbleTestTags.PlaceCardBack).performClick()
        rule.onNodeWithText("Navigate").performClick()
        rule.onNodeWithText("Show on map").performClick()
        assertEquals(listOf("all", "nav 1", "place 1"), actions.calls)
    }

    @Test fun dragToTheLeftSnapsToStart() { // T-C7
        show(visible)
        rule.onNodeWithTag(BubbleTestTags.Bubble).performTouchInput {
            down(center)
            moveBy(Offset(-width * 3f, 0f), delayMillis = 100)
            up()
        }
        rule.mainClock.advanceTimeBy(1000)
        val left = rule.onNodeWithTag(BubbleTestTags.Bubble).getBoundsInRoot().left.value
        assertEquals(12f, left, 1f)
        assertEquals(EdgeSide.Start, anchors.single().side)
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun smallMovementIsATap() { // T-C8
        show(visible)
        rule.onNodeWithTag(BubbleTestTags.Bubble).performTouchInput {
            down(center)
            moveBy(Offset(2f, 2f))
            up()
        }
        assertEquals(listOf("tap"), actions.calls)
        assertTrue(anchors.isEmpty())
    }

    @Test fun semanticsAndCustomActions() { // T-C11
        show(visible.copy(badge = BadgeState.Count(7, false)))
        val bubble = rule.onNodeWithTag(BubbleTestTags.Bubble)
        bubble.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Saved places")))
        bubble.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "7 in view"))
        val action = bubble.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move to left edge" }
        rule.runOnIdle { action.action() }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(BubbleAnchor(EdgeSide.Start, BubbleAnchor.Default.yFraction), anchors.single())
        assertEquals(12f, rule.onNodeWithTag(BubbleTestTags.Bubble).getBoundsInRoot().left.value, 1f)
    }

    @Test fun interruptedSettlePersistsTheTargetOnce() { // T-C14
        show(visible)
        rule.mainClock.autoAdvance = false
        val bubble = rule.onNodeWithTag(BubbleTestTags.Bubble)
        bubble.performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(-width * 2f, 0f), delayMillis = 16) } // centre ends well inside the left half
        }
        rule.mainClock.advanceTimeBy(100)
        bubble.performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        // A new touch lands before the spring has finished.
        bubble.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, anchors.size)
        assertEquals(EdgeSide.Start, anchors.single().side)
    }
}
