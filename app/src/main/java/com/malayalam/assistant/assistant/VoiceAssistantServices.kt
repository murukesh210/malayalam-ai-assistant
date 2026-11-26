package com.malayalam.assistant.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ui.overlay.GeminiFloatingOverlaySheet
import com.malayalam.assistant.ui.theme.MalayalamAssistantTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AssistantVoiceInteractionService : VoiceInteractionService()

class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceInteractionSession(this)
    }
}

/**
 * Companion RecognitionService required by Android's `voice-interaction-service` XML contract
 * so the app appears cleanly inside `Settings.ACTION_VOICE_INPUT_SETTINGS` as a selectable
 * Default Digital Assistant.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.endOfSpeech()
    }

    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

data class OverlayUiState(
    val inputText: String = "",
    val isListeningMic: Boolean = false,
    val speechLocale: String = "ml-IN",
    val capturedScreenText: String = "",
    val screenAppPackage: String = "",
    val isUsingScreenContext: Boolean = false,
    val isThinking: Boolean = false,
    val assistantResponse: String = "Hi! Hold mic for Malayalam/English, tap Read Screen, or ask for an alarm, call, or task."
)

/**
 * Floating Gemini-Style Assistant Overlay (`VoiceInteractionSession`):
 *  - Triggered system-wide via Power-button hold or corner swipe when set as Default Digital Assistant.
 *  - Supports `onHandleAssist` to read visible text from the active app behind the overlay ("Read Screen").
 *  - Supports live `SpeechRecognizer` (`ml-IN` + `en-IN`), text input, `FunctionGemma (270M)` sub-second
 *    daily task routing + Malayalam audio reaction, and instant `ModelCoordinator.unloadAll` in `onHide()`.
 */
class AssistantVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val graph = AppGraph.from(context)
    private val uiState = MutableStateFlow(OverlayUiState())
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        val decorView = window?.window?.decorView
        decorView?.setViewTreeLifecycleOwner(this)
        decorView?.setViewTreeViewModelStoreOwner(this)
        decorView?.setViewTreeSavedStateRegistryOwner(this)

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AssistantVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@AssistantVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@AssistantVoiceInteractionSession)

            setContent {
                MalayalamAssistantTheme {
                    val state by uiState.collectAsState()
                    val playback by graph.audioPlayer.playbackState.collectAsState()
                    val ram by graph.modelCoordinator.ramStatus.collectAsState()

                    GeminiFloatingOverlaySheet(
                        state = state,
                        playbackState = playback,
                        ramStatus = ram,
                        onInputChange = { txt -> uiState.value = uiState.value.copy(inputText = txt) },
                        onToggleLocale = ::toggleLocale,
                        onToggleMic = ::toggleMicListening,
                        onReadScreenPillClick = ::activateReadScreen,
                        onSendPrompt = { submitPrompt(uiState.value.inputText) },
                        onStopAudio = { graph.audioPlayer.stop() },
                        onDismiss = { hide() }
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        sessionScope.launch {
            val savedLocale = graph.prefs.snapshot().speechLocale
            uiState.value = uiState.value.copy(speechLocale = savedLocale)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        extractAssistStructure(state.assistStructure)
    }

    @Deprecated("Deprecated in API 29, kept for minSdk 26-28 compatibility")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        @Suppress("DEPRECATION")
        super.onHandleAssist(data, structure, content)
        extractAssistStructure(structure)
    }

    private fun extractAssistStructure(structure: AssistStructure?) {
        if (structure == null) return
        val sb = StringBuilder()
        var pkg = ""
        val windowCount = structure.windowNodeCount
        for (w in 0 until windowCount) {
            val winNode = structure.getWindowNodeAt(w)
            if (pkg.isBlank() && !winNode.title.isNullOrBlank()) {
                pkg = winNode.title.toString().substringBefore('/')
            }
            collectViewNodeText(winNode.rootViewNode, sb)
        }
        val cleaned = sb.toString().replace(Regex("\\s+"), " ").trim().take(2000)
        if (cleaned.isNotBlank()) {
            uiState.value = uiState.value.copy(
                capturedScreenText = cleaned,
                screenAppPackage = pkg
            )
        }
    }

    private fun collectViewNodeText(node: AssistStructure.ViewNode?, out: StringBuilder) {
        if (node == null || out.length > 2200) return
        if (node.visibility == View.VISIBLE) {
            val t = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrBlank()) out.append(t).append(' ')
            else if (!desc.isNullOrBlank()) out.append(desc).append(' ')
        }
        for (i in 0 until node.childCount) {
            collectViewNodeText(node.getChildAt(i), out)
        }
    }

    private fun activateReadScreen() {
        val screen = uiState.value.capturedScreenText
        if (screen.isBlank()) {
            uiState.value = uiState.value.copy(
                isUsingScreenContext = true,
                assistantResponse = "Enable 'Use text from screen' in Default Assistant settings, then swipe/hold Power over any app to read its screen."
            )
        } else {
            uiState.value = uiState.value.copy(
                isUsingScreenContext = !uiState.value.isUsingScreenContext,
                assistantResponse = "Screen captured (${screen.length} chars): \"${screen.take(140)}...\" — ask anything or react to this screen!"
            )
            // Also check if the screen text immediately matches a Malayalam reaction clip!
            sessionScope.launch {
                val settings = graph.prefs.snapshot()
                val top = graph.embeddingMatcher.rankDialogues(
                    rawQuery = screen.take(400),
                    appPackageHint = uiState.value.screenAppPackage.ifBlank { null },
                    topK = 1
                ).firstOrNull()
                if (top != null && top.score >= settings.matchThreshold) {
                    graph.audioPlayer.playEntry(top.entry)
                }
            }
        }
    }

    private fun toggleLocale() {
        val next = if (uiState.value.speechLocale == "ml-IN") "en-IN" else "ml-IN"
        uiState.value = uiState.value.copy(speechLocale = next)
        sessionScope.launch { graph.prefs.setSpeechLocale(next) }
    }

    private fun toggleMicListening() {
        if (uiState.value.isListeningMic) {
            stopSpeechRecognizer()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            uiState.value = uiState.value.copy(
                assistantResponse = "SpeechRecognizer service unavailable on this device; type your prompt below."
            )
            return
        }
        stopSpeechRecognizer()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                uiState.value = uiState.value.copy(isListeningMic = true)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                uiState.value = uiState.value.copy(isListeningMic = false)
            }
            override fun onError(error: Int) {
                uiState.value = uiState.value.copy(isListeningMic = false)
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                uiState.value = uiState.value.copy(isListeningMic = false, inputText = heard)
                if (heard.isNotBlank()) {
                    submitPrompt(heard)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (part.isNotBlank()) {
                    uiState.value = uiState.value.copy(inputText = part)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, uiState.value.speechLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun submitPrompt(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return
        uiState.value = uiState.value.copy(inputText = "", isThinking = true)

        sessionScope.launch {
            // 1. Route daily actions via FunctionGemma (270M) first for sub-second execution
            val actionResult = graph.functionRouter.routeAndExecute(prompt)
            if (actionResult.handledAsAction) {
                uiState.value = uiState.value.copy(
                    isThinking = false,
                    assistantResponse = actionResult.summaryText
                )
                val match = graph.embeddingMatcher.rankDialogues(
                    rawQuery = "${actionResult.reactionQuery} $prompt",
                    topK = 1
                ).firstOrNull()
                if (match != null && match.score >= 0.45f) {
                    graph.audioPlayer.playEntry(match.entry)
                }
                return@launch
            }

            // 2. Check for matching Malayalam dialogue reaction first, then light Gemma 3 1B reply
            val settings = graph.prefs.snapshot()
            val screenCtx = uiState.value.capturedScreenText.takeIf { uiState.value.isUsingScreenContext && it.isNotBlank() }
            val topDialogue = graph.embeddingMatcher.rankDialogues(
                rawQuery = prompt,
                topK = 1
            ).firstOrNull()
            if (topDialogue != null && topDialogue.score >= settings.matchThreshold) {
                graph.audioPlayer.playEntry(topDialogue.entry)
            }

            val reply = graph.gemmaSituationEngine.generateAssistantReply(
                userMessage = prompt,
                screenContext = screenCtx
            )
            uiState.value = uiState.value.copy(
                isThinking = false,
                assistantResponse = reply
            )
        }
    }

    private fun stopSpeechRecognizer() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        uiState.value = uiState.value.copy(isListeningMic = false)
    }

    override fun onHide() {
        stopSpeechRecognizer()
        // CRITICAL 4 GB RAM rule: immediately unload active AI model when floating overlay closes
        graph.modelCoordinator.unloadAllAsync("overlay_onHide")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        stopSpeechRecognizer()
        graph.modelCoordinator.unloadAllAsync("overlay_onDestroy")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        sessionScope.cancel()
        super.onDestroy()
    }
}
