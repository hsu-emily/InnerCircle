package com.emilyhsu.innercircle.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class TimeSplitterTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()
    private val min = 60_000L

    @Test fun insideOneHourStaysOneSlice() {
        val s = TimeSplitter.split(ms(utc, 2026, 9, 19, 10, 5), ms(utc, 2026, 9, 19, 10, 35), utc)
        assertEquals(listOf(TimeSlice(LocalDate.of(2026, 9, 19), 10, 30 * min)), s)
    }

    @Test fun crossingAnHourBoundarySplitsExactly() {
        val s = TimeSplitter.split(ms(utc, 2026, 9, 19, 10, 50), ms(utc, 2026, 9, 19, 11, 10), utc)
        assertEquals(listOf(10 to 10 * min, 11 to 10 * min), s.map { it.hour to it.ms })
    }

    @Test fun crossingMidnightFilesTimeOnBothDays() {
        val s = TimeSplitter.split(ms(utc, 2026, 9, 19, 23, 55), ms(utc, 2026, 9, 20, 0, 5), utc)
        assertEquals(
            listOf(
                TimeSlice(LocalDate.of(2026, 9, 19), 23, 5 * min),
                TimeSlice(LocalDate.of(2026, 9, 20), 0, 5 * min),
            ),
            s,
        )
    }

    @Test fun aLongSessionCoversEveryHourItTouches() {
        val s = TimeSplitter.split(ms(utc, 2026, 9, 19, 9, 30), ms(utc, 2026, 9, 19, 12, 15), utc)
        assertEquals(listOf(9 to 30 * min, 10 to 60 * min, 11 to 60 * min, 12 to 15 * min), s.map { it.hour to it.ms })
    }

    @Test fun nothingIsEverLostOrInvented() {
        val rng = Random(7)
        repeat(500) {
            val start = ms(utc, 2026, 1, 1, 0, 0) + rng.nextLong(0, 200L * 24 * 60 * min)
            val length = rng.nextLong(1, 30L * 60 * min)
            val slices = TimeSplitter.split(start, start + length, utc)
            assertEquals("total must equal the span", length, slices.sumOf { it.ms })
            assert(slices.all { it.ms in 1..(60 * min) }) { "no slice may exceed an hour" }
        }
    }

    @Test fun daylightSavingDoesNotChangeTheTotal() {
        val ny = ZoneId.of("America/New_York")
        // Clocks jump 2:00 -> 3:00 on 2026-03-08. 1:30am to 3:30am on the wall is one real hour.
        val spring = TimeSplitter.split(ms(ny, 2026, 3, 8, 1, 30), ms(ny, 2026, 3, 8, 3, 30), ny)
        assertEquals(60 * min, spring.sumOf { it.ms })
        // Clocks fall back 2:00 -> 1:00 on 2026-11-01: 12:30am to 2:30am on the wall is three real hours.
        val fall = TimeSplitter.split(ms(ny, 2026, 11, 1, 0, 30), ms(ny, 2026, 11, 1, 2, 30), ny)
        assertEquals(3 * 60 * min, fall.sumOf { it.ms })
    }
}
