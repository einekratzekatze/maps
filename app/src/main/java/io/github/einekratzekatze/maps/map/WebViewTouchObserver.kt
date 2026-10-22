package io.github.einekratzekatze.maps.map

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

sealed interface MapTouch {
    /** Single tap, emitted only after the double-tap timeout passed without a second down. */
    data object Tap : MapTouch
    /** First move past slop, a second pointer, or the second down of a double-tap. */
    data object GestureStart : MapTouch
    /** ACTION_UP / ACTION_CANCEL that ends a gesture. */
    data object GestureEnd : MapTouch
}

/**
 * The only OnTouchListener on the WebView (compose-host.md §5.2). Observes only: always returns
 * false, so GMaps still gets every event. A tap on GMaps' own controls is also a [MapTouch.Tap];
 * we cannot tell them apart from the map without DOM hooks.
 */
class WebViewTouchObserver(
    private val touchSlop: Int,
    private val longPressTimeoutMs: Long,
    private val doubleTapTimeoutMs: Long,
    private val report: (MapTouch) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : View.OnTouchListener {

    constructor(view: View, report: (MapTouch) -> Unit) : this(
        touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop,
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
        doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
        report = report,
    )

    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false
    private var inGesture = false
    private val pendingTap = Runnable {
        tapPending = false
        report(MapTouch.Tap)
    }
    private var tapPending = false

    @SuppressLint("ClickableViewAccessibility") // observe only; the WebView keeps its own click handling
    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tapPending) {
                    // Second down inside the double-tap window: a double-tap zoom, not two taps.
                    cancelPendingTap()
                    tapCandidate = false
                    startGesture()
                } else {
                    downX = event.x
                    downY = event.y
                    tapCandidate = true
                    inGesture = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapCandidate = false
                startGesture()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inGesture && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    tapCandidate = false
                    startGesture()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (inGesture) {
                    endGesture()
                } else if (tapCandidate && event.eventTime - event.downTime < longPressTimeoutMs) {
                    tapPending = true
                    handler.postDelayed(pendingTap, doubleTapTimeoutMs)
                }
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (inGesture) endGesture()
                tapCandidate = false
            }
        }
        return false
    }

    private fun startGesture() {
        if (inGesture) return
        inGesture = true
        report(MapTouch.GestureStart)
    }

    private fun endGesture() {
        inGesture = false
        report(MapTouch.GestureEnd)
    }

    private fun cancelPendingTap() {
        handler.removeCallbacks(pendingTap)
        tapPending = false
    }
}
