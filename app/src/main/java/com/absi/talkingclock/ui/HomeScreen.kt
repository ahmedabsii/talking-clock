@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.absi.talkingclock.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.absi.talkingclock.R
import com.absi.talkingclock.data.Settings
import com.absi.talkingclock.data.TimeFormat
import com.absi.talkingclock.data.TimeLabels
import com.absi.talkingclock.setup.SetupStatus
import kotlin.math.roundToInt

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val settings = state.settings
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onResume()
    }
    var showCustomInterval by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { onOpen(Screen.HISTORY) }) { Text(stringResource(R.string.home_history)) }
                    IconButton(onClick = { onOpen(Screen.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val setup = state.setup
            if (setup != null && setup.openSteps > 0) SetupBanner(setup, onClick = { onOpen(Screen.SETUP) })

            PowerCard(
                state = state,
                onToggle = { on ->
                    vm.setEnabled(on, onNeedsPermission = { onOpen(Screen.SETUP) })
                    if (on && context.needsNotificationPermission()) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onPause = vm::pauseForAnHour,
                onResume = vm::resume,
            )
            IntervalCard(settings.intervalMinutes, onSelect = vm::setInterval, onCustom = { showCustomInterval = true })
            FormatCard(settings.format, onSelect = vm::setFormat)
            VolumeCard(settings.volumePercent, onVolume = vm::setVolume, onTest = vm::testNow)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showCustomInterval) {
        CustomIntervalDialog(
            initial = settings.intervalMinutes,
            onDismiss = { showCustomInterval = false },
            onConfirm = {
                vm.setInterval(it)
                showCustomInterval = false
            },
        )
    }
}

@Composable
private fun SetupBanner(setup: SetupStatus, onClick: () -> Unit) {
    val blocking = !setup.exactAlarms
    val colors = if (blocking) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    }
    Card(onClick = onClick, colors = colors) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (blocking) R.string.setup_banner_required else R.string.setup_banner_recommended),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun PowerCard(state: UiState, onToggle: (Boolean) -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val settings = state.settings
    val paused = settings.enabled && settings.pausedUntil > System.currentTimeMillis()
    val colors = if (settings.enabled) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (settings.enabled) R.string.home_on else R.string.home_off),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(statusLine(state, paused), style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = settings.enabled, onCheckedChange = onToggle)
            }
            if (settings.enabled) {
                Spacer(Modifier.height(12.dp))
                if (paused) {
                    FilledTonalButton(onClick = onResume) { Text(stringResource(R.string.home_resume)) }
                } else {
                    OutlinedButton(onClick = onPause) { Text(stringResource(R.string.home_pause)) }
                }
            }
        }
    }
}

@Composable
private fun statusLine(state: UiState, paused: Boolean): String {
    val settings = state.settings
    return when {
        !settings.enabled -> stringResource(R.string.home_off_hint)
        paused -> stringResource(R.string.home_paused, TimeLabels.label(settings.pausedUntil, settings.format))
        state.nextTriggerAt > 0 -> stringResource(R.string.home_next, TimeLabels.label(state.nextTriggerAt, settings.format))
        else -> stringResource(R.string.home_not_scheduled)
    }
}

@Composable
private fun IntervalCard(minutes: Int, onSelect: (Int) -> Unit, onCustom: () -> Unit) {
    SectionCard(title = stringResource(R.string.home_interval)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in Settings.INTERVAL_PRESETS) {
                FilterChip(selected = minutes == preset, onClick = { onSelect(preset) }, label = { Text(intervalLabel(preset)) })
            }
            val isCustom = minutes !in Settings.INTERVAL_PRESETS
            FilterChip(
                selected = isCustom,
                onClick = onCustom,
                label = { Text(if (isCustom) intervalLabel(minutes) else stringResource(R.string.home_custom)) },
            )
        }
        Text(
            stringResource(R.string.home_interval_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.interval_min, minutes)
    minutes % 60 == 0 -> stringResource(R.string.interval_hour, minutes / 60)
    else -> stringResource(R.string.interval_hour_min, minutes / 60, minutes % 60)
}

@Composable
private fun FormatCard(format: TimeFormat, onSelect: (TimeFormat) -> Unit) {
    SectionCard(title = stringResource(R.string.home_format)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TimeFormat.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = format == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, TimeFormat.entries.size),
                ) {
                    Text(stringResource(if (option == TimeFormat.H12) R.string.format_12h else R.string.format_24h))
                }
            }
        }
        Text(
            stringResource(if (format == TimeFormat.H12) R.string.format_12h_example else R.string.format_24h_example),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VolumeCard(percent: Int, onVolume: (Int) -> Unit, onTest: () -> Unit) {
    // Local while dragging; saved once the finger lifts.
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    SectionCard(
        title = stringResource(R.string.home_volume),
        trailing = { Text(stringResource(R.string.home_volume_value, value.roundToInt()), style = MaterialTheme.typography.titleMedium) },
    ) {
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 0f..100f,
            onValueChangeFinished = { onVolume(value.roundToInt()) },
        )
        Text(
            stringResource(R.string.home_volume_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onTest) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.home_test))
        }
    }
}

@Composable
private fun CustomIntervalDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..Settings.MAX_INTERVAL_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.custom_label)) },
                    isError = minutes == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    stringResource(if (minutes == null) R.string.custom_error else R.string.custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { minutes?.let(onConfirm) }, enabled = minutes != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
