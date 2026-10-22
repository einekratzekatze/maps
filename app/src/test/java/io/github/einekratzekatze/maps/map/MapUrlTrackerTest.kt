package io.github.einekratzekatze.maps.map

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MapUrlTrackerTest {
    private val reported = mutableListOf<String?>()
    private var webViewUrl: String? = null
    private val tracker = MapUrlTracker({ webViewUrl }, { reported += it })

    @Test fun historyEventsAreDeduplicated() {
        tracker.onVisitedHistory("a")
        tracker.onVisitedHistory("a")
        tracker.onVisitedHistory("b")
        assertEquals(listOf("a", "b"), reported)
    }

    @Test fun pageStartReportsNullThenFinishReportsTheUrl() {
        tracker.onVisitedHistory("a")
        tracker.onPageStarted()
        tracker.onPageStarted()
        tracker.onPageFinished("b")
        assertEquals(listOf("a", null, "b"), reported)
    }

    @Test fun pollCatchesChangesHistoryMissed() = runTest {
        tracker.onPageFinished("a")
        val job = launch { tracker.pollWhileResumed() }
        webViewUrl = "a"
        advanceTimeBy(1001)
        assertEquals(listOf("a"), reported)
        webViewUrl = "b"
        advanceTimeBy(999)
        assertEquals(listOf("a"), reported)
        advanceTimeBy(2)
        assertEquals(listOf("a", "b"), reported)
        job.cancel()
    }

    @Test fun pollIsQuietWhileAPageLoads() = runTest {
        tracker.onPageStarted()
        webViewUrl = "loading"
        val job = launch { tracker.pollWhileResumed() }
        advanceTimeBy(3001)
        assertEquals(listOf<String?>(null), reported)
        tracker.onPageFinished("done")
        assertEquals(listOf(null, "done"), reported)
        job.cancel()
    }

    @Test fun pollStopsWhenCancelled() = runTest {
        val job = launch { tracker.pollWhileResumed() }
        runCurrent()
        job.cancel()
        webViewUrl = "late"
        advanceTimeBy(5000)
        assertEquals(emptyList<String?>(), reported)
    }
}
