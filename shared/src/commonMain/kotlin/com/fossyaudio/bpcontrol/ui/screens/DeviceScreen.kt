package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.theme.Sp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val connectedTileColor = Color(0xFF123524)
private val connectedIconColor = Color(0xFF8BE0A8)
private val disconnectedTileColor = Color(0xFF3B2530)
private val disconnectedIconColor = Color(0xFFF2B8B5)
private val infoBorderColor = Color(0xFF2A272F)

@Composable
fun DeviceScreen(state: AppUiState, actions: AppActions) {
    val isConnected by state.isConnected.collectAsState()
    val isSyncing by state.isSyncing.collectAsState()
    val firmwareVersion by state.firmwareVersion.collectAsState()
    val activeSlot by state.activeSlot.collectAsState()
    val lastSyncedAt by state.lastSyncedAt.collectAsState()

    var showFactoryResetDialog by remember { mutableStateOf(false) }

    // Only ticks while connected — this screen stays composed even when another tab is showing
    // (see Modifier.tabLayer), so an unconditional ticker would run forever for nothing.
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(isConnected) {
        while (isConnected) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1000L)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Device",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = Sp.l, end = Sp.l, top = Sp.l, bottom = Sp.xl),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Sp.l),
        ) {
            ConnectionCard(
                isConnected = isConnected,
                isSyncing = isSyncing,
                firmwareVersion = firmwareVersion,
                activeSlot = activeSlot,
                statusLine = connectionStatusLine(isConnected, isSyncing, lastSyncedAt, now),
            )

            Spacer(Modifier.height(Sp.l))

            DiagnosticsCard(
                isSyncing = isSyncing,
                isConnected = isConnected,
                onResync = actions.onResync,
                onCopyLog = actions.onCopyLog,
            )

            Spacer(Modifier.height(Sp.l))

            TextButton(
                onClick = { showFactoryResetDialog = true },
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Factory Reset All Settings", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(Sp.l))
        }
    }

    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false },
            title = { Text("Factory Reset") },
            text = { Text("Restore all hardware settings and load the Flat EQ profile?") },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryResetDialog = false
                    actions.onFactoryReset()
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun connectionStatusLine(isConnected: Boolean, isSyncing: Boolean, lastSyncedAt: Long?, now: Long): String = when {
    !isConnected -> "Disconnected"
    isSyncing -> "Syncing…"
    else -> "Connected · synced ${formatRelative(lastSyncedAt, now)}"
}

private fun formatRelative(lastSyncedAt: Long?, now: Long): String {
    if (lastSyncedAt == null) return "never"
    val deltaSeconds = ((now - lastSyncedAt).coerceAtLeast(0)) / 1000
    return when {
        deltaSeconds < 60 -> "$deltaSeconds s ago"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} min ago"
        else -> {
            val dt = Instant.fromEpochMilliseconds(lastSyncedAt).toLocalDateTime(TimeZone.currentSystemDefault())
            "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
        }
    }
}

@Composable
private fun ConnectionCard(
    isConnected: Boolean,
    isSyncing: Boolean,
    firmwareVersion: String,
    activeSlot: Byte,
    statusLine: String,
) {
    val firmwareUnknown = firmwareVersion == "unknown"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(Sp.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isConnected) connectedTileColor else disconnectedTileColor,
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.Usb else Icons.Filled.UsbOff,
                        contentDescription = null,
                        tint = if (isConnected) connectedIconColor else disconnectedIconColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text("Black Pearl", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !isConnected -> MaterialTheme.colorScheme.error
                            isSyncing -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> connectedIconColor
                        },
                    )
                }
            }

            Spacer(Modifier.height(Sp.l))

            InfoRow("Firmware", firmwareVersion, valueColor = if (firmwareUnknown) MaterialTheme.colorScheme.error else null)
            InfoRow("Protocol profile", "CB")
            InfoRow(
                "Vendor / product ID",
                "0x${BlackPearlProtocol.Device.VID.toString(16).uppercase()} / 0x${BlackPearlProtocol.Device.PID.toString(16).uppercase()}",
            )
            InfoRow("HID report ID", "0x${(BlackPearlProtocol.Device.REPORT_ID.toInt() and 0xFF).toString(16).uppercase()}")
            InfoRow("Active slot", "0x${(activeSlot.toInt() and 0xFF).toString(16).uppercase()}")
            InfoRow("EQ bands", BlackPearlProtocol.Frame.BAND_COUNT.toString())

            if (firmwareUnknown) {
                Spacer(Modifier.height(Sp.s))
                Text(
                    text = "Version probe failed — balance channel mapping may be wrong.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = infoBorderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DiagnosticsCard(
    isSyncing: Boolean,
    isConnected: Boolean,
    onResync: () -> Unit,
    onCopyLog: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(Sp.l)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Sp.s))
            Text(
                text = "Re-read all settings from hardware, or copy the last protocol log for a bug report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sp.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                Button(
                    onClick = onResync,
                    enabled = !isSyncing && isConnected,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4F378B),
                        contentColor = Color(0xFFEADDFF),
                    ),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("Re-sync") }
                OutlinedButton(
                    onClick = onCopyLog,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("Copy log") }
            }
        }
    }
}
