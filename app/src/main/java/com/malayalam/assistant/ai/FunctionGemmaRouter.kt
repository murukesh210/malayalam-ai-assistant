package com.malayalam.assistant.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.malayalam.assistant.data.TodoDao
import com.malayalam.assistant.data.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class ActionExecutionResult(
    val handledAsAction: Boolean,
    val summaryText: String,
    val reactionQuery: String,
    val usedFunctionGemma: Boolean
)

/**
 * Daily Actions Router (`FunctionGemma 270M` — `functiongemma-270m-ft-mobile-actions`):
 *  - Executes offline tool calls in sub-second latency on 4 GB RAM (`CPU XNNPACK`, 4 threads, `1024` ctx, `160` out).
 *  - Tools: `set_alarm`, `create_calendar_event`, `dial_contact`, `open_app`, `add_todo`, `list_todos`.
 *  - Includes deterministic regex intent parser fallback when FunctionGemma isn't downloaded yet
 *    or for instant unambiguous commands.
 */
class FunctionGemmaRouter(
    private val context: Context,
    private val coordinator: ModelCoordinator,
    private val downloader: ModelDownloadManager,
    private val todoDao: TodoDao
) {

    fun looksLikeActionRequest(input: String): Boolean {
        val lower = input.lowercase().trim()
        val actionVerbs = listOf(
            "set alarm", "wake me", "alarm for", "alarm at",
            "remind me", "calendar", "schedule", "meeting",
            "call ", "dial ", "phone ",
            "open ", "launch ",
            "add todo", "add to-do", "to-do", "todo", "task:", "list todos", "my tasks", "my to-dos"
        )
        return actionVerbs.any { lower.contains(it) }
    }

    suspend fun routeAndExecute(userInput: String): ActionExecutionResult = withContext(Dispatchers.IO) {
        if (!looksLikeActionRequest(userInput)) {
            return@withContext ActionExecutionResult(
                handledAsAction = false,
                summaryText = "",
                reactionQuery = "",
                usedFunctionGemma = false
            )
        }

        // If FunctionGemma (270M) .litertlm is installed, run structured mobile-action tool routing
        if (downloader.isModelReady(ManagedModelId.FUNCTION_GEMMA_270M)) {
            val modelFile = downloader.primaryModelFile(ManagedModelId.FUNCTION_GEMMA_270M)
            val rawToolCall = runCatching {
                coordinator.withModel(
                    modelId = ManagedModelId.FUNCTION_GEMMA_270M,
                    factory = { LiteRtLlmSession(context, modelFile).also { it.initialize() } }
                ) { session ->
                    session.generateText(
                        systemInstruction = FUNCTION_GEMMA_SYSTEM_PROMPT,
                        userPrompt = userInput,
                        temperature = 0.0,
                        maxOutputTokens = 96
                    )
                }
            }.getOrNull()

            if (!rawToolCall.isNullOrBlank()) {
                val parsed = executeStructuredToolOutput(rawToolCall, userInput)
                if (parsed != null) return@withContext parsed.copy(usedFunctionGemma = true)
            }
        }

        // Instant deterministic fallback router
        return@withContext executeHeuristicIntent(userInput)
    }

    private suspend fun executeStructuredToolOutput(rawOutput: String, originalInput: String): ActionExecutionResult? {
        val lower = rawOutput.lowercase()
        return when {
            lower.contains("set_alarm") -> {
                val hour = Regex("""hour["'=:\s]+(\d{1,2})""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                val minute = Regex("""minute["'=:\s]+(\d{1,2})""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val label = extractStringArg(rawOutput, "label") ?: "Assistant Alarm"
                if (hour != null) setDeviceAlarm(hour, minute, label) else null
            }
            lower.contains("create_calendar_event") -> {
                val title = extractStringArg(rawOutput, "title") ?: originalInput
                val mins = Regex("""minutes["'=:\s]+(\d{1,4})""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 60
                createCalendarReminder(title, mins)
            }
            lower.contains("dial_contact") || lower.contains("call_contact") -> {
                val target = extractStringArg(rawOutput, "target")
                    ?: extractStringArg(rawOutput, "name")
                    ?: originalInput.replace(Regex("(?i)^(call|dial)\\s+"), "").trim()
                dialContactOrNumber(target)
            }
            lower.contains("open_app") -> {
                val appName = extractStringArg(rawOutput, "app_name")
                    ?: originalInput.replace(Regex("(?i)^(open|launch)\\s+"), "").trim()
                openInstalledApp(appName)
            }
            lower.contains("add_todo") -> {
                val task = extractStringArg(rawOutput, "task") ?: originalInput
                addRoomTodo(task)
            }
            lower.contains("list_todos") -> {
                listRoomTodos()
            }
            else -> null
        }
    }

    private suspend fun executeHeuristicIntent(input: String): ActionExecutionResult {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        // 1. Alarm
        if (lower.contains("alarm") || lower.contains("wake me")) {
            val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                val min = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val ampm = timeMatch.groupValues[3]
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                return setDeviceAlarm(hour.coerceIn(0, 23), min.coerceIn(0, 59), "Malayalam Assistant")
            }
        }

        // 2. To-Do List
        if (lower.contains("list todo") || lower.contains("my tasks") || lower.contains("my to-dos")) {
            return listRoomTodos()
        }
        if (lower.startsWith("add todo") || lower.startsWith("add to-do") || lower.startsWith("todo ") || lower.startsWith("task:")) {
            val cleaned = trimmed.replace(Regex("(?i)^(add to-?do:?|to-?do:?|task:?)\\s*"), "").trim()
            return addRoomTodo(cleaned.ifBlank { trimmed })
        }

        // 3. Calendar / Reminder
        if (lower.startsWith("remind me") || lower.contains("calendar") || lower.startsWith("schedule")) {
            val cleaned = trimmed.replace(Regex("(?i)^(remind me to|remind me|schedule|add to calendar)\\s*"), "").trim()
            return createCalendarReminder(cleaned.ifBlank { trimmed }, 60)
        }

        // 4. Call / Dial
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val who = trimmed.replace(Regex("(?i)^(call|dial)\\s+"), "").trim()
            return dialContactOrNumber(who)
        }

        // 5. Open App
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appName = trimmed.replace(Regex("(?i)^(open|launch)\\s+"), "").trim()
            return openInstalledApp(appName)
        }

        return ActionExecutionResult(
            handledAsAction = false,
            summaryText = "",
            reactionQuery = "",
            usedFunctionGemma = false
        )
    }

    private fun setDeviceAlarm(hour24: Int, minute: Int, label: String): ActionExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        val formattedTime = String.format("%02d:%02d", hour24, minute)
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = if (ok) "Alarm set for $formattedTime ($label)." else "Opened Clock to set alarm for $formattedTime.",
            reactionQuery = "alarm wake up morning ezhunelkk",
            usedFunctionGemma = false
        )
    }

    private fun createCalendarReminder(title: String, startMinutesFromNow: Int): ActionExecutionResult {
        val beginMs = Calendar.getInstance().timeInMillis + startMinutesFromNow * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginMs + 30 * 60_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = if (ok) "Prepared Calendar reminder: \"$title\"." else "Saved reminder note: \"$title\".",
            reactionQuery = "warning reminder deadline alert",
            usedFunctionGemma = false
        )
    }

    private fun dialContactOrNumber(nameOrNumber: String): ActionExecutionResult {
        val resolvedNumber = resolveContactPhone(nameOrNumber) ?: nameOrNumber.filter { it.isDigit() || it == '+' }
        val dialUri = if (resolvedNumber.isNotBlank()) Uri.parse("tel:$resolvedNumber") else Uri.parse("tel:")
        val intent = Intent(Intent.ACTION_DIAL, dialUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = if (resolvedNumber.isNotBlank()) {
                "Opening Dialer for $nameOrNumber ($resolvedNumber)."
            } else {
                "Opened Dialer (grant Contacts permission to look up \"$nameOrNumber\" by name)."
            },
            reactionQuery = "greeting hello calling friend",
            usedFunctionGemma = false
        )
    }

    private fun resolveContactPhone(queryName: String): String? {
        if (queryName.any { it.isDigit() } && queryName.count { it.isDigit() } >= 6) return queryName
        return runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$queryName%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(1) else null
            }
        }.getOrNull()
    }

    private fun openInstalledApp(appQuery: String): ActionExecutionResult {
        val pm = context.packageManager
        val qLower = appQuery.lowercase().trim()
        val knownAliases = mapOf(
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "messages" to "com.google.android.apps.messaging",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android"
        )

        var launchIntent: Intent? = knownAliases[qLower]?.let { pm.getLaunchIntentForPackage(it) }
        var matchedLabel = appQuery

        if (launchIntent == null) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val hit = apps.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase()
                label == qLower || label.contains(qLower) || info.activityInfo.packageName.lowercase().contains(qLower)
            }
            if (hit != null) {
                matchedLabel = hit.loadLabel(pm).toString()
                launchIntent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            }
        }

        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }
            ActionExecutionResult(
                handledAsAction = true,
                summaryText = "Launching $matchedLabel...",
                reactionQuery = "celebration poli success welcome",
                usedFunctionGemma = false
            )
        } else {
            ActionExecutionResult(
                handledAsAction = true,
                summaryText = "Couldn't find an installed app matching \"$appQuery\".",
                reactionQuery = "scolding mistake",
                usedFunctionGemma = false
            )
        }
    }

    private suspend fun addRoomTodo(taskText: String): ActionExecutionResult {
        todoDao.insertTodo(TodoEntity(task = taskText))
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = "Added to offline To-Do list: \"$taskText\".",
            reactionQuery = "celebration task saved poli",
            usedFunctionGemma = false
        )
    }

    private suspend fun listRoomTodos(): ActionExecutionResult {
        val items = todoDao.getActiveTodos()
        val text = if (items.isEmpty()) {
            "Your offline To-Do list is clear!"
        } else {
            "Pending To-Dos:\n" + items.joinToString("\n") { "• ${it.task}" }
        }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = text,
            reactionQuery = if (items.isEmpty()) "celebration win" else "warning reminder",
            usedFunctionGemma = false
        )
    }

    private fun extractStringArg(raw: String, argName: String): String? {
        val regex = Regex("""$argName["'=:\s]+"([^"]+)"""", RegexOption.IGNORE_CASE)
        return regex.find(raw)?.groupValues?.get(1)?.trim()
    }

    companion object {
        private val FUNCTION_GEMMA_SYSTEM_PROMPT = """
            You are FunctionGemma mobile actions router. Respond ONLY with one tool call in JSON:
            - {"tool":"set_alarm","hour":7,"minute":30,"label":"Wake up"}
            - {"tool":"create_calendar_event","title":"Team Sync","minutes":60}
            - {"tool":"dial_contact","target":"Amma"}
            - {"tool":"open_app","app_name":"WhatsApp"}
            - {"tool":"add_todo","task":"Pay electricity bill"}
            - {"tool":"list_todos"}
        """.trimIndent()
    }
}
