package com.emilyhsu.innercircle.data

import java.time.LocalDate

/** A social platform InnerCircle can wrap. Only [enabled] ones can be opened today. */
enum class SocialApp(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val startUrl: String? = null,
) {
    Instagram("instagram", "Instagram", true, "https://www.instagram.com/"),
    // The subscriptions route is intentional: it is the user-chosen alternative to Home's
    // recommendation feed. It asks the person to sign in if they have no YouTube session yet.
    YouTube("youtube", "YouTube", true, "https://m.youtube.com/feed/subscriptions?app=m&persist_app=1"),
    // Straight to the Following feed: "/" is the For You algorithm (see TikTokSelectors).
    TikTok("tiktok", "TikTok", true, "https://www.tiktok.com/following"),
    Facebook("facebook", "Facebook", false),
    LinkedIn("linkedin", "LinkedIn", false);

    companion object {
        fun fromId(id: String?): SocialApp? = entries.firstOrNull { it.id == id }
    }
}

/** How the user gets from a connected app back to InnerCircle. Chosen in Settings. */
enum class ExitMethod(val label: String, val description: String) {
    Button("Floating button", "A small InnerCircle button you can drag anywhere. Tap it to go back."),
    Swipe("Swipe right", "Swipe right from the left side of the screen to go back."),
    Both("Both", "Use the floating button or the swipe, whichever is handier.");

    val showsButton get() = this == Button || this == Both
    val allowsSwipe get() = this == Swipe || this == Both
}

/** What the user told us in the first-run survey. */
data class Profile(
    val completed: Boolean = false,
    val goals: Set<String> = emptySet(),
    val typicalDailyMinutes: Int = 0,
    val hardTimes: Set<String> = emptySet(),
    val triggers: Set<String> = emptySet(),
    val dailyTargetMinutes: Int = 60,
    val notes: String = "",
)

/** Everything measured for one app on one calendar day. */
data class AppDay(
    val totalMs: Long = 0,
    /** Milliseconds spent in each hour of the day, index 0 = 12am. */
    val hourMs: List<Long> = List(24) { 0L },
    val stories: Int = 0,
    val posts: Int = 0,
    /** Feed scroll distance in CSS pixels (1 px = 1/160 inch on Android). */
    val scrollPx: Double = 0.0,
) {
    operator fun plus(o: AppDay) = AppDay(
        totalMs + o.totalMs,
        List(24) { hourMs[it] + o.hourMs[it] },
        stories + o.stories,
        posts + o.posts,
        scrollPx + o.scrollPx,
    )

    val scrollMeters: Double get() = scrollPx / 160.0 * 0.0254
}

/** A calendar window you can step through: one day, one week, or one month. */
enum class Period(val label: String) { Day("Day"), Week("Week"), Month("Month") }

/**
 * One calendar window of usage (a day, a week, or a month), already aggregated for display and for
 * the AI prompt. [start]..[end] is the whole window, which may run into the future for the current
 * week or month; [elapsedDays] is how much of it has actually happened.
 */
data class PeriodSummary(
    val period: Period,
    val start: LocalDate,
    val end: LocalDate,
    val total: AppDay,
    /** The previous window over the same number of days, so a half-finished week isn't compared with a whole one. */
    val previousTotalMs: Long,
    val perApp: Map<SocialApp, AppDay>,
    /** One entry per day in the whole window, oldest first. Days that haven't happened yet are 0. */
    val daily: List<Pair<LocalDate, Long>>,
    val elapsedDays: Int = daily.size,
    /** True when today falls inside this window: there is nothing later to navigate to. */
    val isCurrent: Boolean = false,
    /** Everything measured in the comparison window (same days as [previousTotalMs]). */
    val previous: AppDay = AppDay(totalMs = previousTotalMs),
    /** Milliseconds per hour of the day (index 0 = 12am) for each day in [daily], all apps together. */
    val dailyHours: List<List<Long>> = emptyList(),
) {
    val hasData: Boolean get() = total.totalMs > 0 || total.posts > 0 || total.stories > 0
    val activeDays: Int get() = daily.count { it.second > 0 }

    /** The current week or month, part-way through. */
    val isPartial: Boolean get() = isCurrent && elapsedDays < daily.size
}

data class Insight(
    val period: Period,
    val summary: String,
    val recommendations: List<Recommendation>,
    val generatedAtMs: Long,
    /** Usage total and survey answers the text was written from, so a stale one can be spotted later. */
    val basedOnTotalMs: Long = 0,
    val basedOnProfile: Int = 0,
)

/** One tailored suggestion, as a sentence or two. */
data class Recommendation(val text: String)
