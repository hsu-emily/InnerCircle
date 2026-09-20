package com.emilyhsu.innercircle.webview

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject

/**
 * The page asking the app to tap the screen for it, as fractions of the WebView's width and height.
 *
 * Used to move past a story ad. A tap sent from JavaScript is an "untrusted" event that Instagram's
 * story viewer is free to ignore; a touch injected here is indistinguishable from a finger.
 */
data class TapRequest(val x: Float, val y: Float)

/**
 * `{"type":"nativeTap","x":0.85,"y":0.5}` -> [TapRequest]. Anything else, including a tap aimed outside
 * the right-hand side / middle of the screen (where the story viewer's "next" area is), is refused, so
 * the page can't use this to press arbitrary things.
 */
internal fun parseTapRequest(raw: String): TapRequest? {
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (o.optString("type") != "nativeTap") return null
    val x = o.optDouble("x", Double.NaN).toFloat()
    val y = o.optDouble("y", Double.NaN).toFloat()
    if (x !in MIN_X..MAX_X || y !in MIN_Y..MAX_Y) return null // also rejects NaN
    return TapRequest(x, y)
}

private const val MIN_X = 0.5f
private const val MAX_X = 0.95f
private const val MIN_Y = 0.2f
private const val MAX_Y = 0.8f

/** Presses and releases at [request]'s spot. Must be called on the main thread. */
internal fun WebView.tap(request: TapRequest) {
    val x = width * request.x
    val y = height * request.y
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    dispatchTouchEvent(down)
    down.recycle()
    // A real tap has a short press; releasing in the same instant can be read as a glitch.
    postDelayed({
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        dispatchTouchEvent(up)
        up.recycle()
    }, TAP_PRESS_MS)
}

private const val TAP_PRESS_MS = 50L
