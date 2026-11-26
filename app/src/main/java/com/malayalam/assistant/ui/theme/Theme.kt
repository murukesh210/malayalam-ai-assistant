package com.malayalam.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Matte Dark Charcoal Home Palette (60 FPS on 2 GB RAM — zero blur shaders)
val CharcoalBgTop = Color(0xFF18191D)
val CharcoalBgBottom = Color(0xFF212228)
val OrbCoreSurface = Color(0xFF1A1C22)
val OrbBorderSubtle = Color(0xFF2D303A)

// Ambient Radial Halo Colors (#38BDF8 -> #8B5CF6 -> Transparent)
val HaloCyan = Color(0xFF38BDF8)
val HaloPurple = Color(0xFF8B5CF6)

// Neon Cyan-to-Violet Waveform Stops (#5CE1E6 -> #63A4FF -> #A86BF5 -> #C77DFF)
val WaveCyan = Color(0xFF5CE1E6)
val WaveBlue = Color(0xFF63A4FF)
val WavePurple = Color(0xFFA86BF5)
val WaveViolet = Color(0xFFC77DFF)

// Secondary Surfaces & Accents for Drawer, Vault, and Settings
val GeminiBg = CharcoalBgTop
val GeminiSurface = Color(0xFF1E2027)
val GeminiCard = Color(0xFF262932)
val GeminiPill = Color(0xFF2C303B)
val GeminiBlue = WaveBlue
val GeminiViolet = WaveViolet
val GeminiMint = Color(0xFF6EE7B7)
val GeminiAmber = Color(0xFFFCD34D)
val GeminiCoral = Color(0xFFF87171)
val GeminiTextPrimary = Color(0xFFF3F4F6)
val GeminiTextSecondary = Color(0xFF9CA3AF)
val MutedSparkleGrey = Color(0xFF8B909E)

fun matteCharcoalGradient(): Brush = Brush.verticalGradient(
    colors = listOf(CharcoalBgTop, CharcoalBgBottom)
)

fun waveformNeonBrush(): Brush = Brush.horizontalGradient(
    colors = listOf(WaveCyan, WaveBlue, WavePurple, WaveViolet)
)

private val MinimalDarkColorScheme = darkColorScheme(
    primary = WaveBlue,
    onPrimary = Color(0xFF071324),
    secondary = WavePurple,
    onSecondary = Color(0xFF160829),
    tertiary = GeminiMint,
    background = CharcoalBgTop,
    onBackground = GeminiTextPrimary,
    surface = GeminiSurface,
    onSurface = GeminiTextPrimary,
    surfaceVariant = GeminiCard,
    onSurfaceVariant = GeminiTextSecondary,
    error = GeminiCoral
)

@Composable
fun MalayalamAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MinimalDarkColorScheme,
        content = content
    )
}
