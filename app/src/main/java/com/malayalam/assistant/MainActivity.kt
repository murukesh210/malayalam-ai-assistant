package com.malayalam.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.service.AudioOutputGuard
import com.malayalam.assistant.ui.MainViewModel
import com.malayalam.assistant.ui.tabs.AssistantTab
import com.malayalam.assistant.ui.tabs.DialogueVaultTab
import com.malayalam.assistant.ui.tabs.ModelsAndSettingsTab
import com.malayalam.assistant.ui.theme.GeminiBg
import com.malayalam.assistant.ui.theme.GeminiSurface
import com.malayalam.assistant.ui.theme.MalayalamAssistantTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MalayalamAssistantTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var pendingModelImportTarget by remember { mutableStateOf<ManagedModelId?>(null) }
                var isMicListening by remember { mutableStateOf(false) }
                var notifEnabled by remember {
                    mutableStateOf(AudioOutputGuard.isNotificationAccessGranted(this))
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            notifEnabled = AudioOutputGuard.isNotificationAccessGranted(this@MainActivity)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val ramStatus by viewModel.ramStatus.collectAsState()
                val playbackState by viewModel.playbackState.collectAsState()
                val chatHistory by viewModel.chatHistory.collectAsState()
                val todos by viewModel.todos.collectAsState()
                val isChatBusy by viewModel.isChatBusy.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val dialogues by viewModel.dialogues.collectAsState()
                val pendingReviews by viewModel.pendingReviews.collectAsState()
                val pipelineProgress by viewModel.pipelineProgress.collectAsState()
                val simulatorResults by viewModel.simulatorResults.collectAsState()
                val bannerMessage by viewModel.vaultBannerMessage.collectAsState()
                val modelStates by viewModel.modelStates.collectAsState()

                val runtimePermissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                val safFolderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) viewModel.bindSafFolder(uri)
                }

                val addAudioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) viewModel.addSingleClipFromPicker(uri)
                }

                val importModelFileLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val target = pendingModelImportTarget
                    if (uri != null && target != null) {
                        viewModel.importLocalModelFile(target, uri)
                    }
                }

                Scaffold(
                    containerColor = GeminiBg,
                    bottomBar = {
                        NavigationBar(containerColor = GeminiSurface) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                label = { Text("Assistant") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                                label = { Text("Dialogue Vault") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                label = { Text("Models & Settings") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> AssistantTab(
                                ramStatus = ramStatus,
                                playbackState = playbackState,
                                chatHistory = chatHistory,
                                todos = todos,
                                isBusy = isChatBusy,
                                speechLocale = settings.speechLocale,
                                isMicListening = isMicListening,
                                onSendPrompt = { prompt ->
                                    requestActionPermissionsIfNeeded(runtimePermissionsLauncher::launch)
                                    viewModel.sendChatOrAction(prompt)
                                },
                                onStartVoiceInput = { onResult ->
                                    runtimePermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    toggleActivityMic(
                                        locale = settings.speechLocale,
                                        currentlyListening = isMicListening,
                                        onListeningState = { isMicListening = it },
                                        onFinalResult = onResult
                                    )
                                },
                                onToggleLocale = viewModel::toggleSpeechLocale,
                                onUnloadRam = viewModel::unloadRamNow,
                                onStopAudio = viewModel::stopAudio,
                                onToggleTodo = viewModel::toggleTodoDone,
                                onDeleteTodo = viewModel::deleteTodo
                            )

                            1 -> DialogueVaultTab(
                                safTreeUri = settings.safTreeUri,
                                dialogues = dialogues,
                                pendingReviews = pendingReviews,
                                pipelineProgress = pipelineProgress,
                                playbackState = playbackState,
                                simulatorResults = simulatorResults,
                                matchThreshold = settings.matchThreshold,
                                bannerMessage = bannerMessage,
                                onPickSafFolder = { safFolderLauncher.launch(null) },
                                onAddDialogueFile = { addAudioLauncher.launch(arrayOf("audio/*")) },
                                onScanSafFolder = viewModel::scanAndAutoOrganizeSafFolder,
                                onPlayDialogue = viewModel::playDialogue,
                                onPlayPendingUri = { item ->
                                    viewModel.playRawUri(item.safUriString, item.id, item.title, item.heardText)
                                },
                                onStopAudio = viewModel::stopAudio,
                                onConfirmPending = viewModel::confirmPendingReview,
                                onAutoAcceptAllPending = viewModel::autoAcceptAllPendingReviews,
                                onDismissPending = viewModel::dismissPendingReview,
                                onSaveEditedDialogue = viewModel::saveEditedDialogue,
                                onDeleteDialogue = viewModel::deleteDialogue,
                                onRunSimulator = viewModel::runTestSimulator
                            )

                            2 -> ModelsAndSettingsTab(
                                ramStatus = ramStatus,
                                settings = settings,
                                modelStates = modelStates,
                                isNotificationAccessEnabled = notifEnabled,
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

    private fun toggleActivityMic(
        locale: String,
        currentlyListening: Boolean,
        onListeningState: (Boolean) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        if (currentlyListening) {
            stopMic()
            onListeningState(false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        stopMic()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListeningState(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListeningState(false)
            override fun onError(error: Int) = onListeningState(false)
            override fun onResults(results: Bundle?) {
                onListeningState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onFinalResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        }
        rec.startListening(intent)
    }

    private fun stopMic() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    override fun onDestroy() {
        stopMic()
        super.onDestroy()
    }
}
