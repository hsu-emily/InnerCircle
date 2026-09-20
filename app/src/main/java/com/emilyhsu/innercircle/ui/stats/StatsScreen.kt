package com.emilyhsu.innercircle.ui.stats

import androidx.compose.foundation.background
import java.time.LocalDate
import com.emilyhsu.innercircle.util.formatPeriodLabel
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Insight
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.ui.components.AppTile
import com.emilyhsu.innercircle.ui.components.IcProgressBar
import com.emilyhsu.innercircle.ui.components.PrimaryButton
import com.emilyhsu.innercircle.ui.theme.Ic
import com.emilyhsu.innercircle.util.Change
import com.emilyhsu.innercircle.util.Direction
import com.emilyhsu.innercircle.util.changeBetween
import com.emilyhsu.innercircle.util.comparisonLabels
import com.emilyhsu.innercircle.util.formatDuration
import com.emilyhsu.innercircle.util.formatHour
import com.emilyhsu.innercircle.util.formatMeters
import kotlinx.coroutines.delay
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(viewModel: StatsViewModel) {
    val period by viewModel.period.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val insight by viewModel.insight.collectAsState()

    // The summary writes itself once a window with data is on screen. The short delay means flicking
    // through days or weeks doesn't send a request for every one you pass.
    LaunchedEffect(period, summary.start, summary.hasData) {
        delay(AUTO_INSIGHT_DELAY_MS)
        viewModel.ensureInsight()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Stats", style = MaterialTheme.typography.headlineLarge, color = Ic.Ink)
        Spacer(Modifier.height(20.dp))
        PeriodBar(period, viewModel::setPeriod)
        Spacer(Modifier.height(10.dp))
        DateNavBar(
            label = formatPeriodLabel(summary.period, summary.start, summary.end, LocalDate.now()),
            canGoForward = !summary.isCurrent,
            onBack = viewModel::goBack,
            onForward = viewModel::goForward,
        )
        Spacer(Modifier.height(20.dp))

        TotalCard(summary, profile.dailyTargetMinutes)

        if (!summary.hasData) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (summary.isCurrent) "Nothing recorded yet. Open Instagram from the Apps tab and your time, posts and scrolling will show up here."
                else "Nothing was recorded in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ic.Muted,
            )
        }

        Section("Compared with before") {
            CompareCard(summary)
        }

        Section("Time by app") {
            val apps = SocialApp.entries.filter { it.enabled }
            val maxMs = (apps.maxOfOrNull { summary.perApp[it]?.totalMs ?: 0L } ?: 0L).coerceAtLeast(1L)
            apps.forEach { app ->
                val ms = summary.perApp[app]?.totalMs ?: 0L
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppTile(app, 32.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.bodyMedium, color = Ic.Ink)
                        Spacer(Modifier.height(6.dp))
                        IcProgressBar(ms / maxMs.toFloat())
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(formatDuration(ms), style = MaterialTheme.typography.titleSmall, color = Ic.Ink)
                }
            }
        }

        Section(if (period == Period.Day) "Time of day" else "When you tend to scroll") {
            val hours = summary.total.hourMs.map { it / 60_000f }
            val avg = hours.filter { it > 0f }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            BarChart(
                values = hours,
                labels = List(24) { if (it % 6 == 0) formatHour(it).replace("am", "a").replace("pm", "p") else null },
                barColor = { _, v -> if (v >= avg) Ic.Ink else Ic.Disabled },
            )
            summary.total.hourMs.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { peak ->
                Spacer(Modifier.height(10.dp))
                Text("Busiest around ${formatHour(peak.index)}", style = MaterialTheme.typography.bodySmall, color = Ic.Muted)
            }
        }

        if (period != Period.Day) {
            Section("Day by day") {
                val target = profile.dailyTargetMinutes.toFloat()
                BarChart(
                    values = summary.daily.map { it.second / 60_000f },
                    labels = summary.daily.map { (date, _) ->
                        when {
                            period == Period.Week -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                            date.dayOfMonth == 1 || date.dayOfMonth % 5 == 0 -> date.dayOfMonth.toString()
                            else -> null
                        }
                    },
                    target = target,
                    barColor = { _, v -> if (v > target) Ic.Instagram else Ic.Ink },
                )
                Spacer(Modifier.height(10.dp))
                Text("Dashed line: your ${formatDuration(profile.dailyTargetMinutes * 60_000L)} daily goal", style = MaterialTheme.typography.bodySmall, color = Ic.Muted)
            }
        }

        Section("Activity") {
            SocialApp.entries.filter { it.enabled }.forEach { app ->
                ActivityRow(app, summary.perApp[app] ?: AppDay())
            }
        }

        // Only the daily view has a written summary; week and month just show the numbers.
        if (period == Period.Day) {
            Section("Habit summary") {
                InsightCard(insight, hasData = summary.hasData, onRetry = viewModel::generateInsight)
            }
        }

        Section("How this is measured") { MeasuredNote() }
        Spacer(Modifier.height(32.dp))
    }
}

private val BarShape = RoundedCornerShape(20.dp)

/** The top bar: choose Day, Week or Month. */
@Composable
private fun PeriodBar(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BarShape)
            .background(Ic.Chip)
            .border(1.dp, Ic.Divider, BarShape)
            .padding(5.dp),
    ) {
        Period.entries.forEach { p ->
            val on = p == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) Ic.Ink else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.label, style = MaterialTheme.typography.titleSmall, color = if (on) Ic.Background else Ic.Ink)
            }
        }
    }
}

/**
 * The second bar: the date, week range or month being shown, with arrows to step back and forward.
 * The forward arrow is greyed out and does nothing once the current period is showing.
 */
@Composable
private fun DateNavBar(label: String, canGoForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(BarShape)
            .background(Ic.Chip)
            .border(1.dp, Ic.Divider, BarShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowButton(pointsLeft = true, enabled = true, description = "Previous", onClick = onBack)
        Text(
            label,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = Ic.Ink,
            maxLines = 1,
        )
        ArrowButton(pointsLeft = false, enabled = canGoForward, description = "Next", onClick = onForward)
    }
}

@Composable
private fun ArrowButton(pointsLeft: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val color = if (enabled) Ic.Ink else Ic.Divider
    Box(
        Modifier
            .width(64.dp)
            .fillMaxHeight()
            // A disabled clickable is inert and reported as disabled to accessibility services.
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 11.dp, height = 20.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (pointsLeft) { moveTo(w, 0f); lineTo(0f, h / 2f); lineTo(w, h) }
                else { moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h) }
            }
            drawPath(path, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun TotalCard(summary: PeriodSummary, targetMinutes: Int) {
    val label = when {
        !summary.isCurrent -> "Screen time"
        summary.period == Period.Day -> "Screen time today"
        summary.period == Period.Week -> "Screen time this week"
        else -> "Screen time this month"
    }
    val total = summary.total.totalMs
    Card {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ic.Muted)
        Spacer(Modifier.height(6.dp))
        Text(formatDuration(total), style = MaterialTheme.typography.displayMedium, color = Ic.Ink)

        if (summary.previousTotalMs > 0 && total > 0) {
            val pct = ((total - summary.previousTotalMs) * 100.0 / summary.previousTotalMs).toInt()
            val word = when (summary.period) {
                Period.Day -> "the day before"
                Period.Week -> if (summary.isPartial) "the same days last week" else "the week before"
                Period.Month -> if (summary.isPartial) "the same days last month" else "the month before"
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${if (pct <= 0) "Down" else "Up"} ${kotlin.math.abs(pct)}% from $word",
                style = MaterialTheme.typography.bodyMedium,
                color = if (pct <= 0) Ic.Ink else Ic.Instagram,
            )
        }

        Spacer(Modifier.height(16.dp))
        val targetMs = targetMinutes * 60_000L
        if (summary.period == Period.Day) {
            IcProgressBar((total / targetMs.toFloat()).coerceAtMost(1f))
            Spacer(Modifier.height(8.dp))
            Text(
                if (total <= targetMs) "${formatDuration(total)} of your ${formatDuration(targetMs)} goal"
                else "${formatDuration(total - targetMs)} over your ${formatDuration(targetMs)} goal",
                style = MaterialTheme.typography.bodySmall,
                color = if (total <= targetMs) Ic.Muted else Ic.Instagram,
            )
        } else {
            val avg = total / maxOf(summary.elapsedDays, 1)
            Text(
                "Average ${formatDuration(avg)} a day against a ${formatDuration(targetMs)} goal",
                style = MaterialTheme.typography.bodySmall,
                color = if (avg <= targetMs) Ic.Muted else Ic.Instagram,
            )
        }
    }
}

/** Today vs yesterday, this week vs last week, this month vs last month: whichever period is selected. */
@Composable
private fun CompareCard(summary: PeriodSummary) {
    val labels = comparisonLabels(summary, LocalDate.now())
    val asShare = summary.period == Period.Day && summary.isCurrent
    val cur = summary.total
    val prev = summary.previous
    val rows = listOf(
        CompareRow("Screen time", formatDuration(cur.totalMs), formatDuration(prev.totalMs), changeBetween(cur.totalMs.toDouble(), prev.totalMs.toDouble(), asShare)),
        CompareRow("Stories", cur.stories.toString(), prev.stories.toString(), changeBetween(cur.stories.toDouble(), prev.stories.toDouble(), asShare)),
        CompareRow("Posts seen", cur.posts.toString(), prev.posts.toString(), changeBetween(cur.posts.toDouble(), prev.posts.toDouble(), asShare)),
        CompareRow("Scrolled", formatMeters(cur.scrollMeters), formatMeters(prev.scrollMeters), changeBetween(cur.scrollMeters, prev.scrollMeters, asShare)),
    )
    Card {
        Text(labels.title, style = MaterialTheme.typography.titleMedium, color = Ic.Ink)
        labels.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Ic.Muted)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.25f))
            CompareCell(labels.current, Modifier.weight(1f), MaterialTheme.typography.labelMedium, Ic.Muted)
            CompareCell(labels.previous, Modifier.weight(1f), MaterialTheme.typography.labelMedium, Ic.Muted)
            CompareCell(if (asShare) "Reached" else "Change", Modifier.weight(1.05f), MaterialTheme.typography.labelMedium, Ic.Muted)
        }
        rows.forEach { row ->
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Ic.Divider)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, Modifier.weight(1.25f), style = MaterialTheme.typography.bodyMedium, color = Ic.Ink)
                CompareCell(row.current, Modifier.weight(1f), MaterialTheme.typography.titleSmall, Ic.Ink)
                CompareCell(row.previous, Modifier.weight(1f), MaterialTheme.typography.bodyMedium, Ic.Muted)
                val (arrow, color) = when (row.change.direction) {
                    Direction.Up -> "↑ " to Ic.Instagram
                    Direction.Down -> "↓ " to Ic.Ink
                    Direction.Flat -> "" to Ic.Muted
                }
                CompareCell(arrow + row.change.text, Modifier.weight(1.05f), MaterialTheme.typography.titleSmall, color)
            }
        }
    }
}

private data class CompareRow(val label: String, val current: String, val previous: String, val change: Change)

@Composable
private fun CompareCell(text: String, modifier: Modifier, style: androidx.compose.ui.text.TextStyle, color: Color) {
    Text(text, modifier, style = style, color = color, textAlign = TextAlign.End, maxLines = 2)
}

/** Plain-language account of where each number comes from, so nobody mistakes it for phone-wide screen time. */
@Composable
private fun MeasuredNote() {
    val body = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Time: counted while an app is open on screen inside InnerCircle, split by the hour. Time you spend in Instagram outside InnerCircle, or in other apps, isn't included.",
            style = body, color = Ic.Muted,
        )
        Text(
            "Stories: each story you open. Posts seen: feed posts that were mostly on screen for at least a second, counted once each (hidden ads and suggestions never count).",
            style = body, color = Ic.Muted,
        )
        Text(
            "Scrolled: how far you moved the page, converted to metres from the screen's pixel density. It's an estimate, typically within about 10%.",
            style = body, color = Ic.Muted,
        )
    }
}

@Composable
private fun ActivityRow(app: SocialApp, day: AppDay) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTile(app, 28.dp)
            Spacer(Modifier.size(10.dp))
            Text(app.displayName, style = MaterialTheme.typography.titleSmall, color = Ic.Ink)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Stories", day.stories.toString(), Modifier.weight(1f))
            Metric("Posts seen", day.posts.toString(), Modifier.weight(1f))
            Metric("Scrolled", formatMeters(day.scrollMeters), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ic.Chip)
            .padding(14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ic.Ink, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ic.Muted, maxLines = 2)
    }
}

private const val AUTO_INSIGHT_DELAY_MS = 600L

@Composable
private fun InsightCard(insight: InsightUi, hasData: Boolean, onRetry: () -> Unit) {
    Card {
        when (insight) {
            is InsightUi.Loading -> {
                InsightProgress(if (insight.previous != null) "Updating your summary…" else "Reading your habits…")
                insight.previous?.let {
                    Spacer(Modifier.height(16.dp))
                    InsightBody(it)
                }
            }
            is InsightUi.Ready -> InsightBody(insight.insight)
            is InsightUi.Failed -> {
                Text(insight.message, style = MaterialTheme.typography.bodyMedium, color = Ic.Instagram)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(text = "Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
                insight.previous?.let {
                    Spacer(Modifier.height(20.dp))
                    InsightBody(it)
                }
            }
            // Only visible for the instant before the automatic request starts.
            InsightUi.Idle ->
                if (hasData) InsightProgress("Reading your habits…")
                else Text(
                    "There's nothing to summarize for this period yet. Use an app inside InnerCircle for a while, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ic.Muted,
                )
        }
    }
}

@Composable
private fun InsightProgress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Ic.Ink, strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Ic.Ink)
    }
}

@Composable
private fun InsightBody(insight: Insight) {
    Text("Usage summary", style = MaterialTheme.typography.labelLarge, color = Ic.Muted)
    Spacer(Modifier.height(6.dp))
    Text(insight.summary, style = MaterialTheme.typography.bodyLarge, color = Ic.Ink)
    if (insight.recommendations.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Wellness recommendations", style = MaterialTheme.typography.labelLarge, color = Ic.Muted)
        insight.recommendations.forEachIndexed { i, rec ->
            Spacer(Modifier.height(10.dp))
            Row {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = Ic.Instagram, modifier = Modifier.width(22.dp))
                Text(rec.text, style = MaterialTheme.typography.bodyMedium, color = Ic.Ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(28.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Ic.Ink)
    Spacer(Modifier.height(14.dp))
    Column(content = content)
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ic.Chip)
            .padding(20.dp),
        content = content,
    )
}
