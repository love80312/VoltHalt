package com.im_atp.volthalt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        // Max battery alarm
        private val ALARM_ENABLED        = booleanPreferencesKey("alarm_enabled")
        val TARGET_PERCENTAGE            = intPreferencesKey("target_percentage")
        val VIBRATION_ENABLED            = booleanPreferencesKey("vibration_enabled")
        private val RINGTONE_URI         = stringPreferencesKey("ringtone_uri")
        val ALARM_VOLUME                 = intPreferencesKey("alarm_volume")

        // Low battery alarm
        private val LOW_ALARM_ENABLED    = booleanPreferencesKey("low_alarm_enabled")
        val LOW_TARGET_PERCENTAGE        = intPreferencesKey("low_target_percentage")
        val LOW_VIBRATION_ENABLED        = booleanPreferencesKey("low_vibration_enabled")
        private val LOW_RINGTONE_URI     = stringPreferencesKey("low_ringtone_uri")
        val LOW_ALARM_VOLUME             = intPreferencesKey("low_alarm_volume")

        // Independent master switch for background monitoring.
        // Missing values are migrated logically from the existing alarm settings.
        private val MONITORING_ENABLED   = booleanPreferencesKey("monitoring_enabled")

        // First-run setup
        private val SETUP_COMPLETED      = booleanPreferencesKey("setup_completed")

        // Appearance — "system" | "light" | "dark" | "comfort"
        val THEME_MODE                   = stringPreferencesKey("theme_mode")

        // Sound type — "ringtone" | "tts"
        val MAX_SOUND_TYPE               = stringPreferencesKey("max_sound_type")
        val MAX_TTS_TEXT                 = stringPreferencesKey("max_tts_text")
        val LOW_SOUND_TYPE               = stringPreferencesKey("low_sound_type")
        val LOW_TTS_TEXT                 = stringPreferencesKey("low_tts_text")
    }

    // Max battery alarm flows
    val alarmEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[ALARM_ENABLED] ?: false }
    val targetPercentageFlow: Flow<Int>  = context.dataStore.data.map { it[TARGET_PERCENTAGE] ?: 80 }
    val vibrationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[VIBRATION_ENABLED] ?: true }
    val ringtoneUriFlow: Flow<String?>   = context.dataStore.data.map { it[RINGTONE_URI] }
    val alarmVolumeFlow: Flow<Int>       = context.dataStore.data.map { it[ALARM_VOLUME] ?: 80 }

    // Low battery alarm flows
    val lowAlarmEnabledFlow: Flow<Boolean>    = context.dataStore.data.map { it[LOW_ALARM_ENABLED] ?: false }
    val lowTargetPercentageFlow: Flow<Int>    = context.dataStore.data.map { it[LOW_TARGET_PERCENTAGE] ?: 20 }
    val lowVibrationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[LOW_VIBRATION_ENABLED] ?: true }
    val lowRingtoneUriFlow: Flow<String?>     = context.dataStore.data.map { it[LOW_RINGTONE_URI] }
    val lowAlarmVolumeFlow: Flow<Int>         = context.dataStore.data.map { it[LOW_ALARM_VOLUME] ?: 80 }

    // Independent monitoring switch.
    // For existing installations without this key, retain the previous behavior:
    // monitoring is considered enabled when either alarm was enabled.
    val monitoringEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MONITORING_ENABLED]
            ?: ((preferences[ALARM_ENABLED] ?: false) || (preferences[LOW_ALARM_ENABLED] ?: false))
    }

    // Setup flow
    val setupCompletedFlow: Flow<Boolean> = context.dataStore.data.map { it[SETUP_COMPLETED] ?: false }

    // Appearance flow
    val themeModeFlow: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "system" }

    // TTS flows use localized defaults, while preserving user-customized messages.
    val maxSoundTypeFlow: Flow<String> = context.dataStore.data.map { it[MAX_SOUND_TYPE] ?: "ringtone" }
    val maxTtsTextFlow: Flow<String>   = context.dataStore.data.map { it[MAX_TTS_TEXT] ?: context.getString(R.string.default_max_tts) }
    val lowSoundTypeFlow: Flow<String> = context.dataStore.data.map { it[LOW_SOUND_TYPE] ?: "ringtone" }
    val lowTtsTextFlow: Flow<String>   = context.dataStore.data.map { it[LOW_TTS_TEXT] ?: context.getString(R.string.default_low_tts) }

    // Initialize the independent monitoring switch once for existing installations.
    // Older versions used the alarm settings to determine whether monitoring was active.
    suspend fun initializeMonitoringEnabledIfNeeded() {
        context.dataStore.edit { preferences ->
            if (MONITORING_ENABLED !in preferences) {
                preferences[MONITORING_ENABLED] =
                    (preferences[ALARM_ENABLED] ?: false) ||
                    (preferences[LOW_ALARM_ENABLED] ?: false)
            }
        }
    }

    // Max battery alarm setters
    suspend fun setAlarmEnabled(enabled: Boolean)       { context.dataStore.edit { it[ALARM_ENABLED] = enabled } }
    suspend fun setTargetPercentage(percentage: Int)    { context.dataStore.edit { it[TARGET_PERCENTAGE] = percentage } }
    suspend fun setVibrationEnabled(enabled: Boolean)   { context.dataStore.edit { it[VIBRATION_ENABLED] = enabled } }
    suspend fun setRingtoneUri(uriString: String)       { context.dataStore.edit { it[RINGTONE_URI] = uriString } }
    suspend fun setAlarmVolume(volume: Int)             { context.dataStore.edit { it[ALARM_VOLUME] = volume.coerceIn(0, 100) } }

    // Low battery alarm setters
    suspend fun setLowAlarmEnabled(enabled: Boolean)    { context.dataStore.edit { it[LOW_ALARM_ENABLED] = enabled } }
    suspend fun setLowTargetPercentage(percentage: Int) { context.dataStore.edit { it[LOW_TARGET_PERCENTAGE] = percentage } }
    suspend fun setLowVibrationEnabled(enabled: Boolean){ context.dataStore.edit { it[LOW_VIBRATION_ENABLED] = enabled } }
    suspend fun setLowRingtoneUri(uriString: String)    { context.dataStore.edit { it[LOW_RINGTONE_URI] = uriString } }
    suspend fun setLowAlarmVolume(volume: Int)          { context.dataStore.edit { it[LOW_ALARM_VOLUME] = volume.coerceIn(0, 100) } }

    // Monitoring setter
    suspend fun setMonitoringEnabled(enabled: Boolean)  { context.dataStore.edit { it[MONITORING_ENABLED] = enabled } }

    // Setup setter
    suspend fun setSetupCompleted(completed: Boolean)   { context.dataStore.edit { it[SETUP_COMPLETED] = completed } }

    // Appearance setter
    suspend fun setThemeMode(mode: String)              { context.dataStore.edit { it[THEME_MODE] = mode } }

    // TTS setters
    suspend fun setMaxSoundType(type: String)           { context.dataStore.edit { it[MAX_SOUND_TYPE] = type } }
    suspend fun setMaxTtsText(text: String)              { context.dataStore.edit { it[MAX_TTS_TEXT] = text } }
    suspend fun setLowSoundType(type: String)           { context.dataStore.edit { it[LOW_SOUND_TYPE] = type } }
    suspend fun setLowTtsText(text: String)              { context.dataStore.edit { it[LOW_TTS_TEXT] = text } }
}
