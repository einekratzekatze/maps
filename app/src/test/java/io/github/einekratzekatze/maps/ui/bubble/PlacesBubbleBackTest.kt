package io.github.einekratzekatze.maps.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.einekratzekatze.maps.map.OverlayState
import io.github.einekratzekatze.maps.ui.MapBackAction
import io.github.einekratzekatze.maps.ui.MapBackHandler
import io.github.einekratzekatze.maps.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class PlacesBubbleBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun backWhileExpandedCloses() { // T-C12
        val actions = RecordingActions()
        val state = BubbleUiState(bubbleVisible = true, cardAllowed = true, expanded = true, content = NearbyContent.NoneInView)
        rule.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    PlacesBubbleLayout(state, BubbleAnchor.Default, {}, actions, Modifier.fillMaxSize())
                    MapBackHandler(OverlayState(), bubbleExpanded = state.expanded) { if (it == MapBackAction.CollapseBubble) actions.onClose() }
                }
            }
        }
        rule.onNodeWithTag(BubbleTestTags.NearbyCard)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Nearby saved places"))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("close"), actions.calls)
    }
}
