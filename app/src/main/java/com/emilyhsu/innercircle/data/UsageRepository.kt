package com.emilyhsu.innercircle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.random.Random

/** date (yyyy-MM-dd) -> app id -> what was measured that day. */
typealias UsageDays = Map<String, Map<String, AppDay>>

/**
 * Everything InnerCircle has measured. A handful of numbers per app per day is tiny, so it is kept
 * in memory and written through to SharedPreferences as one JSON blob on every change.
 */
class UsageRepository(context: Context) : UsageSink {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val lock = Any()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _days = MutableStateFlow(load())
    val days: StateFlow<UsageDays> = _days.asStateFlow()

    /** Adds the time between [startMs] and [endMs], split across hours (and midnight) correctly. */
    override fun addTime(app: SocialApp, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        mutate { days ->
            TimeSplitter.split(startMs, endMs, zone).forEach { slice ->
                val hours = MutableList(24) { 0L }.also { it[slice.hour] = slice.ms }
                days.add(slice.date, app, AppDay(totalMs = slice.ms, hourMs = hours))
            }
        }
    }

    override fun addCounts(app: SocialApp, stories: Int, posts: Int, scrollPx: Double, atMs: Long) {
        if (stories == 0 && posts == 0 && scrollPx <= 0.0) return
        mutate { days ->
            val date = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            days.add(date, app, AppDay(stories = stories, posts = posts, scrollPx = scrollPx))
        }
    }

    fun clear() = mutate { it.clear() }

    /** Fills the last 30 days with plausible numbers so the Stats screen and the AI have something to show. */
    fun seedDemoData() = mutate { days ->
        days.clear()
        val rng = Random(42)
        val today = LocalDate.now(zone)
        // Evening and late-night heavy, like the habit this app is trying to change.
        val hourWeights = listOf(
            2, 1, 1, 0, 0, 1, 3, 5, 4, 3, 3, 4,
            6, 5, 4, 4, 5, 6, 8, 10, 12, 11, 8, 4,
        )
        for (back in 0 until 30) {
            val minutes = (35 + rng.nextInt(95)) * (if (back % 7 in 5..6) 13 else 10) / 10
            val totalMs = minutes * 60_000L
            val weightSum = hourWeights.sum().toDouble()
            val hours = hourWeights.map { (totalMs * it / weightSum).toLong() }
            days.add(
                today.minusDays(back.toLong()),
                SocialApp.Instagram,
                AppDay(
                    totalMs = hours.sum(),
                    hourMs = hours,
                    stories = 8 + rng.nextInt(40),
                    posts = 20 + minutes / 2 + rng.nextInt(15),
                    scrollPx = (minutes * (900 + rng.nextInt(500))).toDouble(),
                ),
            )
        }
    }

    // --- aggregation --------------------------------------------------------------------------------

    /** Usage for the [period] window containing [anchor] (default: today). */
    fun summarize(
        period: Period,
        anchor: LocalDate = LocalDate.now(zone),
        data: UsageDays = _days.value,
        today: LocalDate = LocalDate.now(zone),
        firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
    ): PeriodSummary = summarizeUsage(data, period, anchor, today, firstDayOfWeek)

    // --- persistence --------------------------------------------------------------------------------

    private fun mutate(change: (MutableMap<String, MutableMap<String, AppDay>>) -> Unit) {
        synchronized(lock) {
            val copy = _days.value.mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }
            change(copy)
            _days.value = copy
            prefs.edit().putString(KEY, encode(copy)).apply()
        }
    }

    private fun MutableMap<String, MutableMap<String, AppDay>>.add(date: LocalDate, app: SocialApp, delta: AppDay) {
        val day = getOrPut(date.toString()) { mutableMapOf() }
        day[app.id] = (day[app.id] ?: AppDay()) + delta
    }

    private fun load(): UsageDays {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { date ->
                val apps = root.getJSONObject(date)
                apps.keys().asSequence().associateWith { id -> apps.getJSONObject(id).toAppDay() }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encode(days: UsageDays): String {
        val root = JSONObject()
        days.forEach { (date, apps) ->
            val o = JSONObject()
            apps.forEach { (id, d) ->
                o.put(
                    id,
                    JSONObject()
                        .put("totalMs", d.totalMs)
                        .put("hourMs", JSONArray(d.hourMs))
                        .put("stories", d.stories)
                        .put("posts", d.posts)
                        .put("scrollPx", d.scrollPx),
                )
            }
            root.put(date, o)
        }
        return root.toString()
    }

    private fun JSONObject.toAppDay(): AppDay {
        val hours = optJSONArray("hourMs")
        return AppDay(
            totalMs = optLong("totalMs"),
            hourMs = List(24) { hours?.optLong(it) ?: 0L },
            stories = optInt("stories"),
            posts = optInt("posts"),
            scrollPx = optDouble("scrollPx"),
        )
    }

    private companion object {
        const val KEY = "days"
    }
}
