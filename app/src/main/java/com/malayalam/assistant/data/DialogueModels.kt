package com.malayalam.assistant.data

import kotlinx.serialization.Serializable

@Serializable
enum class SituationCategory(val id: String, val displayLabel: String) {
    EXAM_FAIL("exam_fail", "Exam / Study Fail"),
    MONEY("money", "Money / Bank / Salary"),
    ALARM("alarm", "Alarm / Wake Up"),
    LOW_BATTERY("low_battery", "Low Battery"),
    ROMANCE("romance", "Romance / Crush"),
    SCOLDING("scolding", "Scolding / Roast"),
    GREETING("greeting", "Greeting / Welcome"),
    CELEBRATION("celebration", "Celebration / Win"),
    WARNING("warning", "Warning / Alert"),
    CUSTOM("custom", "Custom / General");

    companion object {
        fun fromId(raw: String?): SituationCategory {
            val cleaned = raw?.trim()?.lowercase() ?: return CUSTOM
            return entries.firstOrNull { it.id == cleaned } ?: CUSTOM
        }
    }
}

@Serializable
data class DialogueEntry(
    val id: String = "",
    val fileName: String = "",
    val safUriString: String = "",
    val title: String = "",
    val heardText: String = "",
    val category: String = SituationCategory.CUSTOM.id,
    val situations: List<String> = emptyList(),
    val recommendedApps: List<String> = emptyList(),
    val scope: String = "specific", // "specific" | "broad"
    val durationMs: Long = 0L,
    val addedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun compositeSearchDocument(): String {
        val catLabel = SituationCategory.fromId(category).displayLabel
        return buildString {
            append(title).append(". ")
            if (heardText.isNotBlank()) append(heardText).append(". ")
            append("Category: ").append(catLabel).append(" (").append(category).append("). ")
            if (situations.isNotEmpty()) {
                append("Situations: ").append(situations.joinToString(", "))
            }
        }.trim()
    }
}

@Serializable
data class DialogueVaultFile(
    val version: Int = 1,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val dialogues: List<DialogueEntry> = emptyList()
)

@Serializable
data class EmbeddingsCacheFile(
    val modelTag: String = "embeddinggemma-2-text-270m",
    val vectors: Map<String, List<Float>> = emptyMap()
)

@Serializable
data class GemmaClassificationJson(
    val title: String = "",
    val heardText: String = "",
    val category: String = "custom",
    val situations: List<String> = emptyList(),
    val recommendedApps: List<String> = emptyList()
)

data class MatchCandidate(
    val entry: DialogueEntry,
    val score: Float,
    val usedNeuralEmbedding: Boolean
)
