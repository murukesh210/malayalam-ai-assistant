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
import com.malayalam.assistant.audio.ContinuousVoiceController
import com.malayalam.assistant.audio.VoiceSessionState
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.PendingReviewEntity
import com.malayalam.assistant.data.SituationCategory
import com.malayalam.assistant.data.UserSettings
import com.malayalam.assistant.data.VoicePack
import com.malayalam.assistant.service.AssistantKeepAliveService
import com.malayalam.assistant.service.GmailDirectWatcherService
import com.malayalam.assistant.service.GmailImapClient
import com.malayalam.assistant.worker.AudioAutoOrganizeWorker
import kotlinx.coroutines.Dispatchers
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
    val scope: String = "specific", // "specific" | "broad"
    val situationsCsv: String = "",
    val triggerApps: Set<String> = setOf("ALL"),
    val fileName: String = "",
    val audioUriString: String = "",
    val isAutoTagging: Boolean = false,
    val statusNote: String = ""
)

data class SimulatorDiagnosticUi(
    val understoodMeaning: String,
    val matchedDialogueName: String,
    val latencyMs: Long,
    val apiStatus: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.from(application)
    private val appContext = application.applicationContext

    val isDevice2GbClass: Boolean = AppPreferences.isDevice2GbClass(appContext)
    val isAndroidGoDevice: Boolean = AppPreferences.isAndroidGoDevice(appContext)

    val ramStatus = graph.modelCoordinator.ramStatus
    val playbackState = graph.audioPlayer.playbackState
    val packs = graph.voicePackRepository.packs
    val activePackId = graph.voicePackRepository.activePackId
    val dialogues = graph.voicePackRepository.activeDialogues
    val pipelineProgress = graph.pipelineTracker.state
    val modelStates = graph.modelDownloadManager.states
    val lastApiDebugStatus = graph.geminiBrainClient.lastDebugStatus
    val liveMonitorLogs = graph.incomingMessageReactor.liveMonitorLogs
    val diagnosticLogs = com.malayalam.assistant.ai.DiagnosticLogStore.events
    val isAssistantEnabled: StateFlow<Boolean> = graph.prefs.isAssistantEnabledFlow

    private var activeChatJob: kotlinx.coroutines.Job? = null

    val playableAudioMap: StateFlow<Map<String, Boolean>> = dialogues
        .map { list ->
            withContext(Dispatchers.IO) {
                list.associate { entry -> entry.id to graph.voicePackRepository.hasPlayableAudio(entry) }
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

    private val _externalMicRms = MutableStateFlow(0f)
    private val _isThinkingTyped = MutableStateFlow(false)
    val isThinkingTyped: StateFlow<Boolean> = _isThinkingTyped.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chatHistory: StateFlow<List<ChatTurn>> = _chatHistory.asStateFlow()

    private val _simulatorResults = MutableStateFlow<List<MatchCandidate>>(emptyList())
    val simulatorResults: StateFlow<List<MatchCandidate>> = _simulatorResults.asStateFlow()

    private val _simulatorAiNote = MutableStateFlow("")
    val simulatorAiNote: StateFlow<String> = _simulatorAiNote.asStateFlow()

    private val _simulatorDiagnostic = MutableStateFlow<SimulatorDiagnosticUi?>(null)
    val simulatorDiagnostic: StateFlow<SimulatorDiagnosticUi?> = _simulatorDiagnostic.asStateFlow()

    private val _vaultBannerMessage = MutableStateFlow("")
    val vaultBannerMessage: StateFlow<String> = _vaultBannerMessage.asStateFlow()

    private val _packBannerMessage = MutableStateFlow("")
    val packBannerMessage: StateFlow<String> = _packBannerMessage.asStateFlow()

    private val _importedPackNeedingAiTagId = MutableStateFlow<String?>(null)
    val importedPackNeedingAiTagId: StateFlow<String?> = _importedPackNeedingAiTagId.asStateFlow()

    private val _isBatchAutoTagging = MutableStateFlow(false)
    val isBatchAutoTagging: StateFlow<Boolean> = _isBatchAutoTagging.asStateFlow()

    private val _gmailTestResult = MutableStateFlow("")
    val gmailTestResult: StateFlow<String> = _gmailTestResult.asStateFlow()

    private val _geminiKeyTestResult = MutableStateFlow("")
    val geminiKeyTestResult: StateFlow<String> = _geminiKeyTestResult.asStateFlow()

    private val _whatsappSimStatus = MutableStateFlow("")
    val whatsappSimStatus: StateFlow<String> = _whatsappSimStatus.asStateFlow()

    private val _editorDraft = MutableStateFlow<DialogueEditorDraft?>(null)
    val editorDraft: StateFlow<DialogueEditorDraft?> = _editorDraft.asStateFlow()

    // Continuous Hands-Free Gemini-Live Conversation Controller (with 15s Watchdog Recovery)
    private val continuousVoiceController = ContinuousVoiceController(
        scope = viewModelScope,
        audioPlayer = graph.audioPlayer,
        onTimeoutOrError = { shortReason ->
            _heroGreetingOrReply.value = shortReason
        },
        onProcessCapturedWav = { wavBytes, onPlaybackFinished ->
            processCapturedWavTurn(wavBytes, onPlaybackFinished)
        }
    )

    val voiceSessionState: StateFlow<VoiceSessionState> = continuousVoiceController.state
    val continuousMicRms: StateFlow<Float> = continuousVoiceController.micRmsLevel

    fun activePackDisplayName(): String = graph.voicePackRepository.getActivePackMeta().packName

    fun updateLivePartialSpeech(partial: String) {
        if (partial.isNotBlank()) _heroGreetingOrReply.value = partial
    }

    fun updateExternalMicRms(level: Float) {
        _externalMicRms.value = level
    }

    fun resetGreetingToNamaskaram() {
        if (!graph.prefs.isAssistantEnabledNow) {
            _heroGreetingOrReply.value = "അസിസ്റ്റന്റ് ഓഫ് ആണ്"
            return
        }
        _heroGreetingOrReply.value = if (settings.value.isMalayalamMode) "നമസ്കാരം" else "Namaskaram"
    }

    // --- Persistent Master Power Switch (ON / OFF) ---

    fun toggleMasterAssistantPower() {
        val nextEnabled = !graph.prefs.isAssistantEnabledNow
        setMasterAssistantPower(nextEnabled)
    }

    fun setMasterAssistantPower(enabled: Boolean) {
        // Flip synchronous volatile flag FIRST so all background listeners see the new state in 0ms
        graph.prefs.setAssistantEnabledInstantFlag(enabled)

        if (!enabled) {
            // 1. Immediately stop continuous mic & cancel in-flight Gemini Brain / Streaming TTS jobs
            continuousVoiceController.stopSession()
            activeChatJob?.cancel()
            activeChatJob = null
            _isThinkingTyped.value = false
            graph.geminiTtsClient.cancelStreaming()
            com.malayalam.assistant.ai.SharedGeminiHttp.cancelAllInFlightCalls()

            // 2. Immediately stop and release DialogueAudioPlayer so no clip or TTS keeps speaking
            graph.audioPlayer.releaseAndReset()

            // 3. Stop AssistantKeepAliveService (dismissing FGS notification) & pause GmailDirectWatcher
            AssistantKeepAliveService.syncServiceState(appContext, false)
            GmailDirectWatcherService.syncServiceState(appContext, false)

            _heroGreetingOrReply.value = "അസിസ്റ്റന്റ് ഓഫ് ആണ്"

            viewModelScope.launch {
                graph.prefs.setAssistantEnabled(false)
            }
        } else {
            // Restore normal glowing orb greeting & resume previously enabled background services seamlessly
            _heroGreetingOrReply.value = if (settings.value.isMalayalamMode) "നമസ്കാരം" else "Namaskaram"
            viewModelScope.launch {
                graph.prefs.setAssistantEnabled(true)
                val cur = graph.prefs.snapshot()
                if (cur.backgroundModeActive) {
                    AssistantKeepAliveService.syncServiceState(appContext, true)
                }
                if (cur.gmailWatcherEnabled && cur.gmailAddress.isNotBlank()) {
                    GmailDirectWatcherService.syncServiceState(appContext, true)
                }
            }
        }
    }

    // --- Continuous Live Mode & Barge-In ---

    fun onLiveOrbTapped() {
        if (!graph.prefs.isAssistantEnabledNow) return
        continuousVoiceController.onOrbTapped(continuousMode = settings.value.continuousLiveMode)
    }

    fun stopContinuousLiveSession() {
        continuousVoiceController.stopSession()
        resetGreetingToNamaskaram()
    }

    fun onAppBackgrounded() {
        continuousVoiceController.stopMicForBackground()
    }

    // --- One-Tap Background Mode (mediaPlayback FGS) ---

    fun toggleBackgroundMode() {
        if (!graph.prefs.isAssistantEnabledNow) return
        val nextActive = !settings.value.backgroundModeActive
        if (nextActive) {
            // Stop foreground continuous mic before entering background listener mode
            continuousVoiceController.stopSession()
        }
        viewModelScope.launch {
            graph.prefs.setBackgroundModeActive(nextActive)
            AssistantKeepAliveService.syncServiceState(appContext, nextActive)
        }
    }

    private fun recentTurnsForBrain(): List<ConversationHistoryItem> =
        _chatHistory.value.takeLast(6).map { ConversationHistoryItem(isUser = it.isUser, text = it.text) }

    private suspend fun processCapturedWavTurn(
        wavBytes: ByteArray,
        onPlaybackFinished: () -> Unit
    ) {
        val curSettings = settings.value
        val apiKey = graph.prefs.getDecryptedGeminiApiKey()
        val activePack = graph.voicePackRepository.getActivePackMeta()
        val vaultEntries = graph.voicePackRepository.activeDialogues.value
        val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
            graph.voicePackRepository.hasPlayableAudio(it)
        }

        val aiResp = graph.geminiBrainClient.askWithTextOrAudio(
            apiKey = apiKey,
            wavAudioBytes = wavBytes,
            recentTurns = recentTurnsForBrain(),
            vaultDialogues = summaries,
            packPersonaInstruction = activePack.personaInstruction,
            preferMalayalam = curSettings.isMalayalamMode
        )

        if (aiResp != null) {
            continuousVoiceController.markSpeakingOrPlaying()
            handleGeminiBrainResponse(
                ai = aiResp,
                apiKey = apiKey,
                curSettings = curSettings,
                activePack = activePack,
                onAllAudioFinished = onPlaybackFinished
            )
        } else {
            val debugHint = graph.geminiBrainClient.lastDebugStatus.value
            _heroGreetingOrReply.value = if (curSettings.isMalayalamMode) {
                "ക്ഷമിക്കണം, മറുപടി ലഭിച്ചില്ല ($debugHint)"
            } else {
                "Could not reach Gemini ($debugHint)"
            }
            onPlaybackFinished()
        }
    }

    fun sendChatOrAction(rawInput: String) {
        if (!graph.prefs.isAssistantEnabledNow) return
        val text = rawInput.trim()
        if (text.isBlank() || _isThinkingTyped.value) return

        val historyBeforeCurrent = recentTurnsForBrain()
        _chatHistory.value = (_chatHistory.value + ChatTurn(isUser = true, text = text)).takeLast(40)
        _isThinkingTyped.value = true

        activeChatJob?.cancel()
        activeChatJob = viewModelScope.launch {
            try {
                val curSettings = settings.value
                val apiKey = graph.prefs.getDecryptedGeminiApiKey()

                if (apiKey.isNotBlank()) {
                    val activePack = graph.voicePackRepository.getActivePackMeta()
                    val vaultEntries = graph.voicePackRepository.activeDialogues.value
                    val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                        graph.voicePackRepository.hasPlayableAudio(it)
                    }
                    val aiResp = graph.geminiBrainClient.askWithTextOrAudio(
                        apiKey = apiKey,
                        userText = text,
                        recentTurns = historyBeforeCurrent,
                        vaultDialogues = summaries,
                        packPersonaInstruction = activePack.personaInstruction,
                        preferMalayalam = curSettings.isMalayalamMode
                    )
                    if (aiResp != null) {
                        val followUpTrigger: (() -> Unit)? = if (aiResp.expectFollowUp && curSettings.hasGeminiApiKey) {
                            {
                                continuousVoiceController.startSession(continuousMode = curSettings.continuousLiveMode)
                            }
                        } else null
                        handleGeminiBrainResponse(
                            ai = aiResp,
                            apiKey = apiKey,
                            curSettings = curSettings,
                            activePack = activePack,
                            alreadyLoggedUserText = true,
                            onAllAudioFinished = followUpTrigger
                        )
                        return@launch
                    }
                }

                runOfflineFallback(text, curSettings)
            } finally {
                _isThinkingTyped.value = false
            }
        }
    }

    private suspend fun handleGeminiBrainResponse(
        ai: GeminiBrainResponse,
        apiKey: String,
        curSettings: UserSettings,
        activePack: VoicePack,
        alreadyLoggedUserText: Boolean = false,
        onAllAudioFinished: (() -> Unit)? = null
    ) {
        if (!alreadyLoggedUserText && ai.heardTranscript.isNotBlank()) {
            _chatHistory.value = (_chatHistory.value + ChatTurn(isUser = true, text = ai.heardTranscript)).takeLast(40)
        }

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

        val vaultEntries = graph.voicePackRepository.activeDialogues.value
        val matchedDialogue = ai.resolvedDialogueId?.let { targetId ->
            vaultEntries.firstOrNull { it.id == targetId && graph.voicePackRepository.hasPlayableAudio(it) }
        }
        val activeVoice = activePack.ttsVoice.ifBlank { curSettings.geminiVoiceName }

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
                            voiceName = activeVoice,
                            ttsStyle = ai.ttsStyle,
                            preferMalayalam = curSettings.isMalayalamMode,
                            onFinished = onAllAudioFinished
                        )
                    }
                    Unit
                }
            } else {
                onAllAudioFinished
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
                voiceName = activeVoice,
                ttsStyle = ai.ttsStyle,
                preferMalayalam = curSettings.isMalayalamMode,
                onFinished = onAllAudioFinished
            )
        } else if (playedClipTitle == null) {
            onAllAudioFinished?.invoke()
        }

        _chatHistory.value = (_chatHistory.value + ChatTurn(
            isUser = false,
            text = replyText,
            badge = if (playedClipTitle != null) "${activePack.packName} + Clip" else activePack.packName,
            triggeredClipTitle = playedClipTitle
        )).takeLast(40)
    }

    private suspend fun runOfflineFallback(text: String, curSettings: UserSettings) {
        val ml = curSettings.isMalayalamMode
        val threshold = curSettings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
        val activeList = graph.voicePackRepository.activeDialogues.value

        val action = graph.functionRouter.routeAndExecute(text)
        if (action.handledAsAction) {
            _heroGreetingOrReply.value = action.summaryText
            var playedClipTitle: String? = null
            if (!action.awaitingFollowUp && action.reactionQuery.isNotBlank()) {
                val reaction = graph.embeddingMatcher.rankDialogues(
                    rawQuery = "${action.reactionQuery} $text",
                    requirePlayableAudio = true,
                    overrideDialogues = activeList,
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
            overrideDialogues = activeList,
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

    // --- Actor Voice Packs Management (Switch / Create / Import / Export / Batch AI Tag) ---

    fun selectVoicePack(packId: String) {
        viewModelScope.launch {
            graph.voicePackRepository.selectActivePack(packId)
            val meta = graph.voicePackRepository.getActivePackMeta()
            _packBannerMessage.value = "✅ Switched active pack to '${meta.packName}'"
        }
    }

    fun createVoicePack(name: String, actor: String, voice: String, persona: String) {
        viewModelScope.launch {
            val created = graph.voicePackRepository.createPack(name, actor, voice, persona)
            _packBannerMessage.value = "✅ Created & activated '${created.packName}'!"
        }
    }

    fun deleteVoicePack(packId: String) {
        viewModelScope.launch {
            graph.voicePackRepository.deletePack(packId)
            if (_importedPackNeedingAiTagId.value == packId) {
                _importedPackNeedingAiTagId.value = null
            }
            _packBannerMessage.value = "Deleted voice pack."
        }
    }

    fun importVoicePackFromZip(uri: Uri) {
        viewModelScope.launch {
            _packBannerMessage.value = "Importing voice pack archive..."
            runCatching {
                graph.voicePackRepository.importPackFromZip(uri)
            }.onSuccess { res ->
                val summary = "Imported '${res.pack.packName}' (${res.importedAudioCount} audio clips linked)"
                _packBannerMessage.value = "✅ $summary"
                android.widget.Toast.makeText(appContext, summary, android.widget.Toast.LENGTH_LONG).show()
                _importedPackNeedingAiTagId.value = if (res.missingManifestAutoCreated && res.importedAudioCount > 0) {
                    res.pack.packId
                } else null
            }.onFailure { err ->
                val reason = err.message?.take(120) ?: "Invalid ZIP archive"
                _packBannerMessage.value = "❌ Import failed: $reason"
                android.widget.Toast.makeText(appContext, "Import failed: $reason", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportVoicePackToZip(packId: String, destUri: Uri) {
        viewModelScope.launch {
            _packBannerMessage.value = "Exporting voice pack (.mlpack.zip)..."
            runCatching {
                graph.voicePackRepository.exportPackToZip(packId, destUri)
            }.onSuccess { audioCount ->
                _packBannerMessage.value = "✅ Exported pack with $audioCount audio clip(s)!"
            }.onFailure { err ->
                _packBannerMessage.value = "❌ Export failed: ${err.message?.take(60)}"
            }
        }
    }

    fun batchAutoTagPackWithAi(packId: String) {
        viewModelScope.launch {
            val pack = packs.value.firstOrNull { it.packId == packId } ?: return@launch
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            if (apiKey.isBlank()) {
                _packBannerMessage.value = "Add your Google AI Studio API Key in Settings (⚙️) first."
                return@launch
            }
            _isBatchAutoTagging.value = true
            var taggedCount = 0
            try {
                for (entry in pack.dialogues) {
                    if (entry.safUriString.isBlank()) continue
                    _packBannerMessage.value = "✨ AI listening to ${entry.fileName} (${taggedCount + 1}/${pack.dialogues.size})..."
                    val bytesAndMime = graph.voicePackRepository.readAudioBytesForAi(entry.safUriString, entry.fileName)
                        ?: continue
                    val tagged = graph.geminiBrainClient.autoTagAudioClipWithGemini(
                        apiKey = apiKey,
                        audioBytes = bytesAndMime.first,
                        mimeType = bytesAndMime.second,
                        fileName = entry.fileName
                    ) ?: continue
                    val updated = entry.copy(
                        title = tagged.title.ifBlank { entry.title },
                        heardText = tagged.heardText,
                        category = tagged.category,
                        situations = tagged.situations
                    )
                    graph.voicePackRepository.upsertDialogueInPack(packId, updated)
                    taggedCount++
                }
                _importedPackNeedingAiTagId.value = null
                _packBannerMessage.value = "✅ AI auto-tagged $taggedCount clip(s) in '${pack.packName}'!"
            } finally {
                _isBatchAutoTagging.value = false
            }
        }
    }

    // --- Easy "Add / Edit My Dialogue" Bottom Sheet (Scoped to Active Pack!) ---

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
            scope = entry.scope.ifBlank { "specific" },
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
            val targetPackId = graph.voicePackRepository.getTargetPackIdForEditing()
            val copied = graph.voicePackRepository.copyAudioIntoPack(targetPackId, sourceUri)
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
                val audioPayload = graph.voicePackRepository.readAudioBytesForAi(draft.audioUriString, draft.fileName)
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
                            statusNote = "AI Auto-Listen failed (${graph.geminiBrainClient.lastDebugStatus.value})."
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
            recommendedApps = appPackages,
            scope = draft.scope.ifBlank { "specific" }
        )

        viewModelScope.launch {
            val targetPackId = graph.voicePackRepository.getTargetPackIdForEditing()
            graph.voicePackRepository.upsertDialogueInPack(targetPackId, entry)
            _vaultBannerMessage.value = "✅ Saved '$cleanTitle'!"
            closeDialogueEditor()
        }
    }

    // --- Settings, Live Monitor, Gmail & Simulator ---

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
        viewModelScope.launch { graph.prefs.setSmsAutoReactEnabled(enabled) }
    }

    fun setAlwaysOnKeepAliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.prefs.setBackgroundModeActive(enabled)
            graph.prefs.setAlwaysOnKeepAliveEnabled(enabled)
            AssistantKeepAliveService.syncServiceState(appContext, enabled && graph.prefs.isAssistantEnabledNow)
        }
    }

    fun simulateWhatsAppMessage(sampleText: String) {
        if (!graph.prefs.isAssistantEnabledNow) {
            _whatsappSimStatus.value = "⏸ Assistant is OFF — tap ON on the Home screen first."
            return
        }
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

    fun bindSafFolder(uri: Uri) {
        viewModelScope.launch {
            val existingCount = graph.vaultRepository.bindSafFolder(uri)
            _vaultBannerMessage.value = "SAF Vault linked (.nomedia active, $existingCount dialogues)."
        }
    }

    fun attachAudioToExistingDialogue(dialogueId: String, sourceUri: Uri) {
        viewModelScope.launch {
            val updated = graph.voicePackRepository.attachAudioToExistingDialogue(dialogueId, sourceUri)
            if (updated != null) {
                _vaultBannerMessage.value = "✅ Linked ${updated.fileName} to '${updated.title}'"
                graph.audioPlayer.playEntry(updated)
            } else {
                _vaultBannerMessage.value = "Could not attach selected audio file."
            }
        }
    }

    fun scanAndAutoOrganizeSafFolder() {
        viewModelScope.launch {
            val pendingNames = pendingReviews.value.map { it.fileName }.toSet()
            val unorganized = graph.vaultRepository.findUnorganizedAudioFiles(pendingNames)
            if (unorganized.isEmpty()) {
                _vaultBannerMessage.value = "Tap '+ Add My Dialogue' to pick any MP3 directly!"
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
            graph.voicePackRepository.upsertDialogueInPack(graph.voicePackRepository.getTargetPackIdForEditing(), entry)
            graph.db.pendingReviewDao().deleteById(item.id)
        }
    }

    fun autoAcceptAllPendingReviews() {
        viewModelScope.launch {
            val all = graph.db.pendingReviewDao().getAllPending()
            val targetPackId = graph.voicePackRepository.getTargetPackIdForEditing()
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
                graph.voicePackRepository.upsertDialogueInPack(targetPackId, entry)
            }
            graph.db.pendingReviewDao().clearAll()
            _vaultBannerMessage.value = "Accepted ${all.size} dialogue(s)!"
        }
    }

    fun dismissPendingReview(id: String) {
        viewModelScope.launch { graph.db.pendingReviewDao().deleteById(id) }
    }

    fun deleteDialogue(id: String) {
        viewModelScope.launch { graph.voicePackRepository.deleteDialogueFromActivePack(id) }
    }

    fun playDialogue(entry: DialogueEntry) {
        val ok = graph.audioPlayer.playEntry(entry)
        if (!ok) _vaultBannerMessage.value = "⚠️ Attach an MP3/WAV clip to '${entry.title}' first!"
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
            _simulatorAiNote.value = "Testing via live notification pipeline..."
            _simulatorDiagnostic.value = null
            val activeEntries = graph.voicePackRepository.activeDialogues.value
            val offlineTop3 = graph.embeddingMatcher.rankDialogues(
                rawQuery = testPhrase,
                overrideDialogues = activeEntries,
                topK = 3
            )

            // Execute the EXACT same IncomingMessageReactor + GeminiBrainClient path used by live WhatsApp notifications!
            val outcome = graph.incomingMessageReactor.reactToIncomingDetailed(
                source = "Test Simulator",
                sourceKey = "sim_${System.currentTimeMillis()}",
                sender = "WhatsApp Test",
                messageText = testPhrase,
                appPackageHint = "com.whatsapp",
                bypassCooldownAndGuards = true
            )

            if (outcome != null) {
                val aiEntry = outcome.matchedDialogue
                val merged = if (aiEntry != null) {
                    val aiWinner = MatchCandidate(aiEntry, 0.99f, usedNeuralEmbedding = true)
                    (listOf(aiWinner) + offlineTop3.filterNot { it.entry.id == aiEntry.id }).take(3)
                } else offlineTop3

                _simulatorResults.value = merged
                _simulatorAiNote.value = outcome.actionTaken
                _simulatorDiagnostic.value = SimulatorDiagnosticUi(
                    understoodMeaning = outcome.understoodMeaning.ifBlank { "—" },
                    matchedDialogueName = aiEntry?.title ?: if (outcome.replyMalayalam.isNotBlank()) "TTS Reply: ${outcome.replyMalayalam.take(50)}" else "None",
                    latencyMs = outcome.latencyMs,
                    apiStatus = outcome.apiStatus
                )
            } else {
                _simulatorResults.value = offlineTop3
                _simulatorAiNote.value = "No reaction triggered."
                _simulatorDiagnostic.value = SimulatorDiagnosticUi(
                    understoodMeaning = "No match / skipped",
                    matchedDialogueName = "None",
                    latencyMs = 0L,
                    apiStatus = graph.geminiBrainClient.lastDebugStatus.value
                )
            }
        }
    }

    fun saveGmailConfig(email: String, appPasswordInput: String) {
        viewModelScope.launch {
            graph.prefs.saveGmailCredentials(email, appPasswordInput)
            _gmailTestResult.value = "Saved Gmail credentials securely."
            if (settings.value.gmailWatcherEnabled && graph.prefs.isAssistantEnabledNow) {
                GmailDirectWatcherService.syncServiceState(appContext, true)
            }
        }
    }

    fun setGmailWatcherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.prefs.setGmailWatcherEnabled(enabled)
            GmailDirectWatcherService.syncServiceState(appContext, enabled && graph.prefs.isAssistantEnabledNow)
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
        continuousVoiceController.stopSession()
        super.onCleared()
    }
}
