package com.malayalam.assistant.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-Latency Streaming Google AI Studio TTS Client (`v1beta`):
 *  - Primary: **SSE Streaming** (`gemini-3.8-flash-lite-tts:streamGenerateContent?alt=sse`) over
 *    `SharedGeminiHttp.client` keep-alive HTTP/2 pool — yields raw 24 kHz 16-bit mono PCM chunks
 *    immediately as SSE frames arrive so the first word starts speaking within ~1 second!
 *  - Clean non-streaming fallback (`gemini-3.8-flash-lite-tts:generateContent` -> `gemini-2.5-flash-preview-tts`)
 *    if SSE streaming fails.
 */
class GeminiTtsClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cancelRequested = AtomicBoolean(false)

    fun cancelStreaming() {
        cancelRequested.set(true)
        SharedGeminiHttp.cancelAllInFlightCalls()
    }

    /**
     * Streams decoded 24 kHz 16-bit signed little-endian mono PCM chunks via [onPcmChunk].
     * Returns `true` if at least one audio chunk was streamed, `false` to let caller fall back.
     */
    suspend fun streamPcm24kMono(
        apiKey: String,
        text: String,
        voiceName: String = "Kore",
        ttsStyle: String = "cheerful and friendly",
        onPcmChunk: (ByteArray) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanText = text.replace(Regex("[🎬🔊✅⚠️✦]"), "").trim()
        if (cleanKey.isBlank() || cleanText.isBlank()) return@withContext false
        cancelRequested.set(false)

        val safeVoice = voiceName.ifBlank { "Kore" }
        val primaryPayload = buildTtsPayload(
            text = cleanText,
            voiceName = safeVoice,
            ttsStyle = ttsStyle,
            useMetadataStyle = true
        )

        // 1. Primary: SSE Streaming (`streamGenerateContent?alt=sse`) for sub-second time-to-first-audio!
        val streamedPrimary = runSseAudioStream(
            model = "gemini-3.8-flash-lite-tts",
            apiKey = cleanKey,
            payload = primaryPayload,
            onPcmChunk = onPcmChunk
        )
        if (streamedPrimary) return@withContext true

        // 2. Fallback SSE stream with 2.5 preview TTS (inline style prompt)
        val fallbackPayload = buildTtsPayload(
            text = "Speak in a $ttsStyle tone: $cleanText",
            voiceName = safeVoice,
            ttsStyle = ttsStyle,
            useMetadataStyle = false
        )
        val streamedFallback = runSseAudioStream(
            model = "gemini-2.5-flash-preview-tts",
            apiKey = cleanKey,
            payload = fallbackPayload,
            onPcmChunk = onPcmChunk
        )
        if (streamedFallback) return@withContext true

        // 3. Final non-streaming REST fallback
        val fullPcm = requestFullPcmBytes("gemini-3.8-flash-lite-tts", cleanKey, primaryPayload)
            ?: requestFullPcmBytes("gemini-2.5-flash-preview-tts", cleanKey, fallbackPayload)
        if (fullPcm != null && fullPcm.isNotEmpty()) {
            onPcmChunk(fullPcm)
            return@withContext true
        }
        false
    }

    private suspend fun runSseAudioStream(
        model: String,
        apiKey: String,
        payload: String,
        onPcmChunk: (ByteArray) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            var emittedAnyAudio = false
            SharedGeminiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val stream = resp.body?.byteStream() ?: return@use false
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192).use { reader ->
                    while (isActive && !cancelRequested.get()) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val jsonChunk = line.removePrefix("data:").trim()
                        if (jsonChunk.isBlank() || jsonChunk == "[DONE]") continue
                        val pcmBytes = extractAudioBytesFromJson(jsonChunk)
                        if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                            onPcmChunk(pcmBytes)
                            emittedAnyAudio = true
                        }
                    }
                }
            }
            emittedAnyAudio
        }.getOrDefault(false)
    }

    private fun requestFullPcmBytes(
        model: String,
        apiKey: String,
        payload: String
    ): ByteArray? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            SharedGeminiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                extractAudioBytesFromJson(body)
            }
        }.getOrNull()
    }

    private fun extractAudioBytesFromJson(jsonString: String): ByteArray? {
        return runCatching {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val parts = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray ?: return@runCatching null

            for (part in parts) {
                val inlineData = part.jsonObject["inlineData"]?.jsonObject ?: continue
                val b64 = inlineData["data"]?.jsonPrimitive?.contentOrNull
                if (!b64.isNullOrBlank()) {
                    return@runCatching Base64.decode(b64, Base64.DEFAULT)
                }
            }
            null
        }.getOrNull()
    }

    private fun buildTtsPayload(
        text: String,
        voiceName: String,
        ttsStyle: String,
        useMetadataStyle: Boolean
    ): String = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", text)
                        if (useMetadataStyle) {
                            put("speech_metadata", buildJsonObject {
                                put("style", ttsStyle.ifBlank { "cheerful and friendly" })
                            })
                        }
                    })
                })
            })
        })
        put("generationConfig", buildJsonObject {
            if (!useMetadataStyle) put("temperature", 1.0)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("speechConfig", buildJsonObject {
                put("voiceConfig", buildJsonObject {
                    put("prebuiltVoiceConfig", buildJsonObject {
                        put("voiceName", voiceName)
                    })
                })
            })
        })
    }.toString()

    companion object {
        const val OUTPUT_SAMPLE_RATE = 24_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
