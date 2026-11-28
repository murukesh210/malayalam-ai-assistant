package com.malayalam.assistant

import android.app.Application
import android.content.Context
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.FunctionGemmaRouter
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.ai.GeminiTtsClient
import com.malayalam.assistant.ai.GemmaSituationEngine
import com.malayalam.assistant.ai.ModelCoordinator
import com.malayalam.assistant.ai.ModelDownloadManager
import com.malayalam.assistant.audio.DialogueAudioPlayer
import com.malayalam.assistant.audio.MalayalamAudioListener
import com.malayalam.assistant.data.AppDatabase
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.SafVaultRepository
import com.malayalam.assistant.data.VoicePackRepository
import com.malayalam.assistant.service.AssistantKeepAliveService
import com.malayalam.assistant.service.AudioOutputGuard
import com.malayalam.assistant.service.GmailDirectWatcherService
import com.malayalam.assistant.service.IncomingMessageReactor
import com.malayalam.assistant.worker.PipelineStatusTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val prefs = AppPreferences(appContext)
    val db = AppDatabase.get(appContext)
    val vaultRepository = SafVaultRepository(appContext, prefs)
    val voicePackRepository = VoicePackRepository(appContext, prefs, vaultRepository)

    val modelCoordinator = ModelCoordinator(appContext)
    val modelDownloadManager = ModelDownloadManager(appContext)

    val embeddingMatcher = EmbeddingMatcherEngine(
        context = appContext,
        coordinator = modelCoordinator,
        downloader = modelDownloadManager,
        vaultRepository = vaultRepository
    )

    val functionRouter = FunctionGemmaRouter(
        context = appContext,
        coordinator = modelCoordinator,
        downloader = modelDownloadManager,
        todoDao = db.todoDao(),
        prefs = prefs
    )

    val gemmaSituationEngine = GemmaSituationEngine(
        context = appContext,
        coordinator = modelCoordinator,
        downloader = modelDownloadManager,
        prefs = prefs
    )

    val malayalamAudioListener = MalayalamAudioListener(
        context = appContext,
        coordinator = modelCoordinator,
        downloader = modelDownloadManager
    )

    val audioPlayer = DialogueAudioPlayer(appContext)
    val geminiBrainClient = GeminiBrainClient()
    val geminiTtsClient = GeminiTtsClient()
    val incomingMessageReactor = IncomingMessageReactor(appContext)
    val pipelineTracker = PipelineStatusTracker()

    companion object {
        fun from(context: Context): AppGraph {
            return (context.applicationContext as AssistantApplication).graph
        }
    }
}

class AssistantApplication : Application() {

    lateinit var graph: AppGraph
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        appScope.launch {
            graph.vaultRepository.initializeFromDisk()
            graph.voicePackRepository.initialize()
            val settings = graph.prefs.snapshot()
            if (settings.isAssistantEnabled) {
                if (settings.backgroundModeActive) {
                    AssistantKeepAliveService.syncServiceState(this@AssistantApplication, true)
                }
                if (settings.gmailWatcherEnabled && settings.gmailAddress.isNotBlank()) {
                    GmailDirectWatcherService.syncServiceState(this@AssistantApplication, true)
                }
            } else {
                AssistantKeepAliveService.syncServiceState(this@AssistantApplication, false)
                GmailDirectWatcherService.syncServiceState(this@AssistantApplication, false)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::graph.isInitialized) {
            graph.modelCoordinator.handleTrimMemory(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::graph.isInitialized) {
            graph.modelCoordinator.unloadAllAsync("system_onLowMemory")
        }
    }
}
