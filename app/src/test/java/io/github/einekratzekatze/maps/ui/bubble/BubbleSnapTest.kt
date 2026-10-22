package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleSnapTest {
    // 360 x 640 dp phone at density 2
    private val phone = BubbleBounds.from(720f, 1280f, 2f, searchHidden = false)

    @Test fun boundsFromDp() {
        assertEquals(24f, phone.minX)
        assertEquals(720f - 24f - 112f, phone.maxX)
        assertEquals(144f, phone.minY)
        assertEquals(1280f - 272f - 112f, phone.maxY)
        assertEquals(24f, BubbleBounds.from(720f, 1280f, 2f, searchHidden = true).minY)
    }

    @Test fun tinyWindowCentresVertically() {
        val b = BubbleBounds.from(720f, 400f, 2f, searchHidden = false)
        assertEquals((400f - 112f) / 2, b.minY)
        assertEquals(b.minY, b.maxY)
        assertEquals(0.5f, computeSnapAnchor(Offset(10f, 300f), Velocity.Zero, b, isRtl = false).yFraction)
    }

    @Test fun slowReleaseInLeftHalfGoesToStart() {
        val a = computeSnapAnchor(Offset(100f, 500f), Velocity(300f, 0f), phone, isRtl = false)
        assertEquals(EdgeSide.Start, a.side)
    }

    @Test fun fastFlingRightFromLeftHalfGoesToEnd() {
        val a = computeSnapAnchor(Offset(50f, 500f), Velocity(1500f, 0f), phone, isRtl = false)
        assertEquals(EdgeSide.End, a.side)
    }

    @Test fun projectionCanCrossTheMiddleWithoutAFling() {
        // centre at 300 + 56 = 356 < 360, but 900 px/s * 0.15 s pushes it over
        assertEquals(EdgeSide.End, computeSnapAnchor(Offset(300f, 500f), Velocity(900f, 0f), phone, false).side)
    }

    @Test fun exactMiddleTiesToEnd() {
        val x = 360f - 56f
        assertEquals(EdgeSide.End, computeSnapAnchor(Offset(x, 500f), Velocity.Zero, phone, false).side)
        assertEquals(EdgeSide.End, computeSnapAnchor(Offset(x, 500f), Velocity.Zero, phone, true).side)
    }

    @Test fun yIsClampedToTheRestingRange() {
        assertEquals(0f, computeSnapAnchor(Offset(10f, 0f), Velocity.Zero, phone, false).yFraction)
        assertEquals(1f, computeSnapAnchor(Offset(10f, 5000f), Velocity.Zero, phone, false).yFraction)
        assertEquals(1f, computeSnapAnchor(Offset(10f, 800f), Velocity(0f, 9000f), phone, false).yFraction)
    }

    @Test fun rtlMirrorsTheSides() {
        assertEquals(EdgeSide.End, computeSnapAnchor(Offset(10f, 500f), Velocity.Zero, phone, isRtl = true).side)
        assertEquals(EdgeSide.Start, computeSnapAnchor(Offset(600f, 500f), Velocity.Zero, phone, isRtl = true).side)
        assertEquals(phone.minX, anchorToOffset(BubbleAnchor(EdgeSide.End, 0f), phone, isRtl = true).x)
    }

    @Test fun anchorRoundTrip() {
        for (rtl in listOf(false, true)) for (side in EdgeSide.entries) for (f in listOf(0f, 0.3f, 1f)) {
            val a = BubbleAnchor(side, f)
            val back = computeSnapAnchor(anchorToOffset(a, phone, rtl), Velocity.Zero, phone, rtl)
            assertEquals(a.side, back.side)
            assertEquals(a.yFraction, back.yFraction, 1e-5f)
        }
    }

    @Test fun cardOnPhone() {
        val bubble = anchorToOffset(BubbleAnchor(EdgeSide.End, 0.45f), phone, false)
        val c = cardPlacement(bubble, EdgeSide.End, phone, isRtl = false)
        assertEquals(306f, c.width.value, 0.01f)
        assertEquals(320.dp, c.maxHeight)
        assertEquals((720 - 24 - 612), c.topLeft.x)
        assertEquals((bubble.y - 48f).toInt(), c.topLeft.y)
    }

    @Test fun cardOnLandscape() {
        val land = BubbleBounds.from(1600f, 720f, 2f, searchHidden = false)
        val c = cardPlacement(anchorToOffset(BubbleAnchor.Default, land, false), EdgeSide.End, land, false)
        assertEquals(360.dp, c.width)
        assertEquals(324f, c.maxHeight.value, 0.01f)
        assertEquals(720 - 24 - 648, c.topLeft.y) // too short for the pill limit: stays on screen
    }

    @Test fun cardIsClampedBetweenTopLimitAndBottom() {
        val top = cardPlacement(Offset(24f, phone.minY), EdgeSide.Start, phone, false)
        assertEquals(phone.minY.toInt(), top.topLeft.y)
        assertEquals(24, top.topLeft.x)
        val low = cardPlacement(Offset(24f, phone.maxY), EdgeSide.Start, phone, false)
        assertEquals(1280 - 24 - 640, low.topLeft.y)
    }

    @Test fun largeWindowCard() {
        val tablet = BubbleBounds.from(1600f, 2000f, 2f, searchHidden = false) // 800 x 1000 dp
        val c = cardPlacement(Offset(24f, 400f), EdgeSide.Start, tablet, false)
        assertEquals(360.dp, c.width)
        assertEquals(480.dp, c.maxHeight)
    }
}
