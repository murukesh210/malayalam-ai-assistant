package com.malayalam.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.ConversationHistoryItem
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.ai.GeminiBrainResponse
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.audio.SmartVoiceRecorder
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.PendingReviewEntity
import com.malayalam.assistant.data.SituationCategory
import com.malayalam.assistant.data.UserSettings
import com.malayalam.assistant.service.AssistantKeepAliveService
import com.malayalam.assistant.service.GmailDirectWatcherService
import com.malayalam.assistant.service.GmailImapClient
import com.malayalam.assistant.worker.AudioAutoOrganizeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

data class ChatTurn(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val badge: String = "",
    val triggeredClipTitle: String? = null
)

data class DialogueEditorDraft(
    val existingId: String? = null,
    val title: String = "",
    val heardText: String = "",
    val category: String = SituationCategory.CUSTOM.id,
    val situationsCsv: String = "",
    val triggerApps: Set<String> = setOf("ALL"), // ALL, WHATSAPP, SMS, GMAIL
    val fileName: String = "",
    val audioUriString: String = "",
    val isAutoTagging: Boolean = false,
    val statusNote: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.from(application)
    private val appContext = application.applicationContext
    private val voiceRecorder = SmartVoiceRecorder()
    private var vadRecordingJob: Job? = null

    val isDevice2GbClass: Boolean = AppPreferences.isDevice2GbClass(appContext)
    val isAndroidGoDevice: Boolean = AppPreferences.isAndroidGoDevice(appContext)

    val ramStatus = graph.modelCoordinator.ramStatus
    val playbackState = graph.audioPlayer.playbackState
    val dialogues = graph.vaultRepository.dialogues
    val pipelineProgress = graph.pipelineTracker.state
    val modelStates = graph.modelDownloadManager.states
    val lastApiDebugStatus = graph.geminiBrainClient.lastDebugStatus
    val liveMonitorLogs = graph.incomingMessageReactor.liveMonitorLogs

    val playableAudioMap: StateFlow<Map<String, Boolean>> = dialogues
        .map { list ->
            withContext(Dispatchers.IO) {
                list.associate { entry -> entry.id to graph.vaultRepository.hasPlayableAudio(entry) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings = graph.prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserSettings(
            lowRamSafeMode = graph.prefs.defaultLowRamMode,
            ultraLight2GbMode = graph.prefs.defaultUltraLight2Gb
        )
    )

    val todos = graph.db.todoDao().observeTodos().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val pendingReviews = graph.db.pendingReviewDao().observePendingReviews().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _heroGreetingOrReply = MutableStateFlow("നമസ്കാരം")
    val heroGreetingOrReply: StateFlow<String> = _heroGreetingOrReply.asStateFlow()

    private val _isListeningVad = MutableStateFlow(false)
    val isListeningVad: StateFlow<Boolean> = _isListeningVad.asStateFlow()

    private val _micRmsLevel = MutableStateFlow(0f)
    val micRmsLevel: StateFlow<Float> = _micRmsLevel.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chatHistory: StateFlow<List<ChatTurn>> = _chatHistory.asStateFlow()

    private val _simulatorResults = MutableStateFlow<List<MatchCandidate>>(emptyList())
    val simulatorResults: StateFlow<List<MatchCandidate>> = _simulatorResults.asStateFlow()

    private val _simulatorAiNote = MutableStateFlow("")
    val simulatorAiNote: StateFlow<String> = _simulatorAiNote.asStateFlow()

    private val _vaultBannerMessage = MutableStateFlow("")
    val vaultBannerMessage: StateFlow<String> = _vaultBannerMessage.asStateFlow()

    private val _gmailTestResult = MutableStateFlow("")
    val gmailTestResult: StateFlow<String> = _gmailTestResult.asStateFlow()

    private val _geminiKeyTestResult = MutableStateFlow("")
    val geminiKeyTestResult: StateFlow<String> = _geminiKeyTestResult.asStateFlow()

    private val _whatsappSimStatus = MutableStateFlow("")
    val whatsappSimStatus: StateFlow<String> = _whatsappSimStatus.asStateFlow()

    private val _editorDraft = MutableStateFlow<DialogueEditorDraft?>(null)
    val editorDraft: StateFlow<DialogueEditorDraft?> = _editorDraft.asStateFlow()

    fun updateLivePartialSpeech(partial: String) {
        if (partial.isNotBlank()) _heroGreetingOrReply.value = partial
    }

    fun updateExternalMicRms(level: Float) {
        _micRmsLevel.value = level
    }

    fun resetGreetingToNamaskaram() {
        _heroGreetingOrReply.value = if (settings.value.isMalayalamMode) "നമസ്കാരം" else "Namaskaram"
    }

    fun toggleSmartVadRecording() {
        if (_isListeningVad.value || voiceRecorder.isRecording) {
            voiceRecorder.stopRecordingNow()
            return
        }
        if (playbackState.value.isPlaying) {
            graph.audioPlayer.stop()
            return
        }
        startVadCapture()
    }

    private fun startVadCapture() {
        vadRecordingJob?.cancel()
        vadRecordingJob = viewModelScope.launch {
            _isListeningVad.value = true
            _micRmsLevel.value = 0.16f
            val recording = voiceRecorder.recordWithVad { rms ->
                _micRmsLevel.value = rms
            }
            _isListeningVad.value = false
            _micRmsLevel.value = 0f

            if (!recording.speechDetected || recording.wavBytes.isEmpty()) {
                resetGreetingToNamaskaram()
                return@launch
            }
            sendWavAudioToGemini(recording.wavBytes)
        }
    }

    private fun recentTurnsForBrain(): List<ConversationHistoryItem> =
        _chatHistory.value.takeLast(6).map { ConversationHistoryItem(isUser = it.isUser, text = it.text) }

    private suspend fun sendWavAudioToGemini(wavBytes: ByteArray) {
        _isThinking.value = true
        try {
            val curSettings = settings.value
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            val vaultEntries = graph.vaultRepository.dialogues.value
            val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                graph.vaultRepository.hasPlayableAudio(it)
            }

            val aiResp = graph.geminiBrainClient.askWithTextOrAudio(
                apiKey = apiKey,
                wavAudioBytes = wavBytes,
                recentTurns = recentTurnsForBrain(),
                vaultDialogues = summaries,
                preferMalayalam = curSettings.isMalayalamMode
            )

            if (aiResp != null) {
                handleGeminiBrainResponse(aiResp, apiKey, curSettings)
            } else {
                val debugHint = graph.geminiBrainClient.lastDebugStatus.value
                _heroGreetingOrReply.value = if (curSettings.isMalayalamMode) {
                    "ക്ഷമിക്കണം, മറുപടി ലഭിച്ചില്ല ($debugHint)"
                } else {
                    "Could not reach Gemini ($debugHint)"
                }
            }
        } finally {
            _isThinking.value = false
        }
    }

    fun sendChatOrAction(rawInput: String) {
        val text = rawInput.trim()
        if (text.isBlank() || _isThinking.value) return

        val historyBeforeCurrent = recentTurnsForBrain()
        _chatHistory.value = (_chatHistory.value + ChatTurn(isUser = true, text = text)).takeLast(40)
        _isThinking.value = true

        viewModelScope.launch {
            try {
                val curSettings = settings.value
                val apiKey = graph.prefs.getDecryptedGeminiApiKey()

                if (apiKey.isNotBlank()) {
                    val vaultEntries = graph.vaultRepository.dialogues.value
                    val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                        graph.vaultRepository.hasPlayableAudio(it)
                    }
                    val aiResp = graph.geminiBrainClient.askWithTextOrAudio(
                        apiKey = apiKey,
                        userText = text,
                        recentTurns = historyBeforeCurrent,
                        vaultDialogues = summaries,
                        preferMalayalam = curSettings.isMalayalamMode
                    )
                    if (aiResp != null) {
                        handleGeminiBrainResponse(aiResp, apiKey, curSettings, alreadyLoggedUserText = true)
                        return@launch
                    }
                }

                runOfflineFallback(text, curSettings)
            } finally {
                _isThinking.value = false
            }
        }
    }

    private suspend fun handleGeminiBrainResponse(
        ai: GeminiBrainResponse,
        apiKey: String,
        curSettings: UserSettings,
        alreadyLoggedUserText: Boolean = false
    ) {
        if (!alreadyLoggedUserText && ai.heardTranscript.isNotBlank()) {
            _chatHistory.value = (_chatHistory.value + ChatTurn(isUser = true, text = ai.heardTranscript)).takeLast(40)
        }

        // Execute structured phone action (silent background alarm with hour/minute, open app, dial, todo)
        if (ai.action.type.isNotBlank() && !ai.action.type.equals("none", ignoreCase = true)) {
            graph.functionRouter.executeGeminiAction(
                type = ai.action.type,
                value = ai.action.value,
                hour = ai.action.hour,
                minute = ai.action.minute,
                label = ai.action.label
            )
        }

        val replyText = ai.replyMalayalam.ifBlank {
            if (curSettings.isMalayalamMode) "ശരി, ചെയ്തു!" else "Done!"
        }
        _heroGreetingOrReply.value = replyText

        val vaultEntries = graph.vaultRepository.dialogues.value
        val matchedDialogue = ai.playDialogueId?.let { targetId ->
            vaultEntries.firstOrNull { it.id == targetId && graph.vaultRepository.hasPlayableAudio(it) }
        }

        val followUpCallback: (() -> Unit)? = if (ai.expectFollowUp && curSettings.hasGeminiApiKey) {
            { startVadCapture() }
        } else null

        var playedClipTitle: String? = null
        if (matchedDialogue != null) {
            val shouldSpeakAfter = curSettings.speakAfterDialogueClip && curSettings.ttsVoiceRepliesEnabled && replyText.isNotBlank()
            val clipFinishedCallback: (() -> Unit)? = if (shouldSpeakAfter) {
                {
                    viewModelScope.launch {
                        graph.audioPlayer.speakNaturalReply(
                            text = replyText,
                            apiKey = apiKey,
                            ttsClient = graph.geminiTtsClient,
                            voiceName = curSettings.geminiVoiceName,
                            ttsStyle = ai.ttsStyle,
                            preferMalayalam = curSettings.isMalayalamMode,
                            onFinished = followUpCallback
                        )
                    }
                    Unit
                }
            } else {
                followUpCallback
            }
            val played = graph.audioPlayer.playEntry(
                entry = matchedDialogue,
                onFinished = clipFinishedCallback
            )
            if (played) playedClipTitle = matchedDialogue.title
        }

        if (playedClipTitle == null && curSettings.ttsVoiceRepliesEnabled) {
            graph.audioPlayer.speakNaturalReply(
                text = replyText,
                apiKey = apiKey,
                ttsClient = graph.geminiTtsClient,
                voiceName = curSettings.geminiVoiceName,
                ttsStyle = ai.ttsStyle,
                preferMalayalam = curSettings.isMalayalamMode,
                onFinished = followUpCallback
            )
        } else if (playedClipTitle == null && followUpCallback != null) {
            followUpCallback.invoke()
        }

        _chatHistory.value = (_chatHistory.value + ChatTurn(
            isUser = false,
            text = replyText,
            badge = if (playedClipTitle != null) "Gemini + Clip" else "Gemini Stream",
            triggeredClipTitle = playedClipTitle
        )).takeLast(40)
    }

    private suspend fun runOfflineFallback(text: String, curSettings: UserSettings) {
        val ml = curSettings.isMalayalamMode
        val threshold = curSettings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)

        val action = graph.functionRouter.routeAndExecute(text)
        if (action.handledAsAction) {
            _heroGreetingOrReply.value = action.summaryText
            var playedClipTitle: String? = null
            if (!action.awaitingFollowUp && action.reactionQuery.isNotBlank()) {
                val reaction = graph.embeddingMatcher.rankDialogues(
                    rawQuery = "${action.reactionQuery} $text",
                    requirePlayableAudio = true,
                    topK = 1
                ).firstOrNull()?.takeIf { it.score >= threshold }
                if (reaction != null && graph.audioPlayer.playEntry(reaction.entry)) {
                    playedClipTitle = reaction.entry.title
                }
            }
            if (playedClipTitle == null && curSettings.ttsVoiceRepliesEnabled) {
                graph.audioPlayer.speakShortReplyIfIdle(action.summaryText, preferMalayalam = ml)
            }
            _chatHistory.value = (_chatHistory.value + ChatTurn(
                isUser = false,
                text = action.summaryText,
                badge = "Offline Router",
                triggeredClipTitle = playedClipTitle
            )).takeLast(40)
            return
        }

        val matched = graph.embeddingMatcher.rankDialogues(
            rawQuery = text,
            requirePlayableAudio = true,
            topK = 1
        ).firstOrNull()?.takeIf { it.score >= threshold }

        var playedClipTitle: String? = null
        if (matched != null && graph.audioPlayer.playEntry(matched.entry)) {
            playedClipTitle = matched.entry.title
        }

        val reply = graph.gemmaSituationEngine.generateAssistantReply(
            userMessage = text,
            matchedDialogueTitle = playedClipTitle
        )
        _heroGreetingOrReply.value = reply
        if (playedClipTitle == null && curSettings.ttsVoiceRepliesEnabled) {
            graph.audioPlayer.speakShortReplyIfIdle(reply, preferMalayalam = ml)
        }
        _chatHistory.value = (_chatHistory.value + ChatTurn(
            isUser = false,
            text = reply,
            badge = if (playedClipTitle != null) "Malayalam Clip" else "Offline",
            triggeredClipTitle = playedClipTitle
        )).takeLast(40)
    }

    // --- Easy "Add / Edit My Dialogue" Bottom Sheet + ✨ AI Auto-Listen ---

    fun openAddDialogueSheet() {
        _editorDraft.value = DialogueEditorDraft()
    }

    fun openEditDialogueSheet(entry: DialogueEntry) {
        val appFlags = mutableSetOf<String>()
        if (entry.recommendedApps.isEmpty()) {
            appFlags.add("ALL")
        } else {
            if (entry.recommendedApps.any { it.contains("whatsapp") }) appFlags.add("WHATSAPP")
            if (entry.recommendedApps.any { it.contains("messaging") || it.contains("mms") }) appFlags.add("SMS")
            if (entry.recommendedApps.any { it.contains("gm") }) appFlags.add("GMAIL")
            if (appFlags.isEmpty()) appFlags.add("ALL")
        }
        _editorDraft.value = DialogueEditorDraft(
            existingId = entry.id,
            title = entry.title,
            heardText = entry.heardText,
            category = entry.category,
            situationsCsv = entry.situations.joinToString(", "),
            triggerApps = appFlags,
            fileName = entry.fileName,
            audioUriString = entry.safUriString
        )
    }

    fun closeDialogueEditor() {
        graph.audioPlayer.stop()
        _editorDraft.value = null
    }

    fun updateEditorDraft(transform: (DialogueEditorDraft) -> DialogueEditorDraft) {
        _editorDraft.value?.let { cur -> _editorDraft.value = transform(cur) }
    }

    fun pickAudioIntoEditor(sourceUri: Uri) {
        viewModelScope.launch {
            val copied = graph.vaultRepository.copyExternalClipIntoVault(sourceUri)
            if (copied == null) {
                updateEditorDraft { it.copy(statusNote = "Could not copy selected audio file.") }
                return@launch
            }
            updateEditorDraft { cur ->
                cur.copy(
                    fileName = copied.first,
                    audioUriString = copied.second,
                    title = cur.title.ifBlank {
                        copied.first.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim()
                    },
                    statusNote = "✅ Audio ready (${copied.first}). Tap ✨ Auto-Listen or edit below!"
                )
            }
        }
    }

    fun autoListenAndFillEditorWithAi() {
        val draft = _editorDraft.value ?: return
        if (draft.audioUriString.isBlank()) {
            updateEditorDraft { it.copy(statusNote = "⚠️ Pick an audio clip first!") }
            return
        }
        viewModelScope.launch {
            updateEditorDraft { it.copy(isAutoTagging = true, statusNote = "✨ Listening to Malayalam clip with Gemini...") }
            try {
                val apiKey = graph.prefs.getDecryptedGeminiApiKey()
                if (apiKey.isBlank()) {
                    updateEditorDraft {
                        it.copy(
                            isAutoTagging = false,
                            statusNote = "Add your free Google AI Studio API Key in Settings (⚙️) to enable AI Auto-Listen!"
                        )
                    }
                    return@launch
                }
                val audioPayload = graph.vaultRepository.readAudioBytesForAi(draft.audioUriString, draft.fileName)
                if (audioPayload == null) {
                    updateEditorDraft { it.copy(isAutoTagging = false, statusNote = "Could not read audio clip bytes.") }
                    return@launch
                }
                val tagged = graph.geminiBrainClient.autoTagAudioClipWithGemini(
                    apiKey = apiKey,
                    audioBytes = audioPayload.first,
                    mimeType = audioPayload.second,
                    fileName = draft.fileName
                )
                if (tagged != null) {
                    updateEditorDraft { cur ->
                        cur.copy(
                            isAutoTagging = false,
                            title = tagged.title.ifBlank { cur.title },
                            heardText = tagged.heardText.ifBlank { cur.heardText },
                            category = tagged.category,
                            situationsCsv = tagged.situations.joinToString(", "),
                            statusNote = "✨ AI heard the clip and filled name & trigger situations!"
                        )
                    }
                } else {
                    updateEditorDraft {
                        it.copy(
                            isAutoTagging = false,
                            statusNote = "AI Auto-Listen failed (${graph.geminiBrainClient.lastDebugStatus.value}). You can type name & situations manually."
                        )
                    }
                }
            } finally {
                updateEditorDraft { it.copy(isAutoTagging = false) }
            }
        }
    }

    fun saveEditorDraftToVault() {
        val draft = _editorDraft.value ?: return
        val cleanTitle = draft.title.trim().ifBlank { draft.fileName.substringBeforeLast('.').ifBlank { "My Malayalam Dialogue" } }
        val situations = draft.situationsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val appPackages = if ("ALL" in draft.triggerApps || draft.triggerApps.isEmpty()) {
            emptyList()
        } else {
            buildList {
                if ("WHATSAPP" in draft.triggerApps) {
                    add("com.whatsapp"); add("com.whatsapp.w4b")
                }
                if ("SMS" in draft.triggerApps) {
                    add("com.google.android.apps.messaging"); add("com.android.mms"); add("com.samsung.android.messaging")
                }
                if ("GMAIL" in draft.triggerApps) {
                    add("com.google.android.gm")
                }
            }
        }

        val entry = DialogueEntry(
            id = draft.existingId ?: UUID.randomUUID().toString(),
            fileName = draft.fileName,
            safUriString = draft.audioUriString,
            title = cleanTitle,
            heardText = draft.heardText.trim(),
            category = draft.category,
            situations = situations,
            recommendedApps = appPackages
        )

        viewModelScope.launch {
            val vec = graph.embeddingMatcher.computeDocumentEmbeddingIfInstalled(entry)
            graph.vaultRepository.upsertDialogue(entry, vec)
            _vaultBannerMessage.value = "✅ Saved '$cleanTitle' to My Dialogues!"
            closeDialogueEditor()
        }
    }

    // --- Google AI Studio Key, Voice, Keep-Alive, Live Monitor & SMS Controls ---

    fun saveGeminiApiKey(rawKey: String) {
        viewModelScope.launch {
            graph.prefs.saveGeminiApiKey(rawKey)
            _geminiKeyTestResult.value = if (rawKey.isBlank()) "API key cleared." else "Saved Google AI Studio API key securely."
        }
    }

    fun testGeminiApiKey(draftKey: String) {
        viewModelScope.launch {
            _geminiKeyTestResult.value = "Testing key with Google AI Studio..."
            if (draftKey.isNotBlank()) graph.prefs.saveGeminiApiKey(draftKey)
            val keyToTest = draftKey.trim().ifBlank { graph.prefs.getDecryptedGeminiApiKey() }
            val result = graph.geminiBrainClient.testApiKey(keyToTest)
            _geminiKeyTestResult.value = result.fold(
                onSuccess = { it },
                onFailure = { "❌ ${it.message ?: "Key test failed"}" }
            )
        }
    }

    fun setGeminiVoiceName(voice: String) {
        viewModelScope.launch { graph.prefs.setGeminiVoiceName(voice) }
    }

    fun setSmsAutoReactEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.prefs.setSmsAutoReactEnabled(enabled)
            if (enabled && settings.value.alwaysOnKeepAliveEnabled) {
                AssistantKeepAliveService.syncServiceState(appContext, true)
            }
        }
    }

    fun setAlwaysOnKeepAliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.prefs.setAlwaysOnKeepAliveEnabled(enabled)
            AssistantKeepAliveService.syncServiceState(appContext, enabled)
        }
    }

    fun simulateWhatsAppMessage(sampleText: String) {
        val msg = sampleText.trim().ifBlank { "eda paisa ayachittund" }
        viewModelScope.launch {
            _whatsappSimStatus.value = "Simulating WhatsApp message: \"$msg\"..."
            val note = graph.incomingMessageReactor.reactToIncoming(
                source = "WhatsApp (Simulated)",
                sourceKey = "sim_wa_${System.currentTimeMillis()}",
                sender = "Friend",
                messageText = msg,
                appPackageHint = "com.whatsapp",
                bypassCooldownAndGuards = true
            )
            _whatsappSimStatus.value = note ?: "No matching reaction (add an MP3 or Gemini API key)"
        }
    }

    fun setSpeakAfterDialogueClip(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setSpeakAfterDialogueClip(enabled) }
    }

    fun unloadRamNow() {
        viewModelScope.launch { graph.modelCoordinator.unloadAll("manual_user_tap") }
    }

    fun toggleSpeechLocale() {
        viewModelScope.launch {
            val next = if (settings.value.speechLocale == "ml-IN") "en-IN" else "ml-IN"
            graph.prefs.setSpeechLocale(next)
            if (!playbackState.value.isPlaying) {
                _heroGreetingOrReply.value = if (next == "ml-IN") "നമസ്കാരം" else "Namaskaram"
            }
        }
    }

    fun setTtsRepliesEnabled(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setTtsRepliesEnabled(enabled) }
    }

    fun toggleTodoDone(id: Long, done: Boolean) {
        viewModelScope.launch { graph.db.todoDao().setCompleted(id, done) }
    }

    fun deleteTodo(id: Long) {
        viewModelScope.launch { graph.db.todoDao().deleteTodo(id) }
    }

    // --- Dialogue Vault Helpers & Fixed Test Simulator ---

    fun bindSafFolder(uri: Uri) {
        viewModelScope.launch {
            val existingCount = graph.vaultRepository.bindSafFolder(uri)
            _vaultBannerMessage.value = "SAF Vault linked (.nomedia active, $existingCount dialogues ready)."
        }
    }

    fun attachAudioToExistingDialogue(dialogueId: String, sourceUri: Uri) {
        viewModelScope.launch {
            val updated = graph.vaultRepository.attachAudioToDialogue(dialogueId, sourceUri)
            if (updated != null) {
                _vaultBannerMessage.value = "✅ Linked ${updated.fileName} to '${updated.title}'"
                graph.audioPlayer.playEntry(updated)
            } else {
                _vaultBannerMessage.value = "Could not attach selected audio file."
            }
        }
    }

    fun addSingleClipFromPicker(sourceUri: Uri) {
        viewModelScope.launch {
            val copied = graph.vaultRepository.copyExternalClipIntoVault(sourceUri)
            if (copied == null) {
                _vaultBannerMessage.value = "Could not copy audio clip."
                return@launch
            }
            _vaultBannerMessage.value = "Analyzing & linking ${copied.first}..."
            AudioAutoOrganizeWorker.enqueueSequentialClips(appContext, listOf(copied))
        }
    }

    fun scanAndAutoOrganizeSafFolder() {
        viewModelScope.launch {
            val pendingNames = pendingReviews.value.map { it.fileName }.toSet()
            val unorganized = graph.vaultRepository.findUnorganizedAudioFiles(pendingNames)
            if (unorganized.isEmpty()) {
                _vaultBannerMessage.value = if (settings.value.safTreeUri.isBlank()) {
                    "Tap '+ Add My Dialogue' to pick any MP3 directly, or choose a SAF backup folder."
                } else {
                    "All audio clips in folder are already organized!"
                }
            } else {
                _vaultBannerMessage.value = "Queued ${unorganized.size} new audio clip(s)..."
                AudioAutoOrganizeWorker.enqueueSequentialClips(appContext, unorganized)
            }
        }
    }

    fun confirmPendingReview(
        item: PendingReviewEntity,
        editedTitle: String,
        editedCategory: String,
        editedSituations: List<String>
    ) {
        viewModelScope.launch {
            val entry = DialogueEntry(
                id = item.id,
                fileName = item.fileName,
                safUriString = item.safUriString,
                title = editedTitle.ifBlank { item.title },
                heardText = item.heardText,
                category = editedCategory,
                situations = editedSituations.filter { it.isNotBlank() },
                recommendedApps = item.recommendedAppsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            )
            val cachedVec = runCatching {
                if (item.cachedVectorJson.isNotBlank()) {
                    Json.decodeFromString<List<Float>>(item.cachedVectorJson).toFloatArray()
                } else null
            }.getOrNull()
            val finalVec = graph.embeddingMatcher.computeDocumentEmbeddingIfInstalled(entry) ?: cachedVec
            graph.vaultRepository.upsertDialogue(entry, finalVec)
            graph.db.pendingReviewDao().deleteById(item.id)
        }
    }

    fun autoAcceptAllPendingReviews() {
        viewModelScope.launch {
            val all = graph.db.pendingReviewDao().getAllPending()
            for (item in all) {
                val entry = DialogueEntry(
                    id = item.id,
                    fileName = item.fileName,
                    safUriString = item.safUriString,
                    title = item.title,
                    heardText = item.heardText,
                    category = item.category,
                    situations = item.situationsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    recommendedApps = item.recommendedAppsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                )
                val cachedVec = runCatching {
                    if (item.cachedVectorJson.isNotBlank()) {
                        Json.decodeFromString<List<Float>>(item.cachedVectorJson).toFloatArray()
                    } else null
                }.getOrNull()
                graph.vaultRepository.upsertDialogue(entry, cachedVec)
            }
            graph.db.pendingReviewDao().clearAll()
            _vaultBannerMessage.value = "Accepted ${all.size} dialogue(s)!"
        }
    }

    fun dismissPendingReview(id: String) {
        viewModelScope.launch { graph.db.pendingReviewDao().deleteById(id) }
    }

    fun deleteDialogue(id: String) {
        viewModelScope.launch { graph.vaultRepository.deleteDialogue(id) }
    }

    fun playDialogue(entry: DialogueEntry) {
        val ok = graph.audioPlayer.playEntry(entry)
        if (!ok) {
            _vaultBannerMessage.value = "⚠️ Attach an MP3/WAV clip to '${entry.title}' first!"
        }
    }

    fun playRawUri(uriString: String, id: String, title: String, transcript: String) {
        graph.audioPlayer.playUri(uriString, id, title, transcript)
    }

    fun stopAudio() {
        graph.audioPlayer.stop()
    }

    /**
     * Fixed Test Simulator:
     *  - Uses `GeminiBrainClient` when API key is configured (shows AI's chosen clip at #1 + reply!).
     *  - Always ranks offline scores accurately via `EmbeddingMatcherEngine` (no stale `0.04` winners).
     */
    fun runTestSimulator(testPhrase: String) {
        if (testPhrase.isBlank()) return
        viewModelScope.launch {
            _simulatorAiNote.value = "Testing..."
            val offlineTop3 = graph.embeddingMatcher.rankDialogues(rawQuery = testPhrase, topK = 3)
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            val vaultEntries = graph.vaultRepository.dialogues.value

            if (apiKey.isNotBlank()) {
                val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) { true }
                val ai = graph.geminiBrainClient.analyzeIncomingMessage(
                    apiKey = apiKey,
                    sender = "Simulator",
                    source = "Test Simulator",
                    messageText = testPhrase,
                    vaultDialogues = summaries
                )
                if (ai != null) {
                    val aiEntry = ai.playDialogueId?.let { id -> vaultEntries.firstOrNull { it.id == id } }
                    val merged = if (aiEntry != null) {
                        val aiWinner = MatchCandidate(aiEntry, 0.98f, usedNeuralEmbedding = true)
                        (listOf(aiWinner) + offlineTop3.filterNot { it.entry.id == aiEntry.id }).take(3)
                    } else {
                        offlineTop3
                    }
                    _simulatorResults.value = merged
                    _simulatorAiNote.value = if (aiEntry != null) {
                        "✨ Gemini picked '${aiEntry.title}' • ${ai.replyMalayalam}"
                    } else {
                        "✨ Gemini reply: ${ai.replyMalayalam}"
                    }
                    if (aiEntry != null && graph.vaultRepository.hasPlayableAudio(aiEntry)) {
                        graph.audioPlayer.playEntry(aiEntry)
                    }
                    return@launch
                }
            }

            _simulatorResults.value = offlineTop3
            val threshold = settings.value.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
            val winner = offlineTop3.firstOrNull()
            if (winner != null && winner.score >= threshold) {
                val played = graph.audioPlayer.playEntry(winner.entry)
                _simulatorAiNote.value = if (played) {
                    "🔊 Played '${winner.entry.title}' (${String.format("%.2f", winner.score)})"
                } else {
                    "Matched '${winner.entry.title}' (${String.format("%.2f", winner.score)}) — tap '⚠️ Tap to pick MP3' to attach audio!"
                }
            } else {
                _simulatorAiNote.value = "No match above ${String.format("%.2f", threshold)} (unrelated text stays silent)"
            }
        }
    }

    // --- Gmail Direct Watcher ---

    fun saveGmailConfig(email: String, appPasswordInput: String) {
        viewModelScope.launch {
            graph.prefs.saveGmailCredentials(email, appPasswordInput)
            _gmailTestResult.value = "Saved Gmail credentials securely."
            if (settings.value.gmailWatcherEnabled) {
                GmailDirectWatcherService.syncServiceState(appContext, true)
            }
        }
    }

    fun setGmailWatcherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.prefs.setGmailWatcherEnabled(enabled)
            GmailDirectWatcherService.syncServiceState(appContext, enabled)
        }
    }

    fun testGmailAndCheckLatest(emailDraft: String, passwordDraft: String) {
        viewModelScope.launch {
            _gmailTestResult.value = "Connecting to imap.gmail.com:993..."
            if (emailDraft.isNotBlank() && passwordDraft.isNotBlank()) {
                graph.prefs.saveGmailCredentials(emailDraft, passwordDraft)
            }
            val activeEmail = emailDraft.trim().ifBlank { settings.value.gmailAddress }
            val activePass = if (passwordDraft.isNotBlank()) {
                passwordDraft.replace(" ", "").trim()
            } else {
                graph.prefs.getDecryptedGmailAppPassword()
            }

            runCatching {
                val (latestUid, header) = GmailImapClient.testConnectionAndFetchLatest(activeEmail, activePass)
                graph.prefs.updateGmailWatcherProgress(latestUid, "Verified • Latest UID #$latestUid")
                if (header == null) {
                    _gmailTestResult.value = "✅ Connected to Gmail! Inbox is empty."
                } else {
                    val reaction = graph.incomingMessageReactor.reactToIncoming(
                        source = "Gmail Test",
                        sourceKey = "gmail_test_${System.currentTimeMillis()}",
                        sender = header.from,
                        messageText = "${header.subject} ${header.snippet}",
                        appPackageHint = "com.google.android.gm",
                        bypassCooldownAndGuards = true
                    )
                    _gmailTestResult.value = if (!reaction.isNullOrBlank()) {
                        "✅ Connected! Latest: \"${header.subject.take(40)}\" → $reaction"
                    } else {
                        "✅ Connected! Latest: \"${header.subject.take(45)}\" (Skipped as unrelated/spam)"
                    }
                }
            }.onFailure { err ->
                _gmailTestResult.value = "❌ ${err.message ?: "Connection failed"}"
            }
        }
    }

    // --- Optional Local Models & Settings ---

    fun downloadModel(model: ManagedModelId) {
        viewModelScope.launch { graph.modelDownloadManager.downloadModel(model, settings.value.hfAuthToken) }
    }

    fun importLocalModelFile(model: ManagedModelId, uri: Uri) {
        viewModelScope.launch { graph.modelDownloadManager.importLocalFile(model, uri) }
    }

    fun deleteModel(model: ManagedModelId) {
        viewModelScope.launch { graph.modelDownloadManager.deleteModel(model, graph.modelCoordinator) }
    }

    fun setLowRamSafeMode(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setLowRamSafeMode(enabled) }
    }

    fun setUltraLight2GbMode(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setUltraLight2GbMode(enabled) }
    }

    fun setEnableGemma4E2b(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setEnableGemma4E2b(enabled) }
    }

    fun setMatchThreshold(value: Float) {
        viewModelScope.launch { graph.prefs.setMatchThreshold(value) }
    }

    fun setHeadphonesOnly(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setHeadphonesOnly(enabled) }
    }

    fun setLowBatteryReaction(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setLowBatteryReaction(enabled) }
    }

    fun setAutoAcceptBulk(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setAutoAcceptBulkImports(enabled) }
    }

    fun toggleWatchedPackage(pkg: String, enabled: Boolean) {
        viewModelScope.launch { graph.prefs.toggleWatchedPackage(pkg, enabled) }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch { graph.prefs.setHfToken(token) }
    }

    override fun onCleared() {
        voiceRecorder.stopRecordingNow()
        vadRecordingJob?.cancel()
        super.onCleared()
    }
}
