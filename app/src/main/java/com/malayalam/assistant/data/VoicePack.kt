package com.malayalam.assistant.data

import kotlinx.serialization.Serializable

/**
 * Portable Actor / Theme Voice Pack stored under `filesDir/packs/<packId>/pack.json`
 * and exportable/importable as a standard `.zip` or `.mlpack` archive.
 */
@Serializable
data class VoicePack(
    val packId: String,
    val packName: String,
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

data class PackImportResult(
    val pack: VoicePack,
    val importedAudioCount: Int,
    val missingManifestAutoCreated: Boolean
)
