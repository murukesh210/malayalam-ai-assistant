package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.ai.RamStatus
import com.malayalam.assistant.audio.PlaybackState
import com.malayalam.assistant.data.TodoEntity
import com.malayalam.assistant.ui.ChatTurn
import com.malayalam.assistant.ui.overlay.AudioPlaybackBar
import com.malayalam.assistant.ui.theme.GeminiAmber
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
fun AssistantTab(
    ramStatus: RamStatus,
    playbackState: PlaybackState,
    chatHistory: List<ChatTurn>,
    todos: List<TodoEntity>,
    isBusy: Boolean,
    speechLocale: String,
    isMicListening: Boolean,
    onSendPrompt: (String) -> Unit,
    onStartVoiceInput: ((String) -> Unit) -> Unit,
    onToggleLocale: () -> Unit,
    onUnloadRam: () -> Unit,
    onStopAudio: () -> Unit,
    onToggleTodo: (Long, Boolean) -> Unit,
    onDeleteTodo: (Long) -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var showTodos by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top Header + Live RAM Usage Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GeminiSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = GeminiViolet,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Malayalam Assistant",
                    color = GeminiTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (ramStatus.isSystemLowMemory) GeminiCoral else GeminiMint,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${ramStatus.activeModelLabel} • ${ramStatus.freeRamMb}/${ramStatus.totalRamMb} MB free",
                        color = if (ramStatus.activeModel != null) GeminiAmber else GeminiMint,
                        fontSize = 11.sp
                    )
                }
            }
            if (ramStatus.activeModel != null) {
                TextButton(onClick = onUnloadRam) {
                    Text("Free RAM", color = GeminiBlue, fontSize = 12.sp)
                }
            }
        }

        // Instant Playback Bar when a Malayalam clip triggers
        if (playbackState.isPlaying) {
            Spacer(Modifier.height(8.dp))
            AudioPlaybackBar(playbackState = playbackState, onStop = onStopAudio)
        }

        // Quick Action & To-Do Chips
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { showTodos = !showTodos },
                label = { Text("To-Dos (${todos.count { !it.isCompleted }})", fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (showTodos) GeminiBlue.copy(alpha = 0.22f) else GeminiCard,
                    labelColor = GeminiTextPrimary
                )
            )
            listOf(
                "Set alarm for 6:30 AM",
                "Add todo Buy milk",
                "Open WhatsApp",
                "You failed in your exam"
            ).forEach { suggestion ->
                AssistChip(
                    onClick = { onSendPrompt(suggestion) },
                    label = { Text(suggestion, fontSize = 12.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = GeminiCard,
                        labelColor = GeminiTextSecondary
                    )
                )
            }
        }

        // Collapsible Room To-Do List Panel
        if (showTodos && todos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GeminiSurface)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                todos.take(5).forEach { todo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (todo.isCompleted) GeminiMint else GeminiBlue,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onToggleTodo(todo.id, !todo.isCompleted) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = todo.task,
                            color = if (todo.isCompleted) GeminiTextSecondary else GeminiTextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeleteTodo(todo.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = GeminiTextSecondary)
                        }
                    }
                }
            }
        }

        // Chat Stream
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(chatHistory, key = { it.id }) { turn ->
                ChatBubble(turn)
            }
        }

        if (isBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                color = GeminiBlue,
                trackColor = GeminiCard
            )
        }

        // Bottom Gemini-style Composer
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (speechLocale == "ml-IN") "ML" else "EN",
                color = GeminiViolet,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(GeminiCard)
                    .clickable(onClick = onToggleLocale)
                    .padding(horizontal = 9.dp, vertical = 12.dp)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (isMicListening) "Listening ($speechLocale)..." else "Ask Gemini-style offline AI...",
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
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    onStartVoiceInput { recognized ->
                        inputText = recognized
                        if (recognized.isNotBlank()) {
                            onSendPrompt(recognized)
                            inputText = ""
                        }
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isMicListening) GeminiCoral else GeminiCard)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = if (isMicListening) Color.Black else GeminiBlue
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendPrompt(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(44.dp)
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

@Composable
private fun ChatBubble(turn: ChatTurn) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (turn.isUser) 0.82f else 0.94f)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (turn.isUser) 18.dp else 4.dp,
                        bottomEnd = if (turn.isUser) 4.dp else 18.dp
                    )
                )
                .background(if (turn.isUser) GeminiBlue.copy(alpha = 0.22f) else GeminiSurface)
                .padding(12.dp)
        ) {
            Column {
                if (!turn.isUser && turn.badge.isNotBlank()) {
                    Text(
                        text = turn.badge,
                        color = GeminiViolet,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    text = turn.text,
                    color = GeminiTextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (turn.triggeredClipTitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "🔊 Played Malayalam Reaction: ${turn.triggeredClipTitle}",
                        color = GeminiMint,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
