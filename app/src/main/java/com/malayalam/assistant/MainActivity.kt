package com.malayalam.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.service.AudioOutputGuard
import com.malayalam.assistant.ui.MainViewModel
import com.malayalam.assistant.ui.tabs.DialogueVaultTab
import com.malayalam.assistant.ui.tabs.MinimalVoiceHomeScreen
import com.malayalam.assistant.ui.tabs.ModelsAndSettingsTab
import com.malayalam.assistant.ui.tabs.RecentActivityScreen
import com.malayalam.assistant.ui.tabs.VoicePacksScreen
import com.malayalam.assistant.ui.theme.GeminiBlue
import com.malayalam.assistant.ui.theme.GeminiCard
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.GeminiTextPrimary
import com.malayalam.assistant.ui.theme.GeminiTextSecondary
import com.malayalam.assistant.ui.theme.MalayalamAssistantTheme
import kotlinx.coroutines.launch

private enum class AppDestination {
    HOME_ORB,
    VOICE_PACKS,
    DIALOGUE_VAULT,
    TEST_SIMULATOR,
    RECENT_ACTIVITY,
    MODELS_SETTINGS
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var fallbackSpeechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MalayalamAssistantTheme {
                var destination by rememberSaveable { mutableStateOf(AppDestination.HOME_ORB) }
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val uiScope = rememberCoroutineScope()

                var pendingModelImportTarget by remember { mutableStateOf<ManagedModelId?>(null) }
                var pendingAttachDialogueId by remember { mutableStateOf<String?>(null) }
                var pendingExportPackId by remember { mutableStateOf<String?>(null) }

                var isFallbackMicListening by remember { mutableStateOf(false) }
                var notifEnabled by remember {
                    mutableStateOf(AudioOutputGuard.isNotificationAccessGranted(this))
                }
                var smsPermGranted by remember {
                    mutableStateOf(hasSmsPermissions())
                }
                var isIgnoringBatteryOpt by remember {
                    mutableStateOf(checkIgnoringBatteryOptimizations())
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            notifEnabled = AudioOutputGuard.isNotificationAccessGranted(this@MainActivity)
                            smsPermGranted = hasSmsPermissions()
                            isIgnoringBatteryOpt = checkIgnoringBatteryOptimizations()
                        } else if (event == Lifecycle.Event.ON_STOP) {
                            stopFallbackMic()
                            isFallbackMicListening = false
                            viewModel.onAppBackgrounded()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                BackHandler(enabled = destination != AppDestination.HOME_ORB || drawerState.isOpen) {
                    if (drawerState.isOpen) {
                        uiScope.launch { drawerState.close() }
                    } else {
                        destination = AppDestination.HOME_ORB
                    }
                }

                val heroText by viewModel.heroGreetingOrReply.collectAsState()
                val voiceSessionState by viewModel.voiceSessionState.collectAsState()
                val continuousMicRms by viewModel.continuousMicRms.collectAsState()
                val isThinkingTyped by viewModel.isThinkingTyped.collectAsState()
                val ramStatus by viewModel.ramStatus.collectAsState()
                val playbackState by viewModel.playbackState.collectAsState()
                val chatHistory by viewModel.chatHistory.collectAsState()
                val todos by viewModel.todos.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val packs by viewModel.packs.collectAsState()
                val activePackId by viewModel.activePackId.collectAsState()
                val dialogues by viewModel.dialogues.collectAsState()
                val playableAudioMap by viewModel.playableAudioMap.collectAsState()
                val pendingReviews by viewModel.pendingReviews.collectAsState()
                val pipelineProgress by viewModel.pipelineProgress.collectAsState()
                val simulatorResults by viewModel.simulatorResults.collectAsState()
                val simulatorAiNote by viewModel.simulatorAiNote.collectAsState()
                val bannerMessage by viewModel.vaultBannerMessage.collectAsState()
                val packBannerMessage by viewModel.packBannerMessage.collectAsState()
                val importedPackNeedingAiTagId by viewModel.importedPackNeedingAiTagId.collectAsState()
                val isBatchAutoTagging by viewModel.isBatchAutoTagging.collectAsState()
                val editorDraft by viewModel.editorDraft.collectAsState()
                val geminiKeyTestResult by viewModel.geminiKeyTestResult.collectAsState()
                val lastApiDebugStatus by viewModel.lastApiDebugStatus.collectAsState()
                val liveMonitorLogs by viewModel.liveMonitorLogs.collectAsState()
                val whatsappSimStatus by viewModel.whatsappSimStatus.collectAsState()
                val gmailTestResult by viewModel.gmailTestResult.collectAsState()
                val modelStates by viewModel.modelStates.collectAsState()

                val activePackName = remember(packs, activePackId) {
                    viewModel.activePackDisplayName()
                }

                val runtimePermissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    smsPermGranted = hasSmsPermissions()
                }

                val smsPermissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    val granted = results[Manifest.permission.RECEIVE_SMS] == true || hasSmsPermissions()
                    smsPermGranted = granted
                    viewModel.setSmsAutoReactEnabled(granted)
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        if (settings.hasGeminiApiKey) {
                            viewModel.onLiveOrbTapped()
                        } else {
                            toggleFallbackSpeechRecognizer(
                                locale = settings.speechLocale,
                                currentlyListening = isFallbackMicListening,
                                onListeningState = { active ->
                                    isFallbackMicListening = active
                                    if (!active) viewModel.updateExternalMicRms(0f)
                                },
                                onRmsLevel = viewModel::updateExternalMicRms,
                                onPartialResult = viewModel::updateLivePartialSpeech,
                                onFinalResult = { spoken ->
                                    if (spoken.isNotBlank()) viewModel.sendChatOrAction(spoken)
                                    else viewModel.resetGreetingToNamaskaram()
                                }
                            )
                        }
                    }
                }

                val safFolderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        viewModel.bindSafFolder(uri)
                        destination = AppDestination.DIALOGUE_VAULT
                    }
                }

                val pickEditorAudioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) viewModel.pickAudioIntoEditor(uri)
                }

                val attachExistingAudioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val targetId = pendingAttachDialogueId
                    if (uri != null && targetId != null) {
                        viewModel.attachAudioToExistingDialogue(targetId, uri)
                    }
                }

                val importPackZipLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) viewModel.importVoicePackFromZip(uri)
                }

                val exportPackZipLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip")
                ) { destUri ->
                    val targetPackId = pendingExportPackId
                    if (destUri != null && targetPackId != null) {
                        viewModel.exportVoicePackToZip(targetPackId, destUri)
                    }
                }

                val importModelFileLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val target = pendingModelImportTarget
                    if (uri != null && target != null) {
                        viewModel.importLocalModelFile(target, uri)
                    }
                }

                val drawerItemColors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = GeminiBlue.copy(alpha = 0.16f),
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = GeminiBlue,
                    unselectedIconColor = GeminiTextSecondary,
                    selectedTextColor = Color.White,
                    unselectedTextColor = GeminiTextPrimary
                )

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = destination == AppDestination.HOME_ORB || drawerState.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = GeminiSurface,
                            modifier = Modifier.width(290.dp)
                        ) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = "മലയാളം AI",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Text(
                                text = "🎬 $activePackName • ${dialogues.size} clips",
                                color = GeminiTextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = GeminiCard)
                            Spacer(Modifier.height(10.dp))

                            NavigationDrawerItem(
                                label = { Text("Home (Live Orb)") },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                selected = destination == AppDestination.HOME_ORB,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.HOME_ORB
                                    uiScope.launch { drawerState.close() }
                                }
                            )

                            NavigationDrawerItem(
                                label = { Text("Voice Packs (Actor Packs)") },
                                icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                selected = destination == AppDestination.VOICE_PACKS,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.VOICE_PACKS
                                    uiScope.launch { drawerState.close() }
                                }
                            )

                            NavigationDrawerItem(
                                label = { Text("Dialogue Vault") },
                                icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                                selected = destination == AppDestination.DIALOGUE_VAULT,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.DIALOGUE_VAULT
                                    uiScope.launch { drawerState.close() }
                                }
                            )

                            NavigationDrawerItem(
                                label = { Text("Test Simulator") },
                                icon = { Icon(Icons.Default.Science, contentDescription = null) },
                                selected = destination == AppDestination.TEST_SIMULATOR,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.TEST_SIMULATOR
                                    uiScope.launch { drawerState.close() }
                                }
                            )

                            NavigationDrawerItem(
                                label = { Text("Recent Activity / Log") },
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                selected = destination == AppDestination.RECENT_ACTIVITY,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.RECENT_ACTIVITY
                                    uiScope.launch { drawerState.close() }
                                }
                            )

                            NavigationDrawerItem(
                                label = { Text("Audio Folder Setup") },
                                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                selected = false,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    uiScope.launch { drawerState.close() }
                                    safFolderLauncher.launch(null)
                                }
                            )

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = GeminiCard)
                            Spacer(Modifier.height(8.dp))

                            NavigationDrawerItem(
                                label = { Text("Settings & AI Key") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                selected = destination == AppDestination.MODELS_SETTINGS,
                                colors = drawerItemColors,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = {
                                    destination = AppDestination.MODELS_SETTINGS
                                    uiScope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                    when (destination) {
                        AppDestination.HOME_ORB -> MinimalVoiceHomeScreen(
                            heroText = heroText,
                            activePackName = activePackName,
                            voiceSessionState = voiceSessionState,
                            playbackState = playbackState,
                            chatHistory = chatHistory,
                            speechLocale = settings.speechLocale,
                            isFallbackMicListening = isFallbackMicListening,
                            isThinking = isThinkingTyped,
                            micRmsLevel = continuousMicRms,
                            backgroundModeActive = settings.backgroundModeActive,
                            missingBgPermissionHint = !notifEnabled && !smsPermGranted,
                            isIgnoringBatteryOptimizations = isIgnoringBatteryOpt,
                            onOrbClick = {
                                val hasMicPerm = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMicPerm) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@MinimalVoiceHomeScreen
                                }
                                if (settings.hasGeminiApiKey) {
                                    viewModel.onLiveOrbTapped()
                                } else {
                                    if (playbackState.isPlaying) {
                                        viewModel.stopAudio()
                                    } else {
                                        toggleFallbackSpeechRecognizer(
                                            locale = settings.speechLocale,
                                            currentlyListening = isFallbackMicListening,
                                            onListeningState = { active ->
                                                isFallbackMicListening = active
                                                if (!active) viewModel.updateExternalMicRms(0f)
                                            },
                                            onRmsLevel = viewModel::updateExternalMicRms,
                                            onPartialResult = viewModel::updateLivePartialSpeech,
                                            onFinalResult = { spoken ->
                                                if (spoken.isNotBlank()) viewModel.sendChatOrAction(spoken)
                                                else viewModel.resetGreetingToNamaskaram()
                                            }
                                        )
                                    }
                                }
                            },
                            onStopLiveSession = viewModel::stopContinuousLiveSession,
                            onStopAudio = viewModel::stopAudio,
                            onToggleBackgroundMode = {
                                if (!settings.backgroundModeActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    runtimePermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                }
                                viewModel.toggleBackgroundMode()
                            },
                            onMinimizeToBackground = { moveTaskToBack(true) },
                            onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                            onOpenBgPermissionsSetup = { destination = AppDestination.MODELS_SETTINGS },
                            onOpenVoicePacks = { destination = AppDestination.VOICE_PACKS },
                            onOpenDrawer = { uiScope.launch { drawerState.open() } },
                            onOpenSettings = { destination = AppDestination.MODELS_SETTINGS },
                            onSendTypedPrompt = { prompt ->
                                requestActionPermissionsIfNeeded(runtimePermissionsLauncher::launch)
                                viewModel.sendChatOrAction(prompt)
                            },
                            onToggleLocale = viewModel::toggleSpeechLocale
                        )

                        AppDestination.VOICE_PACKS -> VoicePacksScreen(
                            packs = packs,
                            activePackId = activePackId,
                            packBannerMessage = packBannerMessage,
                            importedPackNeedingAiTagId = importedPackNeedingAiTagId,
                            isBatchAutoTagging = isBatchAutoTagging,
                            onBack = { destination = AppDestination.HOME_ORB },
                            onSelectPack = viewModel::selectVoicePack,
                            onImportPackZip = { importPackZipLauncher.launch(arrayOf("*/*")) },
                            onCreatePack = viewModel::createVoicePack,
                            onExportPack = { pack ->
                                pendingExportPackId = pack.packId
                                val safeFile = pack.packName.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".mlpack.zip"
                                exportPackZipLauncher.launch(safeFile)
                            },
                            onDeletePack = viewModel::deleteVoicePack,
                            onBatchAutoTagImportedPack = viewModel::batchAutoTagPackWithAi,
                            onOpenPackDialogues = { packId ->
                                viewModel.selectVoicePack(packId)
                                destination = AppDestination.DIALOGUE_VAULT
                            }
                        )

                        AppDestination.DIALOGUE_VAULT,
                        AppDestination.TEST_SIMULATOR -> DialogueVaultTab(
                            titleHeader = if (destination == AppDestination.TEST_SIMULATOR) "Test Simulator" else "My Dialogues",
                            activePackName = activePackName,
                            focusSimulatorFirst = destination == AppDestination.TEST_SIMULATOR,
                            safTreeUri = settings.safTreeUri,
                            dialogues = dialogues,
                            playableAudioMap = playableAudioMap,
                            pendingReviews = pendingReviews,
                            pipelineProgress = pipelineProgress,
                            playbackState = playbackState,
                            simulatorResults = simulatorResults,
                            simulatorAiNote = simulatorAiNote,
                            matchThreshold = settings.matchThreshold,
                            bannerMessage = bannerMessage,
                            editorDraft = editorDraft,
                            onBack = { destination = AppDestination.HOME_ORB },
                            onOpenVoicePacks = { destination = AppDestination.VOICE_PACKS },
                            onOpenAddDialogueSheet = viewModel::openAddDialogueSheet,
                            onOpenEditDialogueSheet = viewModel::openEditDialogueSheet,
                            onCloseEditorSheet = viewModel::closeDialogueEditor,
                            onUpdateEditorDraft = viewModel::updateEditorDraft,
                            onPickAudioForEditor = { pickEditorAudioLauncher.launch(arrayOf("audio/*")) },
                            onAutoListenEditorWithAi = viewModel::autoListenAndFillEditorWithAi,
                            onSaveEditorDraft = viewModel::saveEditorDraftToVault,
                            onPickSafFolder = { safFolderLauncher.launch(null) },
                            onAttachMp3ToDialogue = { entry ->
                                pendingAttachDialogueId = entry.id
                                attachExistingAudioLauncher.launch(arrayOf("audio/*"))
                            },
                            onScanSafFolder = viewModel::scanAndAutoOrganizeSafFolder,
                            onPlayDialogue = viewModel::playDialogue,
                            onPlayRawUri = viewModel::playRawUri,
                            onStopAudio = viewModel::stopAudio,
                            onConfirmPending = viewModel::confirmPendingReview,
                            onAutoAcceptAllPending = viewModel::autoAcceptAllPendingReviews,
                            onDismissPending = viewModel::dismissPendingReview,
                            onDeleteDialogue = viewModel::deleteDialogue,
                            onRunSimulator = viewModel::runTestSimulator
                        )

                        AppDestination.RECENT_ACTIVITY -> RecentActivityScreen(
                            chatHistory = chatHistory,
                            todos = todos,
                            onBack = { destination = AppDestination.HOME_ORB },
                            onToggleTodo = viewModel::toggleTodoDone,
                            onDeleteTodo = viewModel::deleteTodo
                        )

                        AppDestination.MODELS_SETTINGS -> ModelsAndSettingsTab(
                            ramStatus = ramStatus,
                            isDevice2GbClass = viewModel.isDevice2GbClass,
                            isAndroidGoDevice = viewModel.isAndroidGoDevice,
                            settings = settings,
                            modelStates = modelStates,
                            isNotificationAccessEnabled = notifEnabled,
                            isSmsPermissionGranted = smsPermGranted,
                            isIgnoringBatteryOptimizations = isIgnoringBatteryOpt,
                            geminiKeyTestResult = geminiKeyTestResult,
                            lastApiDebugStatus = lastApiDebugStatus,
                            liveMonitorLogs = liveMonitorLogs,
                            whatsappSimStatus = whatsappSimStatus,
                            gmailTestResult = gmailTestResult,
                            onBack = { destination = AppDestination.HOME_ORB },
                            onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                            onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                            onTestGeminiApiKey = viewModel::testGeminiApiKey,
                            onSelectGeminiVoice = viewModel::setGeminiVoiceName,
                            onSetSpeakAfterClip = viewModel::setSpeakAfterDialogueClip,
                            onToggleAlwaysOnKeepAlive = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    runtimePermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                }
                                viewModel.setAlwaysOnKeepAliveEnabled(enabled)
                            },
                            onSimulateWhatsAppMessage = viewModel::simulateWhatsAppMessage,
                            onToggleSmsAutoReact = { enabled ->
                                if (enabled && !hasSmsPermissions()) {
                                    smsPermissionsLauncher.launch(
                                        arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                                    )
                                } else {
                                    viewModel.setSmsAutoReactEnabled(enabled)
                                }
                            },
                            onToggleLanguage = viewModel::toggleSpeechLocale,
                            onSetTtsReplies = viewModel::setTtsRepliesEnabled,
                            onSaveGmailConfig = viewModel::saveGmailConfig,
                            onSetGmailWatcherEnabled = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    runtimePermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                }
                                viewModel.setGmailWatcherEnabled(enabled)
                            },
                            onTestGmailConnection = viewModel::testGmailAndCheckLatest,
                            onOpenDefaultAssistantSettings = {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                                }.onFailure {
                                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                                }
                            },
                            onOpenNotificationAccessSettings = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onOpenAppInfoForRestrictedSettings = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                                startActivity(intent)
                            },
                            onDownloadModel = viewModel::downloadModel,
                            onImportLocalModelFile = { model ->
                                pendingModelImportTarget = model
                                importModelFileLauncher.launch(arrayOf("*/*"))
                            },
                            onDeleteModel = viewModel::deleteModel,
                            onSetUltraLight2GbMode = viewModel::setUltraLight2GbMode,
                            onSetLowRamSafeMode = viewModel::setLowRamSafeMode,
                            onSetEnableGemma4E2b = viewModel::setEnableGemma4E2b,
                            onSetMatchThreshold = viewModel::setMatchThreshold,
                            onSetHeadphonesOnly = viewModel::setHeadphonesOnly,
                            onSetLowBatteryReaction = viewModel::setLowBatteryReaction,
                            onSetAutoAcceptBulk = viewModel::setAutoAcceptBulk,
                            onToggleWatchedPackage = viewModel::toggleWatchedPackage,
                            onSaveHfToken = viewModel::setHfToken
                        )
                    }
                }
            }
        }
    }

    private fun hasSmsPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun requestActionPermissionsIfNeeded(launchPerms: (Array<String>) -> Unit) {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launchPerms(perms.toTypedArray())
    }

    private fun toggleFallbackSpeechRecognizer(
        locale: String,
        currentlyListening: Boolean,
        onListeningState: (Boolean) -> Unit,
        onRmsLevel: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        if (currentlyListening) {
            stopFallbackMic()
            onListeningState(false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        stopFallbackMic()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        fallbackSpeechRecognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onListeningState(true)
                onRmsLevel(0.22f)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.06f, 1f)
                onRmsLevel(norm)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListeningState(false)
            override fun onError(error: Int) = onListeningState(false)
            override fun onResults(results: Bundle?) {
                onListeningState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onFinalResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) onPartialResult(partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        rec.startListening(intent)
    }

    private fun stopFallbackMic() {
        runCatching {
            fallbackSpeechRecognizer?.stopListening()
            fallbackSpeechRecognizer?.destroy()
        }
        fallbackSpeechRecognizer = null
    }

    override fun onDestroy() {
        stopFallbackMic()
        super.onDestroy()
    }
}
