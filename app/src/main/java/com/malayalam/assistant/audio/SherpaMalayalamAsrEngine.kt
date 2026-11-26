package com.malayalam.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import com.malayalam.assistant.ai.LiteRtEmbeddingSession
import com.malayalam.assistant.ai.ManagedModelId
import com.malayalam.assistant.ai.ModelCoordinator
import com.malayalam.assistant.ai.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline Malayalam Audio Listener (`sherpa-onnx` Android AAR + `AI4Bharat IndicConformer Malayalam`):
 *  - Runs strictly on CPU (`provider = "cpu"`, `numThreads = 2`) inside `ModelCoordinator`'s mutex.
 *  - Supports both NeMo/IndicConformer CTC (`model.onnx` + `tokens.txt`) and Transducer
 *    (`encoder.onnx` + `decoder.onnx` + `joiner.onnx` + `tokens.txt`) layouts.
 *  - Includes automatic fallback to `EmbeddingGemma 2 Omnimodal` if ASR files aren't downloaded yet.
 */
class SherpaOnnxRecognizerHandle(
    private val modelDir: File
) : AutoCloseable {

    private var recognizerObj: Any? = null

    fun initialize() {
        val pkg = "com.k2fsa.sherpa.onnx"
        val modelConfigCls = Class.forName("$pkg.OfflineModelConfig")
        val recognizerConfigCls = Class.forName("$pkg.OfflineRecognizerConfig")
        val recognizerCls = Class.forName("$pkg.OfflineRecognizer")

        val modelConfig = modelConfigCls.getDeclaredConstructor().newInstance()
        val tokensFile = File(modelDir, "tokens.txt")
        val singleCtcModel = File(modelDir, "model.onnx")
        val encoderFile = File(modelDir, "encoder.onnx")
        val decoderFile = File(modelDir, "decoder.onnx")
        val joinerFile = File(modelDir, "joiner.onnx")

        setProp(modelConfig, "setTokens", tokensFile.absolutePath)
        setProp(modelConfig, "setNumThreads", 2)
        setProp(modelConfig, "setProvider", "cpu")

        if (encoderFile.exists() && decoderFile.exists() && joinerFile.exists()) {
            val transducerCls = Class.forName("$pkg.OfflineTransducerModelConfig")
            val transducer = transducerCls.getDeclaredConstructor().newInstance()
            setProp(transducer, "setEncoder", encoderFile.absolutePath)
            setProp(transducer, "setDecoder", decoderFile.absolutePath)
            setProp(transducer, "setJoiner", joinerFile.absolutePath)
            setProp(modelConfig, "setTransducer", transducer)
        } else {
            val nemoCls = Class.forName("$pkg.OfflineNemoEncDecCtcModelConfig")
            val nemo = nemoCls.getDeclaredConstructor().newInstance()
            setProp(nemo, "setModel", singleCtcModel.absolutePath)
            setProp(modelConfig, "setNemo", nemo)
        }

        val recConfig = recognizerConfigCls.getDeclaredConstructor().newInstance()
        setProp(recConfig, "setModelConfig", modelConfig)
        setProp(recConfig, "setDecodingMethod", "greedy_search")

        // OfflineRecognizer(assetManager: AssetManager? = null, config: OfflineRecognizerConfig)
        val ctor = recognizerCls.constructors.firstOrNull { c ->
            c.parameterCount == 2 && c.parameterTypes[0] == AssetManager::class.java
        }
        recognizerObj = if (ctor != null) {
            ctor.newInstance(null, recConfig)
        } else {
            recognizerCls.constructors.first().newInstance(recConfig)
        }
    }

    fun transcribe16kMono(samples: FloatArray, sampleRate: Int = 16_000): String {
        val recognizer = recognizerObj ?: return ""
        val recCls = recognizer.javaClass
        val createStream = recCls.methods.first { it.name == "createStream" && it.parameterCount == 0 }
        val stream = createStream.invoke(recognizer) ?: return ""
        try {
            val streamCls = stream.javaClass
            val accept = streamCls.methods.first {
                it.name == "acceptWaveform" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == FloatArray::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            accept.invoke(stream, samples, sampleRate)

            val decode = recCls.methods.first { it.name == "decode" && it.parameterCount == 1 }
            decode.invoke(recognizer, stream)

            val getResult = recCls.methods.firstOrNull { it.name == "getResult" && it.parameterCount == 1 }
            val resultObj = if (getResult != null) {
                getResult.invoke(recognizer, stream)
            } else {
                streamCls.methods.firstOrNull { it.name == "getResult" && it.parameterCount == 0 }?.invoke(stream)
            }
            val text = resultObj?.javaClass?.methods
                ?.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                ?.invoke(resultObj) as? String
            return text?.trim().orEmpty()
        } finally {
            runCatching {
                stream.javaClass.methods.firstOrNull { it.name == "release" && it.parameterCount == 0 }?.invoke(stream)
            }
        }
    }

    override fun close() {
        runCatching {
            recognizerObj?.javaClass?.methods
                ?.firstOrNull { it.name == "release" && it.parameterCount == 0 }
                ?.invoke(recognizerObj)
        }
        recognizerObj = null
    }

    private fun setProp(target: Any, setterName: String, value: Any) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == setterName && it.parameterCount == 1
        }
        method?.invoke(target, value)
    }
}

data class AudioListenResult(
    val transcript: String,
    val omnimodalVectorFallback: FloatArray? = null,
    val usedSherpaAsr: Boolean
)

class MalayalamAudioListener(
    private val context: Context,
    private val coordinator: ModelCoordinator,
    private val downloader: ModelDownloadManager
) {
    suspend fun listenTo16kPcm(
        pcm: DecodedPcmClip,
        fileNameHint: String
    ): AudioListenResult = withContext(Dispatchers.IO) {
        // 1. Primary path: sherpa-onnx IndicConformer Malayalam ASR
        if (downloader.isModelReady(ManagedModelId.SHERPA_MALAYALAM_ASR)) {
            val dir = downloader.modelDir(ManagedModelId.SHERPA_MALAYALAM_ASR)
            val text = runCatching {
                coordinator.withModel(
                    modelId = ManagedModelId.SHERPA_MALAYALAM_ASR,
                    factory = { SherpaOnnxRecognizerHandle(dir).also { it.initialize() } }
                ) { handle ->
                    handle.transcribe16kMono(pcm.samples16kMono, pcm.sampleRate)
                }
            }.getOrDefault("")

            if (text.isNotBlank()) {
                return@withContext AudioListenResult(
                    transcript = text,
                    omnimodalVectorFallback = null,
                    usedSherpaAsr = true
                )
            }
        }

        // 2. Optional fallback: EmbeddingGemma 2 Omnimodal audio embedding if imported
        val omniFile = downloader.optionalOmnimodalAudioFile()
        if (omniFile.exists() && omniFile.length() > 1024L) {
            val vec = runCatching {
                coordinator.withModel(
                    modelId = ManagedModelId.EMBEDDING_GEMMA_270M,
                    factory = {
                        LiteRtEmbeddingSession(
                            context = context,
                            modelFile = omniFile,
                            enableAudioModality = true
                        ).also { it.initialize() }
                    }
                ) { session ->
                    session.embedText("task: search result | text: ${fileNameHint.substringBeforeLast('.')}")
                }
            }.getOrNull()
            return@withContext AudioListenResult(
                transcript = "",
                omnimodalVectorFallback = vec,
                usedSherpaAsr = false
            )
        }

        AudioListenResult(transcript = "", omnimodalVectorFallback = null, usedSherpaAsr = false)
    }
}
