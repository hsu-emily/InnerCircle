package com.emilyhsu.innercircle.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Calendar arithmetic for the Stats screen's day / week / month windows. Pure, so it's easy to test. */
object PeriodMath {

    fun start(period: Period, anchor: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (period) {
        Period.Day -> anchor
        Period.Week -> anchor.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        Period.Month -> anchor.withDayOfMonth(1)
    }

    fun end(period: Period, start: LocalDate): LocalDate = when (period) {
        Period.Day -> start
        Period.Week -> start.plusDays(6)
        Period.Month -> start.withDayOfMonth(start.lengthOfMonth())
    }

    fun length(start: LocalDate, end: LocalDate): Int = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    /** How many days of the window have happened by [today] (0 if it hasn't started). */
    fun elapsedDays(start: LocalDate, end: LocalDate, today: LocalDate): Int =
        if (today.isBefore(start)) 0 else length(start, minOf(end, today))

    fun previousStart(period: Period, start: LocalDate): LocalDate = when (period) {
        Period.Day -> start.minusDays(1)
        Period.Week -> start.minusWeeks(1)
        Period.Month -> start.minusMonths(1)
    }

    /** Moves [anchor] one window back (-1) or forward (+1), never past [today]. */
    fun shift(period: Period, anchor: LocalDate, steps: Int, today: LocalDate): LocalDate {
        val moved = when (period) {
            Period.Day -> anchor.plusDays(steps.toLong())
            Period.Week -> anchor.plusWeeks(steps.toLong())
            Period.Month -> anchor.plusMonths(steps.toLong())
        }
        return minOf(moved, today)
    }
}

/**
 * Aggregates [data] into the window of [period] containing [anchor]. The comparison figure is the
 * previous window over the same number of days, so the current, unfinished week or month is
 * compared like for like.
 */
fun summarizeUsage(
    data: UsageDays,
    period: Period,
    anchor: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
): PeriodSummary {
    val start = PeriodMath.start(period, anchor, firstDayOfWeek)
    val end = PeriodMath.end(period, start)
    val elapsed = PeriodMath.elapsedDays(start, end, today)

    val perApp = mutableMapOf<SocialApp, AppDay>()
    val daily = mutableListOf<Pair<LocalDate, Long>>()
    val dailyHours = mutableListOf<List<Long>>()
    for (i in 0 until PeriodMath.length(start, end)) {
        val date = start.plusDays(i.toLong())
        val apps = data[date.toString()].orEmpty()
        daily += date to apps.values.sumOf { it.totalMs }
        dailyHours += List(24) { hour -> apps.values.sumOf { it.hourMs[hour] } }
        apps.forEach { (id, day) ->
            val app = SocialApp.fromId(id) ?: return@forEach
            perApp[app] = (perApp[app] ?: AppDay()) + day
        }
    }

    val previousStart = PeriodMath.previousStart(period, start)
    val previousLength = PeriodMath.length(previousStart, PeriodMath.end(period, previousStart))
    var previous = AppDay()
    for (i in 0 until minOf(elapsed, previousLength)) {
        data[previousStart.plusDays(i.toLong()).toString()].orEmpty().values.forEach { previous += it }
    }

    return PeriodSummary(
        period = period,
        start = start,
        end = end,
        total = perApp.values.fold(AppDay()) { a, b -> a + b },
        previousTotalMs = previous.totalMs,
        perApp = perApp,
        daily = daily,
        elapsedDays = elapsed,
        isCurrent = !today.isBefore(start) && !today.isAfter(end),
        previous = previous,
        dailyHours = dailyHours,
    )
}
