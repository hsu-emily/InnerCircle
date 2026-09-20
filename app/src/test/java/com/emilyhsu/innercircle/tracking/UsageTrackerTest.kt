package com.emilyhsu.innercircle.tracking

import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.data.UsageSink
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTrackerTest {
    private class FakeClock(var wall: Long = 1_000_000_000_000L, var elapsed: Long = 5_000L) : TrackerClock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        /** Real time passing: both clocks advance. */
        fun pass(ms: Long) { wall += ms; elapsed += ms }
    }

    private class FakeSink : UsageSink {
        val spans = mutableListOf<Pair<Long, Long>>()
        val counts = mutableListOf<Triple<Int, Int, Double>>()
        override fun addTime(app: SocialApp, startMs: Long, endMs: Long) { spans += startMs to endMs }
        override fun addCounts(app: SocialApp, stories: Int, posts: Int, scrollPx: Double, atMs: Long) { counts += Triple(stories, posts, scrollPx) }
        val totalMs get() = spans.sumOf { it.second - it.first }
    }

    private val clock = FakeClock()
    private val sink = FakeSink()
    private val tracker = UsageTracker(sink, clock)

    @Test fun countsExactlyTheTimeTheAppWasOnScreen() {
        tracker.startSession(SocialApp.Instagram)
        clock.pass(5_000); tracker.tick()
        clock.pass(5_000); tracker.tick()
        clock.pass(3_000); tracker.endSession()
        assertEquals(13_000, sink.totalMs)
    }

    @Test fun timeInTheBackgroundOrScreenOffIsNotCounted() {
        tracker.startSession(SocialApp.Instagram)
        clock.pass(8_000); tracker.endSession()            // user leaves
        clock.pass(60_000)                                 // a minute elsewhere / screen off
        tracker.startSession(SocialApp.Instagram)          // comes back
        clock.pass(2_000); tracker.endSession()
        assertEquals(10_000, sink.totalMs)
    }

    @Test fun changingThePhonesClockCannotAddOrRemoveTime() {
        tracker.startSession(SocialApp.Instagram)
        clock.pass(5_000); tracker.tick()
        clock.wall += 24L * 3600_000                       // user sets the date forward a day...
        clock.elapsed += 5_000; clock.wall += 5_000; tracker.tick()
        clock.wall -= 3L * 3600_000                        // ...then back three hours (or a timezone change)
        clock.elapsed += 5_000; clock.wall += 5_000; tracker.endSession()
        assertEquals(15_000, sink.totalMs)
        assert(sink.spans.all { it.second > it.first }) { "no negative or empty spans" }
    }

    @Test fun startingTwiceDoesNotResetTheClockOrDoubleCount() {
        tracker.startSession(SocialApp.Instagram)
        clock.pass(4_000)
        tracker.startSession(SocialApp.Instagram)          // e.g. a second RESUME event
        clock.pass(4_000); tracker.endSession()
        assertEquals(8_000, sink.totalMs)
    }

    @Test fun endingWithoutStartingRecordsNothing() {
        clock.pass(10_000); tracker.tick(); tracker.endSession()
        assertEquals(0, sink.totalMs)
    }

    @Test fun pageMessagesAreValidatedAndClamped() {
        tracker.onPageMessage(SocialApp.Instagram, """{"type":"usage","posts":3,"stories":2,"scrollPx":5000}""")
        tracker.onPageMessage(SocialApp.Instagram, """{"type":"usage","posts":99999,"stories":-5,"scrollPx":1e12}""")
        tracker.onPageMessage(SocialApp.Instagram, """{"type":"other","posts":50}""")
        tracker.onPageMessage(SocialApp.Instagram, "not json")
        assertEquals(listOf(Triple(2, 3, 5000.0), Triple(0, 100, 200_000.0)), sink.counts)
    }
}
