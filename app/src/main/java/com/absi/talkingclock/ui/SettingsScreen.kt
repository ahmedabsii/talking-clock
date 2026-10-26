@file:OptIn(ExperimentalMaterial3Api::class)

package com.absi.talkingclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.absi.talkingclock.R
import com.absi.talkingclock.data.MusicMode
import com.absi.talkingclock.data.TimeFormat
import com.absi.talkingclock.data.TimeLabels
import java.time.LocalTime

private enum class QuietEdge { START, END }

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val version = remember { context.versionName() }
    var editing by rememberSaveable { mutableStateOf<QuietEdge?>(null) }
    fun label(minuteOfDay: Int) = TimeLabels.label(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), settings.format)

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.settings_schedule)) {
                SwitchRow(
                    title = stringResource(R.string.settings_quiet),
                    summary = if (settings.quietHoursEnabled) {
                        stringResource(R.string.settings_quiet_summary, label(settings.quietStartMinute), label(settings.quietEndMinute))
                    } else {
                        stringResource(R.string.settings_quiet_off)
                    },
                    checked = settings.quietHoursEnabled,
                    onCheckedChange = vm::setQuietHours,
                )
                if (settings.quietHoursEnabled) {
                    TimeRow(stringResource(R.string.settings_quiet_start), label(settings.quietStartMinute)) { editing = QuietEdge.START }
                    TimeRow(stringResource(R.string.settings_quiet_end), label(settings.quietEndMinute)) { editing = QuietEdge.END }
                }
            }

            SectionCard(stringResource(R.string.settings_sound)) {
                SwitchRow(stringResource(R.string.settings_chime), stringResource(R.string.settings_chime_summary), settings.chime, vm::setChime)
                SwitchRow(stringResource(R.string.settings_boost), stringResource(R.string.settings_boost_summary), settings.boost, vm::setBoost)
                Text(stringResource(R.string.settings_music), style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MusicMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.musicMode == mode,
                            onClick = { vm.setMusicMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, MusicMode.entries.size),
                        ) {
                            Text(stringResource(if (mode == MusicMode.DUCK) R.string.music_duck else R.string.music_pause))
                        }
                    }
                }
            }

            SectionCard(stringResource(R.string.settings_reliability)) {
                SwitchRow(
                    stringResource(R.string.settings_alarm_clock),
                    stringResource(R.string.settings_alarm_clock_summary),
                    settings.alarmClockMode,
                    vm::setAlarmClockMode,
                )
            }

            SectionCard(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_about_text, version), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    editing?.let { edge ->
        val initial = if (edge == QuietEdge.START) settings.quietStartMinute else settings.quietEndMinute
        QuietTimeDialog(
            title = stringResource(if (edge == QuietEdge.START) R.string.settings_quiet_start else R.string.settings_quiet_end),
            initialMinute = initial,
            is24Hour = settings.format == TimeFormat.H24,
            onDismiss = { editing = null },
            onConfirm = { minute ->
                if (edge == QuietEdge.START) vm.setQuietStart(minute) else vm.setQuietEnd(minute)
                editing = null
            },
        )
    }
}

@Composable
private fun TimeRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QuietTimeDialog(title: String, initialMinute: Int, is24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(onClick = { onConfirm(picker.hour * 60 + picker.minute) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
