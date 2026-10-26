package com.absi.talkingclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.absi.talkingclock.R
import com.absi.talkingclock.data.Event
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.TimeFormat
import com.absi.talkingclock.data.TimeLabels
import java.util.Locale

@Composable
fun HistoryScreen(state: UiState, onBack: () -> Unit) {
    val format = state.settings.format
    Scaffold(topBar = { BackTopBar(stringResource(R.string.history_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Summary(state.events) }
            if (state.events.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.events) { EventRow(it, format) }
        }
    }
}

@Composable
private fun Summary(events: List<Event>) {
    val since = System.currentTimeMillis() - DAY_MS
    val lastDay = events.filter { it.at >= since }
    // Scheduled announcements carry a delay; "Test now" ones don't and aren't counted.
    val delays = lastDay.filter { it.kind == EventKind.SPOKEN }.mapNotNull { it.delayMs }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.history_summary,
                    delays.size,
                    lastDay.count { it.kind == EventKind.SKIPPED },
                    lastDay.count { it.kind == EventKind.PROBLEM },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (delays.isNotEmpty()) {
                Text(
                    stringResource(R.string.history_avg_delay, seconds(delays.average().toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: Event, format: TimeFormat) {
    val color = when (event.kind) {
        EventKind.SPOKEN -> MaterialTheme.colorScheme.primary
        EventKind.SKIPPED -> MaterialTheme.colorScheme.outline
        EventKind.PROBLEM -> MaterialTheme.colorScheme.error
        EventKind.INFO -> MaterialTheme.colorScheme.secondary
    }
    val title = when (event.kind) {
        EventKind.SPOKEN -> stringResource(R.string.history_spoken, event.message)
        EventKind.SKIPPED -> stringResource(R.string.history_skipped, event.message)
        else -> event.message
    }
    val delay = event.delayMs?.let { " · " + stringResource(R.string.history_delay, seconds(it)) }.orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Spacer(
            Modifier
                .padding(top = 7.dp)
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                TimeLabels.dayAndTime(event.at, format) + delay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun seconds(millis: Long): String = String.format(Locale.getDefault(), "%.1f", millis / 1000.0)

private const val DAY_MS = 24 * 60 * 60 * 1000L
