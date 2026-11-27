package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.malayalam.assistant.ui.DialogueEditorDraft
import com.malayalam.assistant.ui.theme.GeminiAmber
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiCoral
import com.malayalam.assistant.ui.theme.GeminiMint
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.GeminiViolet
import com.malayalam.assistant.ui.theme.matteCharcoalGradient
import com.malayalam.assistant.worker.PipelineProgressUi

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DialogueVaultTab(
    titleHeader: String = "My Dialogues",
    activePackName: String = "My Custom Pack",
    focusSimulatorFirst: Boolean = false,
    safTreeUri: String,
    dialogues: List<DialogueEntry>,
    playableAudioMap: Map<String, Boolean>,
    pendingReviews: List<PendingReviewEntity>,
    pipelineProgress: PipelineProgressUi,
    playbackState: PlaybackState,
    simulatorResults: List<MatchCandidate>,
    simulatorAiNote: String,
    simulatorDiagnostic: com.malayalam.assistant.ui.SimulatorDiagnosticUi?,
    matchThreshold: Float,
    bannerMessage: String,
    editorDraft: DialogueEditorDraft?,
    onBack: () -> Unit,
    onOpenVoicePacks: () -> Unit,
    onOpenAddDialogueSheet: () -> Unit,
    onOpenEditDialogueSheet: (DialogueEntry) -> Unit,
    onCloseEditorSheet: () -> Unit,
    onUpdateEditorDraft: ((DialogueEditorDraft) -> DialogueEditorDraft) -> Unit,
    onPickAudioForEditor: () -> Unit,
    onAutoListenEditorWithAi: () -> Unit,
    onSaveEditorDraft: () -> Unit,
    onPickSafFolder: () -> Unit,
    onAttachMp3ToDialogue: (DialogueEntry) -> Unit,
    onScanSafFolder: () -> Unit,
    onPlayDialogue: (DialogueEntry) -> Unit,
    onPlayRawUri: (String, String, String, String) -> Unit,
    onStopAudio: () -> Unit,
    onConfirmPending: (PendingReviewEntity, String, String, List<String>) -> Unit,
    onAutoAcceptAllPending: () -> Unit,
    onDismissPending: (String) -> Unit,
    onDeleteDialogue: (String) -> Unit,
    onRunSimulator: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var simulatorQuery by rememberSaveable { mutableStateOf("You failed in your exam") }
    var showSimulatorSection by rememberSaveable(focusSimulatorFirst) { mutableStateOf(focusSimulatorFirst) }
    var showSafFolderSection by rememberSaveable { mutableStateOf(false) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(matteCharcoalGradient())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar + Prominent "+ Add My Dialogue" CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = titleHeader,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onOpenAddDialogueSheet,
                    colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF071324))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Dialogue", color = Color(0xFF071324), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (bannerMessage.isNotBlank()) {
                    item {
                        Text(text = bannerMessage, color = GeminiMint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }

                // Compact Collapsible Tools Row (Test Simulator + Audio Folder)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CollapsibleHeaderCard(
                            title = "Test Simulator",
                            subtitle = "Test 'You failed in your exam' or 'eda paisa ayachittund'",
                            expanded = showSimulatorSection,
                            onToggle = { showSimulatorSection = !showSimulatorSection }
                        ) {
                            SimulatorCardContent(
                                simulatorQuery = simulatorQuery,
                                onQueryChange = { simulatorQuery = it },
                                simulatorResults = simulatorResults,
                                simulatorAiNote = simulatorAiNote,
                                simulatorDiagnostic = simulatorDiagnostic,
                                playableAudioMap = playableAudioMap,
                                matchThreshold = matchThreshold,
                                onRunSimulator = onRunSimulator,
                                onPlayDialogue = onPlayDialogue,
                                onAttachMp3 = onAttachMp3ToDialogue
                            )
                        }

                        CollapsibleHeaderCard(
                            title = "Audio Backup Folder (Optional SAF)",
                            subtitle = if (safTreeUri.isBlank()) "Optional folder sync for reinstalls" else "SAF folder linked (.nomedia)",
                            expanded = showSafFolderSection,
                            onToggle = { showSafFolderSection = !showSafFolderSection }
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onPickSafFolder, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (safTreeUri.isBlank()) "Pick Folder" else "Change Folder", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(onClick = onScanSafFolder, modifier = Modifier.weight(1f)) {
                                    Text("Scan Folder", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                // Sequential Pipeline Banner (if scanning folder)
                if (pipelineProgress.isRunning) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(GeminiCard)
                                .padding(12.dp)
                        ) {
                            Text(text = pipelineProgress.stageLabel, color = GeminiViolet, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (pipelineProgress.stepIndex / 3f).coerceIn(0.15f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = GeminiViolet
                            )
                        }
                    }
                }

                // Pending Review Cards (if any)
                if (pendingReviews.isNotEmpty()) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Review & Confirm (${pendingReviews.size})",
                                color = GeminiAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onAutoAcceptAllPending) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, tint = GeminiMint, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Accept All", color = GeminiMint, fontSize = 12.sp)
                            }
                        }
                    }
                    items(pendingReviews, key = { it.id }) { item ->
                        ReviewAndConfirmCard(
                            item = item,
                            isPlaying = playbackState.isPlaying && playbackState.activeDialogueId == item.id,
                            onPlayToggle = {
                                if (playbackState.isPlaying && playbackState.activeDialogueId == item.id) onStopAudio()
                                else onPlayRawUri(item.safUriString, item.id, item.title, item.heardText)
                            },
                            onConfirm = { title, cat, chips -> onConfirmPending(item, title, cat, chips) },
                            onDismiss = { onDismissPending(item.id) }
                        )
                    }
                }

                // My Dialogues Section Immediately Visible + Quick Pack Switch Chip!
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "My Dialogues (${filteredDialogues.size})",
                            color = GeminiViolet,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = onOpenVoicePacks,
                            label = { Text("🎬 $activePackName", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = GeminiCard,
                                labelColor = GeminiBlue
                            )
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Search name, Malayalam, or Manglish trigger...", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                items(filteredDialogues, key = { it.id }) { entry ->
                    val isPlayingThis = playbackState.isPlaying && playbackState.activeDialogueId == entry.id
                    val isAudioLinked = playableAudioMap[entry.id] == true
                    DialogueItemCard(
                        entry = entry,
                        isAudioLinked = isAudioLinked,
                        isPlaying = isPlayingThis,
                        onPlayOrStop = {
                            if (isPlayingThis) onStopAudio()
                            else if (isAudioLinked) onPlayDialogue(entry)
                            else onAttachMp3ToDialogue(entry)
                        },
                        onAttachMp3 = { onAttachMp3ToDialogue(entry) },
                        onEdit = { onOpenEditDialogueSheet(entry) },
                        onDelete = { onDeleteDialogue(entry.id) }
                    )
                }

                item { Spacer(Modifier.height(84.dp)) }
            }
        }

        // Prominent "+ Add My Dialogue" FloatingActionButton
        ExtendedFloatingActionButton(
            onClick = onOpenAddDialogueSheet,
            containerColor = GeminiBlue,
            contentColor = Color(0xFF071324),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add My Dialogue", fontWeight = FontWeight.SemiBold) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
        )
    }

    // Super-Clean "Add / Edit Dialogue" Bottom Sheet with ✨ AI Auto-Listen!
    if (editorDraft != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCloseEditorSheet,
            sheetState = sheetState,
            containerColor = GeminiSurface
        ) {
            AddEditDialogueSheetContent(
                draft = editorDraft,
                isPreviewPlaying = playbackState.isPlaying && playbackState.activeDialogueId == "editor_preview",
                onUpdate = onUpdateEditorDraft,
                onPickAudio = onPickAudioForEditor,
                onPreviewToggle = {
                    if (playbackState.isPlaying && playbackState.activeDialogueId == "editor_preview") {
                        onStopAudio()
                    } else if (editorDraft.audioUriString.isNotBlank()) {
                        onPlayRawUri(editorDraft.audioUriString, "editor_preview", editorDraft.title.ifBlank { editorDraft.fileName }, editorDraft.heardText)
                    }
                },
                onAutoListenWithAi = onAutoListenEditorWithAi,
                onSave = onSaveEditorDraft
            )
        }
    }
}

@Composable
private fun AddEditDialogueSheetContent(
    draft: DialogueEditorDraft,
    isPreviewPlaying: Boolean,
    onUpdate: ((DialogueEditorDraft) -> DialogueEditorDraft) -> Unit,
    onPickAudio: () -> Unit,
    onPreviewToggle: () -> Unit,
    onAutoListenWithAi: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (draft.existingId == null) "Add My Dialogue" else "Edit Dialogue",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )

        // 1. Pick Audio Clip + Instant Play Preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPickAudio, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (draft.fileName.isBlank()) "1. Pick Audio (.mp3/.wav)" else "Audio: ${draft.fileName.take(18)}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (draft.audioUriString.isNotBlank()) {
                Button(
                    onClick = onPreviewToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPreviewPlaying) GeminiCoral else GeminiMint
                    )
                ) {
                    Icon(
                        imageVector = if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Preview",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPreviewPlaying) "Stop" else "Play", color = Color.Black, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }

        // 2. "✨ Auto-Listen & Fill with AI"
        Button(
            onClick = onAutoListenWithAi,
            enabled = draft.audioUriString.isNotBlank() && !draft.isAutoTagging,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GeminiViolet)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF160829), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (draft.isAutoTagging) "Listening with Gemini..." else "✨ Auto-Listen & Fill with AI",
                color = Color(0xFF160829),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }

        if (draft.isAutoTagging) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GeminiViolet)
        }
        if (draft.statusNote.isNotBlank()) {
            Text(text = draft.statusNote, color = GeminiMint, fontSize = 11.sp)
        }

        // 3. Dialogue Name + Situations + Trigger Apps
        OutlinedTextField(
            value = draft.title,
            onValueChange = { v -> onUpdate { it.copy(title = v) } },
            label = { Text("Dialogue Name (e.g. Njan veendum kodeeshwaran)", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = draft.situationsCsv,
            onValueChange = { v -> onUpdate { it.copy(situationsCsv = v) } },
            label = { Text("When to Play (Manglish / Malayalam / English)", fontSize = 12.sp) },
            placeholder = { Text("eda paisa ayachittund, money received, salary credited, GPay credit", fontSize = 12.sp) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        // Scope Toggle: Specific Event vs Broad Mood (for Specificity-First Semantic Rubric!)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Scope:", color = GeminiTextSecondary, fontSize = 12.sp)
            FilterChip(
                selected = draft.scope != "broad",
                onClick = { onUpdate { it.copy(scope = "specific") } },
                label = { Text("Specific Event", fontSize = 12.sp) }
            )
            FilterChip(
                selected = draft.scope == "broad",
                onClick = { onUpdate { it.copy(scope = "broad") } },
                label = { Text("Broad Mood", fontSize = 12.sp) }
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Apps:", color = GeminiTextSecondary, fontSize = 12.sp)
            listOf("ALL" to "All", "WHATSAPP" to "WhatsApp", "SMS" to "SMS", "GMAIL" to "Gmail").forEach { (key, label) ->
                val selected = key in draft.triggerApps
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdate { cur ->
                            val next = cur.triggerApps.toMutableSet()
                            if (key == "ALL") {
                                next.clear(); next.add("ALL")
                            } else {
                                next.remove("ALL")
                                if (selected) next.remove(key) else next.add(key)
                                if (next.isEmpty()) next.add("ALL")
                            }
                            cur.copy(triggerApps = next)
                        }
                    },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // 4. Save Dialogue Button
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF071324))
            Spacer(Modifier.width(6.dp))
            Text("Save Dialogue", color = Color(0xFF071324), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CollapsibleHeaderCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GeminiSurface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = GeminiBlue, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(text = subtitle, color = GeminiTextSecondary, fontSize = 11.sp)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Toggle",
                tint = GeminiTextSecondary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SimulatorCardContent(
    simulatorQuery: String,
    onQueryChange: (String) -> Unit,
    simulatorResults: List<MatchCandidate>,
    simulatorAiNote: String,
    simulatorDiagnostic: com.malayalam.assistant.ui.SimulatorDiagnosticUi?,
    playableAudioMap: Map<String, Boolean>,
    matchThreshold: Float,
    onRunSimulator: (String) -> Unit,
    onPlayDialogue: (DialogueEntry) -> Unit,
    onAttachMp3: (DialogueEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1-Tap Quick-Test Chips (Pure Malayalam + Manglish cross-lingual test cases)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "എക്സാം പൊട്ടി",
                "pareeksha potty",
                "മാച്ച് തോറ്റു",
                "എടാ പൈസ അയച്ചിട്ടുണ്ട്",
                "എക്സാം പാസ്സായി"
            ).forEach { chip ->
                AssistChip(
                    onClick = {
                        onQueryChange(chip)
                        onRunSimulator(chip)
                    },
                    label = { Text(chip, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = GeminiCard,
                        labelColor = GeminiBlue
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = simulatorQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("Type Malayalam / Manglish / English", fontSize = 12.sp) },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onRunSimulator(simulatorQuery) },
                colors = ButtonDefaults.buttonColors(containerColor = GeminiViolet)
            ) {
                Text("Test", color = Color(0xFF160829), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }

        if (simulatorDiagnostic != null) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GeminiCard)
                    .border(1.dp, GeminiViolet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "🧠 Understood Meaning: ${simulatorDiagnostic.understoodMeaning.ifBlank { "—" }}",
                    color = GeminiTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "🎬 Matched Dialogue: ${simulatorDiagnostic.matchedDialogueName}",
                    color = GeminiMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "⏱ Latency: ${simulatorDiagnostic.latencyMs} ms  •  API: ${simulatorDiagnostic.apiStatus}",
                    color = if (simulatorDiagnostic.apiStatus.contains("HTTP 4") || simulatorDiagnostic.apiStatus.contains("Network")) GeminiCoral else GeminiBlue,
                    fontSize = 11.sp
                )
            }
        } else if (simulatorAiNote.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = simulatorAiNote, color = GeminiMint, fontSize = 12.sp)
        }

        if (simulatorResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            simulatorResults.forEachIndexed { idx, candidate ->
                val passes = candidate.score >= matchThreshold.coerceAtLeast(0.25f)
                val linked = playableAudioMap[candidate.entry.id] == true
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
                        Text(text = candidate.entry.title, color = GeminiTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (linked) "✅ Audio linked" else "⚠️ Tap to pick MP3",
                            color = if (linked) GeminiMint else GeminiAmber,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { if (!linked) onAttachMp3(candidate.entry) }
                        )
                    }
                    Text(
                        text = String.format("%.2f", candidate.score),
                        color = if (passes) GeminiMint else GeminiTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    IconButton(
                        onClick = { if (linked) onPlayDialogue(candidate.entry) else onAttachMp3(candidate.entry) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (linked) Icons.Default.PlayArrow else Icons.Default.AudioFile,
                            contentDescription = "Play or Link",
                            tint = if (linked) GeminiBlue else GeminiAmber
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogueItemCard(
    entry: DialogueEntry,
    isAudioLinked: Boolean,
    isPlaying: Boolean,
    onPlayOrStop: () -> Unit,
    onAttachMp3: () -> Unit,
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
            IconButton(onClick = onPlayOrStop, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = when {
                        isPlaying -> Icons.Default.Stop
                        isAudioLinked -> Icons.Default.PlayArrow
                        else -> Icons.Default.AudioFile
                    },
                    contentDescription = "Play or Link MP3",
                    tint = when {
                        isPlaying -> GeminiCoral
                        isAudioLinked -> GeminiMint
                        else -> GeminiAmber
                    }
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.title, color = GeminiTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (isAudioLinked) {
                        "✅ Audio linked (${entry.fileName.ifBlank { "clip" }}) • Replace"
                    } else {
                        "⚠️ Tap to pick MP3 / WAV"
                    },
                    color = if (isAudioLinked) GeminiMint else GeminiAmber,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GeminiCard)
                        .clickable(onClick = onAttachMp3)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = GeminiTextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = GeminiCoral, modifier = Modifier.size(18.dp))
            }
        }

        if (entry.situations.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.situations.take(7).forEach { sit ->
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GeminiCard)
            .border(1.dp, GeminiAmber.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayToggle, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Preview",
                    tint = if (isPlaying) GeminiCoral else GeminiMint
                )
            }
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chips.forEach { phrase ->
                AssistChip(
                    onClick = { chips.remove(phrase) },
                    label = { Text("$phrase ✕", fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = GeminiSurface, labelColor = GeminiTextPrimary)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { onConfirm(title, category, chips.toList()) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GeminiMint)
        ) {
            Text("Confirm & Save", color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}
