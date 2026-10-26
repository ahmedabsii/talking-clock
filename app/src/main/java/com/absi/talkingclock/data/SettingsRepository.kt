package com.absi.talkingclock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

class SettingsRepository(deviceProtectedContext: Context) {

    // The file is built by hand: preferencesDataStoreFile() resolves against applicationContext,
    // which is credential-encrypted storage and unreadable until the first unlock after a reboot.
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(deviceProtectedContext.filesDir, "datastore/settings.preferences_pb") },
    )

    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<Settings> = prefs.map { it.toSettings() }.distinctUntilChanged()
    val runtime: Flow<RuntimeState> = prefs.map { it.toRuntimeState() }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun currentRuntime(): RuntimeState = runtime.first()

    suspend fun update(transform: (Settings) -> Settings): Settings {
        lateinit var updated: Settings
        store.edit { p ->
            updated = transform(p.toSettings())
            p.write(updated)
        }
        return updated
    }

    suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState) {
        store.edit { p -> p.write(transform(p.toRuntimeState())) }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            enabled = this[ENABLED] ?: d.enabled,
            intervalMinutes = (this[INTERVAL] ?: d.intervalMinutes).coerceIn(1, Settings.MAX_INTERVAL_MINUTES),
            volumePercent = (this[VOLUME] ?: d.volumePercent).coerceIn(0, 100),
            boost = this[BOOST] ?: d.boost,
            format = enumOr(this[FORMAT], d.format),
            chime = this[CHIME] ?: d.chime,
            quietHoursEnabled = this[QUIET_ENABLED] ?: d.quietHoursEnabled,
            quietStartMinute = (this[QUIET_START] ?: d.quietStartMinute).coerceIn(0, MINUTES_PER_DAY - 1),
            quietEndMinute = (this[QUIET_END] ?: d.quietEndMinute).coerceIn(0, MINUTES_PER_DAY - 1),
            musicMode = enumOr(this[MUSIC_MODE], d.musicMode),
            alarmClockMode = this[ALARM_CLOCK_MODE] ?: d.alarmClockMode,
            pausedUntil = this[PAUSED_UNTIL] ?: d.pausedUntil,
            oemStepsDone = this[OEM_STEPS_DONE] ?: d.oemStepsDone,
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[ENABLED] = s.enabled
        this[INTERVAL] = s.intervalMinutes
        this[VOLUME] = s.volumePercent
        this[BOOST] = s.boost
        this[FORMAT] = s.format.name
        this[CHIME] = s.chime
        this[QUIET_ENABLED] = s.quietHoursEnabled
        this[QUIET_START] = s.quietStartMinute
        this[QUIET_END] = s.quietEndMinute
        this[MUSIC_MODE] = s.musicMode.name
        this[ALARM_CLOCK_MODE] = s.alarmClockMode
        this[PAUSED_UNTIL] = s.pausedUntil
        this[OEM_STEPS_DONE] = s.oemStepsDone
    }

    private fun Preferences.toRuntimeState() = RuntimeState(
        nextTriggerAt = this[NEXT_TRIGGER_AT] ?: 0L,
        pendingVolumeRestore = this[PENDING_VOLUME_RESTORE] ?: -1,
    )

    private fun MutablePreferences.write(r: RuntimeState) {
        this[NEXT_TRIGGER_AT] = r.nextTriggerAt
        this[PENDING_VOLUME_RESTORE] = r.pendingVolumeRestore
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60

        val ENABLED = booleanPreferencesKey("enabled")
        val INTERVAL = intPreferencesKey("interval_minutes")
        val VOLUME = intPreferencesKey("volume_percent")
        val BOOST = booleanPreferencesKey("boost")
        val FORMAT = stringPreferencesKey("format")
        val CHIME = booleanPreferencesKey("chime")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start_minute")
        val QUIET_END = intPreferencesKey("quiet_end_minute")
        val MUSIC_MODE = stringPreferencesKey("music_mode")
        val ALARM_CLOCK_MODE = booleanPreferencesKey("alarm_clock_mode")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val OEM_STEPS_DONE = booleanPreferencesKey("oem_steps_done")
        val NEXT_TRIGGER_AT = longPreferencesKey("next_trigger_at")
        val PENDING_VOLUME_RESTORE = intPreferencesKey("pending_volume_restore")

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
