package com.emilyhsu.innercircle.data

import com.emilyhsu.innercircle.util.formatPeriodLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class PeriodMathTest {
    private val sat = LocalDate.of(2026, 9, 19)   // a Saturday
    private val sun = DayOfWeek.SUNDAY
    private val mon = DayOfWeek.MONDAY

    @Test fun fixtureDatesAreWhatTheTestsAssume() {
        assertEquals(DayOfWeek.SATURDAY, sat.dayOfWeek)
    }

    @Test fun windowsAreCalendarAligned() {
        // Day
        assertEquals(sat, PeriodMath.start(Period.Day, sat, sun))
        assertEquals(sat, PeriodMath.end(Period.Day, sat))
        // Week, Sunday start: Sep 13 (Sun) to Sep 19 (Sat)
        val w = PeriodMath.start(Period.Week, sat, sun)
        assertEquals(LocalDate.of(2026, 9, 13), w)
        assertEquals(LocalDate.of(2026, 9, 19), PeriodMath.end(Period.Week, w))
        // Week, Monday start: Sep 14 to Sep 20
        val m = PeriodMath.start(Period.Week, sat, mon)
        assertEquals(LocalDate.of(2026, 9, 14), m)
        assertEquals(LocalDate.of(2026, 9, 20), PeriodMath.end(Period.Week, m))
        // Month
        val month = PeriodMath.start(Period.Month, sat, sun)
        assertEquals(LocalDate.of(2026, 9, 1), month)
        assertEquals(LocalDate.of(2026, 9, 30), PeriodMath.end(Period.Month, month))
        // Leap-year February
        val feb = PeriodMath.start(Period.Month, LocalDate.of(2028, 2, 10), sun)
        assertEquals(LocalDate.of(2028, 2, 29), PeriodMath.end(Period.Month, feb))
    }

    @Test fun elapsedDaysCountsOnlyWhatHasHappened() {
        val start = LocalDate.of(2026, 9, 14); val end = LocalDate.of(2026, 9, 20)
        assertEquals(6, PeriodMath.elapsedDays(start, end, sat))                       // Mon-start week, Saturday: 6 of 7
        assertEquals(7, PeriodMath.elapsedDays(start, end, LocalDate.of(2026, 10, 1))) // long finished
        assertEquals(0, PeriodMath.elapsedDays(start, end, LocalDate.of(2026, 9, 1)))  // hasn't started
    }

    @Test fun steppingNeverPassesToday() {
        assertEquals(LocalDate.of(2026, 9, 18), PeriodMath.shift(Period.Day, sat, -1, sat))
        assertEquals(sat, PeriodMath.shift(Period.Day, sat, +1, sat))
        assertEquals(LocalDate.of(2026, 9, 12), PeriodMath.shift(Period.Week, sat, -1, sat))
        assertEquals(sat, PeriodMath.shift(Period.Week, LocalDate.of(2026, 9, 12), +1, sat))
        assertEquals(LocalDate.of(2026, 8, 19), PeriodMath.shift(Period.Month, sat, -1, sat))
        assertEquals(sat, PeriodMath.shift(Period.Month, LocalDate.of(2026, 8, 19), +1, sat))
        // across a year boundary
        assertEquals(LocalDate.of(2025, 12, 31), PeriodMath.shift(Period.Day, LocalDate.of(2026, 1, 1), -1, sat))
    }

    // --- aggregation -----------------------------------------------------------------------------

    private fun usage(vararg dayMinutes: Pair<LocalDate, Long>): UsageDays =
        dayMinutes.associate { (d, min) -> d.toString() to mapOf("instagram" to AppDay(totalMs = min * 60_000L, posts = 10)) }

    @Test fun currentWeekIsComparedLikeForLikeNotAgainstAWholeWeek() {
        val wed = LocalDate.of(2026, 9, 16)
        val data = usage(
            // this week so far (Sun 13 .. Wed 16)
            LocalDate.of(2026, 9, 13) to 10, LocalDate.of(2026, 9, 14) to 20, LocalDate.of(2026, 9, 15) to 30, LocalDate.of(2026, 9, 16) to 40,
            // last week (Sun 6 .. Sat 12): first four days are 5+5+5+5, the rest is 100 each and must NOT count
            LocalDate.of(2026, 9, 6) to 5, LocalDate.of(2026, 9, 7) to 5, LocalDate.of(2026, 9, 8) to 5, LocalDate.of(2026, 9, 9) to 5,
            LocalDate.of(2026, 9, 10) to 100, LocalDate.of(2026, 9, 11) to 100, LocalDate.of(2026, 9, 12) to 100,
        )
        val s = summarizeUsage(data, Period.Week, wed, wed, sun)

        assertEquals(LocalDate.of(2026, 9, 13), s.start)
        assertEquals(LocalDate.of(2026, 9, 19), s.end)
        assertTrue(s.isCurrent)
        assertTrue(s.isPartial)
        assertEquals(4, s.elapsedDays)
        assertEquals(7, s.daily.size)                                   // future days are present, empty
        assertEquals(0L, s.daily.last().second)
        assertEquals(100 * 60_000L, s.total.totalMs)
        assertEquals(20 * 60_000L, s.previousTotalMs)                   // only the first four days of last week
    }

    @Test fun aggregationKeepsYouTubeSeparateAndIncludesItInTheCombinedTotal() {
        val date = LocalDate.of(2026, 9, 16)
        val data: UsageDays = mapOf(date.toString() to mapOf(
            "instagram" to AppDay(totalMs = 20 * 60_000L),
            "youtube" to AppDay(totalMs = 35 * 60_000L, posts = 3),
        ))
        val summary = summarizeUsage(data, Period.Day, date, date, sun)
        assertEquals(55 * 60_000L, summary.total.totalMs)
        assertEquals(20 * 60_000L, summary.perApp[SocialApp.Instagram]?.totalMs)
        assertEquals(35 * 60_000L, summary.perApp[SocialApp.YouTube]?.totalMs)
        assertEquals(3, summary.perApp[SocialApp.YouTube]?.posts)
    }

    @Test fun pastWindowsAreCompleteAndNotCurrent() {
        val data = usage(LocalDate.of(2026, 9, 8) to 30)
        val s = summarizeUsage(data, Period.Week, LocalDate.of(2026, 9, 8), sat, sun)
        assertEquals(LocalDate.of(2026, 9, 6), s.start)
        assertFalse(s.isCurrent)                                        // -> the forward arrow is enabled
        assertFalse(s.isPartial)
        assertEquals(7, s.elapsedDays)
        assertEquals(30 * 60_000L, s.total.totalMs)
    }

    @Test fun onlyTheCurrentWindowDisablesTheForwardArrow() {
        val today = sat
        listOf(Period.Day, Period.Week, Period.Month).forEach { p ->
            assertTrue("$p: viewing today", summarizeUsage(emptyMap(), p, today, today, sun).isCurrent)
            val earlier = PeriodMath.shift(p, today, -1, today)
            assertFalse("$p: one step back", summarizeUsage(emptyMap(), p, earlier, today, sun).isCurrent)
        }
    }

    @Test fun monthCompareUsesTheSameNumberOfDays() {
        val today = LocalDate.of(2026, 9, 10)                           // 10 days into September
        val data = usage(
            LocalDate.of(2026, 9, 3) to 60,
            LocalDate.of(2026, 8, 3) to 30,                             // within the first 10 days of August: counts
            LocalDate.of(2026, 8, 25) to 500,                           // later in August: must not count
        )
        val s = summarizeUsage(data, Period.Month, today, today, sun)
        assertEquals(10, s.elapsedDays)
        assertEquals(30, s.daily.size)
        assertEquals(30 * 60_000L, s.previousTotalMs)
    }

    // --- labels ----------------------------------------------------------------------------------

    private fun label(p: Period, anchor: LocalDate, today: LocalDate = sat): String {
        val start = PeriodMath.start(p, anchor, sun)
        return formatPeriodLabel(p, start, PeriodMath.end(p, start), today, Locale.US)
    }

    @Test fun labelsReadNaturally() {
        assertEquals("Saturday, Sep 19", label(Period.Day, sat))
        assertEquals("Sep 13 – Sep 19", label(Period.Week, sat))
        assertEquals("September 2026", label(Period.Month, sat))
    }

    @Test fun labelsAddTheYearWhenItIsNotThisYear() {
        val jan2027 = LocalDate.of(2027, 1, 20)
        assertEquals("Friday, Dec 25, 2026", label(Period.Day, LocalDate.of(2026, 12, 25), jan2027))
        assertEquals("Dec 6 – Dec 12, 2026", label(Period.Week, LocalDate.of(2026, 12, 9), jan2027))
        assertEquals("Dec 27, 2026 – Jan 2, 2027", label(Period.Week, LocalDate.of(2026, 12, 30), jan2027))
        assertEquals("December 2026", label(Period.Month, LocalDate.of(2026, 12, 9), jan2027))
    }

    @Test fun perDayHoursAreCarriedForEveryDayAndAddUpAcrossApps() {
        val day = LocalDate.of(2026, 9, 16)
        val evening = MutableList(24) { 0L }.also { it[21] = 20 * 60_000L }
        val data: UsageDays = mapOf(
            day.toString() to mapOf(
                "instagram" to AppDay(totalMs = 20 * 60_000L, hourMs = evening),
                "tiktok" to AppDay(totalMs = 20 * 60_000L, hourMs = evening),
            ),
        )
        val s = summarizeUsage(data, Period.Week, day, day, sun)
        assertEquals(7, s.dailyHours.size)
        assertEquals(s.daily.size, s.dailyHours.size)
        assertEquals(40 * 60_000L, s.dailyHours[3][21])              // Wed is the 4th day of a Sunday-start week
        assertEquals(0L, s.dailyHours[0].sum())                      // a day with no data is all zeros
    }
}
