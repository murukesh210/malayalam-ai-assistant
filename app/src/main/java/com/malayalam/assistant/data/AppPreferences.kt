package com.malayalam.assistant.data

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val enableGemma4E2bOptional: Boolean = false,
    val matchThreshold: Float = 0.65f,
    val headphonesOrBluetoothOnly: Boolean = false,
    val lowBatteryReactionEnabled: Boolean = true,
    val autoAcceptBulkImports: Boolean = false,
    val watchedPackages: Set<String> = DEFAULT_WATCHED_PACKAGES,
    val hfAuthToken: String = "",
    val speechLocale: String = "ml-IN"
) {
    companion object {
        val DEFAULT_WATCHED_PACKAGES = setOf(
            "com.google.android.gm",
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "org.telegram.messenger",
            "com.instagram.android"
        )
    }
}

class AppPreferences(private val context: Context) {

    private object Keys {
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val LOW_RAM_SAFE_MODE = booleanPreferencesKey("low_ram_safe_mode")
        val ENABLE_GEMMA4_E2B = booleanPreferencesKey("enable_gemma4_e2b_optional")
        val MATCH_THRESHOLD = floatPreferencesKey("match_threshold")
        val HEADPHONES_ONLY = booleanPreferencesKey("headphones_or_bt_only")
        val LOW_BATTERY_REACTION = booleanPreferencesKey("low_battery_reaction")
        val AUTO_ACCEPT_BULK = booleanPreferencesKey("auto_accept_bulk_imports")
        val WATCHED_PACKAGES = stringSetPreferencesKey("watched_packages")
        val HF_TOKEN = stringPreferencesKey("hf_auth_token")
        val SPEECH_LOCALE = stringPreferencesKey("speech_locale")
    }

    val defaultLowRamMode: Boolean by lazy {
        isDevice4GbClass(context)
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            safTreeUri = prefs[Keys.SAF_TREE_URI] ?: "",
            lowRamSafeMode = prefs[Keys.LOW_RAM_SAFE_MODE] ?: defaultLowRamMode,
            enableGemma4E2bOptional = prefs[Keys.ENABLE_GEMMA4_E2B] ?: false,
            matchThreshold = prefs[Keys.MATCH_THRESHOLD] ?: 0.65f,
            headphonesOrBluetoothOnly = prefs[Keys.HEADPHONES_ONLY] ?: false,
            lowBatteryReactionEnabled = prefs[Keys.LOW_BATTERY_REACTION] ?: true,
            autoAcceptBulkImports = prefs[Keys.AUTO_ACCEPT_BULK] ?: false,
            watchedPackages = prefs[Keys.WATCHED_PACKAGES] ?: UserSettings.DEFAULT_WATCHED_PACKAGES,
            hfAuthToken = prefs[Keys.HF_TOKEN] ?: "",
            speechLocale = prefs[Keys.SPEECH_LOCALE] ?: "ml-IN"
        )
    }

    suspend fun snapshot(): UserSettings = settingsFlow.first()

    suspend fun setSafTreeUri(uriString: String) {
        context.dataStore.edit { it[Keys.SAF_TREE_URI] = uriString }
    }

    suspend fun setLowRamSafeMode(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.LOW_RAM_SAFE_MODE] = enabled
            if (enabled) {
                it[Keys.ENABLE_GEMMA4_E2B] = false
            }
        }
    }

    suspend fun setEnableGemma4E2b(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_GEMMA4_E2B] = enabled }
    }

    suspend fun setMatchThreshold(threshold: Float) {
        context.dataStore.edit { it[Keys.MATCH_THRESHOLD] = threshold.coerceIn(0.35f, 0.92f) }
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

    companion object {
        /**
         * Returns true when device total RAM <= 4.5 GB (4608 MB) via ActivityManager.MemoryInfo.
         */
        fun isDevice4GbClass(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return totalGb <= 4.5
        }

        fun getTotalRamMb(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4096L
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            return memInfo.totalMem / (1024L * 1024L)
        }
    }
}
