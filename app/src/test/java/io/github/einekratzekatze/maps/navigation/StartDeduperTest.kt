package io.github.einekratzekatze.maps.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartDeduperTest {
    private var t = 10_000L
    private val deduper = StartDeduper(now = { t })
    private val a = NavTarget(null, "A")
    private val b = NavTarget(null, "B")

    @Test fun secondEventForSameTargetIsSuppressed() {
        assertTrue(deduper.shouldLaunch(a))
        t += 50
        assertFalse(deduper.shouldLaunch(a))
    }

    @Test fun differentTargetLaunches() {
        assertTrue(deduper.shouldLaunch(a))
        assertTrue(deduper.shouldLaunch(b))
    }

    @Test fun sameTargetAfterWindowLaunchesAgain() {
        assertTrue(deduper.shouldLaunch(a))
        t += 1_999
        assertFalse(deduper.shouldLaunch(a))
        t += 1
        assertTrue(deduper.shouldLaunch(a))
    }
}
