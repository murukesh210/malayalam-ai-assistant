package com.malayalam.assistant.ai

import android.util.Base64
import com.malayalam.assistant.data.DialogueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

@Serializable
data class GeminiActionSpec(
    val type: String = "none", // none | set_alarm | open_app | dial_call | add_todo
    val value: String = ""
)

@Serializable
data class GeminiBrainResponse(
    val heardTranscript: String = "",
    val replyMalayalam: String = "",
    val playDialogueId: String? = null,
    val action: GeminiActionSpec = GeminiActionSpec(),
    val ttsStyle: String = "cheerful and friendly",
    val skipReaction: Boolean = false
)

data class VaultDialogueSummary(
    val id: String,
    val title: String,
    val situations: List<String>,
    val hasAudio: Boolean
)

/**
 * Google AI Studio (Gemini API) Brain Client (`0 MB` phone RAM):
 *  - Primary: `gemini-3.5-flash-lite` -> automatic fallback to `gemini-3.8-flash` / `gemini-2.5-flash`.
 *  - Accepts BOTH text prompts AND raw 16 kHz WAV audio (`inlineData` `audio/wav`) so Gemini hears
 *    natural Malayalam, Manglish, and English directly without broken on-device `SpeechRecognizer`.
 *  - Forces structured JSON output (`responseMimeType: "application/json"`).
 */
class GeminiBrainClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val defaultHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val fastReactionHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun askWithTextOrAudio(
        apiKey: String,
        userText: String? = null,
        wavAudioBytes: ByteArray? = null,
        screenContext: String? = null,
        vaultDialogues: List<VaultDialogueSummary>,
        preferMalayalam: Boolean = true
    ): GeminiBrainResponse? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val systemInstruction = buildAssistantSystemInstruction(vaultDialogues, preferMalayalam)
        val userParts = buildJsonArray {
            if (wavAudioBytes != null && wavAudioBytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(wavAudioBytes, Base64.NO_WRAP)
                add(
                    buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", "audio/wav")
                            put("data", b64)
                        })
                    }
                )
            }
            val promptLine = buildString {
                if (!screenContext.isNullOrBlank()) {
                    appendLine("Visible screen text: ${screenContext.take(900)}")
                }
                if (!userText.isNullOrBlank()) {
                    appendLine("User message: $userText")
                } else {
                    appendLine("Listen carefully to the user's spoken Malayalam / Manglish / English audio above, transcribe it into heardTranscript, understand the meaning/emotion, and respond in JSON.")
                }
            }.trim()
            add(buildJsonObject { put("text", promptLine) })
        }

        val payload = buildRequestPayload(systemInstruction, userParts, temperature = 0.45, maxTokens = 320)
        val rawText = callWithModelFallback(cleanKey, payload, defaultHttp) ?: return@withContext null
        parseBrainJson(rawText)
    }

    /**
     * Fast 6-second AI classifier for incoming SMS (bank credit/debit), Gmail, and App Notifications.
     */
    suspend fun analyzeIncomingMessage(
        apiKey: String,
        sender: String,
        source: String,
        messageText: String,
        vaultDialogues: List<VaultDialogueSummary>
    ): GeminiBrainResponse? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val systemInstruction = buildIncomingReactorInstruction(vaultDialogues)
        val userParts = buildJsonArray {
            add(
                buildJsonObject {
                    put(
                        "text",
                        """
                        Incoming $source:
                        Sender: $sender
                        Message: ${messageText.take(600)}
                        """.trimIndent()
                    )
                }
            )
        }

        val payload = buildRequestPayload(systemInstruction, userParts, temperature = 0.3, maxTokens = 220)
        val rawText = callWithModelFallback(cleanKey, payload, fastReactionHttp) ?: return@withContext null
        parseBrainJson(rawText)
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter your Google AI Studio API key first."))
        }
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", "Reply in one short friendly Malayalam sentence saying you are ready.") })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", 64)
            })
        }

        var lastErr = "Connection failed"
        for (model in BRAIN_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val res = runCatching {
                defaultHttp.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = extractCandidateText(body)?.trim().orEmpty()
                        return@withContext Result.success("✅ Connected ($model): ${text.ifBlank { "Ready!" }}")
                    } else {
                        lastErr = "HTTP ${resp.code} ($model): ${extractApiErrorMessage(body)}"
                        if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                            return@withContext Result.failure(IllegalStateException(lastErr))
                        }
                    }
                }
            }
            res.onFailure { lastErr = it.message ?: lastErr }
        }
        Result.failure(IllegalStateException(lastErr))
    }

    private fun callWithModelFallback(
        apiKey: String,
        payload: JsonObject,
        client: OkHttpClient
    ): String? {
        val bodyStr = payload.toString()
        for (model in BRAIN_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val text = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string().orEmpty()
                    extractCandidateText(raw)
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun buildRequestPayload(
        systemInstruction: String,
        userParts: JsonArray,
        temperature: Double,
        maxTokens: Int
    ): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", systemInstruction) })
            })
        })
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", userParts)
            })
        })
        put("generationConfig", buildJsonObject {
            put("responseMimeType", "application/json")
            put("temperature", temperature)
            put("maxOutputTokens", maxTokens)
        })
    }

    private fun extractCandidateText(responseBody: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
            val parts = candidates.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray ?: return@runCatching null
            parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("\n")
                .trim()
        }.getOrNull()
    }

    private fun extractApiErrorMessage(responseBody: String): String {
        return runCatching {
            val root = json.parseToJsonElement(responseBody).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.take(90) ?: "Check API key"
    }

    private fun parseBrainJson(raw: String): GeminiBrainResponse? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = raw.substring(start, end + 1)
        return runCatching {
            val parsed = json.decodeFromString<GeminiBrainResponse>(slice)
            parsed.copy(
                playDialogueId = parsed.playDialogueId?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            )
        }.getOrNull()
    }

    private fun formatVaultForPrompt(vault: List<VaultDialogueSummary>): String {
        if (vault.isEmpty()) return "[]"
        return vault.joinToString(prefix = "[", postfix = "]") { d ->
            val sits = d.situations.take(6).joinToString(", ")
            """{"id":"${d.id}","title":"${d.title}","hasAudio":${d.hasAudio},"situations":"$sits"}"""
        }
    }

    private fun buildAssistantSystemInstruction(
        vault: List<VaultDialogueSummary>,
        preferMalayalam: Boolean
    ): String {
        val langNote = if (preferMalayalam) {
            "Always write replyMalayalam in warm, witty, natural conversational Malayalam (1-2 short sentences)."
        } else {
            "Write replyMalayalam in friendly, natural conversational English or Manglish (1-2 short sentences)."
        }
        return """
            You are a warm, witty, human-like Malayalam AI smartphone assistant.
            $langNote
            You understand natural Malayalam, Manglish, and English speech and text.

            Stored User Movie Dialogue Vault:
            ${formatVaultForPrompt(vault)}

            Rules:
            1. If the user expresses a situation matching one of the stored dialogues AND its hasAudio is true, set "playDialogueId" to that exact dialogue "id". Otherwise set "playDialogueId" to null.
            2. If the user asks for a phone action, set "action":
               - Alarm: {"type":"set_alarm","value":"6:30 AM"} (or empty value if time wasn't given)
               - Open app: {"type":"open_app","value":"WhatsApp"}
               - Call/Dial: {"type":"dial_call","value":"Amma"}
               - Add reminder/todo: {"type":"add_todo","value":"Pay KSEB bill"}
               - Otherwise: {"type":"none","value":""}
            3. Respond ONLY with valid JSON:
               {
                 "heardTranscript": "exact transcript of what user said or typed",
                 "replyMalayalam": "1-2 short natural sentences",
                 "playDialogueId": "matching_dialogue_id_or_null",
                 "action": { "type": "none | set_alarm | open_app | dial_call | add_todo", "value": "..." },
                 "ttsStyle": "cheerful and friendly | sympathetic and warm | dramatic and teasing | calm"
               }
        """.trimIndent()
    }

    private fun buildIncomingReactorInstruction(vault: List<VaultDialogueSummary>): String = """
        You react to incoming phone SMS, Gmail emails, and notifications for a Malayalam user.
        Stored User Movie Dialogue Vault:
        ${formatVaultForPrompt(vault)}

        Rules:
        1. Set "skipReaction": true for OTP codes, spam, ads, marketing promos, delivery tracking, or boring automated alerts (EXCEPT bank money credit/debit and exam results — always react to bank credit/debit and exam results!).
        2. If the message matches a stored dialogue with "hasAudio": true (e.g. bank money credited, bank money debited/spent, exam failed/arrear/supply), set "playDialogueId" to that exact "id".
        3. Also write a short, witty, 1-sentence natural Malayalam reaction in "replyMalayalam" (mentioning the amount/context playfully for bank credit/debit or comforting/teasing for exam/personal messages).
        4. Respond ONLY with valid JSON:
           {
             "heardTranscript": "",
             "replyMalayalam": "1 short witty Malayalam reaction",
             "playDialogueId": "matching_id_or_null",
             "action": { "type": "none", "value": "" },
             "ttsStyle": "cheerful and friendly | sympathetic and warm | dramatic and teasing | calm",
             "skipReaction": false
           }
    """.trimIndent()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BRAIN_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-2.5-flash"
        )

        fun toVaultSummaries(
            entries: List<DialogueEntry>,
            hasAudioPredicate: (DialogueEntry) -> Boolean
        ): List<VaultDialogueSummary> = entries.map { e ->
            VaultDialogueSummary(
                id = e.id,
                title = e.title,
                situations = e.situations,
                hasAudio = hasAudioPredicate(e)
            )
        }
    }
}
