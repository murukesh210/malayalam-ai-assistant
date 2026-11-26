package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    settings: UserSettings,
    modelStates: Map<ManagedModelId, ModelInstallState>,
    isNotificationAccessEnabled: Boolean,
    onOpenDefaultAssistantSettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppInfoForRestrictedSettings: () -> Unit,
    onDownloadModel: (ManagedModelId) -> Unit,
    onImportLocalModelFile: (ManagedModelId) -> Unit,
    onDeleteModel: (ManagedModelId) -> Unit,
    onSetLowRamSafeMode: (Boolean) -> Unit,
    onSetEnableGemma4E2b: (Boolean) -> Unit,
    onSetMatchThreshold: (Float) -> Unit,
    onSetHeadphonesOnly: (Boolean) -> Unit,
    onSetLowBatteryReaction: (Boolean) -> Unit,
    onSetAutoAcceptBulk: (Boolean) -> Unit,
    onToggleWatchedPackage: (String, Boolean) -> Unit,
    onSaveHfToken: (String) -> Unit
) {
    var tokenInput by rememberSaveable(settings.hfAuthToken) { mutableStateOf(settings.hfAuthToken) }
    val coreModels = ManagedModelId.entries.filter { !it.isOptional8GbOnly || settings.enableGemma4E2bOptional }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Default Digital Assistant & Memory Discipline Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(GeminiSurface)
                    .padding(14.dp)
            ) {
                Text(
                    text = "System Assistant & 4 GB RAM Discipline",
                    color = GeminiBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Device RAM: ${ramStatus.totalRamMb} MB total (${ramStatus.freeRamMb} MB free). CPU XNNPACK (4 threads, 1024 ctx, 160 max out) + 45s auto-unload active.",
                    color = GeminiTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenDefaultAssistantSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                ) {
                    Text(
                        text = "Set as Default Digital Assistant (Power Hold / Swipe)",
                        color = Color(0xFF081426),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow(
                    title = "Low-RAM Safe Mode (Recommended <= 4.5 GB)",
                    subtitle = "Forces CPU XNNPACK 4 threads & blocks oversized 8 GB+ models",
                    checked = settings.lowRamSafeMode,
                    onCheckedChange = onSetLowRamSafeMode
                )

                SettingToggleRow(
                    title = "Show Optional Gemma 4 E2B (8 GB+ Phones Only)",
                    subtitle = if (settings.lowRamSafeMode) "Turn off Low-RAM Safe Mode on an 8 GB+ phone to enable" else "Enabled for high-RAM devices",
                    checked = settings.enableGemma4E2bOptional,
                    enabled = !settings.lowRamSafeMode && ramStatus.totalRamMb >= 7000L,
                    onCheckedChange = onSetEnableGemma4E2b
                )
            }
        }

        // 2. 1-Tap Post-Install Model Manager (NEVER Bundled in APK)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(GeminiSurface)
                    .padding(14.dp)
            ) {
                Text(
                    text = "4 GB-Safe On-Device Models (0 MB Bundled in APK)",
                    color = GeminiViolet,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Optional Hugging Face Read Token (only needed if downloading gated Gemma repos directly):",
                    color = GeminiTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        placeholder = { Text("hf_... (optional)", fontSize = 12.sp) },
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
            ModelManagerCard(
                model = model,
                state = st,
                onDownload = { onDownloadModel(model) },
                onImportLocal = { onImportLocalModelFile(model) },
                onDelete = { onDeleteModel(model) }
            )
        }

        // 3. Background Notification Access & Restricted Settings Hint
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
                        text = "Background Notification Reactions",
                        color = GeminiBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (isNotificationAccessEnabled) "Active ✅" else "Needs Access",
                        color = if (isNotificationAccessEnabled) GeminiMint else GeminiAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Android 13+ Sideloaded APK Tip: If Notification Access is greyed out, tap 'Open App Info' → tap the top-right ⋮ menu → tap 'Allow restricted settings', then enable Notification Access.",
                    color = GeminiAmber,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
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
                    text = "Similarity Match Threshold: ${String.format("%.2f", settings.matchThreshold)} (15s cooldown, skips OTPs)",
                    color = GeminiTextPrimary,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.matchThreshold,
                    onValueChange = onSetMatchThreshold,
                    valueRange = 0.35f..0.90f
                )

                SettingToggleRow(
                    title = "Headphones / Bluetooth Only",
                    subtitle = "Play notification reactions only when wired earphones or Bluetooth audio is connected",
                    checked = settings.headphonesOrBluetoothOnly,
                    onCheckedChange = onSetHeadphonesOnly
                )

                SettingToggleRow(
                    title = "Low-Battery Broadcast Reaction",
                    subtitle = "Play a Malayalam low-battery clip when Android fires ACTION_BATTERY_LOW",
                    checked = settings.lowBatteryReactionEnabled,
                    onCheckedChange = onSetLowBatteryReaction
                )

                SettingToggleRow(
                    title = "Auto-Accept All Bulk Audio Imports",
                    subtitle = "Commit scanned SAF clips directly to dialogues.json without manual Review cards",
                    checked = settings.autoAcceptBulkImports,
                    onCheckedChange = onSetAutoAcceptBulk
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Watched Apps:",
                    color = GeminiTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                WATCHABLE_APPS.forEach { (pkg, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = pkg in settings.watchedPackages,
                            onCheckedChange = { checked -> onToggleWatchedPackage(pkg, checked) }
                        )
                        Text(text = "$label ($pkg)", color = GeminiTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelManagerCard(
    model: ManagedModelId,
    state: ModelInstallState,
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
                Text(
                    text = model.subtitle,
                    color = GeminiTextSecondary,
                    fontSize = 12.sp
                )
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
                colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
            ) {
                Text(
                    text = if (state.isInstalled) "Re-Download" else "Download",
                    color = Color(0xFF081426),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(
                onClick = onImportLocal,
                enabled = !state.isDownloading
            ) {
                Text("Import Local File", fontSize = 12.sp)
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
