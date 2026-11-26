package com.malayalam.assistant.ui.tabs

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.malayalam.assistant.audio.PlaybackState
import com.malayalam.assistant.data.TodoEntity
import com.malayalam.assistant.ui.ChatTurn
import com.malayalam.assistant.ui.components.FourPointSparkleButton
import com.malayalam.assistant.ui.components.GlowingWaveformOrb
import com.malayalam.assistant.ui.components.SoftGlowIconButton
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiCoral
import com.malayalam.assistant.ui.theme.GeminiMint
import com.malayalam.assistant.ui.theme.GeminiPill
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.GeminiViolet
import com.malayalam.assistant.ui.theme.WavePurple
import com.malayalam.assistant.ui.theme.matteCharcoalGradient

/**
 * Minimalist Dark Voice-First Home Interface:
 *  - Full-screen matte dark charcoal gradient (`#18191D` -> `#212228`).
 *  - Top-left `☰` Hamburger Menu & Top-right `⚙️` Settings Gear with subtle radial glow.
 *  - Center Hero: `നമസ്കാരം` greeting (transitions smoothly to `കേൾക്കുന്നു...`, live reply, or playing clip + Stop pill)
 *    above the `172.dp` Glowing Cyan-to-Violet Waveform Orb.
 *  - Bottom-right subtle `✦` four-point sparkle toggling a slide-up Text Input & Quick Actions sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimalVoiceHomeScreen(
    heroText: String,
    playbackState: PlaybackState,
    chatHistory: List<ChatTurn>,
    speechLocale: String,
    isMicListening: Boolean,
    micRmsLevel: Float,
    onOrbClick: () -> Unit,
    onStopAudio: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendTypedPrompt: (String) -> Unit,
    onToggleLocale: () -> Unit
) {
    var showSparkleInputSheet by rememberSaveable { mutableStateOf(false) }

    val displayHeadline = when {
        isMicListening -> if (speechLocale.startsWith("ml")) "കേൾക്കുന്നു..." else "Listening..."
        playbackState.isPlaying -> playbackState.activeTitle.ifBlank { "Playing Dialogue..." }
        else -> heroText
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(matteCharcoalGradient())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Minimal Top Bar (☰ Left, ⚙️ Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftGlowIconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            SoftGlowIconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(23.dp)
                )
            }
        }

        // 2. Center Hero Area: Greeting / Live Status + Glowing Waveform Orb
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Crossfade(targetState = displayHeadline, label = "hero_headline") { headline ->
                val isShortGreeting = headline.length <= 18
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = if (isShortGreeting) 30.sp else 20.sp,
                    lineHeight = if (isShortGreeting) 38.sp else 28.sp,
                    fontWeight = if (isShortGreeting) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Compact Stop pill when a Malayalam dialogue clip is actively playing
            if (playbackState.isPlaying) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(GeminiCard)
                        .border(1.dp, WavePurple.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .clickable(onClick = onStopAudio)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = GeminiCoral,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (speechLocale.startsWith("ml")) "നിർത്തുക (Stop)" else "Stop Audio",
                        color = GeminiTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Spacer(Modifier.height(22.dp))
            }

            Spacer(Modifier.height(14.dp))

            GlowingWaveformOrb(
                isListening = isMicListening,
                isPlaying = playbackState.isPlaying,
                micLevel = micRmsLevel,
                onClick = onOrbClick
            )
        }

        // 3. Clutter-Free Bottom Area: Subtle Four-Point Sparkle (`✦`) in Bottom-Right
        FourPointSparkleButton(
            onClick = { showSparkleInputSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 16.dp)
        )
    }

    // Slide-Up Minimalist Text Input & Quick Actions Sheet (toggled by `✦`)
    if (showSparkleInputSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var typedText by rememberSaveable { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showSparkleInputSheet = false },
            sheetState = sheetState,
            containerColor = GeminiSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                // Language pill + Quick Action suggestions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = onToggleLocale,
                        label = {
                            Text(
                                text = if (speechLocale.startsWith("ml")) "ഭാഷ: മലയാളം (ML)" else "Lang: English (EN)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = GeminiBlue.copy(alpha = 0.18f),
                            labelColor = GeminiBlue
                        )
                    )
                    listOf(
                        "സെറ്റ് അലാം",
                        "രാവിലെ 6:30 അലാം",
                        "വാട്സ്ആപ്പ് തുറക്ക്",
                        "ചാർജ് എത്ര",
                        "You failed in your exam"
                    ).forEach { chip ->
                        AssistChip(
                            onClick = {
                                onSendTypedPrompt(chip)
                                showSparkleInputSheet = false
                            },
                            label = { Text(chip, fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = GeminiCard,
                                labelColor = GeminiTextSecondary
                            )
                        )
                    }
                }

                // Show last 3 messages subtly if any exist
                val recent = chatHistory.takeLast(3)
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { turn ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (turn.isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Text(
                                    text = turn.text,
                                    color = if (turn.isUser) GeminiBlue else GeminiTextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (turn.isUser) GeminiBlue.copy(alpha = 0.14f) else GeminiCard)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { typedText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = if (speechLocale.startsWith("ml")) {
                                    "ടൈപ്പ് ചെയ്യുക (ഉദാ: സെറ്റ് അലാം, വാട്സ്ആപ്പ് തുറക്ക്)..."
                                } else {
                                    "Type a command (e.g. set an alarm, open WhatsApp)..."
                                },
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
                        onClick = {
                            if (typedText.isNotBlank()) {
                                val send = typedText
                                typedText = ""
                                onSendTypedPrompt(send)
                                showSparkleInputSheet = false
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
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
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Secondary Drawer Destination: Recent Activity / Chat Log + Offline To-Do List.
 */
@Composable
fun RecentActivityScreen(
    chatHistory: List<ChatTurn>,
    todos: List<TodoEntity>,
    onBack: () -> Unit,
    onToggleTodo: (Long, Boolean) -> Unit,
    onDeleteTodo: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(matteCharcoalGradient())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Recent Activity & To-Dos",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (todos.isNotEmpty()) {
                item {
                    Text(
                        text = "Saved Reminders / To-Dos",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
                items(todos, key = { "todo_${it.id}" }) { todo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(GeminiSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (todo.isCompleted) GeminiMint else GeminiBlue,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onToggleTodo(todo.id, !todo.isCompleted) }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = todo.task,
                            color = if (todo.isCompleted) GeminiTextSecondary else GeminiTextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeleteTodo(todo.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = GeminiTextSecondary)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Voice & Command Log",
                    color = GeminiBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (chatHistory.isEmpty()) {
                item {
                    Text(
                        text = "No commands spoken yet. Tap the glowing orb on Home to speak!",
                        color = GeminiTextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                items(chatHistory.reversed(), key = { it.id }) { turn ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (turn.isUser) GeminiBlue.copy(alpha = 0.14f) else GeminiSurface)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (turn.isUser) "You" else turn.badge.ifBlank { "Assistant" },
                            color = if (turn.isUser) GeminiBlue else GeminiViolet,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(text = turn.text, color = GeminiTextPrimary, fontSize = 14.sp)
                        if (turn.triggeredClipTitle != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "🔊 ${turn.triggeredClipTitle}",
                                color = GeminiMint,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
