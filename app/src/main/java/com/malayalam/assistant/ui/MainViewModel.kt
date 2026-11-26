package com.malayalam.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.PendingReviewEntity
import com.malayalam.assistant.data.UserSettings
import com.malayalam.assistant.worker.AudioAutoOrganizeWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    val ramStatus = graph.modelCoordinator.ramStatus
    val playbackState = graph.audioPlayer.playbackState
    val dialogues = graph.vaultRepository.dialogues
    val pipelineProgress = graph.pipelineTracker.state
    val modelStates = graph.modelDownloadManager.states

    val settings = graph.prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserSettings(lowRamSafeMode = graph.prefs.defaultLowRamMode)
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

    private val _chatHistory = MutableStateFlow(
        listOf(
            ChatTurn(
                isUser = false,
                text = "Namaskaram! I'm your 4 GB-safe offline Malayalam AI assistant. Ask me to set an alarm, add a to-do, call someone, open an app, or drop Malayalam audio clips in Dialogue Vault so I can react to notifications!",
                badge = "One-Model-at-a-Time Ready"
            )
        )
    )
    val chatHistory: StateFlow<List<ChatTurn>> = _chatHistory.asStateFlow()

    private val _isChatBusy = MutableStateFlow(false)
    val isChatBusy: StateFlow<Boolean> = _isChatBusy.asStateFlow()

    private val _simulatorResults = MutableStateFlow<List<MatchCandidate>>(emptyList())
    val simulatorResults: StateFlow<List<MatchCandidate>> = _simulatorResults.asStateFlow()

    private val _vaultBannerMessage = MutableStateFlow("")
    val vaultBannerMessage: StateFlow<String> = _vaultBannerMessage.asStateFlow()

    fun sendChatOrAction(rawInput: String) {
        val text = rawInput.trim()
        if (text.isBlank() || _isChatBusy.value) return

        _chatHistory.value = _chatHistory.value + ChatTurn(isUser = true, text = text)
        _isChatBusy.value = true

        viewModelScope.launch {
            try {
                // 1. Check daily actions via FunctionGemma (270M)
                val action = graph.functionRouter.routeAndExecute(text)
                if (action.handledAsAction) {
                    val reaction = graph.embeddingMatcher.rankDialogues(
                        rawQuery = "${action.reactionQuery} $text",
                        topK = 1
                    ).firstOrNull()?.takeIf { it.score >= 0.45f }

                    reaction?.let { graph.audioPlayer.playEntry(it.entry) }

                    _chatHistory.value = _chatHistory.value + ChatTurn(
                        isUser = false,
                        text = action.summaryText,
                        badge = if (action.usedFunctionGemma) "FunctionGemma 270M" else "Instant Offline Router",
                        triggeredClipTitle = reaction?.entry?.title
                    )
                    return@launch
                }

                // 2. Match Malayalam reaction clip + run Gemma 3 1B light reply
                val threshold = settings.value.matchThreshold
                val matched = graph.embeddingMatcher.rankDialogues(text, topK = 1)
                    .firstOrNull()
                    ?.takeIf { it.score >= threshold }

                matched?.let { graph.audioPlayer.playEntry(it.entry) }

                val reply = graph.gemmaSituationEngine.generateAssistantReply(userMessage = text)
                _chatHistory.value = _chatHistory.value + ChatTurn(
                    isUser = false,
                    text = reply,
                    badge = if (graph.modelDownloadManager.isModelReady(ManagedModelId.GEMMA_3_1B)) "Gemma 3 1B (CPU XNNPACK)" else "Lightweight Fallback",
                    triggeredClipTitle = matched?.entry?.title
                )
            } finally {
                _isChatBusy.value = false
            }
        }
    }

    fun unloadRamNow() {
        viewModelScope.launch {
            graph.modelCoordinator.unloadAll("manual_user_tap")
        }
    }

    fun toggleSpeechLocale() {
        viewModelScope.launch {
            val next = if (settings.value.speechLocale == "ml-IN") "en-IN" else "ml-IN"
            graph.prefs.setSpeechLocale(next)
        }
    }

    fun toggleTodoDone(id: Long, done: Boolean) {
        viewModelScope.launch { graph.db.todoDao().setCompleted(id, done) }
    }

    fun deleteTodo(id: Long) {
        viewModelScope.launch { graph.db.todoDao().deleteTodo(id) }
    }

    // --- Dialogue Vault & SAF Pipeline ---

    fun bindSafFolder(uri: Uri) {
        viewModelScope.launch {
            val existingCount = graph.vaultRepository.bindSafFolder(uri)
            _vaultBannerMessage.value = "SAF Vault linked (.nomedia active, $existingCount dialogues loaded)."
        }
    }

    fun addSingleClipFromPicker(sourceUri: Uri) {
        viewModelScope.launch {
            val copied = graph.vaultRepository.copyExternalClipIntoVault(sourceUri)
            if (copied == null) {
                _vaultBannerMessage.value = "Could not copy audio clip."
                return@launch
            }
            _vaultBannerMessage.value = "Queued ${copied.first} for Malayalam AI listening..."
            AudioAutoOrganizeWorker.enqueueSequentialClips(appContext, listOf(copied))
        }
    }

    fun scanAndAutoOrganizeSafFolder() {
        viewModelScope.launch {
            val pendingNames = pendingReviews.value.map { it.fileName }.toSet()
            val unorganized = graph.vaultRepository.findUnorganizedAudioFiles(pendingNames)
            if (unorganized.isEmpty()) {
                _vaultBannerMessage.value = if (settings.value.safTreeUri.isBlank()) {
                    "Pick your Documents/MalayalamAssistant/ folder first."
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
            // Re-embed if situations changed & EmbeddingGemma is downloaded, else use cached vector
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
            _vaultBannerMessage.value = "Accepted ${all.size} dialogue(s) into dialogues.json!"
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
        graph.audioPlayer.playEntry(entry)
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
            val winner = top3.firstOrNull()
            if (winner != null) {
                graph.audioPlayer.playEntry(winner.entry)
            }
        }
    }

    // --- Models & Settings ---

    fun downloadModel(model: ManagedModelId) {
        viewModelScope.launch {
            graph.modelDownloadManager.downloadModel(model, settings.value.hfAuthToken)
        }
    }

    fun importLocalModelFile(model: ManagedModelId, uri: Uri) {
        viewModelScope.launch {
            graph.modelDownloadManager.importLocalFile(model, uri)
        }
    }

    fun deleteModel(model: ManagedModelId) {
        viewModelScope.launch {
            graph.modelDownloadManager.deleteModel(model, graph.modelCoordinator)
        }
    }

    fun setLowRamSafeMode(enabled: Boolean) {
        viewModelScope.launch { graph.prefs.setLowRamSafeMode(enabled) }
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
}
