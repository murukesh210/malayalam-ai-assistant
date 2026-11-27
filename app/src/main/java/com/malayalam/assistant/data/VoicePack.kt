package com.malayalam.assistant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Portable Actor / Theme Voice Pack stored under `filesDir/packs/<packId>/pack.json`
 * and exportable/importable as a standard `.zip` or `.mlpack` archive.
 */
@Serializable
data class VoicePack(
    val packId: String = DEFAULT_PACK_ID,
    val packName: String = "My Custom Pack",
    val actor: String = "Custom",
    val ttsVoice: String = "Kore",
    val personaInstruction: String = "Warm, witty, friendly Malayalam conversational style.",
    val dialogues: List<DialogueEntry> = emptyList(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_PACK_ID = "default_custom"
        const val ALL_PACKS_ID = "__ALL_PACKS__"
    }
}

/**
 * Flexible import DTO accepting BOTH portable hand-authored `pack.json` schemas
 * (`name`, `audioFile`, `whenToPlay`, `dialogue`, `specificity`, `apps`)
 * AND app-exported `VoicePack` schemas (`packName`, `title`, `fileName`, `situations`, `heardText`, `scope`, `recommendedApps`).
 */
@Serializable
data class ImportPackDto(
    val packId: String = "",
    val id: String = "",
    val packName: String = "",
    val name: String = "",
    val actor: String = "",
    val ttsVoice: String = "",
    val voice: String = "",
    val personaInstruction: String = "",
    val persona: String = "",
    val dialogues: List<ImportDialogueDto> = emptyList()
)

@Serializable
data class ImportDialogueDto(
    val id: String = "",
    val title: String = "",
    val name: String = "",
    val fileName: String = "",
    val audioFile: String = "",
    val audio: String = "",
    val safUriString: String = "",
    val heardText: String = "",
    val dialogue: String = "",
    val whenToPlay: JsonElement? = null,
    val situations: JsonElement? = null,
    val category: String = "custom",
    val specificity: String = "",
    val scope: String = "",
    val apps: List<String> = emptyList(),
    val recommendedApps: List<String> = emptyList(),
    val durationMs: Long = 0L
) {
    fun resolvedSituations(): List<String> {
        val out = mutableListOf<String>()
        extractStringList(whenToPlay, out)
        extractStringList(situations, out)
        return out.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun extractStringList(el: JsonElement?, target: MutableList<String>) {
        when (el) {
            is JsonPrimitive -> {
                val txt = el.contentOrNull?.trim().orEmpty()
                if (txt.isNotBlank()) target.add(txt)
            }
            is JsonArray -> {
                for (item in el) {
                    val txt = runCatching { item.jsonPrimitive.contentOrNull }.getOrNull()?.trim().orEmpty()
                    if (txt.isNotBlank()) target.add(txt)
                }
            }
            else -> Unit
        }
    }
}

data class PackImportResult(
    val pack: VoicePack,
    val importedAudioCount: Int,
    val missingManifestAutoCreated: Boolean
)
