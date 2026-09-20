package com.emilyhsu.innercircle.util

import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.UsageDays
import com.emilyhsu.innercircle.data.summarizeUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

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

    @Test fun dayReadsTodayVsYesterday() {
        val l = comparisonLabels(Period.Day)
        assertEquals("Today vs Yesterday", l.title)
        assertEquals("Today", l.current)
        assertEquals("Yesterday", l.previous)
    }

    @Test fun weekReadsThisWeekVsLastWeek() {
        val l = comparisonLabels(Period.Week)
        assertEquals("This week vs Last week", l.title)
        assertEquals("This week", l.current)
        assertEquals("Last week", l.previous)
    }

    @Test fun monthReadsThisMonthVsLastMonth() {
        val l = comparisonLabels(Period.Month)
        assertEquals("This month vs Last month", l.title)
        assertEquals("This month", l.current)
        assertEquals("Last month", l.previous)
    }
}
