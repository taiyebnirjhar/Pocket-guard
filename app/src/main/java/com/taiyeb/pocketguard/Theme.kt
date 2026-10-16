package com.taiyeb.pocketguard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF0B1120)
val CardColor = Color(0xFF151E32)
val Green = Color(0xFF22C55E)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFEF4444)
val Muted = Color(0xFF94A3B8)

@Composable
fun PocketGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Green, background = Background, surface = CardColor),
        content = content,
    )
}
