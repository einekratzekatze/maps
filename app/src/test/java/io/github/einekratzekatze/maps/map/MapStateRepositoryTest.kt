package io.github.einekratzekatze.maps.map

import androidx.compose.ui.unit.IntSize
import app.cash.turbine.test
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.domain.MapUrlState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStateRepositoryTest {
    private val repo = MapStateRepository()
    private val berlin = "https://www.google.com/maps/@52.52,13.405,12z?g_ep=x"

    @Test fun urlAndParseUpdateTogether() {
        repo.reportUrl(berlin)
        assertEquals(berlin, repo.url.value)
        assertEquals(MapMode.Map, repo.urlState.value.mode)
        assertEquals(12.0, repo.urlState.value.camera!!.zoom, 0.0)

        repo.reportUrl(null)
        assertNull(repo.url.value)
        assertEquals(MapUrlState.Unknown, repo.urlState.value)
    }

    @Test fun viewportIsInCssPixels() {
        repo.reportViewportSize(1079, 2216, 2.625f)
        assertEquals(IntSize(411, 844), repo.viewportCssPx.value)
    }

    @Test fun viewportIgnoresInvalidSizes() {
        repo.reportViewportSize(0, 100, 2f)
        repo.reportViewportSize(100, 100, 0f)
        assertNull(repo.viewportCssPx.value)
    }

    @Test fun viewportIsFrozenWhileImeVisible() {
        repo.reportViewportSize(1080, 2200, 2f)
        repo.setImeVisible(true)
        repo.reportViewportSize(1080, 1200, 2f)
        assertEquals(IntSize(540, 1100), repo.viewportCssPx.value)
        repo.setImeVisible(false)
        repo.reportViewportSize(1080, 2000, 2f)
        assertEquals(IntSize(540, 1000), repo.viewportCssPx.value)
    }

    @Test fun boundsNeedCameraAndViewport() = runTest {
        repo.bounds.test {
            assertNull(awaitItem())
            repo.reportUrl(berlin)
            repo.reportViewportSize(1080, 2200, 2f)
            val b = awaitItem()
            assertNotNull(b)
            assertTrue(b!!.contains(LatLon(52.52, 13.405)))
            repo.reportUrl(null)
            assertNull(awaitItem())
        }
    }

    @Test fun touchesAndPageMessagesAreDelivered() = runTest {
        repo.touches.test {
            repo.reportTouch(MapTouch.GestureStart)
            repo.reportTouch(MapTouch.GestureEnd)
            assertEquals(MapTouch.GestureStart, awaitItem())
            assertEquals(MapTouch.GestureEnd, awaitItem())
        }
        repo.pageMessages.test {
            repo.reportPageMessage(PageMessage("ready", """{"type":"ready"}"""))
            assertEquals("ready", awaitItem().type)
        }
    }

    @Test fun overlaySettersAreIndependent() {
        repo.setSearchEditing(true)
        repo.setDrivePromptVisible(true)
        repo.setGmapsSearchHidden(true)
        repo.setSearchEditing(false)
        assertEquals(OverlayState(drivePromptVisible = true, gmapsSearchHidden = true), repo.overlay.value)
    }
}
