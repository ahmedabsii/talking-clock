package com.absi.talkingclock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.absi.talkingclock.audio.AnnouncerService
import com.absi.talkingclock.data.Event
import com.absi.talkingclock.data.MusicMode
import com.absi.talkingclock.data.Settings
import com.absi.talkingclock.data.TimeFormat
import com.absi.talkingclock.graph
import com.absi.talkingclock.setup.SetupChecks
import com.absi.talkingclock.setup.SetupStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

data class UiState(
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val nextTriggerAt: Long = 0,
    val setup: SetupStatus? = null,
    val events: List<Event> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.graph
    private val setup = MutableStateFlow<SetupStatus?>(null)

    val state: StateFlow<UiState> = combine(
        graph.settings.settings,
        graph.settings.runtime,
        setup,
        graph.eventLog.version,
    ) { settings, runtime, setup, _ ->
        UiState(
            loaded = true,
            settings = settings,
            nextTriggerAt = runtime.nextTriggerAt,
            setup = setup?.copy(oemDone = settings.oemStepsDone),
            events = graph.eventLog.recent(),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Called whenever the app comes to the foreground. */
    fun onResume() {
        viewModelScope.launch {
            val settings = graph.settings.current()
            setup.value = SetupChecks.status(getApplication(), settings.oemStepsDone)
            // The app is visible, so Android 17 allows volume changes: undo one a crash left behind.
            if (!AnnouncerService.isRunning) graph.volume.restore()
            // Re-arms after a Force stop, or anything else that silently dropped the alarm.
            if (settings.enabled) graph.controller.refresh()
        }
    }

    fun setEnabled(on: Boolean, onNeedsPermission: () -> Unit) {
        viewModelScope.launch {
            if (!graph.controller.setEnabled(on)) onNeedsPermission()
        }
    }

    fun setInterval(minutes: Int) = change { it.copy(intervalMinutes = minutes.coerceIn(1, Settings.MAX_INTERVAL_MINUTES)) }
    fun setFormat(format: TimeFormat) = change { it.copy(format = format) }
    fun setVolume(percent: Int) = change { it.copy(volumePercent = percent.coerceIn(0, 100)) }
    fun setBoost(on: Boolean) = change { it.copy(boost = on) }
    fun setChime(on: Boolean) = change { it.copy(chime = on) }
    fun setMusicMode(mode: MusicMode) = change { it.copy(musicMode = mode) }
    fun setAlarmClockMode(on: Boolean) = change { it.copy(alarmClockMode = on) }
    fun setQuietHours(on: Boolean) = change { it.copy(quietHoursEnabled = on) }
    fun setQuietStart(minuteOfDay: Int) = change { it.copy(quietStartMinute = minuteOfDay) }
    fun setQuietEnd(minuteOfDay: Int) = change { it.copy(quietEndMinute = minuteOfDay) }
    fun setOemStepsDone(done: Boolean) = change { it.copy(oemStepsDone = done) }

    fun pauseForAnHour() {
        viewModelScope.launch { graph.controller.pauseFor(Duration.ofHours(1)) }
    }

    fun resume() {
        viewModelScope.launch { graph.controller.resume() }
    }

    fun testNow() = AnnouncerService.test(getApplication())

    private fun change(transform: (Settings) -> Settings) {
        viewModelScope.launch { graph.controller.change(transform) }
    }
}
