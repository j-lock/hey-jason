package com.jlock.heyjason

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF12151C)
val Slate = Color(0xFF1A1F29)
val Card = Color(0xFF232A37)
val CardHi = Color(0xFF2C3545)
val Peanut = Color(0xFFF4A259)
val OnPeanut = Color(0xFF2A1705)
val Trunk = Color(0xFF9DB4CC)
val TextHi = Color(0xFFEDF0F5)
val TextLo = Color(0xFF8D97A8)
val Coral = Color(0xFFFF7A6B)
val Mint = Color(0xFF7BD389)

private val Scheme = darkColorScheme(
    primary = Peanut,
    onPrimary = OnPeanut,
    secondary = Trunk,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextHi,
    surface = Slate,
    onSurface = TextHi,
    surfaceVariant = Card,
    onSurfaceVariant = TextLo,
    surfaceContainerHigh = Slate,
    outline = Color(0xFF3A4456),
    error = Coral,
)

@Composable
fun HeyJasonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
