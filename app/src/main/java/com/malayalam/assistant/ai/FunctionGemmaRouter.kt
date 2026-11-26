package com.malayalam.assistant.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.TodoDao
import com.malayalam.assistant.data.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ActionExecutionResult(
    val handledAsAction: Boolean,
    val summaryText: String,
    val reactionQuery: String,
    val usedFunctionGemma: Boolean,
    val awaitingFollowUp: Boolean = false
)

/**
 * Smart Zero-RAM Malayalam + Manglish + English Command Engine (`FunctionGemmaRouter`):
 *  - Works instantaneously with **0 AI in RAM** on 2 GB RAM phones!
 *  - Understands Malayalam script (`സെറ്റ് അലാം`, `വിളിക്ക്`, `തുറക്ക്`, `ഓർമ്മിപ്പിക്കുക`, `സമയം`, `ചാർജ്`),
 *    Manglish (`alarm vekk`, `6 manikk alarm`, `whatsapp thurakk`, `amma vilikk`), and English.
 *  - Remembers conversational pending alarm state when the user says `"സെറ്റ് അലാം"` or `"set an alarm"`
 *    without a time, asking `"എത്ര മണിക്കാണ് അലാറം വെക്കേണ്ടത്?"` AND opening the Clock UI if desired,
 *    then setting the exact alarm on the next reply (`"6 am"` / `"രാവിലെ 6:30"`).
 */
class FunctionGemmaRouter(
    private val context: Context,
    private val coordinator: ModelCoordinator,
    private val downloader: ModelDownloadManager,
    private val todoDao: TodoDao,
    private val prefs: AppPreferences
) {
    @Volatile
    private var pendingAlarmAwaitingTime: Boolean = false

    /**
     * Executes a structured action returned directly by `GeminiBrainClient`:
     * `type`: `set_alarm | open_app | dial_call | add_todo | none`.
     */
    suspend fun executeGeminiAction(type: String, value: String): ActionExecutionResult = withContext(Dispatchers.IO) {
        val ml = prefs.snapshot().isMalayalamMode
        val cleanType = type.trim().lowercase()
        val cleanVal = value.trim()
        when (cleanType) {
            "set_alarm" -> {
                val parsed = parseMalayalamOrEnglishTime(cleanVal)
                if (parsed != null) {
                    setDeviceAlarm(parsed.first, parsed.second, "Malayalam Assistant", ml)
                } else {
                    pendingAlarmAwaitingTime = true
                    openClockAlarmUi()
                    ActionExecutionResult(
                        handledAsAction = true,
                        summaryText = if (ml) "എത്ര മണിക്കാണ് അലാറം വെക്കേണ്ടത്?" else "What time should I set the alarm for?",
                        reactionQuery = "",
                        usedFunctionGemma = false,
                        awaitingFollowUp = true
                    )
                }
            }
            "open_app" -> if (cleanVal.isNotBlank()) openInstalledApp(cleanVal, ml) else ActionExecutionResult(false, "", "", false)
            "dial_call", "call" -> dialContactOrNumber(cleanVal, ml)
            "add_todo", "remind" -> addRoomTodo(cleanVal.ifBlank { "Reminder" }, ml)
            else -> ActionExecutionResult(false, "", "", false)
        }
    }

    suspend fun routeAndExecute(userInput: String): ActionExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) {
            return@withContext ActionExecutionResult(false, "", "", false)
        }
        val settings = prefs.snapshot()
        val ml = settings.isMalayalamMode

        // 0. Check if we were waiting for the user to tell us the alarm time!
        if (pendingAlarmAwaitingTime) {
            val parsedTime = parseMalayalamOrEnglishTime(trimmed)
            if (parsedTime != null) {
                pendingAlarmAwaitingTime = false
                return@withContext setDeviceAlarm(parsedTime.first, parsedTime.second, "Malayalam Assistant", ml)
            }
            if (trimmed.lowercase() in setOf("cancel", "വേണ്ട", "venda", "stop", "no")) {
                pendingAlarmAwaitingTime = false
                return@withContext ActionExecutionResult(
                    handledAsAction = true,
                    summaryText = if (ml) "ശരി, അലാറം റദ്ദാക്കി." else "Okay, cancelled alarm setup.",
                    reactionQuery = "",
                    usedFunctionGemma = false
                )
            }
            // User switched to a new command — clear pending flag and continue routing
            pendingAlarmAwaitingTime = false
        }

        // 1. Instant deterministic Malayalam + Manglish + English Engine (0 MB RAM, sub-millisecond!)
        val deterministic = executeMultilingualIntent(trimmed, ml)
        if (deterministic.handledAsAction) {
            return@withContext deterministic
        }

        // 2. Optional FunctionGemma (270M) neural fallback ONLY if installed & not in Ultra-Light 2 GB mode
        if (!settings.ultraLight2GbMode &&
            looksLikeEnglishActionCandidate(trimmed) &&
            downloader.isModelReady(ManagedModelId.FUNCTION_GEMMA_270M)
        ) {
            val modelFile = downloader.primaryModelFile(ManagedModelId.FUNCTION_GEMMA_270M)
            val rawToolCall = runCatching {
                coordinator.withModel(
                    modelId = ManagedModelId.FUNCTION_GEMMA_270M,
                    factory = { LiteRtLlmSession(context, modelFile).also { it.initialize() } }
                ) { session ->
                    session.generateText(
                        systemInstruction = FUNCTION_GEMMA_SYSTEM_PROMPT,
                        userPrompt = trimmed,
                        temperature = 0.0,
                        maxOutputTokens = 96
                    )
                }
            }.getOrNull()

            if (!rawToolCall.isNullOrBlank()) {
                val parsed = executeStructuredToolOutput(rawToolCall, trimmed, ml)
                if (parsed != null) return@withContext parsed.copy(usedFunctionGemma = true)
            }
        }

        ActionExecutionResult(
            handledAsAction = false,
            summaryText = "",
            reactionQuery = "",
            usedFunctionGemma = false
        )
    }

    private suspend fun executeMultilingualIntent(input: String, ml: Boolean): ActionExecutionResult {
        val lower = input.lowercase().trim()

        // --- A. ALARMS (Malayalam + Manglish + English) ---
        val isAlarmIntent = ALARM_KEYWORDS.any { lower.contains(it) } ||
            (lower.contains("മണിക്ക്") && (lower.contains("വിളി") || lower.contains("എഴുന്നേ"))) ||
            (lower.contains("manikk") && lower.contains("ezhun"))
        if (isAlarmIntent) {
            val parsedTime = parseMalayalamOrEnglishTime(input)
            return if (parsedTime != null) {
                setDeviceAlarm(parsedTime.first, parsedTime.second, "Malayalam Assistant", ml)
            } else {
                // NO time was specified (e.g., "സെറ്റ് അലാം" or "set an alarm"):
                // Remember pending state AND open Clock alarm UI cleanly without skipping UI!
                pendingAlarmAwaitingTime = true
                openClockAlarmUi()
                ActionExecutionResult(
                    handledAsAction = true,
                    summaryText = if (ml) {
                        "എത്ര മണിക്കാണ് അലാറം വെക്കേണ്ടത്? (ഉദാഹരണത്തിന്: 'രാവിലെ 6:30' എന്ന് പറയൂ)"
                    } else {
                        "What time should I set the alarm for? (Say e.g. '6:30 AM')"
                    },
                    reactionQuery = "alarm wake up morning",
                    usedFunctionGemma = false,
                    awaitingFollowUp = true
                )
            }
        }

        // --- B. TIME / DATE / BATTERY STATUS ---
        if (BATTERY_STATUS_KEYWORDS.any { lower.contains(it) } &&
            !lower.contains("low") && !lower.contains("dying")
        ) {
            return readBatteryStatus(ml)
        }

        if (TIME_QUERY_KEYWORDS.any { lower == it || lower.contains(it) }) {
            val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return ActionExecutionResult(
                handledAsAction = true,
                summaryText = if (ml) "ഇപ്പോൾ സമയം $fmt ആണ്." else "It is currently $fmt.",
                reactionQuery = "",
                usedFunctionGemma = false
            )
        }

        if (DATE_QUERY_KEYWORDS.any { lower == it || lower.contains(it) }) {
            val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            return ActionExecutionResult(
                handledAsAction = true,
                summaryText = if (ml) "ഇന്നത്തെ തീയതി: $fmt" else "Today is $fmt.",
                reactionQuery = "",
                usedFunctionGemma = false
            )
        }

        // --- C. TO-DOs & REMINDERS ---
        if (LIST_TODO_KEYWORDS.any { lower.contains(it) }) {
            return listRoomTodos(ml)
        }
        if (TODO_REMINDER_KEYWORDS.any { lower.contains(it) }) {
            val cleaned = stripCommandKeywords(input, TODO_REMINDER_STRIP_WORDS)
            if (lower.contains("calendar") || lower.contains("കലണ്ടർ") || lower.contains("meeting")) {
                return createCalendarReminder(cleaned.ifBlank { input }, 60, ml)
            }
            return addRoomTodo(cleaned.ifBlank { input }, ml)
        }

        // --- D. CALLS / DIALER ---
        val callTarget = extractCallTarget(input, lower)
        if (callTarget != null) {
            return dialContactOrNumber(callTarget, ml)
        }

        // --- E. OPEN APPS ---
        val appTarget = extractOpenAppTarget(input, lower)
        if (appTarget != null) {
            return openInstalledApp(appTarget, ml)
        }

        return ActionExecutionResult(false, "", "", false)
    }

    /**
     * Parses English, Manglish, and Malayalam time expressions:
     *  - `"6:30 am"`, `"7 pm"`, `"18:45"`, `"6 am"`
     *  - `"രാവിലെ 6 മണി"`, `"വൈകുന്നേരം 7:15"`, `"രാത്രി 10 മണിക്ക്"`, `"6 അര"`, `"6 manikk"`
     */
    fun parseMalayalamOrEnglishTime(text: String): Pair<Int, Int>? {
        val lower = text.lowercase()
        val isMorning = listOf("am", "a.m", "രാവിലെ", "പുലർച്ചെ", "ravile", "morning").any { lower.contains(it) }
        val isEvening = listOf("pm", "p.m", "വൈകുന്നേരം", "വൈകിട്ട്", "രാത്രി", "ഉച്ചയ്ക്ക്", "ഉച്ചക്ക്", "rathri", "vaikunneram", "evening", "night", "afternoon")
            .any { lower.contains(it) }
        val hasHalf = listOf("അര", "ara", "half").any { lower.contains(it) }

        val timeRegex = Regex("""(\d{1,2})(?:[:.](\d{1,2}))?\s*(am|pm|a\.m\.?|p\.m\.?)?""")
        val match = timeRegex.find(lower) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        if (hour > 23) return null
        var minute = match.groupValues[2].toIntOrNull() ?: if (hasHalf) 30 else 0
        if (minute > 59) minute = 0

        val suffix = match.groupValues[3]
        when {
            suffix.startsWith("p") || isEvening -> if (hour in 1..11) hour += 12
            suffix.startsWith("a") || isMorning -> if (hour == 12) hour = 0
        }
        return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }

    private fun extractCallTarget(original: String, lower: String): String? {
        // Prefix style: "call Amma", "കോൾ അമ്മ", "വിളിക്ക് രാഹുൽ"
        val prefixRegex = Regex("(?i)^(call|dial|phone|കോൾ|വിളിക്ക്|വിളിക്കുക|vilikk|vilikku)\\s+(.+)$")
        prefixRegex.find(original.trim())?.let {
            return it.groupValues[2].trim()
        }
        // Suffix style in Malayalam/Manglish: "അമ്മയെ വിളിക്ക്", "rahuline vilikk", "9847000000 vilikk"
        val suffixRegex = Regex("(?i)^(.+?)\\s*(?:യെ|നെ|ക്ക്)?\\s+(വിളിക്ക്|വിളിക്കുക|കോൾ ചെയ്യൂ|കോൾ|vilikk|vilikku|call cheyyu)$")
        suffixRegex.find(original.trim())?.let {
            return it.groupValues[1].trim()
        }
        if (lower in setOf("call", "dial", "കോൾ", "വിളിക്ക്", "vilikk")) {
            return ""
        }
        return null
    }

    private fun extractOpenAppTarget(original: String, lower: String): String? {
        // Prefix: "open WhatsApp", "ഓപ്പൺ വാട്സ്ആപ്പ്", "തുറക്ക് യൂട്യൂബ്"
        val prefixRegex = Regex("(?i)^(open|launch|start|ഓപ്പൺ|തുറക്ക്|തുറക്കുക|thurakk|thurakku)\\s+(.+)$")
        prefixRegex.find(original.trim())?.let {
            return it.groupValues[2].replace(Regex("(?i)\\bapp\\b|ആപ്പ്"), "").trim()
        }
        // Suffix: "വാട്സ്ആപ്പ് തുറക്ക്", "whatsapp thurakk", "youtube open cheyyu"
        val suffixRegex = Regex("(?i)^(.+?)\\s+(തുറക്ക്|തുറക്കുക|ഓപ്പൺ ചെയ്യൂ|ഓപ്പൺ|thurakk|thurakku|open cheyyu|open)$")
        suffixRegex.find(original.trim())?.let {
            return it.groupValues[1].replace(Regex("(?i)\\bapp\\b|ആപ്പ്"), "").trim()
        }
        return if (lower in setOf("open camera", "ക്യാമറ")) "camera" else null
    }

    private fun stripCommandKeywords(raw: String, words: List<String>): String {
        var out = raw
        for (w in words) {
            out = out.replace(Regex("(?i)\\b${Regex.escape(w)}\\b"), "")
            out = out.replace(w, "", ignoreCase = true)
        }
        return out.replace(Regex("^[:\\-\\s]+"), "").trim()
    }

    private fun openClockAlarmUi() {
        val showIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(showIntent) }.isFailure) {
            val fallback = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    private fun setDeviceAlarm(hour24: Int, minute: Int, label: String, ml: Boolean): ActionExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            openClockAlarmUi()
        }
        val formattedTime = format12Hour(hour24, minute)
        val summary = if (ml) {
            if (ok) "$formattedTime-ന് അലാറം സെറ്റ് ചെയ്തു." else "$formattedTime അലാറത്തിനായി ക്ലോക്ക് തുറന്നു."
        } else {
            if (ok) "Alarm set for $formattedTime." else "Opened Clock for $formattedTime."
        }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = summary,
            reactionQuery = "alarm wake up morning",
            usedFunctionGemma = false
        )
    }

    private fun format12Hour(hour24: Int, minute: Int): String {
        val ampm = if (hour24 < 12) "AM" else "PM"
        val h12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return String.format("%d:%02d %s", h12, minute, ampm)
    }

    private fun createCalendarReminder(title: String, startMinutesFromNow: Int, ml: Boolean): ActionExecutionResult {
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
            summaryText = if (ml) {
                if (ok) "\"$title\" കലണ്ടറിൽ ചേർക്കാൻ തുറന്നു." else "\"$title\" റിമൈൻഡറായി സേവ് ചെയ്തു."
            } else {
                if (ok) "Opening Calendar for \"$title\"." else "Saved reminder \"$title\"."
            },
            reactionQuery = "warning reminder",
            usedFunctionGemma = false
        )
    }

    private fun dialContactOrNumber(nameOrNumber: String, ml: Boolean): ActionExecutionResult {
        val cleanedTarget = nameOrNumber.trim()
        val resolvedNumber = if (cleanedTarget.isNotEmpty()) {
            resolveContactPhone(cleanedTarget) ?: cleanedTarget.filter { it.isDigit() || it == '+' }
        } else ""
        val dialUri = if (resolvedNumber.isNotBlank()) Uri.parse("tel:$resolvedNumber") else Uri.parse("tel:")
        val intent = Intent(Intent.ACTION_DIAL, dialUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        val summary = when {
            resolvedNumber.isNotBlank() && ml -> "$cleanedTarget ($resolvedNumber) വിളിക്കാൻ ഡയലർ തുറക്കുന്നു..."
            resolvedNumber.isNotBlank() -> "Opening Dialer for $cleanedTarget ($resolvedNumber)."
            ml -> "ഫോൺ ഡയലർ തുറന്നു."
            else -> "Opened Phone Dialer."
        }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = summary,
            reactionQuery = "greeting calling",
            usedFunctionGemma = false
        )
    }

    private fun resolveContactPhone(queryName: String): String? {
        if (queryName.count { it.isDigit() } >= 6) return queryName
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

    private fun openInstalledApp(appQuery: String, ml: Boolean): ActionExecutionResult {
        val pm = context.packageManager
        val normalized = normalizeMalayalamAppAlias(appQuery.lowercase().trim())

        if (normalized == "camera") {
            val camIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(camIntent) }.isSuccess) {
                return ActionExecutionResult(
                    handledAsAction = true,
                    summaryText = if (ml) "ക്യാമറ തുറക്കുന്നു..." else "Opening Camera...",
                    reactionQuery = "celebration",
                    usedFunctionGemma = false
                )
            }
        }

        val knownPackages = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "gmail" to listOf("com.google.android.gm", "com.google.android.gm.lite"),
            "youtube" to listOf("com.google.android.youtube", "com.google.android.apps.youtube.mango"),
            "maps" to listOf("com.google.android.apps.maps", "com.google.android.apps.mapslite"),
            "chrome" to listOf("com.android.chrome"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
            "telegram" to listOf("org.telegram.messenger"),
            "instagram" to listOf("com.instagram.android", "com.instagram.lite"),
            "settings" to listOf("com.android.settings")
        )

        var launchIntent: Intent? = knownPackages[normalized]
            ?.firstNotNullOfOrNull { pkg -> pm.getLaunchIntentForPackage(pkg) }
        var matchedLabel = appQuery

        if (launchIntent == null) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val hit = apps.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase()
                label == normalized || label.contains(normalized) ||
                    normalized.contains(label) ||
                    info.activityInfo.packageName.lowercase().contains(normalized)
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
                summaryText = if (ml) "$matchedLabel തുറക്കുന്നു..." else "Opening $matchedLabel...",
                reactionQuery = "celebration poli",
                usedFunctionGemma = false
            )
        } else {
            ActionExecutionResult(
                handledAsAction = true,
                summaryText = if (ml) "\"$appQuery\" ആപ്പ് ഫോണിൽ കണ്ടെത്താനായില്ല." else "Could not find app \"$appQuery\".",
                reactionQuery = "",
                usedFunctionGemma = false
            )
        }
    }

    private fun normalizeMalayalamAppAlias(raw: String): String = when {
        raw.contains("വാട്സ്") || raw.contains("വാട്ട്സ്") || raw.contains("whats") -> "whatsapp"
        raw.contains("യൂട്യൂബ്") || raw.contains("യുട്യൂബ്") || raw.contains("youtub") -> "youtube"
        raw.contains("ജിമെയിൽ") || raw.contains("മെയിൽ") || raw.contains("gmail") -> "gmail"
        raw.contains("ക്യാമറ") || raw.contains("ഫോട്ടോ") || raw.contains("camera") -> "camera"
        raw.contains("ക്രോം") || raw.contains("ബ്രൗസർ") || raw.contains("chrome") -> "chrome"
        raw.contains("മാപ്") || raw.contains("മാപ്പ്") || raw.contains("map") -> "maps"
        raw.contains("ഇൻസ്റ്റ") || raw.contains("insta") -> "instagram"
        raw.contains("ടെലിഗ്രാം") || raw.contains("telegram") -> "telegram"
        raw.contains("മെസ്സേജ്") || raw.contains("message") || raw.contains("sms") -> "messages"
        raw.contains("സെറ്റിംഗ്") || raw.contains("setting") -> "settings"
        else -> raw
    }

    private fun readBatteryStatus(ml: Boolean): ActionExecutionResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val msg = if (pct in 0..100) {
            if (ml) {
                if (charging) "ബാറ്ററി $pct% ഉണ്ട് (ചാർജ് ചെയ്യുന്നു ⚡)." else "ഫോണിൽ ഇപ്പോൾ $pct% ചാർജ് ഉണ്ട്."
            } else {
                if (charging) "Battery is at $pct% (Charging ⚡)." else "Battery is at $pct%."
            }
        } else {
            if (ml) "ബാറ്ററി നില ലഭ്യമല്ല." else "Battery level unavailable."
        }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = msg,
            reactionQuery = if (pct in 1..20) "low_battery battery low" else "",
            usedFunctionGemma = false
        )
    }

    private suspend fun addRoomTodo(taskText: String, ml: Boolean): ActionExecutionResult {
        val cleanTask = taskText.trim().ifBlank { if (ml) "പുതിയ ടാസ്ക്" else "New reminder" }
        todoDao.insertTodo(TodoEntity(task = cleanTask))
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = if (ml) "ഓർമ്മിപ്പിക്കൽ സേവ് ചെയ്തു: \"$cleanTask\"" else "Saved reminder: \"$cleanTask\"",
            reactionQuery = "celebration",
            usedFunctionGemma = false
        )
    }

    private suspend fun listRoomTodos(ml: Boolean): ActionExecutionResult {
        val items = todoDao.getActiveTodos()
        val text = if (items.isEmpty()) {
            if (ml) "പെൻഡിംഗ് ടാസ്കുകൾ ഒന്നുമില്ല!" else "Your To-Do list is all clear!"
        } else {
            val header = if (ml) "നിങ്ങളുടെ ടാസ്കുകൾ:" else "Pending To-Dos:"
            header + "\n" + items.take(5).joinToString("\n") { "• ${it.task}" }
        }
        return ActionExecutionResult(
            handledAsAction = true,
            summaryText = text,
            reactionQuery = "",
            usedFunctionGemma = false
        )
    }

    private suspend fun executeStructuredToolOutput(
        rawOutput: String,
        originalInput: String,
        ml: Boolean
    ): ActionExecutionResult? {
        val lower = rawOutput.lowercase()
        return when {
            lower.contains("set_alarm") -> {
                val hour = Regex("""hour["'=:\s]+(\d{1,2})""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                val minute = Regex("""minute["'=:\s]+(\d{1,2})""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val label = extractStringArg(rawOutput, "label") ?: "Assistant Alarm"
                if (hour != null) setDeviceAlarm(hour, minute, label, ml) else null
            }
            lower.contains("create_calendar_event") -> {
                val title = extractStringArg(rawOutput, "title") ?: originalInput
                val mins = Regex("""minutes["'=:\s]+(\d{1,4})""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 60
                createCalendarReminder(title, mins, ml)
            }
            lower.contains("dial_contact") || lower.contains("call_contact") -> {
                val target = extractStringArg(rawOutput, "target")
                    ?: extractStringArg(rawOutput, "name")
                    ?: originalInput
                dialContactOrNumber(target, ml)
            }
            lower.contains("open_app") -> {
                val appName = extractStringArg(rawOutput, "app_name") ?: originalInput
                openInstalledApp(appName, ml)
            }
            lower.contains("add_todo") -> {
                val task = extractStringArg(rawOutput, "task") ?: originalInput
                addRoomTodo(task, ml)
            }
            lower.contains("list_todos") -> listRoomTodos(ml)
            else -> null
        }
    }

    private fun looksLikeEnglishActionCandidate(input: String): Boolean {
        val l = input.lowercase()
        return listOf("schedule", "wake", "timer", "appointment", "launch", "phone").any { l.contains(it) }
    }

    private fun extractStringArg(raw: String, argName: String): String? {
        val regex = Regex("""$argName["'=:\s]+"([^"]+)"""", RegexOption.IGNORE_CASE)
        return regex.find(raw)?.groupValues?.get(1)?.trim()
    }

    companion object {
        private val ALARM_KEYWORDS = listOf(
            "സെറ്റ് അലാം", "സെറ്റ് അലാറം", "അലാറം", "അലാം",
            "alarm", "wake me", "alarm vekk", "alaram"
        )

        private val TODO_REMINDER_KEYWORDS = listOf(
            "ഓർമ്മിപ്പിക്കുക", "ഓർമ്മിപ്പിക്ക്", "ഓർമ്മിപ്പിക്കണം", "റിമൈൻഡർ", "ടാസ്ക് ചേർക്കുക",
            "remind me", "reminder", "add todo", "add to-do", "todo ", "to-do ", "ormippikk"
        )

        private val TODO_REMINDER_STRIP_WORDS = listOf(
            "ഓർമ്മിപ്പിക്കുക", "ഓർമ്മിപ്പിക്ക്", "ഓർമ്മിപ്പിക്കണം", "റിമൈൻഡർ",
            "remind me to", "remind me", "reminder", "add todo", "add to-do", "todo", "to-do", "ormippikkuka", "ormippikk"
        )

        private val LIST_TODO_KEYWORDS = listOf(
            "list todo", "my tasks", "my to-dos", "show todos", "ടാസ്കുകൾ", "എന്റെ ലിസ്റ്റ്"
        )

        private val TIME_QUERY_KEYWORDS = listOf(
            "സമയം", "എത്ര മണിയായി", "മണി എത്രയായി", "samayam", "maniyayi", "what time", "current time", "time now", "time"
        )

        private val DATE_QUERY_KEYWORDS = listOf(
            "തീയതി", "ഇന്ന് ഏതാ ദിവസം", "ഇന്നത്തെ ദിവസം", "what date", "today's date", "date today", "innathe divasam"
        )

        private val BATTERY_STATUS_KEYWORDS = listOf(
            "ബാറ്ററി", "ചാർജ് എത്ര", "ചാർജ് ഉണ്ടോ", "ചാർജ്", "battery percentage", "battery level", "battery status", "battery", "charge ethra"
        )

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
