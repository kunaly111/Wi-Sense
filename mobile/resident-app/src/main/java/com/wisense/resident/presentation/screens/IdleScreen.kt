package com.wisense.resident.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wisense.resident.domain.model.BleEvent
import com.wisense.resident.domain.model.ConnectionState
import com.wisense.resident.presentation.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen for >99% of the app's life (§8): monitoring status, BLE link
 * state, and the trigger event log — "ALERT received" shows here when the
 * physical fall trigger fires on the RX board (Phase 1 milestone).
 */
@Composable
fun IdleScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val log by viewModel.eventLog.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Wi-Sense monitor",
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        Spacer(Modifier.height(12.dp))

        StatusCard(state = state, onRetry = viewModel::retryNow)

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Event log",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        if (log.isEmpty()) {
            Text(
                text = "Nothing yet — trigger a fall on the sensor to see ALERT arrive here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(log) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState, onRetry: () -> Unit) {
    val (label, detail, color) = when (state) {
        is ConnectionState.Idle -> Triple("Starting", "monitor service warming up", Color.Gray)
        is ConnectionState.Scanning -> Triple("Monitoring active", "looking for the room sensor", Color(0xFF4A7BA6))
        is ConnectionState.Connecting -> Triple("Monitoring active", "connecting to ${state.deviceAddress}", Color(0xFF4A7BA6))
        is ConnectionState.Connected -> Triple(
            "Monitoring active",
            "sensor connected (${state.deviceAddress})",
            Color(0xFF2E7D32),
        )
        is ConnectionState.Retrying -> Triple(
            "Sensor disconnected",
            "retry ${state.attempt} in ${state.nextRetryInSeconds} s",
            Color(0xFFF9A825),
        )
        is ConnectionState.GiveUp -> Triple(
            "Sensor disconnected",
            "could not reconnect — check the sensor and retry",
            Color(0xFFB3261E),
        )
        is ConnectionState.Unavailable -> Triple("Cannot monitor", state.reason, Color(0xFFB3261E))
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleLarge, color = color)
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is ConnectionState.GiveUp || state is ConnectionState.Unavailable) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun EventRow(event: BleEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val color = when (event.kind) {
        BleEvent.Kind.ALERT -> Color(0xFFB3261E)
        BleEvent.Kind.CANCEL -> Color(0xFF2E7D32)
        BleEvent.Kind.CONNECTED -> Color(0xFF2E7D32)
        BleEvent.Kind.DISCONNECTED -> Color(0xFFF9A825)
        BleEvent.Kind.ERROR -> Color(0xFFB3261E)
        BleEvent.Kind.IDLE -> Color.Gray
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeFormat.format(Date(event.timestampMillis)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = event.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
