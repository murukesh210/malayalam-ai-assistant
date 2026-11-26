package com.malayalam.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.malayalam.assistant.ui.theme.HaloCyan
import com.malayalam.assistant.ui.theme.HaloPurple
import com.malayalam.assistant.ui.theme.MutedSparkleGrey
import com.malayalam.assistant.ui.theme.OrbBorderSubtle
import com.malayalam.assistant.ui.theme.OrbCoreSurface
import com.malayalam.assistant.ui.theme.WaveBlue
import com.malayalam.assistant.ui.theme.WaveCyan
import com.malayalam.assistant.ui.theme.WavePurple
import com.malayalam.assistant.ui.theme.WaveViolet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Center Glowing Waveform Orb (`~170.dp` dark circle inside a smooth cyan-to-purple radial halo):
 *  - Uses `Brush.radialGradient` ONLY (ZERO `Modifier.blur()`) so it runs at 60 FPS on 2 GB RAM phones.
 *  - When idle (`!isListening && !isPlaying`), renders a stationary crisp neon cyan-to-violet wave
 *    with 0 continuous frame wakeups (zero battery/CPU drain).
 *  - When listening (`isListening`) or playing a Malayalam clip (`isPlaying`), smoothly animates
 *    wave amplitude driven by `micLevel` (`0f..1f`) and phase.
 */
@Composable
fun GlowingWaveformOrb(
    isListening: Boolean,
    isPlaying: Boolean,
    micLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 172.dp,
    haloSize: Dp = 264.dp
) {
    val isActive = isListening || isPlaying
    var phase by remember { mutableFloatStateOf(0f) }

    // Only run 60 FPS frame ticker while actively listening or playing audio!
    LaunchedEffect(isActive) {
        if (!isActive) {
            phase = 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = (now - lastNanos) / 1_000_000_000f
                    phase = (phase + dt * 5.2f) % (2f * PI.toFloat() * 10f)
                }
                lastNanos = now
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier.size(haloSize),
        contentAlignment = Alignment.Center
    ) {
        // 1. Lightweight Ambient Cyan-to-Purple Radial Halo (No Modifier.blur!)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val pulse = if (isActive) {
                0.28f + 0.10f * sin(phase * 0.7f) + (micLevel.coerceIn(0f, 1f) * 0.14f)
            } else {
                0.22f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to HaloCyan.copy(alpha = (pulse * 1.1f).coerceAtMost(0.50f)),
                    0.52f to HaloPurple.copy(alpha = (pulse * 0.85f).coerceAtMost(0.38f)),
                    0.82f to HaloPurple.copy(alpha = 0.08f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        // 2. Dark Matte Circular Orb Button
        val borderColor = when {
            isListening -> WaveCyan.copy(alpha = 0.75f)
            isPlaying -> WaveViolet.copy(alpha = 0.75f)
            else -> OrbBorderSubtle
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF22252E),
                            OrbCoreSurface,
                            Color(0xFF14161B)
                        )
                    )
                )
                .border(width = 1.5.dp, color = borderColor, shape = CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            // 3. Neon Cyan-to-Violet Smooth Audio Waveform Canvas
            Canvas(modifier = Modifier.size(orbSize * 0.74f)) {
                val w = size.width
                val h = size.height
                val midY = h / 2f
                val gradientBrush = Brush.horizontalGradient(
                    colors = listOf(WaveCyan, WaveBlue, WavePurple, WaveViolet),
                    startX = 0f,
                    endX = w
                )

                val dynamicGain = when {
                    isListening -> 0.55f + micLevel.coerceIn(0.08f, 1f) * 0.65f
                    isPlaying -> 0.72f + 0.25f * abs(sin(phase * 0.85f))
                    else -> 0.46f
                }

                // Draw crisp symmetric rounded vertical wave bars + smooth carrier wave
                val barCount = 21
                val horizontalPad = w * 0.06f
                val usableW = w - horizontalPad * 2f
                val stepX = usableW / (barCount - 1).toFloat()
                val strokeW = (usableW / barCount) * 0.44f

                for (i in 0 until barCount) {
                    val norm = (i.toFloat() / (barCount - 1).toFloat()) * 2f - 1f // -1..+1
                    // Gaussian envelope so center bars rise gracefully like a voice waveform
                    val envelope = exp(-2.8f * norm * norm)
                    val idlePattern = 0.35f + 0.65f * abs(sin((i * 0.72f) + 0.4f))
                    val liveMod = if (isActive) {
                        0.45f + 0.55f * abs(sin(phase + i * 0.55f))
                    } else {
                        idlePattern
                    }
                    val halfBarHeight = (h * 0.40f * envelope * liveMod * dynamicGain)
                        .coerceAtLeast(strokeW * 0.55f)

                    val x = horizontalPad + i * stepX
                    // Subtle soft outer glow stroke (no blur needed!)
                    drawLine(
                        brush = gradientBrush,
                        start = Offset(x, midY - halfBarHeight),
                        end = Offset(x, midY + halfBarHeight),
                        strokeWidth = strokeW * 1.75f,
                        cap = StrokeCap.Round,
                        alpha = 0.18f
                    )
                    // Crisp inner neon stroke
                    drawLine(
                        brush = gradientBrush,
                        start = Offset(x, midY - halfBarHeight),
                        end = Offset(x, midY + halfBarHeight),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                        alpha = 0.96f
                    )
                }

                // Subtle continuous silk wave thread connecting across the center
                val wavePath = Path()
                val samples = 48
                for (s in 0..samples) {
                    val frac = s.toFloat() / samples.toFloat()
                    val x = horizontalPad + frac * usableW
                    val norm = frac * 2f - 1f
                    val env = exp(-2.4f * norm * norm)
                    val y = midY + sin(frac * PI.toFloat() * 4f + phase) * (h * 0.14f * env * dynamicGain)
                    if (s == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                drawPath(
                    path = wavePath,
                    brush = gradientBrush,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    alpha = 0.42f
                )
            }
        }
    }
}

/**
 * Top Bar circular button (`☰` or `⚙️`) with a subtle soft radial glow (no expensive blur).
 */
@Composable
fun SoftGlowIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0x28FFFFFF),
                        Color(0x10FFFFFF),
                        Color.Transparent
                    )
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Subtle muted grey four-point sparkle icon (`✦`) for the bottom-right corner.
 */
@Composable
fun FourPointSparkleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MutedSparkleGrey
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val pinch = w * 0.14f
            val path = Path().apply {
                moveTo(cx, 0f)
                quadraticTo(cx + pinch, cy - pinch, w, cy)
                quadraticTo(cx + pinch, cy + pinch, cx, h)
                quadraticTo(cx - pinch, cy + pinch, 0f, cy)
                quadraticTo(cx - pinch, cy - pinch, cx, 0f)
                close()
            }
            drawPath(path = path, color = tint)
        }
    }
}
