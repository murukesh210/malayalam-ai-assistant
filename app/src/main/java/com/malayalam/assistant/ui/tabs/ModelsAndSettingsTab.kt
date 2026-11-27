package com.malayalam.assistant.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malayalam.assistant.ai.ApiDiagnosticEvent
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.ai.ModelInstallState
import com.malayalam.assistant.ai.RamStatus
import com.malayalam.assistant.data.UserSettings
import com.malayalam.assistant.service.LiveNotificationLogEntry
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
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "com.google.android.apps.messaging" to "Google Messages",
    "com.samsung.android.messaging" to "Samsung Messages",
    "com.android.mms" to "System SMS/MMS",
    "com.google.android.gm" to "Gmail",
    "org.telegram.messenger" to "Telegram",
    "com.instagram.android" to "Instagram",
    "com.google.android.apps.nbu.paisa.user" to "Google Pay (GPay)",
    "com.phonepe.app" to "PhonePe",
    "net.one97.paytm" to "Paytm"
)

@Composable
fun ModelsAndSettingsTab(
    ramStatus: RamStatus,
    isDevice2GbClass: Boolean,
    isAndroidGoDevice: Boolean,
    settings: UserSettings,
    modelStates: Map<ManagedModelId, ModelInstallState>,
    isNotificationAccessEnabled: Boolean,
    isSmsPermissionGranted: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    geminiKeyTestResult: String,
    lastApiDebugStatus: String,
    liveMonitorLogs: List<LiveNotificationLogEntry>,
    diagnosticLogs: List<ApiDiagnosticEvent>,
    whatsappSimStatus: String,
    gmailTestResult: String,
    onBack: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onTestGeminiApiKey: (String) -> Unit,
    onSelectGeminiVoice: (String) -> Unit,
    onSetSpeakAfterClip: (Boolean) -> Unit,
    onToggleAlwaysOnKeepAlive: (Boolean) -> Unit,
    onSimulateWhatsAppMessage: (String) -> Unit,
    onToggleSmsAutoReact: (Boolean) -> Unit,
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
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var simWhatsappInput by rememberSaveable { mutableStateOf("eda paisa ayachittund") }
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
                text = "Settings & AI Brain",
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
            // 1. GOOGLE AI STUDIO API KEY + STREAMING VOICE PICKER
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .border(1.dp, GeminiBlue.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Google AI Studio API Key",
                            color = GeminiBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (settings.hasGeminiApiKey) "Saved ✅" else "Free Key",
                            color = if (settings.hasGeminiApiKey) GeminiMint else GeminiAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Get Free Key at aistudio.google.com • Streaming SSE voice + minimal-thinking AI (0 MB RAM).",
                        color = GeminiTextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = {
                            Text(
                                text = if (settings.hasGeminiApiKey) {
                                    "API Key saved ✅ (paste new key to replace)"
                                } else {
                                    "Paste AIza... Google AI Studio Key"
                                },
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Show or Hide Key",
                                    tint = GeminiTextSecondary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSaveGeminiApiKey(apiKeyInput)
                                apiKeyInput = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Key", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = { onTestGeminiApiKey(apiKeyInput) },
                            modifier = Modifier.weight(1.2f),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                        ) {
                            Text("Test Key", color = Color(0xFF071324), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "API Debug: $lastApiDebugStatus",
                        color = GeminiTextSecondary,
                        fontSize = 11.sp
                    )

                    if (geminiKeyTestResult.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = geminiKeyTestResult,
                            color = if (geminiKeyTestResult.startsWith("❌")) GeminiCoral else GeminiMint,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Streaming Malayalam Voice (${settings.geminiVoiceName}):",
                        color = GeminiTextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UserSettings.AVAILABLE_GEMINI_VOICES.forEach { voice ->
                            FilterChip(
                                selected = settings.geminiVoiceName == voice,
                                onClick = { onSelectGeminiVoice(voice) },
                                label = { Text(voice, fontSize = 12.sp) }
                            )
                        }
                    }

                    SettingToggleRow(
                        title = "Speak Malayalam Reply After Movie Clip",
                        subtitle = "Off by default: movie clip plays immediately; streaming TTS speaks when no clip matches",
                        checked = settings.speakAfterDialogueClip,
                        onCheckedChange = onSetSpeakAfterClip
                    )
                }
            }

            // 2. ALWAYS-ON BACKGROUND WHATSAPP, SMS & LIVE NOTIFICATION MONITOR
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .border(1.dp, GeminiViolet.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Always-On WhatsApp, SMS & Notification Monitor",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )

                    SettingToggleRow(
                        title = "Always-On Background Message Reactions",
                        subtitle = "Runs a lightweight START_STICKY service so reactions still fire when app is swiped closed",
                        checked = settings.alwaysOnKeepAliveEnabled,
                        onCheckedChange = onToggleAlwaysOnKeepAlive
                    )

                    if (!isIgnoringBatteryOptimizations) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = onRequestBatteryOptimizationExemption,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiAmber)
                        ) {
                            Text(
                                text = "⚡ Fix Background Lag (Disable Battery Optimization)",
                                color = Color(0xFF1A1200),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = "✅ Battery Optimization Disabled — Zero-Lag Instant Reactions Active",
                            color = GeminiMint,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    SettingToggleRow(
                        title = "Auto-React to Bank & Message SMS",
                        subtitle = if (isSmsPermissionGranted) {
                            "SMS Permission Granted ✅ • Reacts to bank credit/debit & personal SMS"
                        } else {
                            "Tap ON to grant RECEIVE_SMS permission for instant bank credit/debit clips"
                        },
                        checked = settings.smsAutoReactEnabled && isSmsPermissionGranted,
                        onCheckedChange = onToggleSmsAutoReact
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Simulate WhatsApp Message (1-Tap Pipeline Test):",
                        color = GeminiTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = simWhatsappInput,
                            onValueChange = { simWhatsappInput = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onSimulateWhatsAppMessage(simWhatsappInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiViolet)
                        ) {
                            Text("Simulate", color = Color(0xFF160829), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    if (whatsappSimStatus.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(text = whatsappSimStatus, color = GeminiMint, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Live Notification Monitor (Last 5 Messages):",
                        color = GeminiBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    if (liveMonitorLogs.isEmpty()) {
                        Text(
                            text = "No messages intercepted yet — tap 'Simulate' above or send a WhatsApp/SMS!",
                            color = GeminiTextSecondary,
                            fontSize = 11.sp
                        )
                    } else {
                        liveMonitorLogs.forEach { log ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GeminiCard)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "[${log.timeLabel}] ${log.sourceApp} • ${log.sender}",
                                    color = GeminiBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(text = log.messagePreview, color = GeminiTextPrimary, fontSize = 12.sp)
                                Text(text = log.actionTaken, color = GeminiMint, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Live AI & Connection Log (Last ${diagnosticLogs.size} / 20 Calls):",
                        color = GeminiViolet,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    if (diagnosticLogs.isEmpty()) {
                        Text(
                            text = "No Gemini API calls logged yet — speak to the orb or run a Simulator test.",
                            color = GeminiTextSecondary,
                            fontSize = 11.sp
                        )
                    } else {
                        diagnosticLogs.forEach { ev ->
                            val isErr = ev.errorMessage.isNotBlank() || (ev.httpCode != 0 && ev.httpCode !in 200..299)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GeminiCard)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "[${ev.timestamp}] ${ev.source} • HTTP ${ev.httpCode} • ${ev.latencyMs}ms",
                                    color = if (isErr) GeminiCoral else GeminiMint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "In: ${ev.inputText}",
                                    color = GeminiTextPrimary,
                                    fontSize = 11.sp
                                )
                                if (ev.understoodMeaning.isNotBlank()) {
                                    Text(
                                        text = "Meaning: ${ev.understoodMeaning}",
                                        color = GeminiBlue,
                                        fontSize = 11.sp
                                    )
                                }
                                if (!ev.matchedDialogueId.isNullOrBlank()) {
                                    Text(
                                        text = "Matched ID: ${ev.matchedDialogueId}",
                                        color = GeminiMint,
                                        fontSize = 11.sp
                                    )
                                }
                                if (ev.errorMessage.isNotBlank()) {
                                    Text(
                                        text = "Error: ${ev.errorMessage}",
                                        color = GeminiCoral,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Language & Default Assistant
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Language & Default Digital Assistant",
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
                        title = "Speak Replies Aloud (Streaming Gemini / Offline TTS)",
                        subtitle = "Uses streaming Gemini voice when API key is set, Android TTS offline",
                        checked = settings.ttsVoiceRepliesEnabled,
                        onCheckedChange = onSetTtsReplies
                    )

                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onOpenDefaultAssistantSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiCard)
                    ) {
                        Text(
                            text = "Set as Default Digital Assistant",
                            color = GeminiTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 4. Built-In Gmail Direct Watcher (Android Go / 2 GB RAM)
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
                                text = "Raw SSL IMAP (imap.gmail.com:993) polling UNSEEN mail every 20s • Tracks UID.",
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
                                    "16-char App Password (Saved ✅)"
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
                            Text("Save", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = { onTestGmailConnection(gmailInput, gmailPassInput) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiMint)
                        ) {
                            Text("Test Gmail & Latest", color = Color(0xFF062016), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(text = "Status: ${settings.gmailLastStatus}", color = GeminiTextSecondary, fontSize = 11.sp)
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

            // 5. Notification Listener & Watched Apps
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
                            text = "App Notification Access & Watched Apps",
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
                            text = "⚠️ Android Go blocks Notification Access. Use SMS Auto-React & Gmail Direct Watcher above!",
                            color = GeminiAmber,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Android 13+ Sideloaded APK: Tap 'Open App Info (⋮)' → 'Allow restricted settings', then enable Notification Access.",
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
                            Text("Notification Access", color = GeminiTextPrimary, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onOpenAppInfoForRestrictedSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("App Info (⋮)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Offline Match Threshold: ${String.format("%.2f", settings.matchThreshold)}",
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
                        subtitle = "Play background reactions only on earphones or Bluetooth",
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

            // 6. Optional Offline On-Device Models (4 GB+ RAM)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "RAM Safe Mode & Optional Offline Models",
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
                        subtitle = if (isDevice2GbClass) "Auto-enabled for < 2.5 GB RAM — 0 MB local AI RAM" else "0 MB AI overhead",
                        checked = settings.ultraLight2GbMode,
                        onCheckedChange = onSetUltraLight2GbMode
                    )

                    SettingToggleRow(
                        title = "4 GB Low-RAM Safe Mode",
                        subtitle = "CPU XNNPACK (4 threads) + One-Model-at-a-Time 45s auto-unload",
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
                            Text("Save", fontSize = 12.sp, maxLines = 1, softWrap = false)
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
                Text("${heavyModel.title} requires ~${heavyModel.estRamMb} MB peak RAM. On 2 GB phones, use your free Google AI Studio API Key above (0 MB RAM) instead! Download anyway?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val m = heavyModel
                        confirmHeavyModelWarning = null
                        onDownloadModel(m)
                    }
                ) { Text("Download Anyway", color = GeminiCoral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHeavyModelWarning = null }) { Text("Keep Ultra-Light") }
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
                Text(text = model.title, color = GeminiTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(text = model.subtitle, color = GeminiTextSecondary, fontSize = 12.sp)
                if (needs4GbWarning) {
                    Text(text = "⚠️ Needs 4 GB+ RAM", color = GeminiAmber, fontSize = 11.sp)
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDownload,
                enabled = !state.isDownloading,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (needs4GbWarning) GeminiCard else GeminiBlue
                )
            ) {
                Text(
                    text = if (state.isInstalled) "Re-Download" else "Download",
                    color = if (needs4GbWarning) GeminiAmber else Color(0xFF071324),
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            OutlinedButton(
                onClick = onImportLocal,
                enabled = !state.isDownloading,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Import", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            if (state.isInstalled || state.diskBytes > 0L) {
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Delete",
                        color = GeminiCoral,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
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
