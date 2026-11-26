package com.malayalam.assistant.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Expressive Natural Malayalam Voice TTS Client via Google AI Studio REST (`v1beta`):
 *  - Primary: `gemini-3.8-flash-lite-tts` with `speech_metadata: { style: ttsStyle }` and chosen voice (`Kore`, `Puck`, `Aoede`, `Zephyr`, `Fenrir`, `Charon`).
 *  - Automatic Fallback: `gemini-2.5-flash-preview-tts` (`temperature: 1.0`) if 3.8 TTS returns HTTP 400/404.
 *  - Returns decoded 24 kHz 16-bit mono PCM bytes ready for zero-latency `AudioTrack` playback.
 */
class GeminiTtsClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizePcm24kMono(
        apiKey: String,
        text: String,
        voiceName: String = "Kore",
        ttsStyle: String = "cheerful and friendly"
    ): ByteArray? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanText = text.replace(Regex("[🎬🔊✅⚠️✦]"), "").trim()
        if (cleanKey.isBlank() || cleanText.isBlank()) return@withContext null

        val safeVoice = voiceName.ifBlank { "Kore" }

        // 1. Primary: gemini-3.8-flash-lite-tts with speech_metadata.style
        val primaryPayload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", cleanText)
                            put("speech_metadata", buildJsonObject {
                                put("style", ttsStyle.ifBlank { "cheerful and friendly" })
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", safeVoice)
                        })
                    })
                })
            })
        }

        val primaryAudio = requestPcmBytes(
            model = "gemini-3.8-flash-lite-tts",
            apiKey = cleanKey,
            payload = primaryPayload.toString()
        )
        if (primaryAudio != null && primaryAudio.isNotEmpty()) {
            return@withContext primaryAudio
        }

        // 2. Fallback: gemini-2.5-flash-preview-tts (temperature 1.0, inline style instruction)
        val fallbackPayload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "Speak in a $ttsStyle tone: $cleanText")
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 1.0)
                put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", safeVoice)
                        })
                    })
                })
            })
        }

        requestPcmBytes(
            model = "gemini-2.5-flash-preview-tts",
            apiKey = cleanKey,
            payload = fallbackPayload.toString()
        )
    }

    private fun requestPcmBytes(
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
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val parts = root["candidates"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray ?: return@use null

                for (part in parts) {
                    val inlineData = part.jsonObject["inlineData"]?.jsonObject ?: continue
                    val b64 = inlineData["data"]?.jsonPrimitive?.contentOrNull
                    if (!b64.isNullOrBlank()) {
                        return@use Base64.decode(b64, Base64.DEFAULT)
                    }
                }
                null
            }
        }.getOrNull()
    }

    companion object {
        const val OUTPUT_SAMPLE_RATE = 24_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
