package com.absi.talkingclock.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.absi.talkingclock.R
import com.absi.talkingclock.oem.OemGuides
import com.absi.talkingclock.setup.SetupChecks

@Composable
fun SetupScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val setup = state.setup
    // The system dialog can only be shown a couple of times; after that, send the user to Settings.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onResume()
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.setup_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
            if (setup == null) return@Column

            SetupItem(
                title = stringResource(R.string.setup_exact_title),
                description = stringResource(R.string.setup_exact_desc),
                done = setup.exactAlarms,
                required = true,
                actionLabel = stringResource(R.string.setup_allow),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startSafely(SetupChecks.exactAlarmSettings(context))
                    }
                },
            )
            SetupItem(
                title = stringResource(R.string.setup_notif_title),
                description = stringResource(R.string.setup_notif_desc),
                done = setup.notifications,
                actionLabel = stringResource(R.string.setup_allow),
                onAction = {
                    if (context.needsNotificationPermission() && !askedForNotifications) {
                        askedForNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startSafely(SetupChecks.notificationSettings(context))
                    }
                },
            )
            SetupItem(
                title = stringResource(R.string.setup_battery_title),
                description = stringResource(R.string.setup_battery_desc),
                done = setup.battery,
                actionLabel = stringResource(R.string.setup_open),
                onAction = { context.startSafely(SetupChecks.appInfo(context)) },
            )
            setup.oemGuide?.let { guide ->
                SetupItem(
                    title = stringResource(R.string.setup_oem_title, guide.brand),
                    description = stringResource(guide.steps),
                    done = setup.oemDone,
                    actionLabel = stringResource(R.string.setup_open),
                    onAction = { OemGuides.open(context, guide) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = setup.oemDone, onValueChange = vm::setOemStepsDone, role = Role.Checkbox),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = setup.oemDone, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_oem_done))
                    }
                }
            }
            Text(
                stringResource(R.string.setup_force_stop_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupItem(
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    required: Boolean = false,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = when {
                    done -> MaterialTheme.colorScheme.primary
                    required -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = stringResource(if (done) R.string.setup_state_done else R.string.setup_state_todo),
                    tint = tint,
                )
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            extra()
            if (!done) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
