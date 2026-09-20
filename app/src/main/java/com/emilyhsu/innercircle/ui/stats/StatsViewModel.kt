package com.emilyhsu.innercircle.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emilyhsu.innercircle.AppContainer
import com.emilyhsu.innercircle.ai.AiException
import com.emilyhsu.innercircle.data.Insight
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodMath
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface InsightUi {
    data object Idle : InsightUi
    /** [previous] is the older text still worth showing while a refreshed one is written. */
    data class Loading(val previous: Insight? = null) : InsightUi
    data class Ready(val insight: Insight) : InsightUi
    data class Failed(val message: String, val previous: Insight?) : InsightUi
}

class StatsViewModel(private val container: AppContainer) : ViewModel() {
    private val usage = container.usage
    private val settings = container.settings

    private val _period = MutableStateFlow(Period.Day)
    val period: StateFlow<Period> = _period.asStateFlow()

    /** Any date inside the window being viewed. Stepping back/forward moves it a whole window at a time. */
    private val _anchor = MutableStateFlow(LocalDate.now())

    val summary: StateFlow<PeriodSummary> = combine(usage.days, _period, _anchor) { days, period, anchor ->
        usage.summarize(period, anchor, days)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), usage.summarize(Period.Day))

    val profile: StateFlow<Profile> = container.profile.profile

    /** Windows whose automatic attempt failed. They wait for "Try again" instead of retrying on every visit. */
    private val failures = mutableMapOf<String, InsightUi.Failed>()

    private val _insight = MutableStateFlow<InsightUi>(uiFor(usage.summarize(Period.Day)))
    val insight: StateFlow<InsightUi> = _insight.asStateFlow()

    private var generation: Job? = null

    /** Switching between Day / Week / Month keeps the date, so "Sep 12" becomes "the week of Sep 12". */
    fun setPeriod(period: Period) {
        if (period == _period.value) return
        generation?.cancel()
        _period.value = period
        showSavedInsight()
    }

    fun goBack() = move(-1)

    /** No-op once the current window is showing: there is nothing later to look at. */
    fun goForward() {
        if (!summary.value.isCurrent) move(+1)
    }

    private fun move(steps: Int) {
        generation?.cancel()
        _anchor.value = PeriodMath.shift(_period.value, _anchor.value, steps, LocalDate.now())
        showSavedInsight()
    }

    private fun currentSummary() = usage.summarize(_period.value, _anchor.value)

    /** Each window keeps its own saved insight, so paging back shows the one written for that window. */
    private fun showSavedInsight() {
        _insight.value = uiFor(currentSummary())
    }

    /**
     * Called whenever a window is on screen. Writes the summary by itself when there isn't a usable one,
     * so it's already there by the time you look. Does nothing when the saved one is still good, when
     * the window is empty, or when this window already failed (that waits for [generateInsight]).
     */
    fun ensureInsight() {
        if (_insight.value is InsightUi.Loading) return
        val target = currentSummary()
        if (failures.containsKey(windowKey(target))) return
        if (!needsInsight(settings.cachedInsight(target.period, target.start), target, profile.value)) return
        generateInsight()
    }

    /** Sends the survey answers and this period's numbers to the insights service and shows what comes back. */
    fun generateInsight() {
        if (_insight.value is InsightUi.Loading) return
        val target = currentSummary()
        if (target.period != Period.Day) return // only the daily view shows a summary
        if (!target.hasData) return // nothing to write about; the card explains this instead of calling the model
        val key = windowKey(target)
        failures.remove(key)
        val previous = settings.cachedInsight(target.period, target.start)
        val survey = profile.value
        // Changing period or date cancels this job, so a result for a window you've left never lands.
        generation = viewModelScope.launch {
            _insight.value = InsightUi.Loading(previous)
            _insight.value = try {
                val written = container.insights.generateInsight(survey, target)
                    .copy(basedOnTotalMs = target.total.totalMs, basedOnProfile = survey.hashCode())
                settings.cacheInsight(written, target.start)
                InsightUi.Ready(written)
            } catch (e: AiException) {
                InsightUi.Failed(e.message ?: "Something went wrong.", previous).also { failures[key] = it }
            }
        }
    }

    private fun windowKey(summary: PeriodSummary) = "${summary.period}|${summary.start}"

    private fun uiFor(summary: PeriodSummary): InsightUi =
        failures[windowKey(summary)]
            ?: settings.cachedInsight(summary.period, summary.start)?.let { InsightUi.Ready(it) }
            ?: InsightUi.Idle
}
