package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.audio.PlaybackState
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.PendingReviewEntity
import com.malayalam.assistant.data.SituationCategory
import com.malayalam.assistant.ui.theme.GeminiAmber
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiCoral
import com.malayalam.assistant.ui.theme.GeminiMint
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.GeminiViolet
import com.malayalam.assistant.worker.PipelineProgressUi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogueVaultTab(
    safTreeUri: String,
    dialogues: List<DialogueEntry>,
    pendingReviews: List<PendingReviewEntity>,
    pipelineProgress: PipelineProgressUi,
    playbackState: PlaybackState,
    simulatorResults: List<MatchCandidate>,
    matchThreshold: Float,
    bannerMessage: String,
    onPickSafFolder: () -> Unit,
    onAddDialogueFile: () -> Unit,
    onScanSafFolder: () -> Unit,
    onPlayDialogue: (DialogueEntry) -> Unit,
    onPlayPendingUri: (PendingReviewEntity) -> Unit,
    onStopAudio: () -> Unit,
    onConfirmPending: (PendingReviewEntity, String, String, List<String>) -> Unit,
    onAutoAcceptAllPending: () -> Unit,
    onDismissPending: (String) -> Unit,
    onSaveEditedDialogue: (DialogueEntry, String, String, String) -> Unit,
    onDeleteDialogue: (String) -> Unit,
    onRunSimulator: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var simulatorQuery by rememberSaveable { mutableStateOf("You failed in your exam") }
    var editingEntry by remember { mutableStateOf<DialogueEntry?>(null) }

    val filteredDialogues = remember(dialogues, searchQuery) {
        if (searchQuery.isBlank()) dialogues
        else {
            val q = searchQuery.lowercase()
            dialogues.filter { entry ->
                entry.title.lowercase().contains(q) ||
                    entry.heardText.lowercase().contains(q) ||
                    entry.category.lowercase().contains(q) ||
                    entry.situations.any { it.lowercase().contains(q) }
            }
        }
    }

    val groupedByCategory = remember(filteredDialogues) {
        filteredDialogues.groupBy { SituationCategory.fromId(it.category) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. SAF Vault Folder & Action Buttons
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(GeminiSurface)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = GeminiBlue)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (safTreeUri.isBlank()) "Pick Dedicated Folder (SAF)" else "SAF Folder Connected (.nomedia active)",
                            color = GeminiTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (safTreeUri.isBlank()) {
                                "Select e.g. Documents/MalayalamAssistant/ so clips & dialogues.json survive reinstalls."
                            } else {
                                "Syncing dialogues.json + embeddings (${dialogues.size} clips)"
                            },
                            color = GeminiTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = onPickSafFolder) {
                        Text(if (safTreeUri.isBlank()) "Select" else "Change", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAddDialogueFile,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Add Dialogue", fontSize = 12.sp, color = Color(0xFF081426))
                    }
                    OutlinedButton(
                        onClick = onScanSafFolder,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan & Auto-Organize", fontSize = 12.sp)
                    }
                }

                if (bannerMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = bannerMessage, color = GeminiMint, fontSize = 12.sp)
                }
            }
        }

        // 2. Live Sequential WorkManager Pipeline Progress Banner
        if (pipelineProgress.isRunning) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GeminiCard)
                        .border(1.dp, GeminiViolet.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Sequential 4 GB Low-RAM Pipeline (Step ${pipelineProgress.stepIndex}/3)",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pipelineProgress.stageLabel,
                        color = GeminiTextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (pipelineProgress.stepIndex / 3f).coerceIn(0.15f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = GeminiViolet,
                        trackColor = GeminiSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Listening... → Understanding Situation... → Indexing...",
                        color = GeminiTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Review & Confirm Cards (AI Heard & Classified Dialogues)
        if (pendingReviews.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Review & Confirm (${pendingReviews.size})",
                        color = GeminiAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onAutoAcceptAllPending) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, tint = GeminiMint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-Accept All", color = GeminiMint, fontSize = 12.sp)
                    }
                }
            }

            items(pendingReviews, key = { it.id }) { item ->
                ReviewAndConfirmCard(
                    item = item,
                    isPlaying = playbackState.isPlaying && playbackState.activeDialogueId == item.id,
                    onPlayToggle = {
                        if (playbackState.isPlaying && playbackState.activeDialogueId == item.id) onStopAudio()
                        else onPlayPendingUri(item)
                    },
                    onConfirm = { title, cat, chips -> onConfirmPending(item, title, cat, chips) },
                    onDismiss = { onDismissPending(item.id) }
                )
            }
        }

        // 4. Test Simulator Box (Top 3 Match Scores + Play Winning Clip)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(GeminiSurface)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Test Simulator (Top 3 Match Scores)",
                    color = GeminiBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = simulatorQuery,
                        onValueChange = { simulatorQuery = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Simulate Notification / Situation", fontSize = 12.sp) },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onRunSimulator(simulatorQuery) },
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiViolet)
                    ) {
                        Text("Test", color = Color(0xFF1A092B), fontWeight = FontWeight.SemiBold)
                    }
                }

                if (simulatorResults.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    simulatorResults.forEachIndexed { idx, candidate ->
                        val passes = candidate.score >= matchThreshold
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GeminiCard)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${idx + 1}",
                                color = if (idx == 0 && passes) GeminiMint else GeminiTextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.entry.title,
                                    color = GeminiTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${if (candidate.usedNeuralEmbedding) "EmbeddingGemma" else "TF-IDF Fallback"} • ${candidate.entry.category}",
                                    color = GeminiTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = String.format("%.2f", candidate.score),
                                color = if (passes) GeminiMint else GeminiAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            IconButton(onClick = { onPlayDialogue(candidate.entry) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = GeminiBlue)
                            }
                        }
                    }
                }
            }
        }

        // 5. Search Bar & Grouped Dialogue Vault
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search Malayalam transcript, English or Manglish tags...", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (groupedByCategory.isEmpty()) {
            item {
                Text(
                    text = "No dialogues indexed yet. Tap '+ Add Dialogue' or drop .mp3/.wav/.ogg clips into your SAF folder and tap 'Scan & Auto-Organize'.",
                    color = GeminiTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            groupedByCategory.forEach { (category, entries) ->
                item(key = "header_${category.id}") {
                    Text(
                        text = "${category.displayLabel} (${entries.size})",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    val isPlayingThis = playbackState.isPlaying && playbackState.activeDialogueId == entry.id
                    DialogueItemCard(
                        entry = entry,
                        isPlaying = isPlayingThis,
                        onPlayOrStop = { if (isPlayingThis) onStopAudio() else onPlayDialogue(entry) },
                        onEdit = { editingEntry = entry },
                        onDelete = { onDeleteDialogue(entry.id) }
                    )
                }
            }
        }
    }

    editingEntry?.let { target ->
        EditDialogueModal(
            entry = target,
            onDismiss = { editingEntry = null },
            onSave = { newTitle, newCat, newSituationsCsv ->
                onSaveEditedDialogue(target, newTitle, newCat, newSituationsCsv)
                editingEntry = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewAndConfirmCard(
    item: PendingReviewEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onConfirm: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var category by remember(item.id) { mutableStateOf(item.category) }
    val chips = remember(item.id) {
        mutableStateListOf<String>().apply {
            addAll(item.situationsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
    }
    var newChipText by remember(item.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GeminiCard)
            .border(1.dp, GeminiAmber.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Preview Clip",
                    tint = if (isPlaying) GeminiCoral else GeminiMint
                )
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = GeminiTextSecondary)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Heard Malayalam: ${item.heardText}",
            color = GeminiTextPrimary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SituationCategory.entries.forEach { cat ->
                FilterChip(
                    selected = category == cat.id,
                    onClick = { category = cat.id },
                    label = { Text(cat.id, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            chips.forEach { phrase ->
                AssistChip(
                    onClick = { chips.remove(phrase) },
                    label = { Text("$phrase ✕", fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = GeminiSurface,
                        labelColor = GeminiTextPrimary
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newChipText,
                onValueChange = { newChipText = it },
                placeholder = { Text("Add English/Manglish trigger...", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    if (newChipText.isNotBlank()) {
                        chips.add(newChipText.trim())
                        newChipText = ""
                    }
                }
            ) {
                Text("+ Chip")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onConfirm(title, category, chips.toList()) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GeminiMint)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Confirm & Index Dialogue", color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogueItemCard(
    entry: DialogueEntry,
    isPlaying: Boolean,
    onPlayOrStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GeminiSurface)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayOrStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = if (isPlaying) GeminiCoral else GeminiBlue
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    color = GeminiTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = entry.fileName,
                    color = GeminiTextSecondary,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = GeminiTextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = GeminiCoral, modifier = Modifier.size(18.dp))
            }
        }

        if (entry.heardText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.heardText,
                color = GeminiTextPrimary.copy(alpha = 0.9f),
                fontSize = 12.sp
            )
        }

        if (entry.situations.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.situations.take(6).forEach { sit ->
                    Text(
                        text = sit,
                        color = GeminiBlue,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GeminiCard)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditDialogueModal(
    entry: DialogueEntry,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf(entry.title) }
    var category by remember { mutableStateOf(entry.category) }
    var situationsCsv by remember { mutableStateOf(entry.situations.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Dialogue Situations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (exam_fail, money, alarm...)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = situationsCsv,
                    onValueChange = { situationsCsv = it },
                    label = { Text("Trigger Situations (comma-separated)") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, category, situationsCsv) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
