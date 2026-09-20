package com.emilyhsu.innercircle.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsTest {
    private val m = 60_000L
    private fun hours(vararg pairs: Pair<Int, Long>) = MutableList(24) { 0L }.also { pairs.forEach { (h, ms) -> it[h] = ms } }

    @Test fun neighbouringHoursMergeIntoOneWindow() {
        val w = usageWindows(hours(20 to 35 * m, 21 to 50 * m, 22 to 5 * m))
        assertEquals(listOf(UsageWindow(20, 23, 90 * m, peakHour = 21)), w)
    }

    @Test fun separateStretchesStaySeparateAndInOrder() {
        val w = usageWindows(hours(8 to 30 * m, 21 to 20 * m, 22 to 20 * m))
        assertEquals(listOf(UsageWindow(8, 9, 30 * m, 8), UsageWindow(21, 23, 40 * m, 21)), w)
    }

    @Test fun strayMinutesDoNotStretchAWindow() {
        assertEquals(emptyList<UsageWindow>(), usageWindows(hours(3 to 2 * m, 4 to 59_000)))
        // Two real hours with a 2-minute hour between them stay two windows, not one long one.
        assertEquals(2, usageWindows(hours(19 to 20 * m, 20 to 2 * m, 21 to 30 * m)).size)
    }

    @Test fun aWindowCanRunToMidnight() {
        assertEquals(listOf(UsageWindow(22, 24, 30 * m, 23)), usageWindows(hours(22 to 10 * m, 23 to 20 * m)))
    }

    @Test fun hourRangesReadLikeTheExamplesInThePrompt() {
        assertEquals("8–9am", formatHourRange(8, 9))
        assertEquals("9–10pm", formatHourRange(21, 22))
        assertEquals("9–11pm", formatHourRange(21, 23))
        assertEquals("11am–1pm", formatHourRange(11, 13))
        assertEquals("11am–12pm", formatHourRange(11, 12))
        assertEquals("12–1pm", formatHourRange(12, 13))
        assertEquals("12–1am", formatHourRange(0, 1))
        assertEquals("11pm–12am", formatHourRange(23, 24))
        assertEquals("10pm–12am", formatHourRange(22, 24))
        // A range that spans noon or runs to midnight from the morning must keep both labels.
        assertEquals("6am–12am", formatHourRange(6, 24))
        assertEquals("11am–12am", formatHourRange(11, 24))
        assertEquals("10am–12pm", formatHourRange(10, 12))
        assertEquals("12am–12am", formatHourRange(0, 24))
        assertEquals("7–9am", formatHourRange(7, 9))
    }
}
