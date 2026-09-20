package com.emilyhsu.innercircle.ui.survey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.ui.components.IcProgressBar
import com.emilyhsu.innercircle.ui.components.OptionRow
import com.emilyhsu.innercircle.ui.components.PrimaryButton
import com.emilyhsu.innercircle.ui.theme.Ic

/** First-run profile survey. [onFinished] is called after the answers are saved. */
@Composable
fun SurveyScreen(viewModel: SurveyViewModel, onFinished: () -> Unit, onExit: () -> Unit) {
    val stepIndex by viewModel.step.collectAsState()
    val answers by viewModel.answers.collectAsState()
    val step = viewModel.steps[stepIndex]
    val last = stepIndex == viewModel.steps.lastIndex

    // Back walks through the questions; from the first one it leaves the survey.
    BackHandler { if (!viewModel.back()) onExit() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { if (!viewModel.back()) onExit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Ic.Ink)
            }
            Spacer(Modifier.size(12.dp))
            IcProgressBar((stepIndex + 1) / viewModel.steps.size.toFloat(), Modifier.weight(1f))
            Spacer(Modifier.size(12.dp))
            Text("${stepIndex + 1}/${viewModel.steps.size}", style = MaterialTheme.typography.labelLarge, color = Ic.Muted)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(step.title, style = MaterialTheme.typography.headlineMedium, color = Ic.Ink)
            Spacer(Modifier.height(10.dp))
            Text(step.subtitle, style = MaterialTheme.typography.bodyLarge, color = Ic.Muted)
            Spacer(Modifier.height(24.dp))

            when (step.kind) {
                StepKind.Multi, StepKind.Single -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    step.options.forEach { option ->
                        val selected = if (step.kind == StepKind.Multi) option in answers.multi(step.key) else answers.single(step.key) == option
                        OptionRow(option, selected, onClick = { viewModel.toggle(step, option) })
                    }
                }
                StepKind.Text -> OutlinedTextField(
                    value = answers.notes,
                    onValueChange = viewModel::setNotes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    placeholder = { Text("For example: I want to stop checking Instagram first thing in the morning.", color = Ic.DisabledText) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ic.Ink,
                        unfocusedBorderColor = Ic.Divider,
                        cursorColor = Ic.Ink,
                        focusedTextColor = Ic.Ink,
                        unfocusedTextColor = Ic.Ink,
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        PrimaryButton(
            text = if (last) "Finish" else "Continue",
            enabled = answers.isAnswered(step),
            onClick = {
                if (last) {
                    viewModel.finish()
                    onFinished()
                } else {
                    viewModel.next()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}
