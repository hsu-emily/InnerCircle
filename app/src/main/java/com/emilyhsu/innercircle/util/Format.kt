package com.emilyhsu.innercircle.util

import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodMath
import com.emilyhsu.innercircle.data.PeriodSummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 5_400_000 -> "1h 30m", 45_000 -> "<1m", 0 -> "0m". */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    if (totalMinutes == 0L) return if (ms > 0) "<1m" else "0m"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** 0 -> "12am", 13 -> "1pm". */
fun formatHour(hour: Int): String = when {
    hour == 0 -> "12am"
    hour < 12 -> "${hour}am"
    hour == 12 -> "12pm"
    else -> "${hour - 12}pm"
}

/** 0.35 -> "35 cm", 12.4 -> "12.4 m", 1500 -> "1.5 km". */
fun formatMeters(meters: Double): String = when {
    meters < 1.0 -> "${(meters * 100).toInt()} cm"
    meters < 1000.0 -> String.format(Locale.US, "%.1f m", meters)
    else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
}

/**
 * What the date bar shows: "Saturday, Sep 19" for a day, "Sep 13 – Sep 19" for a week, and
 * "September 2026" for a month. The year is added when it isn't the current one.
 */
fun formatPeriodLabel(
    period: Period,
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
    locale: Locale = Locale.getDefault(),
): String {
    fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, locale)
    return when (period) {
        Period.Day -> fmt(if (start.year == today.year) "EEEE, MMM d" else "EEEE, MMM d, yyyy").format(start)
        Period.Month -> fmt("MMMM yyyy").format(start)
        Period.Week -> when {
            start.year != end.year -> "${fmt("MMM d, yyyy").format(start)} – ${fmt("MMM d, yyyy").format(end)}"
            start.year != today.year -> "${fmt("MMM d").format(start)} – ${fmt("MMM d, yyyy").format(end)}"
            else -> "${fmt("MMM d").format(start)} – ${fmt("MMM d").format(end)}"
        }
    }
}

// --- comparison box ------------------------------------------------------------------------------

enum class Direction { Up, Down, Flat }

/** How a number changed against the comparison period. [text] has no arrow; the UI picks one from [direction]. */
data class Change(val text: String, val direction: Direction)

/**
 * [current] against [previous]. With [asShareOfPrevious] (a day that isn't over yet) the result is how much
 * of the previous total has been reached so far, because a half-finished day can't fairly be called
 * "down 60%" against a whole one.
 */
fun changeBetween(current: Double, previous: Double, asShareOfPrevious: Boolean = false): Change = when {
    asShareOfPrevious ->
        if (previous <= 0.0) Change("–", Direction.Flat) else Change("${(current / previous * 100).roundToInt()}%", Direction.Flat)
    current <= 0.0 && previous <= 0.0 -> Change("–", Direction.Flat)
    previous <= 0.0 -> Change("New", Direction.Up)
    else -> {
        val pct = ((current - previous) / previous * 100).roundToInt()
        when {
            pct > 0 -> Change("$pct%", Direction.Up)
            pct < 0 -> Change("${abs(pct)}%", Direction.Down)
            else -> Change("0%", Direction.Flat)
        }
    }
}

/** Column headings and the title for the comparison box. [note] explains a like-for-like comparison. */
data class ComparisonLabels(val title: String, val current: String, val previous: String, val note: String?)

fun comparisonLabels(summary: PeriodSummary, today: LocalDate, locale: Locale = Locale.getDefault()): ComparisonLabels {
    fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, locale)
    val prevStart = PeriodMath.previousStart(summary.period, summary.start)
    val prevEnd = PeriodMath.end(summary.period, prevStart)

    fun shortRange(a: LocalDate, b: LocalDate): String =
        if (a.year == b.year && a.month == b.month) "${fmt("MMM d").format(a)}–${b.dayOfMonth}"
        else "${fmt("MMM d").format(a)} – ${fmt("MMM d").format(b)}"
    fun monthName(d: LocalDate) = fmt(if (d.year == today.year) "MMMM" else "MMM yyyy").format(d)

    if (summary.isCurrent) {
        val (cur, prev) = when (summary.period) {
            Period.Day -> "Today" to "Yesterday"
            Period.Week -> "This week" to "Last week"
            Period.Month -> "This month" to "Last month"
        }
        val note = when {
            summary.period == Period.Day ->
                "Today isn't over yet, so the change shows how much of yesterday's total you've reached so far."
            summary.isPartial ->
                "Comparing the first ${summary.elapsedDays} days of each ${summary.period.label.lowercase()}."
            else -> null
        }
        return ComparisonLabels("$cur vs ${prev.lowercase()}", cur, prev, note)
    }
    val (cur, prev) = when (summary.period) {
        Period.Day -> fmt("MMM d").format(summary.start) to fmt("MMM d").format(prevStart)
        Period.Week -> shortRange(summary.start, summary.end) to shortRange(prevStart, prevEnd)
        Period.Month -> monthName(summary.start) to monthName(prevStart)
    }
    return ComparisonLabels("$cur vs $prev", cur, prev, null)
}

// --- time-of-day windows (what the AI reads instead of exact session times) --------------------

/**
 * A run of consecutive hours in which the app was used, e.g. hours 21 and 22 -> 9pm to 11pm.
 * [endHour] is exclusive (24 = midnight). [peakHour] is the busiest hour inside it.
 */
data class UsageWindow(val startHour: Int, val endHour: Int, val ms: Long, val peakHour: Int)

/**
 * Merges neighbouring hours that each have at least [minMs] of use into windows. Hourly totals can't say
 * whether a window was one long session or several short ones, so the peak hour is kept to show where
 * the use concentrated, and stray minutes are ignored rather than stretching a window.
 */
fun usageWindows(hourMs: List<Long>, minMs: Long = 3 * 60_000): List<UsageWindow> {
    val windows = mutableListOf<UsageWindow>()
    var hour = 0
    while (hour < hourMs.size) {
        if (hourMs[hour] < minMs) { hour++; continue }
        val start = hour
        var total = 0L
        var peak = hour
        while (hour < hourMs.size && hourMs[hour] >= minMs) {
            total += hourMs[hour]
            if (hourMs[hour] > hourMs[peak]) peak = hour
            hour++
        }
        windows += UsageWindow(start, hour, total, peak)
    }
    return windows
}

/**
 * 8..9 -> "8–9am", 21..23 -> "9–11pm", 11..13 -> "11am–1pm", 23..24 -> "11pm–12am", 6..24 -> "6am–12am".
 * The shared am/pm is dropped only when the whole range sits inside one half of the day; otherwise
 * "11–12am" would read as the last hour before midnight when it means 11 in the morning to midnight.
 */
fun formatHourRange(startHour: Int, endHour: Int): String {
    fun clock(h: Int) = if (h % 12 == 0) 12 else h % 12
    fun suffix(h: Int) = if (h % 24 < 12) "am" else "pm"
    val end = endHour % 24
    val sameHalfOfDay = startHour / 12 == (endHour - 1) / 12
    return if (sameHalfOfDay && suffix(startHour) == suffix(end)) "${clock(startHour)}–${clock(end)}${suffix(end)}"
    else "${clock(startHour)}${suffix(startHour)}–${clock(end)}${suffix(end)}"
}
