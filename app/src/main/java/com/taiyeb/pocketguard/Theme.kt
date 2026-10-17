package com.taiyeb.pocketguard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

val Background = Color(0xFF141414)
val CardColor = Color(0xFF232325)
val CardBorder = Color(0x14FFFFFF)
val Track = Color(0xFF38383C)
val Purple = Color(0xFF7A5ADF)
val Lavender = Color(0xFFAA90EA)
val TextPrimary = Color(0xFFF4F3F6)
val Muted = Color(0xFFA09EA6)
val Green = Color(0xFF6EDBA6)
val Amber = Color(0xFFF5B547)
val Red = Color(0xFFFF5470)

/** Manrope is a variable font: one file, each weight picked through the "wght" axis. */
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private fun TextStyle.manrope() = copy(fontFamily = Manrope)

private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.manrope(),
        displayMedium = displayMedium.manrope(),
        displaySmall = displaySmall.manrope(),
        headlineLarge = headlineLarge.manrope(),
        headlineMedium = headlineMedium.manrope(),
        headlineSmall = headlineSmall.manrope(),
        titleLarge = titleLarge.manrope(),
        titleMedium = titleMedium.manrope(),
        titleSmall = titleSmall.manrope(),
        bodyLarge = bodyLarge.manrope(),
        bodyMedium = bodyMedium.manrope(),
        bodySmall = bodySmall.manrope(),
        labelLarge = labelLarge.manrope(),
        labelMedium = labelMedium.manrope(),
        labelSmall = labelSmall.manrope(),
    )
}

/** Colour of each guard phase: grey off, lavender armed, purple protected, amber warning, red siren. */
val Phase.color
    get() = when (this) {
        Phase.OFF -> Muted
        Phase.WAITING_FOR_POCKET -> Lavender
        Phase.IN_POCKET, Phase.ON_TABLE -> Purple
        Phase.WARNING -> Amber
        Phase.SIREN -> Red
    }

@Composable
fun PocketGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple,
            onPrimary = Color.White,
            secondary = Lavender,
            background = Background,
            surface = CardColor,
            onSurface = TextPrimary,
            onBackground = TextPrimary,
            surfaceVariant = Track,
            onSurfaceVariant = Muted,
        ),
        typography = AppTypography,
        content = content,
    )
}
