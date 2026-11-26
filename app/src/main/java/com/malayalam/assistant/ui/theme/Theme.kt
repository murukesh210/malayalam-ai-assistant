package com.malayalam.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GeminiBg = Color(0xFF0E1117)
val GeminiSurface = Color(0xFF161B22)
val GeminiCard = Color(0xFF1E2530)
val GeminiPill = Color(0xFF262F3D)
val GeminiBlue = Color(0xFF8AB4F8)
val GeminiViolet = Color(0xFFC58AF9)
val GeminiMint = Color(0xFF81C995)
val GeminiAmber = Color(0xFFFDD663)
val GeminiCoral = Color(0xFFF28B82)
val GeminiTextPrimary = Color(0xFFE8EAED)
val GeminiTextSecondary = Color(0xFF9AA0A6)

private val GeminiDarkColorScheme = darkColorScheme(
    primary = GeminiBlue,
    onPrimary = Color(0xFF081426),
    secondary = GeminiViolet,
    onSecondary = Color(0xFF1A092B),
    tertiary = GeminiMint,
    background = GeminiBg,
    onBackground = GeminiTextPrimary,
    surface = GeminiSurface,
    onSurface = GeminiTextPrimary,
    surfaceVariant = GeminiCard,
    onSurfaceVariant = GeminiTextSecondary,
    error = GeminiCoral
)

/**
 * Lightweight Dark Gemini-inspired Material 3 theme.
 * Deliberately avoids expensive RenderEffect blurs, multi-layer shadows, or infinite shimmer shaders
 * so 4 GB RAM budget chipsets stay cool and responsive.
 */
@Composable
fun MalayalamAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GeminiDarkColorScheme,
        content = content
    )
}
