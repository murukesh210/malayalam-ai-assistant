package com.malayalam.assistant.data

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "assistant_settings")

data class UserSettings(
    val safTreeUri: String = "",
    val lowRamSafeMode: Boolean = true,
    val ultraLight2GbMode: Boolean = false,
    val enableGemma4E2bOptional: Boolean = false,
    val matchThreshold: Float = 0.35f,
    val headphonesOrBluetoothOnly: Boolean = false,
    val lowBatteryReactionEnabled: Boolean = true,
    val autoAcceptBulkImports: Boolean = false,
    val watchedPackages: Set<String> = DEFAULT_WATCHED_PACKAGES,
    val hfAuthToken: String = "",
    val speechLocale: String = "ml-IN",
    val ttsVoiceRepliesEnabled: Boolean = true,
    val speakAfterDialogueClip: Boolean = false,
    // Google AI Studio (Gemini API) Brain + Expressive TTS
    val geminiApiKeyEncrypted: String = "",
    val geminiVoiceName: String = "Kore",
    // Automatic Bank & Message SMS Auto-Reaction + Always-On Keep-Alive
    val smsAutoReactEnabled: Boolean = false,
    val alwaysOnKeepAliveEnabled: Boolean = true,
    // Direct SSL IMAP Gmail Watcher (Android Go / 2 GB RAM)
    val gmailWatcherEnabled: Boolean = false,
    val gmailAddress: String = "",
    val gmailEncryptedAppPassword: String = "",
    val gmailLastSeenUid: Long = 0L,
    val gmailLastStatus: String = "Not configured"
) {
    val isMalayalamMode: Boolean
        get() = speechLocale.startsWith("ml")

    val hasGeminiApiKey: Boolean
        get() = geminiApiKeyEncrypted.isNotBlank()

    companion object {
        val AVAILABLE_GEMINI_VOICES = listOf("Kore", "Puck", "Aoede", "Zephyr", "Fenrir", "Charon")

        val DEFAULT_WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.google.android.gm",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm"
        )
    }
}

class AppPreferences(private val context: Context) {

    private object Keys {
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val LOW_RAM_SAFE_MODE = booleanPreferencesKey("low_ram_safe_mode")
        val ULTRA_LIGHT_2GB_MODE = booleanPreferencesKey("ultra_light_2gb_mode")
        val ENABLE_GEMMA4_E2B = booleanPreferencesKey("enable_gemma4_e2b_optional")
        val MATCH_THRESHOLD = floatPreferencesKey("match_threshold")
        val HEADPHONES_ONLY = booleanPreferencesKey("headphones_or_bt_only")
        val LOW_BATTERY_REACTION = booleanPreferencesKey("low_battery_reaction")
        val AUTO_ACCEPT_BULK = booleanPreferencesKey("auto_accept_bulk_imports")
        val WATCHED_PACKAGES = stringSetPreferencesKey("watched_packages")
        val HF_TOKEN = stringPreferencesKey("hf_auth_token")
        val SPEECH_LOCALE = stringPreferencesKey("speech_locale")
        val TTS_REPLIES = booleanPreferencesKey("tts_voice_replies")
        val SPEAK_AFTER_CLIP = booleanPreferencesKey("speak_after_dialogue_clip")
        val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_encrypted")
        val GEMINI_VOICE_NAME = stringPreferencesKey("gemini_voice_name")
        val SMS_AUTO_REACT = booleanPreferencesKey("sms_auto_react_enabled")
        val ALWAYS_ON_KEEP_ALIVE = booleanPreferencesKey("always_on_keep_alive_enabled")
        val GMAIL_WATCHER_ENABLED = booleanPreferencesKey("gmail_watcher_enabled")
        val GMAIL_ADDRESS = stringPreferencesKey("gmail_address")
        val GMAIL_ENC_APP_PASSWORD = stringPreferencesKey("gmail_enc_app_password")
        val GMAIL_LAST_SEEN_UID = longPreferencesKey("gmail_last_seen_uid")
        val GMAIL_LAST_STATUS = stringPreferencesKey("gmail_last_status")
    }

    val defaultLowRamMode: Boolean by lazy { isDevice4GbClass(context) }
    val defaultUltraLight2Gb: Boolean by lazy { isDevice2GbClass(context) }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            safTreeUri = prefs[Keys.SAF_TREE_URI] ?: "",
            lowRamSafeMode = prefs[Keys.LOW_RAM_SAFE_MODE] ?: defaultLowRamMode,
            ultraLight2GbMode = prefs[Keys.ULTRA_LIGHT_2GB_MODE] ?: defaultUltraLight2Gb,
            enableGemma4E2bOptional = prefs[Keys.ENABLE_GEMMA4_E2B] ?: false,
            matchThreshold = (prefs[Keys.MATCH_THRESHOLD] ?: 0.35f).coerceIn(0.25f, 0.90f),
            headphonesOrBluetoothOnly = prefs[Keys.HEADPHONES_ONLY] ?: false,
            lowBatteryReactionEnabled = prefs[Keys.LOW_BATTERY_REACTION] ?: true,
            autoAcceptBulkImports = prefs[Keys.AUTO_ACCEPT_BULK] ?: false,
            watchedPackages = prefs[Keys.WATCHED_PACKAGES] ?: UserSettings.DEFAULT_WATCHED_PACKAGES,
            hfAuthToken = prefs[Keys.HF_TOKEN] ?: "",
            speechLocale = prefs[Keys.SPEECH_LOCALE] ?: "ml-IN",
            ttsVoiceRepliesEnabled = prefs[Keys.TTS_REPLIES] ?: true,
            speakAfterDialogueClip = prefs[Keys.SPEAK_AFTER_CLIP] ?: false,
            geminiApiKeyEncrypted = prefs[Keys.GEMINI_API_KEY_ENC] ?: "",
            geminiVoiceName = prefs[Keys.GEMINI_VOICE_NAME] ?: "Kore",
            smsAutoReactEnabled = prefs[Keys.SMS_AUTO_REACT] ?: false,
            alwaysOnKeepAliveEnabled = prefs[Keys.ALWAYS_ON_KEEP_ALIVE] ?: true,
            gmailWatcherEnabled = prefs[Keys.GMAIL_WATCHER_ENABLED] ?: false,
            gmailAddress = prefs[Keys.GMAIL_ADDRESS] ?: "",
            gmailEncryptedAppPassword = prefs[Keys.GMAIL_ENC_APP_PASSWORD] ?: "",
            gmailLastSeenUid = prefs[Keys.GMAIL_LAST_SEEN_UID] ?: 0L,
            gmailLastStatus = prefs[Keys.GMAIL_LAST_STATUS] ?: "Not configured"
        )
    }

    suspend fun snapshot(): UserSettings = settingsFlow.first()

    suspend fun saveGeminiApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        val encrypted = if (trimmed.isNotEmpty()) CredentialCipher.encrypt(trimmed) else ""
        context.dataStore.edit { it[Keys.GEMINI_API_KEY_ENC] = encrypted }
    }

    suspend fun getDecryptedGeminiApiKey(): String {
        val enc = snapshot().geminiApiKeyEncrypted
        return CredentialCipher.decrypt(enc)
    }

    suspend fun setGeminiVoiceName(voice: String) {
        context.dataStore.edit { it[Keys.GEMINI_VOICE_NAME] = voice }
    }

    suspend fun setSmsAutoReactEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMS_AUTO_REACT] = enabled }
    }

    suspend fun setAlwaysOnKeepAliveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALWAYS_ON_KEEP_ALIVE] = enabled }
    }

    suspend fun setSpeakAfterDialogueClip(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPEAK_AFTER_CLIP] = enabled }
    }

    suspend fun setSafTreeUri(uriString: String) {
        context.dataStore.edit { it[Keys.SAF_TREE_URI] = uriString }
    }

    suspend fun setLowRamSafeMode(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.LOW_RAM_SAFE_MODE] = enabled
            if (enabled) it[Keys.ENABLE_GEMMA4_E2B] = false
        }
    }

    suspend fun setUltraLight2GbMode(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.ULTRA_LIGHT_2GB_MODE] = enabled
            if (enabled) {
                it[Keys.LOW_RAM_SAFE_MODE] = true
                it[Keys.ENABLE_GEMMA4_E2B] = false
            }
        }
    }

    suspend fun setEnableGemma4E2b(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_GEMMA4_E2B] = enabled }
    }

    suspend fun setMatchThreshold(threshold: Float) {
        context.dataStore.edit { it[Keys.MATCH_THRESHOLD] = threshold.coerceIn(0.25f, 0.90f) }
    }

    suspend fun setHeadphonesOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HEADPHONES_ONLY] = enabled }
    }

    suspend fun setLowBatteryReaction(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOW_BATTERY_REACTION] = enabled }
    }

    suspend fun setAutoAcceptBulkImports(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ACCEPT_BULK] = enabled }
    }

    suspend fun toggleWatchedPackage(pkg: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.WATCHED_PACKAGES] ?: UserSettings.DEFAULT_WATCHED_PACKAGES).toMutableSet()
            if (enabled) current.add(pkg) else current.remove(pkg)
            prefs[Keys.WATCHED_PACKAGES] = current
        }
    }

    suspend fun setHfToken(token: String) {
        context.dataStore.edit { it[Keys.HF_TOKEN] = token.trim() }
    }

    suspend fun setSpeechLocale(locale: String) {
        context.dataStore.edit { it[Keys.SPEECH_LOCALE] = locale }
    }

    suspend fun setTtsRepliesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TTS_REPLIES] = enabled }
    }

    suspend fun saveGmailCredentials(email: String, rawAppPassword: String) {
        val cleanedEmail = email.trim()
        val cleanedPass = rawAppPassword.replace(" ", "").trim()
        val encrypted = if (cleanedPass.isNotEmpty()) CredentialCipher.encrypt(cleanedPass) else ""
        context.dataStore.edit { prefs ->
            prefs[Keys.GMAIL_ADDRESS] = cleanedEmail
            if (cleanedPass.isNotEmpty()) {
                prefs[Keys.GMAIL_ENC_APP_PASSWORD] = encrypted
            }
            prefs[Keys.GMAIL_LAST_STATUS] = if (cleanedEmail.isNotEmpty()) {
                "Credentials saved for $cleanedEmail"
            } else {
                "Not configured"
            }
        }
    }

    suspend fun getDecryptedGmailAppPassword(): String {
        val enc = snapshot().gmailEncryptedAppPassword
        return CredentialCipher.decrypt(enc)
    }

    suspend fun setGmailWatcherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GMAIL_WATCHER_ENABLED] = enabled }
    }

    suspend fun updateGmailWatcherProgress(lastUid: Long?, status: String) {
        context.dataStore.edit { prefs ->
            if (lastUid != null && lastUid > (prefs[Keys.GMAIL_LAST_SEEN_UID] ?: 0L)) {
                prefs[Keys.GMAIL_LAST_SEEN_UID] = lastUid
            }
            prefs[Keys.GMAIL_LAST_STATUS] = status
        }
    }

    companion object {
        fun isDevice2GbClass(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return totalGb < 2.5
        }

        fun isDevice4GbClass(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return totalGb <= 4.5
        }

        fun isAndroidGoDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return am.isLowRamDevice
        }

        fun getTotalRamMb(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2048L
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            return memInfo.totalMem / (1024L * 1024L)
        }
    }
}
