package com.emilyhsu.innercircle.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Palette sampled from the InnerCircle design mockups. */
object Ic {
    val Background = Color(0xFFF9F6F1)   // app screens
    val Splash = Color(0xFFC2BFBB)       // splash screen
    val Cream = Color(0xFFF6F1E7)        // logo disc
    val Ink = Color(0xFF141414)          // text, logo, progress fill
    val Muted = Color(0xFF6F6B65)        // secondary text
    val Instagram = Color(0xFFCF416D)    // the one live platform tile
    val Disabled = Color(0xFFB0ADA8)     // platforms that aren't connectable yet
    val DisabledText = Color(0xFFB6B3AE)
    val Divider = Color(0xFFD0CCC3)
    val Chip = Color(0xFFEEEBE5)         // buttons, tracks, unselected fills
}

private val IcColorScheme: ColorScheme = lightColorScheme(
    primary = Ic.Ink,
    onPrimary = Ic.Background,
    primaryContainer = Ic.Chip,
    onPrimaryContainer = Ic.Ink,
    secondary = Ic.Instagram,
    onSecondary = Ic.Background,
    background = Ic.Background,
    onBackground = Ic.Ink,
    surface = Ic.Background,
    onSurface = Ic.Ink,
    surfaceVariant = Ic.Chip,
    onSurfaceVariant = Ic.Muted,
    outline = Ic.Divider,
    outlineVariant = Ic.Divider,
)

/** The design uses a serif throughout; the system serif keeps the APK free of font files. */
private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun InnerCircleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IcColorScheme,
        typography = Typography().withFamily(FontFamily.Serif),
        content = content,
    )
}
