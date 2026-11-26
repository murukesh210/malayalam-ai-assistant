package com.malayalam.assistant.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
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
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
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
    val micRmsLevel: Float = 0f,
    val speechLocale: String = "ml-IN",
    val capturedScreenText: String = "",
    val screenAppPackage: String = "",
    val isUsingScreenContext: Boolean = false,
    val isThinking: Boolean = false,
    val assistantResponse: String = "നമസ്കാരം"
)

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

                    GeminiFloatingOverlaySheet(
                        state = state,
                        playbackState = playback,
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
            uiState.value = uiState.value.copy(
                speechLocale = savedLocale,
                assistantResponse = if (savedLocale.startsWith("ml")) "നമസ്കാരം" else "Namaskaram"
            )
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
        val ml = uiState.value.speechLocale.startsWith("ml")
        if (screen.isBlank()) {
            uiState.value = uiState.value.copy(
                isUsingScreenContext = true,
                assistantResponse = if (ml) {
                    "സ്ക്രീൻ വായിക്കാൻ Default Assistant സെറ്റിംഗ്സിൽ 'Use text from screen' ഓൺ ചെയ്യുക."
                } else {
                    "Enable 'Use text from screen' in Default Assistant settings."
                }
            )
        } else {
            uiState.value = uiState.value.copy(
                isUsingScreenContext = !uiState.value.isUsingScreenContext,
                assistantResponse = "\"${screen.take(110)}...\""
            )
            sessionScope.launch {
                val settings = graph.prefs.snapshot()
                val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
                val top = graph.embeddingMatcher.rankDialogues(
                    rawQuery = screen.take(400),
                    appPackageHint = uiState.value.screenAppPackage.ifBlank { null },
                    requirePlayableAudio = true,
                    topK = 1
                ).firstOrNull()
                if (top != null && top.score >= threshold) {
                    graph.audioPlayer.playEntry(top.entry)
                }
            }
        }
    }

    private fun toggleLocale() {
        val next = if (uiState.value.speechLocale == "ml-IN") "en-IN" else "ml-IN"
        uiState.value = uiState.value.copy(
            speechLocale = next,
            assistantResponse = if (next.startsWith("ml")) "നമസ്കാരം" else "Namaskaram"
        )
        sessionScope.launch { graph.prefs.setSpeechLocale(next) }
    }

    private fun toggleMicListening() {
        if (uiState.value.isListeningMic) {
            stopSpeechRecognizer()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            uiState.value = uiState.value.copy(
                assistantResponse = "വോയ്‌സ് സർവീസ് ലഭ്യമല്ല — താഴെയുള്ള ✦ അമർത്തി ടൈപ്പ് ചെയ്യാം."
            )
            return
        }
        graph.audioPlayer.stop()
        stopSpeechRecognizer()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                uiState.value = uiState.value.copy(isListeningMic = true, micRmsLevel = 0.2f)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                uiState.value = uiState.value.copy(micRmsLevel = norm)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                uiState.value = uiState.value.copy(isListeningMic = false, micRmsLevel = 0f)
            }
            override fun onError(error: Int) {
                uiState.value = uiState.value.copy(isListeningMic = false, micRmsLevel = 0f)
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                uiState.value = uiState.value.copy(isListeningMic = false, micRmsLevel = 0f, inputText = "")
                if (heard.isNotBlank()) {
                    submitPrompt(heard)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (part.isNotBlank()) {
                    uiState.value = uiState.value.copy(assistantResponse = part)
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
            val settings = graph.prefs.snapshot()
            val ml = settings.isMalayalamMode
            val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
            val screenCtx = uiState.value.capturedScreenText
                .takeIf { uiState.value.isUsingScreenContext && it.isNotBlank() }

            // 1. Primary: Google AI Studio Brain + Expressive Malayalam TTS when API key is configured
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            if (apiKey.isNotBlank()) {
                val vaultEntries = graph.vaultRepository.dialogues.value
                val summaries = com.malayalam.assistant.ai.GeminiBrainClient.toVaultSummaries(vaultEntries) {
                    graph.vaultRepository.hasPlayableAudio(it)
                }
                val ai = graph.geminiBrainClient.askWithTextOrAudio(
                    apiKey = apiKey,
                    userText = prompt,
                    screenContext = screenCtx,
                    vaultDialogues = summaries,
                    preferMalayalam = ml
                )
                if (ai != null) {
                    if (ai.action.type.isNotBlank() && !ai.action.type.equals("none", ignoreCase = true)) {
                        graph.functionRouter.executeGeminiAction(ai.action.type, ai.action.value)
                    }
                    val replyText = ai.replyMalayalam.ifBlank { if (ml) "ശരി!" else "Done!" }
                    uiState.value = uiState.value.copy(isThinking = false, assistantResponse = replyText)

                    val matchedClip = ai.playDialogueId?.let { id ->
                        vaultEntries.firstOrNull { it.id == id && graph.vaultRepository.hasPlayableAudio(it) }
                    }
                    val playedClip = matchedClip?.let { graph.audioPlayer.playEntry(it) } == true
                    if (!playedClip && settings.ttsVoiceRepliesEnabled) {
                        graph.audioPlayer.speakNaturalReply(
                            text = replyText,
                            apiKey = apiKey,
                            ttsClient = graph.geminiTtsClient,
                            voiceName = settings.geminiVoiceName,
                            ttsStyle = ai.ttsStyle,
                            preferMalayalam = ml
                        )
                    }
                    return@launch
                }
            }

            // 2. Offline Fallback: Zero-RAM Malayalam + Manglish + English Command Engine
            val actionResult = graph.functionRouter.routeAndExecute(prompt)
            if (actionResult.handledAsAction) {
                uiState.value = uiState.value.copy(
                    isThinking = false,
                    assistantResponse = actionResult.summaryText
                )
                var played = false
                if (!actionResult.awaitingFollowUp && actionResult.reactionQuery.isNotBlank()) {
                    val match = graph.embeddingMatcher.rankDialogues(
                        rawQuery = "${actionResult.reactionQuery} $prompt",
                        requirePlayableAudio = true,
                        topK = 1
                    ).firstOrNull()
                    if (match != null && match.score >= threshold) {
                        played = graph.audioPlayer.playEntry(match.entry)
                    }
                }
                if (!played && settings.ttsVoiceRepliesEnabled) {
                    graph.audioPlayer.speakShortReplyIfIdle(actionResult.summaryText, preferMalayalam = ml)
                }
                return@launch
            }

            val topDialogue = graph.embeddingMatcher.rankDialogues(
                rawQuery = prompt,
                requirePlayableAudio = true,
                topK = 1
            ).firstOrNull()

            var playedTitle: String? = null
            if (topDialogue != null && topDialogue.score >= threshold) {
                if (graph.audioPlayer.playEntry(topDialogue.entry)) {
                    playedTitle = topDialogue.entry.title
                }
            }

            val reply = graph.gemmaSituationEngine.generateAssistantReply(
                userMessage = prompt,
                screenContext = screenCtx,
                matchedDialogueTitle = playedTitle
            )
            uiState.value = uiState.value.copy(
                isThinking = false,
                assistantResponse = reply
            )
            if (playedTitle == null && settings.ttsVoiceRepliesEnabled) {
                graph.audioPlayer.speakShortReplyIfIdle(reply, preferMalayalam = ml)
            }
        }
    }

    private fun stopSpeechRecognizer() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        uiState.value = uiState.value.copy(isListeningMic = false, micRmsLevel = 0f)
    }

    override fun onHide() {
        stopSpeechRecognizer()
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
