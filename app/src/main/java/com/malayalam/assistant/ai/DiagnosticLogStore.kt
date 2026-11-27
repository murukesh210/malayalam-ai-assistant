package com.malayalam.assistant.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApiDiagnosticEvent(
    val id: Long = System.nanoTime(),
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val source: String,
    val inputText: String,
    val httpCode: Int,
    val latencyMs: Long,
    val understoodMeaning: String = "",
    val matchedDialogueId: String? = null,
    val errorMessage: String = ""
)

/**
 * In-memory ring log of the last 20 Gemini API & Reactor diagnostic events,
 * surfaced in Settings -> Live AI & Connection Log so zero errors or stalls are ever hidden.
 */
object DiagnosticLogStore {
    private const val MAX_EVENTS = 20

    private val _events = MutableStateFlow<List<ApiDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<ApiDiagnosticEvent>> = _events.asStateFlow()

    fun record(
        source: String,
        inputText: String,
        httpCode: Int,
        latencyMs: Long,
        understoodMeaning: String = "",
        matchedDialogueId: String? = null,
        errorMessage: String = ""
    ) {
        val ev = ApiDiagnosticEvent(
            source = source,
            inputText = inputText.take(90),
            httpCode = httpCode,
            latencyMs = latencyMs,
            understoodMeaning = understoodMeaning.take(120),
            matchedDialogueId = matchedDialogueId,
            errorMessage = errorMessage.take(140)
        )
        _events.value = (listOf(ev) + _events.value).take(MAX_EVENTS)
    }
}
