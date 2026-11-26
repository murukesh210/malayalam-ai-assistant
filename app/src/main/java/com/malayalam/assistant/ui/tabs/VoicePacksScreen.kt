package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
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
import com.malayalam.assistant.data.UserSettings
import com.malayalam.assistant.data.VoicePack
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

/**
 * Actor Voice Packs Screen (`Mammootty Pack`, `Mohanlal Pack`, `Suresh Gopi Pack`, `My Custom Pack`,
 * and `All Packs Combined`) with 1-tap `.zip` / `.mlpack` Import, Create, Export, and AI Batch Auto-Tag!
 */
@Composable
fun VoicePacksScreen(
    packs: List<VoicePack>,
    activePackId: String,
    packBannerMessage: String,
    importedPackNeedingAiTagId: String?,
    isBatchAutoTagging: Boolean,
    onBack: () -> Unit,
    onSelectPack: (String) -> Unit,
    onImportPackZip: () -> Unit,
    onCreatePack: (name: String, actor: String, voice: String, persona: String) -> Unit,
    onExportPack: (VoicePack) -> Unit,
    onDeletePack: (String) -> Unit,
    onBatchAutoTagImportedPack: (String) -> Unit,
    onOpenPackDialogues: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(matteCharcoalGradient())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
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
                text = "Actor Voice Packs",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, tint = GeminiBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Pack", color = GeminiBlue, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Action Bar: Import (.zip / .mlpack) + Create New Pack
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onImportPackZip,
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = Color(0xFF071324), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Import (.zip / .mlpack)",
                            color = Color(0xFF071324),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Create Pack", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            if (packBannerMessage.isNotBlank()) {
                item {
                    Text(text = packBannerMessage, color = GeminiMint, fontSize = 12.sp)
                }
            }

            // 1-Tap AI Batch Auto-Tag Banner for Raw Audio ZIP Imports (without pack.json)
            if (importedPackNeedingAiTagId != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(GeminiCard)
                            .border(1.dp, GeminiViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Raw Audio ZIP Imported!",
                            color = GeminiViolet,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "No pack.json was inside the ZIP. Let Gemini listen to every clip and auto-fill names & Manglish situations!",
                            color = GeminiTextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        if (isBatchAutoTagging) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GeminiViolet)
                        } else {
                            Button(
                                onClick = { onBatchAutoTagImportedPack(importedPackNeedingAiTagId) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = GeminiViolet)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF160829), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("✨ Auto-Listen & Tag All Clips with AI", color = Color(0xFF160829), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // "All Packs Combined" Mode Card
            item {
                val isAllSelected = activePackId == VoicePack.ALL_PACKS_ID
                val totalDialogues = packs.sumOf { it.dialogues.size }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GeminiSurface)
                        .border(
                            width = 1.dp,
                            color = if (isAllSelected) GeminiMint else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onSelectPack(VoicePack.ALL_PACKS_ID) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAllSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isAllSelected) GeminiMint else GeminiTextSecondary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "All Packs Combined",
                            color = GeminiTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Match across all ${packs.size} installed packs ($totalDialogues dialogues)",
                            color = GeminiTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    if (isAllSelected) {
                        Text("Active", color = GeminiMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Individual Voice Pack Cards
            items(packs, key = { it.packId }) { pack ->
                val isSelected = activePackId == pack.packId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GeminiSurface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) GeminiBlue else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onSelectPack(pack.packId) }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) GeminiBlue else GeminiTextSecondary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = GeminiViolet, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = pack.packName,
                                    color = GeminiTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
                            Text(
                                text = "Actor: ${pack.actor} • Voice: ${pack.ttsVoice} • ${pack.dialogues.size} clips",
                                color = GeminiTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        if (isSelected) {
                            Text("Active ✅", color = GeminiMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (pack.personaInstruction.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Persona: ${pack.personaInstruction}",
                            color = GeminiTextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onOpenPackDialogues(pack.packId) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Dialogues", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { onExportPack(pack) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        if (pack.packId != VoicePack.DEFAULT_PACK_ID) {
                            TextButton(
                                onClick = { onDeletePack(pack.packId) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = GeminiCoral, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Delete", color = GeminiCoral, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showCreateDialog) {
        CreateVoicePackDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, actor, voice, persona ->
                onCreatePack(name, actor, voice, persona)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CreateVoicePackDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var actor by rememberSaveable { mutableStateOf("") }
    var persona by rememberSaveable { mutableStateOf("") }
    var selectedVoice by rememberSaveable { mutableStateOf("Charon") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Actor Voice Pack") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Pack Name (e.g. Mammootty Pack)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = actor,
                    onValueChange = { actor = it },
                    label = { Text("Actor / Theme (e.g. Mammootty)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text("Persona Style (e.g. Mass Kottayam punch style)") },
                    minLines = 2
                )
                Text("TTS Voice:", fontSize = 12.sp, color = GeminiTextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UserSettings.AVAILABLE_GEMINI_VOICES.forEach { v ->
                        FilterChip(
                            selected = selectedVoice == v,
                            onClick = { selectedVoice = v },
                            label = { Text(v, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name, actor.ifBlank { name }, selectedVoice, persona)
                    }
                }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
