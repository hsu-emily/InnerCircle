package com.emilyhsu.innercircle.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.AppContainer
import com.emilyhsu.innercircle.data.ExitMethod
import com.emilyhsu.innercircle.ui.components.OptionRow
import com.emilyhsu.innercircle.ui.theme.Ic
import com.emilyhsu.innercircle.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onRetakeSurvey: () -> Unit) {
    val profile by container.profile.profile.collectAsState()
    val exitMethod by container.settings.exitMethod.collectAsState()
    var sliderMinutes by remember(profile.dailyTargetMinutes) { mutableStateOf(profile.dailyTargetMinutes.toFloat()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Ic.Ink)

        Group("Your goals") {
            if (profile.goals.isEmpty()) {
                Text("You haven't taken the survey yet.", style = MaterialTheme.typography.bodyMedium, color = Ic.Muted)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.goals.forEach { goal ->
                        Text(
                            goal,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ic.Ink,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Ic.Chip)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(if (profile.completed) "Retake the survey" else "Take the survey", onRetakeSurvey)
        }

        Group("Daily limit") {
            Text(formatDuration(sliderMinutes.toLong() * 60_000L), style = MaterialTheme.typography.headlineSmall, color = Ic.Ink)
            Text("Used for your progress on the Statistics tab and in the AI's advice.", style = MaterialTheme.typography.bodySmall, color = Ic.Muted)
            Slider(
                value = sliderMinutes,
                onValueChange = { sliderMinutes = (it / 5f).toInt() * 5f },
                onValueChangeFinished = { container.profile.update { it.copy(dailyTargetMinutes = sliderMinutes.toInt()) } },
                valueRange = 15f..240f,
                steps = 44,
                colors = SliderDefaults.colors(
                    thumbColor = Ic.Ink,
                    activeTrackColor = Ic.Ink,
                    inactiveTrackColor = Ic.Divider,
                    activeTickColor = Ic.Ink,
                    inactiveTickColor = Ic.Divider,
                ),
            )
        }

        Group("Leaving an app") {
            Text(
                "How do you get back to InnerCircle from Instagram? The Android Back gesture always works too.",
                style = MaterialTheme.typography.bodySmall,
                color = Ic.Muted,
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExitMethod.entries.forEach { method ->
                    OptionRow(method.label, selected = method == exitMethod, onClick = { container.settings.setExitMethod(method) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(exitMethod.description, style = MaterialTheme.typography.bodySmall, color = Ic.Muted)
        }

        Group("AI insights") {
            Text(
                "InnerCircle writes your habit summary with AI (powered by OpenAI), so there's nothing to set up. Only your survey answers and usage totals are sent, never account names or content. Your daily habit summary is written automatically when you open the Day view in Statistics.",
                style = MaterialTheme.typography.bodySmall,
                color = Ic.Muted,
            )
        }

        Group("About") {
            Text("InnerCircle 1.0", style = MaterialTheme.typography.bodyMedium, color = Ic.Ink)
            Text(
                "Everything is stored on this device. Time is measured only while an app is open inside InnerCircle.",
                style = MaterialTheme.typography.bodySmall,
                color = Ic.Muted,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Ic.Ink)
    Spacer(Modifier.height(12.dp))
    Column(content = content)
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ic.Ink)
    }
}
