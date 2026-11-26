package com.malayalam.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.ai.GeminiBrainResponse
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.audio.SmartVoiceRecorder
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.PendingReviewEntity
import com.malayalam.assistant.data.UserSettings
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

data class ChatTurn(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val badge: String = "",
    val triggeredClipTitle: String? = null
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

    private val _vaultBannerMessage = MutableStateFlow("")
    val vaultBannerMessage: StateFlow<String> = _vaultBannerMessage.asStateFlow()

    private val _gmailTestResult = MutableStateFlow("")
    val gmailTestResult: StateFlow<String> = _gmailTestResult.asStateFlow()

    private val _geminiKeyTestResult = MutableStateFlow("")
    val geminiKeyTestResult: StateFlow<String> = _geminiKeyTestResult.asStateFlow()

    fun updateLivePartialSpeech(partial: String) {
        if (partial.isNotBlank()) _heroGreetingOrReply.value = partial
    }

    fun updateExternalMicRms(level: Float) {
        _micRmsLevel.value = level
    }

    fun resetGreetingToNamaskaram() {
        _heroGreetingOrReply.value = if (settings.value.isMalayalamMode) "നമസ്കാരം" else "Namaskaram"
    }

    /**
     * Real VAD Mic Recording (`SmartVoiceRecorder`):
     *  - If already recording, tapping immediately ends capture and sends captured audio to Gemini.
     *  - Otherwise calibrates 350ms ambient noise, waits up to 8s for speech, auto-stops after
     *    1500ms of silence, and sends 16 kHz WAV straight to `GeminiBrainClient`!
     */
    fun toggleSmartVadRecording() {
        if (_isListeningVad.value || voiceRecorder.isRecording) {
            voiceRecorder.stopRecordingNow()
            return
        }
        if (playbackState.value.isPlaying) {
            graph.audioPlayer.stop()
            return
        }

        vadRecordingJob?.cancel()
        vadRecordingJob = viewModelScope.launch {
            _isListeningVad.value = true
            _micRmsLevel.value = 0.15f
            val recording = voiceRecorder.recordWithVad { rms ->
                _micRmsLevel.value = rms
            }
            _isListeningVad.value = false
            _micRmsLevel.value = 0f

            if (!recording.speechDetected || recording.wavBytes.isEmpty()) {
                // No speech within 8 seconds -> return cleanly to Namaskaram without error
                resetGreetingToNamaskaram()
                return@launch
            }

            sendWavAudioToGemini(recording.wavBytes)
        }
    }

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
                vaultDialogues = summaries,
                preferMalayalam = curSettings.isMalayalamMode
            )

            if (aiResp != null) {
                handleGeminiBrainResponse(aiResp, apiKey, curSettings)
            } else {
                val offlineMsg = if (curSettings.isMalayalamMode) {
                    "ഇന്റർനെറ്റ് കണക്ഷൻ അല്ലെങ്കിൽ API കീ പരിശോധിക്കുക."
                } else {
                    "Check internet connection or Google AI Studio API key."
                }
                _heroGreetingOrReply.value = offlineMsg
            }
        } finally {
            _isThinking.value = false
        }
    }

    fun sendChatOrAction(rawInput: String) {
        val text = rawInput.trim()
        if (text.isBlank() || _isThinking.value) return

        _chatHistory.value = (_chatHistory.value + ChatTurn(isUser = true, text = text)).takeLast(40)
        _isThinking.value = true

        viewModelScope.launch {
            try {
                val curSettings = settings.value
                val apiKey = graph.prefs.getDecryptedGeminiApiKey()

                // 1. Primary: Google AI Studio Brain (`0 MB` phone RAM) when API key is configured
                if (apiKey.isNotBlank()) {
                    val vaultEntries = graph.vaultRepository.dialogues.value
                    val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                        graph.vaultRepository.hasPlayableAudio(it)
                    }
                    val aiResp = graph.geminiBrainClient.askWithTextOrAudio(
                        apiKey = apiKey,
                        userText = text,
                        vaultDialogues = summaries,
                        preferMalayalam = curSettings.isMalayalamMode
                    )
                    if (aiResp != null) {
                        handleGeminiBrainResponse(aiResp, apiKey, curSettings, alreadyLoggedUserText = true)
                        return@launch
                    }
                }

                // 2. Automatic Offline Fallback (when API key is empty or phone has no internet)
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

        // Execute phone action if Gemini requested one (set_alarm, open_app, dial_call, add_todo)
        if (ai.action.type.isNotBlank() && !ai.action.type.equals("none", ignoreCase = true)) {
            graph.functionRouter.executeGeminiAction(ai.action.type, ai.action.value)
        }

        val replyText = ai.replyMalayalam.ifBlank {
            if (curSettings.isMalayalamMode) "ശരി, ചെയ്തു!" else "Done!"
        }
        _heroGreetingOrReply.value = replyText

        val vaultEntries = graph.vaultRepository.dialogues.value
        val matchedDialogue = ai.playDialogueId?.let { targetId ->
            vaultEntries.firstOrNull { it.id == targetId && graph.vaultRepository.hasPlayableAudio(it) }
        }

        var playedClipTitle: String? = null
        if (matchedDialogue != null) {
            val shouldSpeakAfter = curSettings.speakAfterDialogueClip && curSettings.ttsVoiceRepliesEnabled && replyText.isNotBlank()
            val played = graph.audioPlayer.playEntry(
                entry = matchedDialogue,
                onFinished = if (shouldSpeakAfter) {
                    {
                        viewModelScope.launch {
                            graph.audioPlayer.speakNaturalReply(
                                text = replyText,
                                apiKey = apiKey,
                                ttsClient = graph.geminiTtsClient,
                                voiceName = curSettings.geminiVoiceName,
                                ttsStyle = ai.ttsStyle,
                                preferMalayalam = curSettings.isMalayalamMode
                            )
                        }
                    }
                } else null
            )
            if (played) playedClipTitle = matchedDialogue.title
        }

        // Default: speak replyMalayalam via expressive Gemini TTS when no movie clip matched!
        if (playedClipTitle == null && curSettings.ttsVoiceRepliesEnabled) {
            graph.audioPlayer.speakNaturalReply(
                text = replyText,
                apiKey = apiKey,
                ttsClient = graph.geminiTtsClient,
                voiceName = curSettings.geminiVoiceName,
                ttsStyle = ai.ttsStyle,
                preferMalayalam = curSettings.isMalayalamMode
            )
        }

        _chatHistory.value = (_chatHistory.value + ChatTurn(
            isUser = false,
            text = replyText,
            badge = if (playedClipTitle != null) "Gemini + Clip" else "Gemini Voice",
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

    // --- Google AI Studio Key, Voice & SMS Controls ---

    fun saveGeminiApiKey(rawKey: String) {
        viewModelScope.launch {
            graph.prefs.saveGeminiApiKey(rawKey)
            _geminiKeyTestResult.value = if (rawKey.isBlank()) "API key cleared." else "Saved Google AI Studio API key securely."
        }
    }

    fun testGeminiApiKey(draftKey: String) {
        viewModelScope.launch {
            _geminiKeyTestResult.value = "Testing key with Google AI Studio..."
            if (draftKey.isNotBlank()) {
                graph.prefs.saveGeminiApiKey(draftKey)
            }
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
        viewModelScope.launch { graph.prefs.setSmsAutoReactEnabled(enabled) }
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

    // --- Dialogue Vault & 1-Tap Direct Audio Linking ---

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
                    "Select a folder first, or tap '+ Add Dialogue' / '⚠️ Tap to pick MP3' on any card."
                } else {
                    "All audio clips in folder are already organized!"
                }
            } else {
                _vaultBannerMessage.value = "Queued ${unorganized.size} new audio clip(s) sequentially..."
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

    fun saveEditedDialogue(
        entry: DialogueEntry,
        newTitle: String,
        newCategory: String,
        newSituationsCsv: String
    ) {
        viewModelScope.launch {
            val updated = entry.copy(
                title = newTitle.trim().ifBlank { entry.title },
                category = newCategory,
                situations = newSituationsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            )
            val vec = graph.embeddingMatcher.computeDocumentEmbeddingIfInstalled(updated)
            graph.vaultRepository.upsertDialogue(updated, vec)
        }
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

    fun runTestSimulator(testPhrase: String) {
        if (testPhrase.isBlank()) return
        viewModelScope.launch {
            val top3 = graph.embeddingMatcher.rankDialogues(rawQuery = testPhrase, topK = 3)
            _simulatorResults.value = top3
            val threshold = settings.value.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
            val winner = top3.firstOrNull()
            if (winner != null && winner.score >= threshold) {
                val played = graph.audioPlayer.playEntry(winner.entry)
                if (!played) {
                    _vaultBannerMessage.value = "Matched '${winner.entry.title}' (${String.format("%.2f", winner.score)}) — tap '⚠️ Tap to pick MP3' to attach audio!"
                }
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
                        appPackageHint = "com.google.android.gm"
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
        viewModelScope.launch {
            graph.modelDownloadManager.downloadModel(model, settings.value.hfAuthToken)
        }
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
