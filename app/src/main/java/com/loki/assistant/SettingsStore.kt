package com.loki.assistant

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("loki_settings")

data class LokiSettings(
    val listeningEnabled: Boolean = false,
    val language: String = "en-IN",
    val wakeWords: String = "hey loki, hey lowkey, hey low key",
    val wakeLock: Boolean = false,
    val onlyCharging: Boolean = false,
    val minBattery: Int = 15,
    val backendUrl: String = "",
    val backendToken: String = "",
)

object Keys {
    val LISTENING = booleanPreferencesKey("listening")
    val LANGUAGE = stringPreferencesKey("language")
    val WAKE_WORDS = stringPreferencesKey("wake_words")
    val WAKE_LOCK = booleanPreferencesKey("wake_lock")
    val ONLY_CHARGING = booleanPreferencesKey("only_charging")
    val MIN_BATTERY = intPreferencesKey("min_battery")
    val BACKEND_URL = stringPreferencesKey("backend_url")
    val BACKEND_TOKEN = stringPreferencesKey("backend_token")
}

class SettingsStore(private val ctx: Context) {
    val flow: Flow<LokiSettings> = ctx.dataStore.data.map { p ->
        val d = LokiSettings()
        LokiSettings(
            listeningEnabled = p[Keys.LISTENING] ?: d.listeningEnabled,
            language = p[Keys.LANGUAGE] ?: d.language,
            wakeWords = p[Keys.WAKE_WORDS] ?: d.wakeWords,
            wakeLock = p[Keys.WAKE_LOCK] ?: d.wakeLock,
            onlyCharging = p[Keys.ONLY_CHARGING] ?: d.onlyCharging,
            minBattery = p[Keys.MIN_BATTERY] ?: d.minBattery,
            backendUrl = p[Keys.BACKEND_URL] ?: d.backendUrl,
            backendToken = p[Keys.BACKEND_TOKEN] ?: d.backendToken,
        )
    }

    suspend fun current(): LokiSettings = flow.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        ctx.dataStore.edit { it[key] = value }
    }
}
