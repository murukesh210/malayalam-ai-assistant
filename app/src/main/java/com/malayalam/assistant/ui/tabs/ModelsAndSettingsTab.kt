package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.ai.ModelInstallState
import com.malayalam.assistant.ai.RamStatus
import com.malayalam.assistant.data.UserSettings
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

private val WATCHABLE_APPS = listOf(
    "com.google.android.gm" to "Gmail",
    "com.whatsapp" to "WhatsApp",
    "com.google.android.apps.messaging" to "Google Messages",
    "org.telegram.messenger" to "Telegram",
    "com.instagram.android" to "Instagram"
)

@Composable
fun ModelsAndSettingsTab(
    ramStatus: RamStatus,
    isDevice2GbClass: Boolean,
    isAndroidGoDevice: Boolean,
    settings: UserSettings,
    modelStates: Map<ManagedModelId, ModelInstallState>,
    isNotificationAccessEnabled: Boolean,
    gmailTestResult: String,
    onBack: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSetTtsReplies: (Boolean) -> Unit,
    onSaveGmailConfig: (String, String) -> Unit,
    onSetGmailWatcherEnabled: (Boolean) -> Unit,
    onTestGmailConnection: (String, String) -> Unit,
    onOpenDefaultAssistantSettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppInfoForRestrictedSettings: () -> Unit,
    onDownloadModel: (ManagedModelId) -> Unit,
    onImportLocalModelFile: (ManagedModelId) -> Unit,
    onDeleteModel: (ManagedModelId) -> Unit,
    onSetUltraLight2GbMode: (Boolean) -> Unit,
    onSetLowRamSafeMode: (Boolean) -> Unit,
    onSetEnableGemma4E2b: (Boolean) -> Unit,
    onSetMatchThreshold: (Float) -> Unit,
    onSetHeadphonesOnly: (Boolean) -> Unit,
    onSetLowBatteryReaction: (Boolean) -> Unit,
    onSetAutoAcceptBulk: (Boolean) -> Unit,
    onToggleWatchedPackage: (String, Boolean) -> Unit,
    onSaveHfToken: (String) -> Unit
) {
    var gmailInput by rememberSaveable(settings.gmailAddress) { mutableStateOf(settings.gmailAddress) }
    var gmailPassInput by rememberSaveable { mutableStateOf("") }
    var tokenInput by rememberSaveable(settings.hfAuthToken) { mutableStateOf(settings.hfAuthToken) }
    var confirmHeavyModelWarning by remember { mutableStateOf<ManagedModelId?>(null) }

    val coreModels = ManagedModelId.entries.filter { !it.isOptional8GbOnly || settings.enableGemma4E2bOptional }

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
                text = "Settings & Models",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Language (ML / EN), Voice TTS & Default Assistant
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Voice Language & Default Assistant",
                        color = GeminiBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.isMalayalamMode,
                            onClick = { if (!settings.isMalayalamMode) onToggleLanguage() },
                            label = { Text("മലയാളം (ML Default)") }
                        )
                        FilterChip(
                            selected = !settings.isMalayalamMode,
                            onClick = { if (settings.isMalayalamMode) onToggleLanguage() },
                            label = { Text("English (EN)") }
                        )
                    }

                    SettingToggleRow(
                        title = "Speak Short Replies Aloud (Built-in TTS)",
                        subtitle = "Uses Android offline ml-IN / en-IN voice when no movie clip plays",
                        checked = settings.ttsVoiceRepliesEnabled,
                        onCheckedChange = onSetTtsReplies
                    )

                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onOpenDefaultAssistantSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                    ) {
                        Text(
                            text = "Set as Default Digital Assistant",
                            color = Color(0xFF071324),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 2. Built-In Gmail Direct Watcher (Works on ALL Phones / Android Go / 2 GB RAM!)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .border(1.dp, GeminiMint.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gmail Direct Watcher (Works on Android Go!)",
                                color = GeminiMint,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Lightweight SSL IMAP (imap.gmail.com:993) polling UNSEEN mail every 20s • Tracks UID so emails never repeat.",
                                color = GeminiTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = settings.gmailWatcherEnabled,
                            onCheckedChange = { enabled ->
                                if (gmailInput.isNotBlank() && gmailPassInput.isNotBlank()) {
                                    onSaveGmailConfig(gmailInput, gmailPassInput)
                                    gmailPassInput = ""
                                }
                                onSetGmailWatcherEnabled(enabled)
                            }
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = gmailInput,
                        onValueChange = { gmailInput = it },
                        label = { Text("Gmail Address (you@gmail.com)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gmailPassInput,
                        onValueChange = { gmailPassInput = it },
                        label = {
                            Text(
                                text = if (settings.gmailEncryptedAppPassword.isNotBlank()) {
                                    "16-char Google App Password (Saved ✅ — type to replace)"
                                } else {
                                    "16-char Google App Password"
                                },
                                fontSize = 12.sp
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSaveGmailConfig(gmailInput, gmailPassInput)
                                gmailPassInput = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Login", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { onTestGmailConnection(gmailInput, gmailPassInput) },
                            modifier = Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiMint)
                        ) {
                            Text(
                                text = "Test Gmail & Latest Mail",
                                color = Color(0xFF062016),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Status: ${settings.gmailLastStatus}",
                        color = GeminiTextSecondary,
                        fontSize = 11.sp
                    )
                    if (gmailTestResult.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = gmailTestResult,
                            color = if (gmailTestResult.startsWith("❌")) GeminiCoral else GeminiMint,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 3. Notification Listener + Android Go Detection + Deduplication Settings
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "App Notification Reactions",
                            color = GeminiBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isNotificationAccessEnabled) "Active ✅" else "Off",
                            color = if (isNotificationAccessEnabled) GeminiMint else GeminiAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (isAndroidGoDevice) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "⚠️ Android (Go edition) / Low-RAM OS detected: Android Go blocks Notification Access at the OS level. Use 'Gmail Direct Watcher' above for automatic email dialogue reactions!",
                            color = GeminiAmber,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Android 13+ Sideloaded APK Tip: If Notification Access is restricted, tap 'Open App Info (⋮)' → 'Allow restricted settings'.",
                            color = GeminiTextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenNotificationAccessSettings,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiCard)
                        ) {
                            Text("Notification Access", color = GeminiTextPrimary, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onOpenAppInfoForRestrictedSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open App Info (⋮)", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Match Sensitivity Threshold: ${String.format("%.2f", settings.matchThreshold)} (60s anti-repeat cooldown)",
                        color = GeminiTextPrimary,
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.matchThreshold,
                        onValueChange = onSetMatchThreshold,
                        valueRange = 0.25f..0.85f
                    )

                    SettingToggleRow(
                        title = "Headphones / Bluetooth Only",
                        subtitle = "Play background reactions only on earphones or Bluetooth audio",
                        checked = settings.headphonesOrBluetoothOnly,
                        onCheckedChange = onSetHeadphonesOnly
                    )

                    SettingToggleRow(
                        title = "Low-Battery Broadcast Reaction",
                        subtitle = "Play Malayalam low-battery clip on ACTION_BATTERY_LOW",
                        checked = settings.lowBatteryReactionEnabled,
                        onCheckedChange = onSetLowBatteryReaction
                    )

                    SettingToggleRow(
                        title = "Auto-Accept Bulk Audio Imports",
                        subtitle = "Save scanned folder clips directly without manual review",
                        checked = settings.autoAcceptBulkImports,
                        onCheckedChange = onSetAutoAcceptBulk
                    )

                    Spacer(Modifier.height(6.dp))
                    WATCHABLE_APPS.forEach { (pkg, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = pkg in settings.watchedPackages,
                                onCheckedChange = { checked -> onToggleWatchedPackage(pkg, checked) }
                            )
                            Text(text = label, color = GeminiTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 4. 2 GB / 4 GB RAM Discipline & Optional On-Device AI Models
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "RAM Safe Mode & Optional AI Models",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Detected RAM: ${ramStatus.freeRamMb} / ${ramStatus.totalRamMb} MB free • ${ramStatus.activeModelLabel}",
                        color = GeminiTextSecondary,
                        fontSize = 12.sp
                    )

                    SettingToggleRow(
                        title = "Ultra-Light Mode (2 GB RAM Safe)",
                        subtitle = if (isDevice2GbClass) {
                            "Auto-enabled for your < 2.5 GB RAM phone — keeps 0 heavy LLMs in RAM"
                        } else {
                            "Runs commands & dialogue matching with 0 MB AI overhead"
                        },
                        checked = settings.ultraLight2GbMode,
                        onCheckedChange = onSetUltraLight2GbMode
                    )

                    SettingToggleRow(
                        title = "4 GB Low-RAM Safe Mode",
                        subtitle = "Forces CPU XNNPACK (4 threads) & One-Model-at-a-Time 45s auto-unload",
                        checked = settings.lowRamSafeMode,
                        onCheckedChange = onSetLowRamSafeMode
                    )

                    SettingToggleRow(
                        title = "Show Optional Gemma 4 E2B (8 GB+ Phones Only)",
                        subtitle = "Disabled on 2 GB / 4 GB phones",
                        checked = settings.enableGemma4E2bOptional,
                        enabled = !settings.lowRamSafeMode && !settings.ultraLight2GbMode && ramStatus.totalRamMb >= 7000L,
                        onCheckedChange = onSetEnableGemma4E2b
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            placeholder = { Text("Optional HF Token (hf_...)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onSaveHfToken(tokenInput) }) {
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }
            }

            items(coreModels, key = { it.key }) { model ->
                val st = modelStates[model] ?: ModelInstallState(model = model, isInstalled = false)
                val needs4GbWarning = (isDevice2GbClass || settings.ultraLight2GbMode) &&
                    (model == ManagedModelId.GEMMA_3_1B || model == ManagedModelId.FUNCTION_GEMMA_270M)
                ModelManagerCard(
                    model = model,
                    state = st,
                    needs4GbWarning = needs4GbWarning,
                    onDownload = {
                        if (needs4GbWarning) confirmHeavyModelWarning = model
                        else onDownloadModel(model)
                    },
                    onImportLocal = { onImportLocalModelFile(model) },
                    onDelete = { onDeleteModel(model) }
                )
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    confirmHeavyModelWarning?.let { heavyModel ->
        AlertDialog(
            onDismissRequest = { confirmHeavyModelWarning = null },
            title = { Text("Needs 4 GB+ RAM") },
            text = {
                Text(
                    text = "${heavyModel.title} requires ~${heavyModel.estRamMb} MB peak RAM and may cause slowdowns or crashes on a 2 GB RAM phone. Your built-in Malayalam/Manglish command router and dialogue matcher already work with 0 MB AI RAM! Download anyway?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val m = heavyModel
                        confirmHeavyModelWarning = null
                        onDownloadModel(m)
                    }
                ) {
                    Text("Download Anyway", color = GeminiCoral)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmHeavyModelWarning = null }) {
                    Text("Keep Ultra-Light (Recommended)")
                }
            }
        )
    }
}

@Composable
private fun ModelManagerCard(
    model: ManagedModelId,
    state: ModelInstallState,
    needs4GbWarning: Boolean,
    onDownload: () -> Unit,
    onImportLocal: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GeminiSurface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.title,
                    color = GeminiTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(text = model.subtitle, color = GeminiTextSecondary, fontSize = 12.sp)
                if (needs4GbWarning) {
                    Text(
                        text = "⚠️ Needs 4 GB+ RAM (Not needed in 2 GB Ultra-Light Mode)",
                        color = GeminiAmber,
                        fontSize = 11.sp
                    )
                } else if (model == ManagedModelId.EMBEDDING_GEMMA_270M) {
                    Text(
                        text = "✓ Optional lightweight semantic upgrade (~165 MB)",
                        color = GeminiMint,
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = if (state.isInstalled) "Downloaded ✅" else "~${model.approxDiskMb} MB",
                color = if (state.isInstalled) GeminiMint else GeminiAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        if (state.isDownloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = GeminiBlue,
                trackColor = GeminiCard
            )
        }

        if (state.statusMessage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = state.statusMessage, color = GeminiTextSecondary, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDownload,
                enabled = !state.isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (needs4GbWarning) GeminiCard else GeminiBlue
                )
            ) {
                Text(
                    text = if (state.isInstalled) "Re-Download" else "Download",
                    color = if (needs4GbWarning) GeminiAmber else Color(0xFF071324),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onImportLocal, enabled = !state.isDownloading) {
                Text("Import File", fontSize = 12.sp)
            }
            if (state.isInstalled || state.diskBytes > 0L) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = GeminiCoral, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = GeminiTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = GeminiTextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
