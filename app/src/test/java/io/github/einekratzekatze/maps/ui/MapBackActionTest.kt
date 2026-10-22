package io.github.einekratzekatze.maps.ui

import io.github.einekratzekatze.maps.map.OverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapBackActionTest {
    @Test fun nothingOpenFallsThrough() {
        assertNull(mapBackAction(OverlayState(imeVisible = true, gmapsSearchHidden = true), bubbleExpanded = false))
    }

    @Test fun priorityIsSearchThenPromptThenBubble() {
        val all = OverlayState(searchEditing = true, drivePromptVisible = true)
        assertEquals(MapBackAction.ClearSearchFocus, mapBackAction(all, bubbleExpanded = true))
        assertEquals(MapBackAction.DismissDrivePrompt, mapBackAction(all.copy(searchEditing = false), bubbleExpanded = true))
        assertEquals(MapBackAction.CollapseBubble, mapBackAction(OverlayState(), bubbleExpanded = true))
    }
}
