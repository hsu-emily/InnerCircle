package com.emilyhsu.innercircle.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilyhsu.innercircle.ui.theme.Ic

/**
 * A plain bar chart: one bar per entry in [values], optional text under some of them, and an
 * optional dashed [target] line in the same units as [values].
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String?>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
    target: Float? = null,
    barColor: (index: Int, value: Float) -> Color = { _, _ -> Ic.Ink },
) {
    val max = maxOf(values.maxOrNull() ?: 0f, target ?: 0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight),
        ) {
            val slot = size.width / values.size
            val barWidth = slot * 0.62f
            val corner = CornerRadius(barWidth.coerceAtMost(8.dp.toPx()) / 2f)
            values.forEachIndexed { i, v ->
                val h = if (v <= 0f) 0f else (v / max * size.height).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = barColor(i, v),
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = corner,
                )
            }
            drawLine(Ic.Divider, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (target != null) {
                val y = size.height - target / max * size.height
                drawLine(
                    Ic.Instagram,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            values.indices.forEach { i ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    labels.getOrNull(i)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Ic.Muted,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}
