package com.emilyhsu.innercircle.ui.stats

import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Insight
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightPolicyTest {
    private val profile = Profile(completed = true, goals = setOf("Sleep better"), dailyTargetMinutes = 60)
    private val day = LocalDate.of(2026, 9, 19)

    private fun summary(totalMin: Long, isCurrent: Boolean, period: Period = Period.Day) = PeriodSummary(
        period = period, start = day, end = day,
        total = AppDay(totalMs = totalMin * 60_000L),
        previousTotalMs = 0, perApp = emptyMap(),
        daily = listOf(day to totalMin * 60_000L), isCurrent = isCurrent,
    )

    private fun insight(basedOnMin: Long, basedOnProfile: Int = profile.hashCode()) = Insight(
        period = Period.Day, summary = "s", recommendations = emptyList(), generatedAtMs = 0,
        basedOnTotalMs = basedOnMin * 60_000L, basedOnProfile = basedOnProfile,
    )

    @Test fun emptyWindowNeverCallsTheModel() {
        assertFalse(needsInsight(null, summary(0, isCurrent = true), profile))
        assertFalse(needsInsight(null, summary(0, isCurrent = false), profile))
    }

    @Test fun windowWithDataAndNoSavedInsightGetsOne() {
        assertTrue(needsInsight(null, summary(30, isCurrent = true), profile))
        assertTrue(needsInsight(null, summary(30, isCurrent = false), profile))
    }

    @Test fun finishedWindowKeepsItsSavedInsightEvenIfProfileChanged() {
        val old = insight(basedOnMin = 30, basedOnProfile = 12345)
        assertFalse(needsInsight(old, summary(30, isCurrent = false), profile))
    }

    @Test fun currentWindowIsLeftAloneUntilEnoughNewUse() {
        val saved = insight(basedOnMin = 30)
        assertFalse(needsInsight(saved, summary(30, isCurrent = true), profile))
        assertFalse(needsInsight(saved, summary(44, isCurrent = true), profile))
        assertTrue(needsInsight(saved, summary(45, isCurrent = true), profile))
    }

    @Test fun editingTheSurveyRefreshesTheCurrentWindow() {
        val saved = insight(basedOnMin = 30)
        val edited = profile.copy(dailyTargetMinutes = 30)
        assertTrue(needsInsight(saved, summary(30, isCurrent = true), edited))
    }

    @Test fun weekAndMonthNeverGetASummary() {
        assertFalse(needsInsight(null, summary(300, isCurrent = true, period = Period.Week), profile))
        assertFalse(needsInsight(null, summary(300, isCurrent = false, period = Period.Month), profile))
    }
}
