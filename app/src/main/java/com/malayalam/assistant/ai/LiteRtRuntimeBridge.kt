package com.malayalam.assistant.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Low-RAM LiteRT-LM CPU XNNPACK Session Handle:
 *  - Default CPU XNNPACK backend with 4 threads (prevents GPU driver OOM on 4 GB chipsets)
 *  - Context length strictly capped to `1024` tokens (`maxNumTokens = 1024`)
 *  - Max output tokens strictly capped to `160` tokens
 *  - Vision & audio backends disabled (`null`) unless Omnimodal audio fallback is explicitly requested
 */
class LiteRtLlmSession(
    private val context: Context,
    private val modelFile: File,
    val cpuThreads: Int = DEFAULT_CPU_THREADS,
    val maxContextTokens: Int = MAX_CONTEXT_TOKENS
) : AutoCloseable {

    private var engine: Engine? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = createLowRamCpuBackend(cpuThreads),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = maxContextTokens,
            cacheDir = context.cacheDir.absolutePath
        )
        val created = Engine(config)
        created.initialize()
        engine = created
    }

    suspend fun generateText(
        systemInstruction: String,
        userPrompt: String,
        temperature: Double = 0.2,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS
    ): String = withContext(Dispatchers.IO) {
        val activeEngine = engine ?: error("LiteRT-LM Engine not initialized")
        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            samplerConfig = SamplerConfig(
                topK = 24,
                topP = 0.9,
                temperature = temperature
            )
        )
        activeEngine.createConversation(convConfig).use { conversation ->
            val sb = StringBuilder()
            var chunkCount = 0
            conversation.sendMessageAsync(userPrompt).collect { chunk ->
                if (chunkCount < maxOutputTokens && sb.length < maxOutputTokens * 5) {
                    sb.append(chunk.toString())
                    chunkCount++
                }
            }
            sb.toString().trim()
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        const val DEFAULT_CPU_THREADS = 4
        const val MAX_CONTEXT_TOKENS = 1024
        const val MAX_OUTPUT_TOKENS = 160

        fun createLowRamCpuBackend(threads: Int = DEFAULT_CPU_THREADS): Backend {
            return runCatching {
                val cpuCls = Backend.CPU::class.java
                val singleIntCtor = cpuCls.constructors.firstOrNull {
                    it.parameterCount == 1 &&
                        (it.parameterTypes[0] == Integer::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType)
                }
                if (singleIntCtor != null) {
                    singleIntCtor.newInstance(threads) as Backend
                } else {
                    Backend.CPU()
                }
            }.getOrElse { Backend.CPU() }
        }
    }
}

/**
 * Low-RAM LiteRT-LM EmbeddingEngine Handle for `EmbeddingGemma 2 Text (270M)`
 * (and optional `EmbeddingGemma 2 Omnimodal` audio fallback).
 * Uses dynamic invocation over `com.google.ai.edge.litertlm.EmbeddingEngine` so it binds cleanly
 * across any `0.18+` / `latest.release` version of `litertlm-android`.
 */
class LiteRtEmbeddingSession(
    private val context: Context,
    private val modelFile: File,
    private val enableAudioModality: Boolean = false
) : AutoCloseable {

    private var embeddingEngineObj: Any? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = LiteRtLlmSession.createLowRamCpuBackend(LiteRtLlmSession.DEFAULT_CPU_THREADS),
            visionBackend = null,
            audioBackend = if (enableAudioModality) LiteRtLlmSession.createLowRamCpuBackend(2) else null,
            maxNumTokens = 512,
            cacheDir = context.cacheDir.absolutePath
        )

        val cls = Class.forName("com.google.ai.edge.litertlm.EmbeddingEngine")
        val ctor = cls.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == EngineConfig::class.java }
            ?: cls.constructors.first()
        val instance = ctor.newInstance(engineConfig)
        cls.methods.firstOrNull { it.name == "initialize" && it.parameterCount == 0 }?.invoke(instance)
        embeddingEngineObj = instance
    }

    suspend fun embedText(formattedText: String): FloatArray? = withContext(Dispatchers.IO) {
        val instance = embeddingEngineObj ?: return@withContext null
        runCatching {
            val cls = instance.javaClass
            val computeMethod = cls.methods.firstOrNull { m ->
                (m.name == "computeEmbedding" || m.name == "embed") && m.parameterCount >= 1
            } ?: return@runCatching null

            val firstParamType = computeMethod.parameterTypes[0]
            val firstArg: Any = when {
                firstParamType == String::class.java -> formattedText
                firstParamType == Contents::class.java -> Contents.of(formattedText)
                else -> formattedText
            }
            val args = Array<Any?>(computeMethod.parameterCount) { idx ->
                if (idx == 0) firstArg else null
            }
            val result = computeMethod.invoke(instance, *args) ?: return@runCatching null
            extractFloatVector(result)
        }.getOrNull()
    }

    private fun extractFloatVector(result: Any): FloatArray? {
        if (result is FloatArray) return normalizeL2(result)
        val vecField = result.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.returnType == FloatArray::class.java
        }
        val raw = vecField?.invoke(result) as? FloatArray ?: return null
        return normalizeL2(raw)
    }

    override fun close() {
        runCatching {
            (embeddingEngineObj as? AutoCloseable)?.close()
                ?: embeddingEngineObj?.javaClass?.methods?.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                    ?.invoke(embeddingEngineObj)
        }
        embeddingEngineObj = null
    }

    companion object {
        fun normalizeL2(vec: FloatArray): FloatArray {
            var sumSq = 0.0
            for (v in vec) sumSq += (v * v).toDouble()
            val norm = kotlin.math.sqrt(sumSq).toFloat()
            if (norm <= 1e-8f) return vec
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }
    }
}
