package com.malayalam.assistant.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.audio.NativeAudioDecoder
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.PendingReviewEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class PipelineProgressUi(
    val isRunning: Boolean = false,
    val currentFileName: String = "",
    val stageLabel: String = "",
    val stepIndex: Int = 0, // 1 = Listening, 2 = Understanding Situation, 3 = Indexing
    val queuedRemaining: Int = 0
)

class PipelineStatusTracker {
    private val _state = MutableStateFlow(PipelineProgressUi())
    val state: StateFlow<PipelineProgressUi> = _state.asStateFlow()

    fun update(
        isRunning: Boolean,
        fileName: String = "",
        stageLabel: String = "",
        stepIndex: Int = 0,
        queuedRemaining: Int = 0
    ) {
        _state.value = PipelineProgressUi(
            isRunning = isRunning,
            currentFileName = fileName,
            stageLabel = stageLabel,
            stepIndex = stepIndex,
            queuedRemaining = queuedRemaining
        )
    }
}

/**
 * Sequential Low-RAM "AI Hears & Places Dialogue" Worker (`ExistingWorkPolicy.APPEND_OR_REPLACE`):
 *  1. Decode first 15s of audio clip to 16 kHz mono PCM via `MediaExtractor` + `MediaCodec` (trim silence).
 *  2. Run `sherpa-onnx` IndicConformer Malayalam ASR -> immediately unload ASR.
 *  3. Load `Gemma 3 1B` -> classify into JSON `{ title, heardText, category, situations, recommendedApps }` -> immediately unload Gemma 3 1B.
 *  4. Load `EmbeddingGemma 2 Text (270M)` -> compute situation vector -> unload -> store Review Card (or Auto-Accept).
 */
class AudioAutoOrganizeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val uriString = inputData.getString(KEY_SAF_URI) ?: return Result.failure()
        val forceAutoAccept = inputData.getBoolean(KEY_FORCE_AUTO_ACCEPT, false)

        val graph = AppGraph.from(applicationContext)
        val tracker = graph.pipelineTracker
        val coordinator = graph.modelCoordinator

        try {
            // STEP 1 + 2: Decode 16 kHz mono PCM & Run IndicConformer Malayalam ASR
            val step1Msg = "Listening... ($fileName)"
            tracker.update(isRunning = true, fileName = fileName, stageLabel = step1Msg, stepIndex = 1)
            setProgress(workDataOf(PROGRESS_STAGE to step1Msg, PROGRESS_STEP to 1))

            val pcm = NativeAudioDecoder.decodeFirst15sMono16k(applicationContext, Uri.parse(uriString))
            val listenResult = if (pcm != null && pcm.samples16kMono.isNotEmpty()) {
                graph.malayalamAudioListener.listenTo16kPcm(pcm, fileName)
            } else null

            // Strictly unload ASR before loading Gemma 3 1B
            coordinator.unloadAll("pipeline_after_asr")

            // STEP 3: Load Gemma 3 1B for structured JSON situation classification
            val step2Msg = "Understanding Situation... ($fileName)"
            tracker.update(isRunning = true, fileName = fileName, stageLabel = step2Msg, stepIndex = 2)
            setProgress(workDataOf(PROGRESS_STAGE to step2Msg, PROGRESS_STEP to 2))

            val heardTranscript = listenResult?.transcript.orEmpty()
            val classified = graph.gemmaSituationEngine.classifyHeardDialogue(
                heardMalayalam = heardTranscript,
                fileName = fileName
            )

            // Strictly unload Gemma 3 1B before loading EmbeddingGemma 2 (270M)
            coordinator.unloadAll("pipeline_after_gemma_1b")

            // STEP 4: Compute & cache EmbeddingGemma 2 Text (270M) vector
            val step3Msg = "Indexing... ($fileName)"
            tracker.update(isRunning = true, fileName = fileName, stageLabel = step3Msg, stepIndex = 3)
            setProgress(workDataOf(PROGRESS_STAGE to step3Msg, PROGRESS_STEP to 3))

            val entryId = UUID.randomUUID().toString()
            val candidateEntry = DialogueEntry(
                id = entryId,
                fileName = fileName,
                safUriString = uriString,
                title = classified.title,
                heardText = classified.heardText,
                category = classified.category,
                situations = classified.situations,
                recommendedApps = classified.recommendedApps,
                durationMs = pcm?.originalDurationMs ?: 0L
            )

            val vector = graph.embeddingMatcher.computeDocumentEmbeddingIfInstalled(candidateEntry)
                ?: listenResult?.omnimodalVectorFallback
            coordinator.unloadAll("pipeline_after_embedding")

            val autoAccept = forceAutoAccept || graph.prefs.snapshot().autoAcceptBulkImports
            if (autoAccept) {
                graph.vaultRepository.upsertDialogue(candidateEntry, vector)
            } else {
                val vecJson = if (vector != null) Json.encodeToString(vector.toList()) else ""
                graph.db.pendingReviewDao().upsert(
                    PendingReviewEntity(
                        id = entryId,
                        fileName = fileName,
                        safUriString = uriString,
                        title = candidateEntry.title,
                        heardText = candidateEntry.heardText,
                        category = candidateEntry.category,
                        situationsCsv = candidateEntry.situations.joinToString(", "),
                        recommendedAppsCsv = candidateEntry.recommendedApps.joinToString(","),
                        cachedVectorJson = vecJson
                    )
                )
            }

            tracker.update(isRunning = false, fileName = fileName, stageLabel = "Completed $fileName", stepIndex = 0)
            return Result.success()
        } catch (e: Exception) {
            coordinator.unloadAll("pipeline_exception")
            tracker.update(isRunning = false, stageLabel = "Error on $fileName: ${e.message?.take(40)}", stepIndex = 0)
            return Result.failure()
        }
    }

    companion object {
        const val UNIQUE_QUEUE_NAME = "malayalam_audio_auto_organize_queue"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_SAF_URI = "saf_uri"
        const val KEY_FORCE_AUTO_ACCEPT = "force_auto_accept"
        const val PROGRESS_STAGE = "progress_stage"
        const val PROGRESS_STEP = "progress_step"

        fun enqueueSequentialClips(
            context: Context,
            clips: List<Pair<String, String>>,
            forceAutoAccept: Boolean = false
        ) {
            if (clips.isEmpty()) return
            val wm = WorkManager.getInstance(context)
            clips.forEachIndexed { idx, (fileName, uriStr) ->
                val req = OneTimeWorkRequestBuilder<AudioAutoOrganizeWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_FILE_NAME to fileName,
                            KEY_SAF_URI to uriStr,
                            KEY_FORCE_AUTO_ACCEPT to forceAutoAccept
                        )
                    )
                    .addTag(UNIQUE_QUEUE_NAME)
                    .build()

                val policy = if (idx == 0) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.APPEND
                wm.enqueueUniqueWork(UNIQUE_QUEUE_NAME, policy, req)
            }
        }
    }
}
