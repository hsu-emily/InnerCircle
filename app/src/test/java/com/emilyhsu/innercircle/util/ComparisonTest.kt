package com.emilyhsu.innercircle.util

import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.UsageDays
import com.emilyhsu.innercircle.data.summarizeUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class ComparisonTest {
    private val sat = LocalDate.of(2026, 9, 19)
    private val sun = DayOfWeek.SUNDAY

    @Test fun percentChangeAndDirection() {
        assertEquals(Change("25%", Direction.Up), changeBetween(125.0, 100.0))
        assertEquals(Change("40%", Direction.Down), changeBetween(60.0, 100.0))
        assertEquals(Change("0%", Direction.Flat), changeBetween(100.0, 100.0))
        assertEquals(Change("New", Direction.Up), changeBetween(5.0, 0.0))
        assertEquals(Change("–", Direction.Flat), changeBetween(0.0, 0.0))
        assertEquals(Change("100%", Direction.Down), changeBetween(0.0, 50.0))     // dropped to nothing
    }

    @Test fun aDayInProgressIsShownAsAShareOfTheWholeEarlierDay() {
        assertEquals(Change("30%", Direction.Flat), changeBetween(30.0, 100.0, asShareOfPrevious = true))
        assertEquals(Change("–", Direction.Flat), changeBetween(30.0, 0.0, asShareOfPrevious = true))
    }

    private fun day(min: Long, stories: Int, posts: Int, px: Double) =
        mapOf("instagram" to AppDay(totalMs = min * 60_000, stories = stories, posts = posts, scrollPx = px))

    @Test fun previousPeriodCarriesEveryStatNotJustTime() {
        val data: UsageDays = mapOf(
            "2026-09-19" to day(50, 10, 40, 8_000.0),
            "2026-09-18" to day(90, 30, 120, 40_000.0),
        )
        val s = summarizeUsage(data, Period.Day, sat, sat, sun)
        assertEquals(50 * 60_000L, s.total.totalMs)
        assertEquals(90 * 60_000L, s.previous.totalMs)
        assertEquals(30, s.previous.stories)
        assertEquals(120, s.previous.posts)
        assertEquals(40_000.0, s.previous.scrollPx, 0.0)
    }

    @Test fun weekComparisonIsLikeForLikeForEveryStat() {
        val wed = LocalDate.of(2026, 9, 16)
        val data: UsageDays = mapOf(
            "2026-09-13" to day(10, 1, 5, 100.0), "2026-09-16" to day(10, 1, 5, 100.0),      // this week: 2 days recorded
            "2026-09-06" to day(20, 2, 8, 200.0), "2026-09-09" to day(20, 2, 8, 200.0),      // first 4 days of last week
            "2026-09-11" to day(999, 99, 99, 9e6),                                           // later last week: must not count
        )
        val s = summarizeUsage(data, Period.Week, wed, wed, sun)
        assertEquals(40 * 60_000L, s.previous.totalMs)
        assertEquals(4, s.previous.stories)
        assertEquals(16, s.previous.posts)
        assertEquals(400.0, s.previous.scrollPx, 0.0)
    }

    private fun labels(p: Period, anchor: LocalDate, today: LocalDate = sat): ComparisonLabels {
        val s = summarizeUsage(emptyMap(), p, anchor, today, sun)
        return comparisonLabels(s, today, Locale.US)
    }

    @Test fun titlesReadTodayVsYesterdayAndSoOn() {
        assertEquals("Today vs yesterday", labels(Period.Day, sat).title)
        assertEquals("This week vs last week", labels(Period.Week, sat).title)
        assertEquals("This month vs last month", labels(Period.Month, sat).title)
    }

    @Test fun notesExplainWhenTheComparisonIsNotAWholeAgainstAWhole() {
        assertEquals(
            "Today isn't over yet, so the change shows how much of yesterday's total you've reached so far.",
            labels(Period.Day, sat).note,
        )
        assertEquals("Comparing the first 3 days of each week.", labels(Period.Week, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15)).note)
        assertEquals("Comparing the first 10 days of each month.", labels(Period.Month, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10)).note)
        assertNull("a finished week needs no note", labels(Period.Week, LocalDate.of(2026, 9, 8)).note)
    }

    @Test fun pastPeriodsAreNamedByTheirDates() {
        assertEquals("Sep 12 vs Sep 11", labels(Period.Day, LocalDate.of(2026, 9, 12)).title)
        assertEquals("Sep 6–12 vs Aug 30 – Sep 5", labels(Period.Week, LocalDate.of(2026, 9, 8)).title)
        assertEquals("August vs July", labels(Period.Month, LocalDate.of(2026, 8, 10)).title)
    }
}
