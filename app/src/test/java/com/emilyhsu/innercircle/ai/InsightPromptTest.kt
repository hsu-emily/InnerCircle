package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import com.emilyhsu.innercircle.data.SocialApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightPromptTest {
    private val profile = Profile(
        completed = true,
        goals = setOf("Cut down on doom scrolling", "Sleep better and stop late-night scrolling"),
        typicalDailyMinutes = 180,
        hardTimes = setOf("Late at night"),
        triggers = setOf("Boredom"),
        dailyTargetMinutes = 60,
        notes = "I want to stop scrolling in bed",
    )

    /** 4h30m total: 3h at 11pm, 1h at midnight, 30m at 8pm; 3 of 7 days over a 1h target. */
    private fun weekSummary(): PeriodSummary {
        val hours = MutableList(24) { 0L }.also {
            it[23] = 3 * 3_600_000L; it[0] = 3_600_000L; it[20] = 1_800_000L
        }
        val ig = AppDay(totalMs = hours.sum(), hourMs = hours, stories = 40, posts = 210, scrollPx = 160_000.0)
        val end = LocalDate.of(2026, 9, 19)
        val minutes = listOf(30L, 45L, 95L, 20L, 110L, 25L, 80L)
        return PeriodSummary(
            period = Period.Week,
            start = end.minusDays(6),
            end = end,
            total = ig,
            previousTotalMs = 7 * 3_600_000L,
            perApp = mapOf(SocialApp.Instagram to ig),
            daily = minutes.mapIndexed { i, m -> end.minusDays(6L - i) to m * 60_000L },
        )
    }

    @Test fun promptCarriesGoalsAndTheRealNumbers() {
        val text = InsightPrompt.user(profile, weekSummary())
        println(text)
        assertTrue("goals", "Cut down on doom scrolling" in text && "Sleep better" in text)
        assertTrue("target", "Their daily target: 1h" in text)
        assertTrue("own words", "stop scrolling in bed" in text)
        assertTrue("per app numbers", "Instagram: 4h 30m, 210 posts seen, 40 stories viewed" in text)
        assertTrue("comparison with previous week", "previous week: 7h" in text)
        assertTrue("busiest hours", "Busiest hours: 11pm" in text)
        assertTrue("late night share", "Share of time between 10pm and 5am: 88%" in text)
        assertTrue("days over target", "Days over the daily target: 3 of 7" in text)
        assertTrue("scroll distance", "of scrolling" in text)
    }

    @Test fun emptyPeriodSaysSoInsteadOfInventingData() {
        val empty = PeriodSummary(Period.Day, LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), AppDay(), 0, emptyMap(), listOf(LocalDate.of(2026, 9, 19) to 0L))
        val text = InsightPrompt.user(profile, empty)
        assertTrue("No usage has been recorded in this period yet." in text)
        assertFalse("Total time" in text)
    }

    @Test fun promptNeverContainsAccountOrContentFields() {
        val text = InsightPrompt.user(profile, weekSummary()).lowercase()
        listOf("@", "username", "http", "caption").forEach { assertFalse("leaks '$it'", it in text) }
    }

    // --- time-of-day windows ----------------------------------------------------------------------

    private fun windowSummary(days: List<Pair<LocalDate, List<Long>>>): PeriodSummary {
        val ig = AppDay(totalMs = 3_600_000, hourMs = days.first().second, posts = 5)
        return PeriodSummary(
            period = Period.Week, start = days.first().first, end = days.last().first,
            total = ig, previousTotalMs = 0, perApp = mapOf(SocialApp.Instagram to ig),
            daily = days.map { it.first to it.second.sum() }, dailyHours = days.map { it.second },
        )
    }

    private fun hours(vararg pairs: Pair<Int, Long>) = MutableList(24) { 0L }.also { pairs.forEach { (h, ms) -> it[h] = ms } }
    private val m = 60_000L

    @Test fun listsEachDaysWindowsInTheShapeOfThePromptsOwnExample() {
        val mon = LocalDate.of(2026, 9, 14) // a Monday
        val text = InsightPrompt.user(profile, windowSummary(listOf(mon to hours(8 to 30 * m, 20 to 35 * m, 21 to 50 * m))))
        assertTrue("Time-of-day windows of use, by day" in text)
        assertTrue("Mon 9/14: 8–9am (30m), 8–10pm (1h 25m, peak 9–10pm)" in text)
    }

    @Test fun daysWithNoUseAreLeftOut() {
        val mon = LocalDate.of(2026, 9, 14)
        val text = InsightPrompt.user(profile, windowSummary(listOf(mon to hours(9 to 20 * m), mon.plusDays(1) to hours())))
        assertTrue("Mon 9/14:" in text)
        assertFalse("Tue 9/15:" in text.substringAfter("Time-of-day windows"))
    }

    @Test fun aBusyDayShowsTheLongestWindowsAndCountsTheRest() {
        val mon = LocalDate.of(2026, 9, 14)
        val busy = hours(*(0..22 step 2).map { it to (it + 4) * m }.toTypedArray())   // 12 separate windows
        val text = InsightPrompt.user(profile, windowSummary(listOf(mon to busy)))
        val line = text.lines().first { it.contains("Mon 9/14:") && it.contains("(") }
        assertEquals("only 6 windows are listed", 6, Regex("""\(\d+m\)|\(\d+h""").findAll(line).count())
        assertTrue(", plus 6 shorter" in line)
    }

    @Test fun aWholeBusyMonthStaysUnderTheServersInputLimit() {
        val first = LocalDate.of(2026, 9, 1)
        val busy = hours(*(0..22 step 2).map { it to (it + 4) * m }.toTypedArray())
        val days = (0 until 31).map { first.plusDays(it.toLong()) to busy }
        val text = InsightPrompt.user(profile.copy(notes = "n".repeat(400)), windowSummary(days).copy(period = Period.Month))
        assertTrue("prompt is ${text.length} chars", text.length < 6_000)
        assertTrue("(remaining days omitted for length)" in text)
    }

    // --- stating the arithmetic instead of leaving it to the model ------------------------------------

    private fun daySummary(totalMin: Long, current: Boolean, previousMin: Long = 50) = PeriodSummary(
        period = Period.Day, start = LocalDate.of(2026, 9, 19), end = LocalDate.of(2026, 9, 19),
        total = AppDay(totalMs = totalMin * 60_000, posts = 3), previousTotalMs = previousMin * 60_000,
        perApp = mapOf(SocialApp.Instagram to AppDay(totalMs = totalMin * 60_000, posts = 3)),
        daily = listOf(LocalDate.of(2026, 9, 19) to totalMin * 60_000), isCurrent = current,
    )

    @Test fun saysPlainlyWhetherTheyAreOverOrUnderTheirTarget() {
        assertTrue("UNDER their daily target of 1h by 8m" in InsightPrompt.user(profile, daySummary(52, current = false)))
        assertTrue("OVER their daily target of 1h by 25m" in InsightPrompt.user(profile, daySummary(85, current = false)))
        assertTrue("exactly at their daily target of 1h" in InsightPrompt.user(profile, daySummary(60, current = false)))
    }

    @Test fun aDayThatIsNotOverIsSaidToBeUnfinishedAndNotGivenAMisleadingPercentage() {
        val text = InsightPrompt.user(profile, daySummary(52, current = true))
        assertTrue("USAGE, day so far" in text)
        assertTrue("yesterday's full total was 50m; today is not over yet" in text)
        assertFalse("no percentage for an unfinished day", "%" in text.substringAfter("Total time").substringBefore("- Days with any use"))
    }

    @Test fun aFinishedDayKeepsTheNormalComparison() {
        val text = InsightPrompt.user(profile, daySummary(52, current = false))
        assertTrue("USAGE, day (" in text)
        assertTrue("previous day: 50m, +4%" in text)
    }
}
