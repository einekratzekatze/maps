package io.github.einekratzekatze.maps.map

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebViewTouchObserverTest {
    private val touches = mutableListOf<MapTouch>()
    private val observer = WebViewTouchObserver(
        touchSlop = 10, longPressTimeoutMs = 400, doubleTapTimeoutMs = 300,
        report = { touches += it }, handler = Handler(Looper.getMainLooper()),
    )
    private var downTime = 0L

    private fun send(action: Int, t: Long, x: Float = 100f, y: Float = 100f, pointers: Int = 1) {
        if (action == MotionEvent.ACTION_DOWN) downTime = t
        val props = Array(pointers) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pointers) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 200; this.y = y } }
        val e = MotionEvent.obtain(downTime, t, action, pointers, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        observer.onTouch(null, e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun singleTapIsReportedAfterTheDoubleTapTimeout() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_MOVE, 50, x = 105f)
        send(MotionEvent.ACTION_UP, 100, x = 105f)
        idle(299)
        assertEquals(emptyList<MapTouch>(), touches)
        idle(1)
        assertEquals(listOf(MapTouch.Tap), touches)
    }

    @Test fun doubleTapIsAGestureNotATap() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_UP, 80)
        idle(150)
        send(MotionEvent.ACTION_DOWN, 230)
        send(MotionEvent.ACTION_UP, 300)
        idle(1000)
        assertEquals(listOf(MapTouch.GestureStart, MapTouch.GestureEnd), touches)
    }

    @Test fun dragPastSlopIsAGesture() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_MOVE, 20, x = 130f)
        send(MotionEvent.ACTION_MOVE, 40, x = 200f)
        send(MotionEvent.ACTION_UP, 60, x = 200f)
        idle(1000)
        assertEquals(listOf(MapTouch.GestureStart, MapTouch.GestureEnd), touches)
    }

    @Test fun twoFingersAreOneGesture() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10, pointers = 2)
        send(MotionEvent.ACTION_MOVE, 30, x = 150f, pointers = 2)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, pointers = 2)
        send(MotionEvent.ACTION_UP, 60)
        idle(1000)
        assertEquals(listOf(MapTouch.GestureStart, MapTouch.GestureEnd), touches)
    }

    @Test fun longPressIsNotATap() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_UP, 500)
        idle(1000)
        assertEquals(emptyList<MapTouch>(), touches)
    }

    @Test fun cancelEndsAGesture() {
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_MOVE, 20, x = 150f)
        send(MotionEvent.ACTION_CANCEL, 30, x = 150f)
        assertEquals(listOf(MapTouch.GestureStart, MapTouch.GestureEnd), touches)
    }
}
