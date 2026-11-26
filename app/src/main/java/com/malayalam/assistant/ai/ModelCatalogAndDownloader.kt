package com.malayalam.assistant.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class ManagedModelId(
    val key: String,
    val title: String,
    val subtitle: String,
    val approxDiskMb: Int,
    val estRamMb: Int,
    val primaryFileName: String,
    val secondaryFileName: String? = null,
    val defaultDownloadUrl: String,
    val secondaryDownloadUrl: String? = null,
    val isOptional8GbOnly: Boolean = false
) {
    EMBEDDING_GEMMA_270M(
        key = "embedding_gemma_270m",
        title = "EmbeddingGemma 2 Text (270M)",
        subtitle = "Background notification & dialogue matcher (~165 MB .litertlm, ~191 MB RAM)",
        approxDiskMb = 165,
        estRamMb = 191,
        primaryFileName = "embeddinggemma-2-text-270m.litertlm",
        defaultDownloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm?download=true"
    ),
    FUNCTION_GEMMA_270M(
        key = "function_gemma_270m",
        title = "FunctionGemma (270M Mobile Actions)",
        subtitle = "Sub-second offline router for Alarms, Calendar, Calls, Apps & To-Dos (~289 MB, ~510 MB RAM)",
        approxDiskMb = 289,
        estRamMb = 510,
        primaryFileName = "functiongemma-270m-ft-mobile-actions.litertlm",
        defaultDownloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/functiongemma-270m-ft-mobile-actions.litertlm?download=true"
    ),
    SHERPA_MALAYALAM_ASR(
        key = "sherpa_indicconformer_ml",
        title = "IndicConformer Malayalam ASR (sherpa-onnx)",
        subtitle = "Offline 16 kHz Malayalam speech listener (model.onnx + tokens.txt, CPU-only)",
        approxDiskMb = 128,
        estRamMb = 240,
        primaryFileName = "model.onnx",
        secondaryFileName = "tokens.txt",
        defaultDownloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-malayalam/resolve/main/model.int8.onnx?download=true",
        secondaryDownloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-malayalam/resolve/main/tokens.txt?download=true"
    ),
    GEMMA_3_1B(
        key = "gemma_3_1b_int4",
        title = "Gemma 3 1B IT (dynamic_int4 QAT)",
        subtitle = "4 GB-safe Situation Classifier & Light Chat (~529 MB .litertlm, ~1.15 GB peak RAM)",
        approxDiskMb = 529,
        estRamMb = 1150,
        primaryFileName = "gemma3-1b-it-int4.litertlm",
        defaultDownloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv1280.litertlm?download=true"
    ),
    GEMMA_4_E2B_OPTIONAL(
        key = "gemma_4_e2b_optional",
        title = "Gemma 4 E2B IT (8 GB+ Optional Only)",
        subtitle = "Optional larger model for 8 GB+ phones ONLY — disabled by default to protect 4 GB RAM",
        approxDiskMb = 1480,
        estRamMb = 2350,
        primaryFileName = "gemma4-e2b-it-int4.litertlm",
        defaultDownloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-int4.litertlm?download=true",
        isOptional8GbOnly = true
    );
}

data class ModelInstallState(
    val model: ManagedModelId,
    val isInstalled: Boolean,
    val diskBytes: Long = 0L,
    val isDownloading: Boolean = false,
    val progressFraction: Float = 0f,
    val statusMessage: String = ""
)

class ModelDownloadManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _states = MutableStateFlow<Map<ManagedModelId, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<ManagedModelId, ModelInstallState>> = _states.asStateFlow()

    init {
        refreshStates()
    }

    fun modelDir(model: ManagedModelId): File {
        val base = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(base, model.key).apply { mkdirs() }
    }

    fun primaryModelFile(model: ManagedModelId): File = File(modelDir(model), model.primaryFileName)

    fun secondaryModelFile(model: ManagedModelId): File? =
        model.secondaryFileName?.let { File(modelDir(model), it) }

    fun optionalOmnimodalAudioFile(): File =
        File(modelDir(ManagedModelId.EMBEDDING_GEMMA_270M), "embeddinggemma-2-omnimodal.litertlm")

    fun isModelReady(model: ManagedModelId): Boolean {
        val primary = primaryModelFile(model)
        if (!primary.exists() || primary.length() < 1024L) return false
        val secondary = secondaryModelFile(model)
        if (secondary != null && (!secondary.exists() || secondary.length() < 16L)) return false
        return true
    }

    fun refreshStates() {
        val updated = ManagedModelId.entries.associateWith { model ->
            val dir = modelDir(model)
            val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
            val current = _states.value[model]
            ModelInstallState(
                model = model,
                isInstalled = isModelReady(model),
                diskBytes = bytes,
                isDownloading = current?.isDownloading ?: false,
                progressFraction = current?.progressFraction ?: 0f,
                statusMessage = current?.statusMessage ?: ""
            )
        }
        _states.value = updated
    }

    suspend fun downloadModel(model: ManagedModelId, hfToken: String = "") = withContext(Dispatchers.IO) {
        updateModelState(model) {
            it.copy(isDownloading = true, progressFraction = 0.01f, statusMessage = "Connecting...")
        }
        try {
            downloadSingleFile(
                url = model.defaultDownloadUrl,
                dest = primaryModelFile(model),
                hfToken = hfToken
            ) { pct ->
                updateModelState(model) {
                    it.copy(
                        isDownloading = true,
                        progressFraction = pct,
                        statusMessage = "Downloading ${(pct * 100).toInt()}%"
                    )
                }
            }

            if (model.secondaryDownloadUrl != null && model.secondaryFileName != null) {
                updateModelState(model) { it.copy(statusMessage = "Downloading tokens.txt...") }
                downloadSingleFile(
                    url = model.secondaryDownloadUrl,
                    dest = File(modelDir(model), model.secondaryFileName),
                    hfToken = hfToken
                ) { _ -> }
            }

            refreshStates()
            updateModelState(model) {
                it.copy(isDownloading = false, progressFraction = 1f, statusMessage = "Downloaded")
            }
        } catch (e: Exception) {
            refreshStates()
            updateModelState(model) {
                it.copy(
                    isDownloading = false,
                    progressFraction = 0f,
                    statusMessage = "Download failed (${e.message?.take(60) ?: "check URL / HF token or use Import Local File"})"
                )
            }
        }
    }

    /**
     * Imports a local `.litertlm`, `.onnx`, or `tokens.txt` file chosen via SAF document picker.
     */
    suspend fun importLocalFile(model: ManagedModelId, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val pickedName = queryFileName(sourceUri)?.lowercase() ?: ""
        val targetFile = when {
            model == ManagedModelId.SHERPA_MALAYALAM_ASR && pickedName.endsWith(".txt") ->
                File(modelDir(model), "tokens.txt")
            model == ManagedModelId.SHERPA_MALAYALAM_ASR && pickedName.contains("encoder") ->
                File(modelDir(model), "encoder.onnx")
            model == ManagedModelId.SHERPA_MALAYALAM_ASR && pickedName.contains("decoder") ->
                File(modelDir(model), "decoder.onnx")
            model == ManagedModelId.SHERPA_MALAYALAM_ASR && pickedName.contains("joiner") ->
                File(modelDir(model), "joiner.onnx")
            model == ManagedModelId.EMBEDDING_GEMMA_270M && pickedName.contains("omni") ->
                optionalOmnimodalAudioFile()
            else -> primaryModelFile(model)
        }

        updateModelState(model) { it.copy(isDownloading = true, statusMessage = "Importing ${targetFile.name}...") }
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        } ?: error("Cannot read selected file")

        if (targetFile.exists()) targetFile.delete()
        tempFile.renameTo(targetFile)

        refreshStates()
        val ready = isModelReady(model)
        val note = if (ready) {
            "Imported ${targetFile.name} Ready"
        } else {
            "Imported ${targetFile.name} (now import tokens.txt to complete ASR)"
        }
        updateModelState(model) { it.copy(isDownloading = false, statusMessage = note) }
        note
    }

    suspend fun deleteModel(model: ManagedModelId, coordinator: ModelCoordinator) = withContext(Dispatchers.IO) {
        if (coordinator.ramStatus.value.activeModel == model) {
            coordinator.unloadAll("model_deleted")
        }
        modelDir(model).deleteRecursively()
        refreshStates()
        updateModelState(model) { it.copy(statusMessage = "Deleted") }
    }

    private fun downloadSingleFile(
        url: String,
        dest: File,
        hfToken: String,
        onProgress: (Float) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        val partFile = File(dest.parentFile, "${dest.name}.part")
        val reqBuilder = Request.Builder().url(url)
        if (hfToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${if (resp.code == 401 || resp.code == 403) "Add HF Token or Import Local File" else resp.message}")
            }
            val body = resp.body ?: error("Empty response body")
            val total = body.contentLength()
            var copied = 0L
            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0L) {
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 0.99f))
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        partFile.renameTo(dest)
    }

    private fun queryFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun updateModelState(
        model: ManagedModelId,
        transform: (ModelInstallState) -> ModelInstallState
    ) {
        val currentMap = _states.value.toMutableMap()
        val existing = currentMap[model] ?: ModelInstallState(model = model, isInstalled = isModelReady(model))
        currentMap[model] = transform(existing)
        _states.value = currentMap
    }
}
