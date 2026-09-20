package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import com.emilyhsu.innercircle.util.UsageWindow
import com.emilyhsu.innercircle.util.formatDuration
import com.emilyhsu.innercircle.util.formatHour
import com.emilyhsu.innercircle.util.formatHourRange
import com.emilyhsu.innercircle.util.formatMeters
import com.emilyhsu.innercircle.util.usageWindows
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes up what the model gets to see. Only the survey answers and aggregate usage numbers go in:
 * no account names, post content, or anything else from inside the apps. The instructions that
 * tell the model how to answer live on the server, not here, so they can't be tampered with.
 */
object InsightPrompt {

    fun user(profile: Profile, summary: PeriodSummary): String = buildString {
        appendLine("USER PROFILE (from their survey)")
        appendLine("- Goals: ${profile.goals.joinOrNone()}")
        appendLine("- Typical social media time per day before InnerCircle: ${
            if (profile.typicalDailyMinutes > 0) "about ${formatDuration(profile.typicalDailyMinutes * 60_000L)}" else "not given"
        }")
        appendLine("- Hardest times to put the phone down: ${profile.hardTimes.joinOrNone()}")
        appendLine("- What usually pulls them in: ${profile.triggers.joinOrNone()}")
        appendLine("- Their daily target: ${formatDuration(profile.dailyTargetMinutes * 60_000L)}")
        if (profile.notes.isNotBlank()) appendLine("- In their own words: ${profile.notes.trim().take(400)}")
        appendLine()

        val dates = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val name = periodName(summary.period)
        val lastDay = summary.start.plusDays(maxOf(summary.elapsedDays, 1) - 1L)
        // The current week or month is unfinished: say so, or the model will read a low total as a good week.
        val dayInProgress = summary.period == Period.Day && summary.isCurrent
        val heading = if (summary.isPartial || dayInProgress) "$name so far" else name
        appendLine("USAGE, $heading (${dates.format(summary.start)} to ${dates.format(lastDay)}), measured only inside InnerCircle")
        if (!summary.hasData) {
            appendLine("- No usage has been recorded in this period yet.")
            return@buildString
        }
        val total = summary.total.totalMs
        val delta = when {
            // A half-finished day can't be fairly compared with a whole one, so don't hand the model a percentage.
            dayInProgress && summary.previousTotalMs > 0 ->
                " (yesterday's full total was ${formatDuration(summary.previousTotalMs)}; today is not over yet)"
            summary.previousTotalMs > 0 -> {
                val pct = ((total - summary.previousTotalMs) * 100.0 / summary.previousTotalMs).toInt()
                val vs = if (summary.isPartial) "same days last $name" else "previous $name"
                " ($vs: ${formatDuration(summary.previousTotalMs)}, ${if (pct >= 0) "+" else ""}$pct%)"
            }
            else -> " (no data for the previous $name)"
        }
        appendLine("- Total time: ${formatDuration(total)}$delta")

        val days = maxOf(summary.elapsedDays, 1)
        val avgPerDay = total / days
        val target = profile.dailyTargetMinutes * 60_000L
        // Say plainly which side of the target they are on: models get this wrong when left to compare two numbers.
        val gap = avgPerDay - target
        val againstTarget = when {
            gap > 0 -> "OVER their daily target of ${formatDuration(target)} by ${formatDuration(gap)}"
            gap < 0 -> "UNDER their daily target of ${formatDuration(target)} by ${formatDuration(-gap)}"
            else -> "exactly at their daily target of ${formatDuration(target)}"
        }
        val averageLabel = if (summary.period == Period.Day) "time so far" else "average per day"
        appendLine("- Days with any use: ${summary.activeDays} of $days; $averageLabel: ${formatDuration(avgPerDay)}, which is $againstTarget")
        if (summary.period != Period.Day) {
            val over = summary.daily.count { it.second > target }
            appendLine("- Days over the daily target: $over of $days")
        }

        summary.perApp.forEach { (app, day) ->
            appendLine("- ${app.displayName}: ${formatDuration(day.totalMs)}, ${day.posts} posts seen, ${day.stories} stories viewed, ${formatMeters(day.scrollMeters)} of scrolling")
        }

        val hourTotal = summary.total.hourMs.sum().coerceAtLeast(1)
        val busiest = summary.total.hourMs.withIndex().filter { it.value > 0 }
            .sortedByDescending { it.value }.take(3)
        if (busiest.isNotEmpty()) {
            appendLine("- Busiest hours: " + busiest.joinToString { "${formatHour(it.index)} (${it.value * 100 / hourTotal}%)" })
        }
        val lateNight = (22..23).plus(0..4).sumOf { summary.total.hourMs[it] }
        appendLine("- Share of time between 10pm and 5am: ${lateNight * 100 / hourTotal}%")

        if (summary.period != Period.Day) {
            val dayFmt = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)
            appendLine("- Time by day: " + summary.daily.joinToString { "${dayFmt.format(it.first)} ${formatDuration(it.second)}" })
        }
        appendWindows(summary)
    }

    /**
     * The time-of-day windows in which the app was used each day, in the shape of the prompt's own example
     * ("Mon: 8-9am, 9-10pm"). Capped so a busy month can't overflow the server's input limit.
     */
    private fun StringBuilder.appendWindows(summary: PeriodSummary) {
        val dayFmt = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)
        val lines = summary.daily.zip(summary.dailyHours).mapNotNull { (day, hours) ->
            val windows = usageWindows(hours)
            if (windows.isEmpty()) return@mapNotNull null
            // Keep the longest few, but list them in time order.
            val shown = windows.sortedByDescending { it.ms }.take(MAX_WINDOWS_PER_DAY).sortedBy { it.startHour }
            val more = if (windows.size > shown.size) ", plus ${windows.size - shown.size} shorter" else ""
            "  ${dayFmt.format(day.first)}: " + shown.joinToString(", ") { windowText(it) } + more
        }
        if (lines.isEmpty()) return
        appendLine("- Time-of-day windows of use, by day (consecutive hours with 3+ minutes of use; a window can hold several short visits):")
        var budget = WINDOW_SECTION_CHARS
        for (line in lines) {
            if (line.length + 1 > budget) { appendLine("  (remaining days omitted for length)"); break }
            appendLine(line)
            budget -= line.length + 1
        }
    }

    /** "6–11pm (1h 31m, peak 8–9pm)": the peak is only shown when the window spans more than one hour. */
    private fun windowText(w: UsageWindow): String {
        val peak = if (w.endHour - w.startHour > 1) ", peak ${formatHourRange(w.peakHour, w.peakHour + 1)}" else ""
        return "${formatHourRange(w.startHour, w.endHour)} (${formatDuration(w.ms)}$peak)"
    }

    private const val MAX_WINDOWS_PER_DAY = 6
    private const val WINDOW_SECTION_CHARS = 2_400

    private fun Set<String>.joinOrNone() = if (isEmpty()) "not given" else joinToString("; ")

    private fun periodName(p: Period) = when (p) {
        Period.Day -> "day"
        Period.Week -> "week"
        Period.Month -> "month"
    }
}
