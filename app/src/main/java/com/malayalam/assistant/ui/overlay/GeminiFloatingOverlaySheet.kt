package com.malayalam.assistant.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.assistant.OverlayUiState
import com.malayalam.assistant.audio.PlaybackState
import com.malayalam.assistant.ui.components.FourPointSparkleButton
import com.malayalam.assistant.ui.components.GlowingWaveformOrb
import com.malayalam.assistant.ui.theme.CharcoalBgTop
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiCoral
import com.malayalam.assistant.ui.theme.GeminiPill
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.GeminiViolet
import com.malayalam.assistant.ui.theme.OrbBorderSubtle
import com.malayalam.assistant.ui.theme.matteCharcoalGradient

/**
 * Floating VoiceInteractionSession Overlay restyled to match the Minimalist Matte Charcoal +
 * Glowing Cyan-to-Violet Waveform Orb design (`60 FPS` on 2 GB RAM, zero blur shaders).
 */
@Composable
fun GeminiFloatingOverlaySheet(
    state: OverlayUiState,
    playbackState: PlaybackState,
    onInputChange: (String) -> Unit,
    onToggleLocale: () -> Unit,
    onToggleMic: () -> Unit,
    onReadScreenPillClick: () -> Unit,
    onSendPrompt: () -> Unit,
    onStopAudio: () -> Unit,
    onDismiss: () -> Unit
) {
    var showTextComposer by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding()
                .border(
                    width = 1.dp,
                    color = OrbBorderSubtle,
                    shape = RoundedCornerShape(30.dp)
                )
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(30.dp),
            color = CharcoalBgTop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(matteCharcoalGradient())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top row: Read Screen + Language pill + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = onReadScreenPillClick,
                        label = {
                            Text(
                                text = if (state.isUsingScreenContext) "Screen ✓" else "Read Screen",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ScreenSearchDesktop,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (state.isUsingScreenContext) GeminiBlue.copy(alpha = 0.22f) else GeminiCard,
                            labelColor = if (state.isUsingScreenContext) GeminiBlue else GeminiTextPrimary,
                            leadingIconContentColor = GeminiBlue
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onToggleLocale,
                        label = {
                            Text(
                                text = if (state.speechLocale.startsWith("ml")) "ML മലയാളം" else "EN English",
                                fontSize = 11.sp
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = GeminiCard,
                            labelColor = GeminiTextPrimary
                        )
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = GeminiTextSecondary)
                    }
                }

                Spacer(Modifier.height(6.dp))

                val headline = when {
                    state.isListeningMic -> if (state.speechLocale.startsWith("ml")) "കേൾക്കുന്നു..." else "Listening..."
                    playbackState.isPlaying -> playbackState.activeTitle
                    else -> state.assistantResponse
                }
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = if (headline.length <= 16) 24.sp else 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                if (playbackState.isPlaying) {
                    Spacer(Modifier.height(8.dp))
                    AudioPlaybackBar(playbackState = playbackState, onStop = onStopAudio)
                }

                Spacer(Modifier.height(6.dp))

                // Compact Glowing Waveform Orb + Bottom-Right `✦` Toggle
                Box(modifier = Modifier.fillMaxWidth()) {
                    GlowingWaveformOrb(
                        isListening = state.isListeningMic,
                        isPlaying = playbackState.isPlaying,
                        micLevel = state.micRmsLevel,
                        onClick = onToggleMic,
                        orbSize = 118.dp,
                        haloSize = 176.dp,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    FourPointSparkleButton(
                        onClick = { showTextComposer = !showTextComposer },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }

                if (showTextComposer) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = onInputChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    text = if (state.speechLocale.startsWith("ml")) "സെറ്റ് അലാം, വാട്സ്ആപ്പ് തുറക്ക്..." else "Type command...",
                                    color = GeminiTextSecondary,
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = GeminiPill,
                                unfocusedContainerColor = GeminiPill,
                                focusedBorderColor = GeminiBlue,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = GeminiTextPrimary,
                                unfocusedTextColor = GeminiTextPrimary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = onSendPrompt,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GeminiBlue)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color(0xFF071324)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlaybackBar(
    playbackState: PlaybackState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GeminiViolet.copy(alpha = 0.14f))
            .border(1.dp, GeminiViolet.copy(alpha = 0.38f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = GeminiViolet,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = playbackState.activeTitle,
            color = GeminiTextPrimary,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(GeminiCoral)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop Audio",
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
