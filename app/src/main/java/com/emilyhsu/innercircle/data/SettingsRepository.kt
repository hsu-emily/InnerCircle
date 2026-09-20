package com.emilyhsu.innercircle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** App-level settings, plus the most recent AI insight per period so it isn't regenerated on every visit. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * A random id made once per install. It identifies nobody: it only lets the insights server count
     * how many insights this install has used today. It is not an account and can't be tied to a person.
     */
    fun installId(): String = prefs.getString(KEY_INSTALL, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_INSTALL, it).apply()
    }

    // --- leaving an app ---------------------------------------------------------------------------

    private val _exitMethod = MutableStateFlow(
        ExitMethod.entries.firstOrNull { it.name == prefs.getString(KEY_EXIT, null) } ?: ExitMethod.Button,
    )
    val exitMethod: StateFlow<ExitMethod> = _exitMethod.asStateFlow()

    fun setExitMethod(method: ExitMethod) {
        _exitMethod.value = method
        prefs.edit().putString(KEY_EXIT, method.name).apply()
    }

    /** Where the floating button was left, as fractions (0..1) of the space it can move in. */
    fun buttonPosition(): Pair<Float, Float> =
        prefs.getFloat(KEY_BTN_X, DEFAULT_BTN_X) to prefs.getFloat(KEY_BTN_Y, DEFAULT_BTN_Y)

    fun setButtonPosition(x: Float, y: Float) {
        prefs.edit().putFloat(KEY_BTN_X, x).putFloat(KEY_BTN_Y, y).apply()
    }

    // --- cached insights ------------------------------------------------------------------------

    fun cachedInsight(period: Period, start: LocalDate): Insight? {
        val raw = prefs.getString(insightKey(period, start), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val recs = o.getJSONArray("recommendations")
            Insight(
                period = period,
                summary = o.getString("summary"),
                recommendations = (0 until recs.length()).map {
                    // Earlier versions saved {title, detail}; newer ones save the sentence itself.
                    val r = recs.get(it)
                    Recommendation(if (r is JSONObject) "${r.getString("title")}: ${r.getString("detail")}" else r.toString())
                },
                generatedAtMs = o.getLong("generatedAtMs"),
                basedOnTotalMs = o.optLong("basedOnTotalMs", 0),
                basedOnProfile = o.optInt("basedOnProfile", 0),
            )
        }.getOrNull()
    }

    fun cacheInsight(insight: Insight, start: LocalDate) {
        val o = JSONObject()
            .put("summary", insight.summary)
            .put("recommendations", JSONArray(insight.recommendations.map { it.text }))
            .put("generatedAtMs", insight.generatedAtMs)
            .put("basedOnTotalMs", insight.basedOnTotalMs)
            .put("basedOnProfile", insight.basedOnProfile)
        prefs.edit().putString(insightKey(insight.period, start), o.toString()).apply()
    }

    /** One saved insight per window, so paging back to last week shows last week's, not this week's. */
    private fun insightKey(period: Period, start: LocalDate) = "insight_${period.name}_$start"

    private companion object {
        const val KEY_INSTALL = "install_id"
        const val KEY_EXIT = "exit_method"
        const val KEY_BTN_X = "exit_button_x"
        const val KEY_BTN_Y = "exit_button_y"
        const val DEFAULT_BTN_X = 1f    // right edge
        const val DEFAULT_BTN_Y = 0.72f // lower third, clear of Instagram's header
    }
}
