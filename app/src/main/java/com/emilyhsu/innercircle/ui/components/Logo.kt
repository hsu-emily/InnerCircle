package com.emilyhsu.innercircle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.emilyhsu.innercircle.ui.theme.Ic

/**
 * The InnerCircle mark: an open ring around a keyhole-shaped figure, on a cream disc.
 * Proportions are taken from the design mockup, expressed as fractions of [size].
 */
@Composable
fun InnerCircleLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    disc: Color = Ic.Cream,
    ink: Color = Ic.Ink,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        drawCircle(disc, radius = s / 2f, center = c)

        // Ring, open at the bottom where the figure's body comes up.
        val ringR = s * 0.305f
        val stroke = s * 0.07f
        drawArc(
            color = ink,
            startAngle = 118f,
            sweepAngle = 304f,
            useCenter = false,
            topLeft = Offset(c.x - ringR, c.y - ringR - s * 0.01f),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // Head.
        drawCircle(ink, radius = s * 0.11f, center = Offset(c.x, c.y + s * 0.02f))

        // Body: a tapered trapezoid, like the stem of a keyhole.
        val body = Path().apply {
            moveTo(c.x - s * 0.05f, c.y + s * 0.10f)
            lineTo(c.x + s * 0.05f, c.y + s * 0.10f)
            lineTo(c.x + s * 0.095f, c.y + s * 0.28f)
            lineTo(c.x - s * 0.095f, c.y + s * 0.28f)
            close()
        }
        drawPath(body, ink)
    }
}
