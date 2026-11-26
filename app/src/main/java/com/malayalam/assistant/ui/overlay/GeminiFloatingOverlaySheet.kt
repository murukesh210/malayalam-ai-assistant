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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.ai.RamStatus
import com.malayalam.assistant.assistant.OverlayUiState
import com.malayalam.assistant.audio.PlaybackState
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiCoral
import com.malayalam.assistant.ui.theme.GeminiMint
import com.malayalam.assistant.ui.theme.GeminiPill
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.GeminiViolet

@Composable
fun GeminiFloatingOverlaySheet(
    state: OverlayUiState,
    playbackState: PlaybackState,
    ramStatus: RamStatus,
    onInputChange: (String) -> Unit,
    onToggleLocale: () -> Unit,
    onToggleMic: () -> Unit,
    onReadScreenPillClick: () -> Unit,
    onSendPrompt: () -> Unit,
    onStopAudio: () -> Unit,
    onDismiss: () -> Unit
) {
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
                    color = GeminiBlue.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(28.dp)
                )
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(28.dp),
            color = GeminiSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(GeminiTextSecondary.copy(alpha = 0.45f))
                )
                Spacer(Modifier.height(10.dp))

                // Header row: Gemini spark + Live RAM pill + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = GeminiViolet,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Malayalam AI",
                        color = GeminiTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${ramStatus.freeRamMb} MB free",
                        color = GeminiMint,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(GeminiCard)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = GeminiTextSecondary)
                    }
                }

                // Instant Audio Playback Bar with Stop button
                if (playbackState.isPlaying) {
                    Spacer(Modifier.height(8.dp))
                    AudioPlaybackBar(
                        playbackState = playbackState,
                        onStop = onStopAudio
                    )
                }

                // Pills Row: Read Screen + Mic Language Toggle
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = onReadScreenPillClick,
                        label = {
                            Text(
                                text = if (state.isUsingScreenContext) "Screen Attached ✓" else "Read Screen",
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ScreenSearchDesktop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (state.isUsingScreenContext) GeminiBlue.copy(alpha = 0.22f) else GeminiCard,
                            labelColor = if (state.isUsingScreenContext) GeminiBlue else GeminiTextPrimary,
                            leadingIconContentColor = GeminiBlue
                        )
                    )

                    AssistChip(
                        onClick = onToggleLocale,
                        label = {
                            Text(
                                text = if (state.speechLocale == "ml-IN") "Mic: മലയാളം (ml-IN)" else "Mic: English (en-IN)",
                                fontSize = 12.sp
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = GeminiCard,
                            labelColor = GeminiTextPrimary
                        )
                    )
                }

                if (state.isThinking) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = GeminiBlue,
                        trackColor = GeminiCard
                    )
                }

                // Assistant reply bubble
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GeminiCard)
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.assistantResponse,
                        color = GeminiTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }

                // Bottom Gemini Composer Bar
                Spacer(Modifier.height(12.dp))
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
                                text = if (state.isListeningMic) "Listening (${state.speechLocale})..." else "Ask or set alarm, call, todo...",
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
                        onClick = onToggleMic,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (state.isListeningMic) GeminiCoral else GeminiCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (state.isListeningMic) Color.Black else GeminiBlue
                        )
                    }

                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onSendPrompt,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(GeminiBlue)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color(0xFF081426)
                        )
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
            .background(GeminiViolet.copy(alpha = 0.16f))
            .border(1.dp, GeminiViolet.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = GeminiViolet,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Playing: ${playbackState.activeTitle}",
                color = GeminiTextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playbackState.activeTranscript.isNotBlank()) {
                Text(
                    text = playbackState.activeTranscript,
                    color = GeminiTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(GeminiCoral)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop Audio",
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
