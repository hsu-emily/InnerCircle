package com.emilyhsu.innercircle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.withSave
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.data.SocialApp

/**
 * The platform's app icon: its brand colour (Instagram's gradient) with its white logo on top.
 * Platforms that aren't connected yet are shown faded, so they read as "coming soon".
 */
@Composable
fun AppTile(app: SocialApp, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .alpha(if (app.enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(size * 0.25f))
            .background(tileBrush(app)),
        contentAlignment = Alignment.Center,
    ) {
        AppLogo(app, size * 0.58f, Color.White)
    }
}

/** Just the logo shape, in one colour. */
@Composable
fun AppLogo(app: SocialApp, size: Dp, color: Color) {
    val path = remember(app) { PathParser().parsePathString(logoPath(app)).toPath() }
    Canvas(Modifier.size(size)) {
        // The path is drawn on a 24 x 24 grid; scale it up to whatever size was asked for.
        val scale = this.size.minDimension / LOGO_GRID
        drawContext.canvas.withSave {
            drawContext.canvas.scale(scale, scale)
            drawPath(path, color)
        }
    }
}

private fun logoPath(app: SocialApp): String = when (app) {
    SocialApp.Instagram -> BrandLogos.INSTAGRAM
    SocialApp.YouTube -> BrandLogos.YOUTUBE
    SocialApp.TikTok -> BrandLogos.TIKTOK
    SocialApp.Facebook -> BrandLogos.FACEBOOK
    SocialApp.LinkedIn -> BrandLogos.LINKEDIN
}

private fun tileBrush(app: SocialApp): Brush = when (app) {
    // Bottom-left to top-right, like the real icon's sweep from yellow through pink to purple.
    SocialApp.Instagram -> Brush.linearGradient(
        colors = listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f),
    )
    SocialApp.YouTube -> Brush.linearGradient(listOf(Color(0xFFFF0000), Color(0xFFFF0000)))
    SocialApp.TikTok -> Brush.linearGradient(listOf(Color(0xFF000000), Color(0xFF000000)))
    SocialApp.Facebook -> Brush.linearGradient(listOf(Color(0xFF0866FF), Color(0xFF0866FF)))
    SocialApp.LinkedIn -> Brush.linearGradient(listOf(Color(0xFF0A66C2), Color(0xFF0A66C2)))
}

private const val LOGO_GRID = 24f
private const val DISABLED_ALPHA = 0.4f

/** Standard tile size used on the Apps screen and the "Opening…" screen. */
val TileSize = 60.dp
