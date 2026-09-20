package com.emilyhsu.innercircle.tracking

import android.util.Log
import com.emilyhsu.innercircle.data.SocialApp
import android.os.SystemClock
import com.emilyhsu.innercircle.data.UsageSink
import org.json.JSONObject

/** Two clocks: wall time says *when* something happened, the monotonic one says *how long* it lasted. */
interface TrackerClock {
    fun wallMs(): Long
    fun elapsedMs(): Long
}

object SystemTrackerClock : TrackerClock {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
}

/**
 * Turns "the user is looking at Instagram" into numbers.
 *
 * - Time on app is measured here: [startSession] when an app screen comes to the foreground,
 *   [tick] periodically while it stays there, [endSession] when it leaves.
 * - Posts / stories / scroll distance arrive from the page (usage_tracker.js) as JSON deltas via
 *   [onPageMessage].
 */
class UsageTracker(
    private val usage: UsageSink,
    private val clock: TrackerClock = SystemTrackerClock,
) {
    private var app: SocialApp? = null
    private var lastElapsedMs = 0L

    @Synchronized
    fun startSession(app: SocialApp) {
        if (this.app != null) return
        this.app = app
        lastElapsedMs = clock.elapsedMs()
    }

    /** Commits the time since the last tick, so a crash or kill loses at most a few seconds. */
    @Synchronized
    fun tick() {
        val current = app ?: return
        // Duration comes from the monotonic clock, so changing the phone's time or timezone while an
        // app is open can't add or remove time. Wall time only decides which day and hour it lands in.
        val elapsed = clock.elapsedMs()
        val duration = elapsed - lastElapsedMs
        lastElapsedMs = elapsed
        if (duration <= 0) return
        val now = clock.wallMs()
        usage.addTime(current, now - duration, now)
    }

    @Synchronized
    fun endSession() {
        tick()
        app = null
    }

    /**
     * The page is third-party code, so nothing it sends is trusted: every value is clamped to what
     * could plausibly happen in one flush interval.
     */
    fun onPageMessage(app: SocialApp, raw: String) {
        runCatching {
            val o = JSONObject(raw)
            if (o.optString("type") != "usage") return
            usage.addCounts(
                app = app,
                atMs = clock.wallMs(),
                stories = o.optInt("stories").coerceIn(0, MAX_COUNT),
                posts = o.optInt("posts").coerceIn(0, MAX_COUNT),
                scrollPx = o.optDouble("scrollPx", 0.0).coerceIn(0.0, MAX_SCROLL_PX),
            )
        }.onFailure { Log.w(TAG, "Ignoring malformed page message: ${raw.take(80)}", it) }
    }

    private companion object {
        const val TAG = "UsageTracker"
        const val MAX_COUNT = 100
        const val MAX_SCROLL_PX = 200_000.0
    }
}
