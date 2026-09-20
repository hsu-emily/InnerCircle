package com.emilyhsu.innercircle.ui.survey

import androidx.lifecycle.ViewModel
import com.emilyhsu.innercircle.data.Profile
import com.emilyhsu.innercircle.data.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StepKind { Multi, Single, Text }

/** One screen of the survey. Answers are keyed by [key] in [SurveyAnswers]. */
data class Step(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: StepKind,
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
)

/** Everything the survey asks. Edit this list to change the questions. */
object SurveySteps {
    val goals = listOf(
        "Cut down on doom scrolling",
        "Spend less time on social media overall",
        "Be more intentional with my time",
        "Sleep better and stop late-night scrolling",
        "Focus on work or study",
        "See less of what isn't good for me (ads, suggestions, reels)",
    )
    val typical = listOf("Under 1 hour" to 45, "1 to 2 hours" to 90, "2 to 4 hours" to 180, "4 to 6 hours" to 300, "6+ hours" to 420)
    val hardTimes = listOf("Morning", "Afternoon", "Evening", "Late at night", "During work or class")
    val triggers = listOf("Boredom", "Stress or anxiety", "Habit: I just open it", "Fear of missing out", "Procrastination", "Loneliness")
    val targets = listOf("15 minutes" to 15, "30 minutes" to 30, "45 minutes" to 45, "1 hour" to 60, "1.5 hours" to 90, "2 hours" to 120)

    const val GOALS = "goals"
    const val TYPICAL = "typical"
    const val HARD_TIMES = "hardTimes"
    const val TRIGGERS = "triggers"
    const val TARGET = "target"
    const val NOTES = "notes"

    val all = listOf(
        Step(GOALS, "What brings you to InnerCircle?", "Pick everything that fits. This shapes the advice you'll get.", StepKind.Multi, goals),
        Step(TYPICAL, "How much time do you spend on social media on a typical day?", "A rough guess is fine.", StepKind.Single, typical.map { it.first }),
        Step(HARD_TIMES, "When is it hardest to put your phone down?", "Choose all that apply.", StepKind.Multi, hardTimes),
        Step(TRIGGERS, "What usually pulls you in?", "Knowing the trigger is half the fix.", StepKind.Multi, triggers),
        Step(TARGET, "What's a good daily limit for you?", "You can change this any time in Settings.", StepKind.Single, targets.map { it.first }),
        Step(NOTES, "Anything else we should know?", "Optional. In your own words: what would a better relationship with your phone look like?", StepKind.Text, optional = true),
    )
}

data class SurveyAnswers(
    val goals: Set<String> = emptySet(),
    val typical: String? = null,
    val hardTimes: Set<String> = emptySet(),
    val triggers: Set<String> = emptySet(),
    val target: String? = null,
    val notes: String = "",
) {
    fun multi(key: String): Set<String> = when (key) {
        SurveySteps.GOALS -> goals
        SurveySteps.HARD_TIMES -> hardTimes
        SurveySteps.TRIGGERS -> triggers
        else -> emptySet()
    }

    fun single(key: String): String? = when (key) {
        SurveySteps.TYPICAL -> typical
        SurveySteps.TARGET -> target
        else -> null
    }

    fun isAnswered(step: Step): Boolean = when (step.kind) {
        StepKind.Multi -> multi(step.key).isNotEmpty()
        StepKind.Single -> single(step.key) != null
        StepKind.Text -> step.optional || notes.isNotBlank()
    }
}

class SurveyViewModel(private val repo: ProfileRepository) : ViewModel() {
    private val _answers = MutableStateFlow(prefill(repo.profile.value))
    val answers: StateFlow<SurveyAnswers> = _answers.asStateFlow()

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    val steps = SurveySteps.all
    val isLastStep: Boolean get() = _step.value == steps.lastIndex

    fun toggle(step: Step, option: String) = _answers.update { a ->
        fun Set<String>.flip() = if (option in this) this - option else this + option
        when (step.key) {
            SurveySteps.GOALS -> a.copy(goals = a.goals.flip())
            SurveySteps.HARD_TIMES -> a.copy(hardTimes = a.hardTimes.flip())
            SurveySteps.TRIGGERS -> a.copy(triggers = a.triggers.flip())
            SurveySteps.TYPICAL -> a.copy(typical = option)
            SurveySteps.TARGET -> a.copy(target = option)
            else -> a
        }
    }

    fun setNotes(text: String) = _answers.update { it.copy(notes = text.take(500)) }

    fun next() { if (_step.value < steps.lastIndex) _step.value += 1 }
    fun back(): Boolean = if (_step.value > 0) { _step.value -= 1; true } else false

    fun finish() {
        val a = _answers.value
        repo.save(
            Profile(
                completed = true,
                goals = a.goals,
                typicalDailyMinutes = SurveySteps.typical.firstOrNull { it.first == a.typical }?.second ?: 0,
                hardTimes = a.hardTimes,
                triggers = a.triggers,
                dailyTargetMinutes = SurveySteps.targets.firstOrNull { it.first == a.target }?.second ?: 60,
                notes = a.notes.trim(),
            ),
        )
    }

    /** When retaking the survey, start from what the user said last time. */
    private fun prefill(p: Profile): SurveyAnswers {
        if (!p.completed) return SurveyAnswers()
        return SurveyAnswers(
            goals = p.goals,
            typical = SurveySteps.typical.minByOrNull { kotlin.math.abs(it.second - p.typicalDailyMinutes) }?.first,
            hardTimes = p.hardTimes,
            triggers = p.triggers,
            target = SurveySteps.targets.minByOrNull { kotlin.math.abs(it.second - p.dailyTargetMinutes) }?.first,
            notes = p.notes,
        )
    }
}
