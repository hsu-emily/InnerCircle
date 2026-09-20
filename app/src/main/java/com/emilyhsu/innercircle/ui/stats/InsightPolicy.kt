package com.emilyhsu.innercircle.ui.stats

import com.emilyhsu.innercircle.data.Insight
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile

/** New use since a saved insight was written before the current day/week/month is worth writing again. */
internal const val REFRESH_AFTER_MS = 15 * 60_000L

/**
 * Whether opening this window should ask the AI for a new summary. Only days have one. Each request
 * counts against a small daily allowance, so it only says yes when the text on screen would be wrong
 * or missing.
 */
internal fun needsInsight(cached: Insight?, target: PeriodSummary, profile: Profile): Boolean {
    if (target.period != Period.Day) return false // week and month views don't show a summary
    if (!target.hasData) return false // nothing to write about
    if (cached == null) return true
    // A finished window can't change, and rewriting it each time you page back would burn the allowance.
    if (!target.isCurrent) return false
    if (cached.basedOnProfile != profile.hashCode()) return true // goals or target were edited since
    return target.total.totalMs - cached.basedOnTotalMs >= REFRESH_AFTER_MS
}
