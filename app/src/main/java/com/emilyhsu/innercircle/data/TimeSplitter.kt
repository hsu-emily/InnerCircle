package com.emilyhsu.innercircle.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A stretch of time that falls inside a single hour of a single calendar day. */
data class TimeSlice(val date: LocalDate, val hour: Int, val ms: Long)

/** Cuts a span of time at every hour boundary (which includes midnight) so it can be filed by day and hour. */
object TimeSplitter {
    fun split(startMs: Long, endMs: Long, zone: ZoneId): List<TimeSlice> {
        val slices = mutableListOf<TimeSlice>()
        var cursor = startMs
        while (cursor < endMs) {
            val at = Instant.ofEpochMilli(cursor).atZone(zone)
            val hourEnd = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val segment = minOf(endMs, hourEnd) - cursor
            slices += TimeSlice(at.toLocalDate(), at.hour, segment)
            cursor += segment
        }
        return slices
    }
}

/** Where measured usage is recorded. The repository implements it; tests use a fake. */
interface UsageSink {
    fun addTime(app: SocialApp, startMs: Long, endMs: Long)
    fun addCounts(app: SocialApp, stories: Int, posts: Int, scrollPx: Double, atMs: Long = System.currentTimeMillis())
}
