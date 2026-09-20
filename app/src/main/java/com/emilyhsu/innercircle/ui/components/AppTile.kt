package com.emilyhsu.innercircle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.ui.theme.Ic

/** The rounded-square platform icon from the mockup: brand colour when live, grey when not. */
@Composable
fun AppTile(app: SocialApp, size: Dp, modifier: Modifier = Modifier) {
    val fill = if (app.enabled) Ic.Instagram else Ic.Disabled
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.25f))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        AppGlyph(app, size * 0.55f, Ic.Background)
    }
}

@Composable
fun AppGlyph(app: SocialApp, size: Dp, color: Color) {
    when (app) {
        SocialApp.Instagram -> InstagramGlyph(size, color)
        SocialApp.YouTube -> PlayGlyph(size, color)
        SocialApp.TikTok -> TextGlyph("♪", size * 1.1f, color)
        SocialApp.Facebook -> TextGlyph("f", size * 1.2f, color)
        SocialApp.LinkedIn -> TextGlyph("in", size * 0.85f, color)
    }
}

@Composable
private fun TextGlyph(text: String, size: Dp, color: Color) {
    Text(text, color = color, fontSize = fontSize(size), fontWeight = FontWeight.Bold)
}

private fun fontSize(size: Dp): TextUnit = size.value.sp

@Composable
private fun InstagramGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawRoundRect(
            color,
            topLeft = Offset(w / 2, w / 2),
            size = Size(s - w, s - w),
            cornerRadius = CornerRadius(s * 0.3f),
            style = Stroke(w),
        )
        drawCircle(color, radius = s * 0.22f, center = Offset(s / 2, s / 2), style = Stroke(w))
        drawCircle(color, radius = s * 0.055f, center = Offset(s * 0.76f, s * 0.24f))
    }
}

@Composable
private fun PlayGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawRoundRect(
            color,
            topLeft = Offset(w / 2, s * 0.14f),
            size = Size(s - w, s * 0.72f),
            cornerRadius = CornerRadius(s * 0.22f),
            style = Stroke(w),
        )
        val tri = Path().apply {
            moveTo(s * 0.42f, s * 0.36f)
            lineTo(s * 0.66f, s * 0.5f)
            lineTo(s * 0.42f, s * 0.64f)
            close()
        }
        drawPath(tri, color, style = Stroke(w * 0.9f, join = StrokeJoin.Round))
    }
}

/** Standard tile size used on the Apps screen and the "Opening…" screen. */
val TileSize = 60.dp
