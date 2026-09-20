package com.emilyhsu.innercircle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Stores the survey answers. Small enough to live in SharedPreferences as one JSON blob. */
class ProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun save(profile: Profile) {
        _profile.value = profile
        prefs.edit().putString(KEY, profile.toJson().toString()).apply()
    }

    fun update(transform: (Profile) -> Profile) = save(transform(_profile.value))

    private fun load(): Profile {
        val raw = prefs.getString(KEY, null) ?: return Profile()
        return runCatching { JSONObject(raw).toProfile() }.getOrDefault(Profile())
    }

    private fun Profile.toJson() = JSONObject()
        .put("completed", completed)
        .put("goals", JSONArray(goals.toList()))
        .put("typicalDailyMinutes", typicalDailyMinutes)
        .put("hardTimes", JSONArray(hardTimes.toList()))
        .put("triggers", JSONArray(triggers.toList()))
        .put("dailyTargetMinutes", dailyTargetMinutes)
        .put("notes", notes)

    private fun JSONObject.toProfile() = Profile(
        completed = optBoolean("completed"),
        goals = optJSONArray("goals").toStringSet(),
        typicalDailyMinutes = optInt("typicalDailyMinutes"),
        hardTimes = optJSONArray("hardTimes").toStringSet(),
        triggers = optJSONArray("triggers").toStringSet(),
        dailyTargetMinutes = optInt("dailyTargetMinutes", 60),
        notes = optString("notes"),
    )

    private fun JSONArray?.toStringSet(): Set<String> =
        if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

    private companion object {
        const val KEY = "profile"
    }
}
