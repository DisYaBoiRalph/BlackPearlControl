package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import kotlin.math.roundToInt

private val filterOptions = arrayOf("FAST-LL", "Fast-PC (BEST)", "Slow-LL", "SLOW-PC", "NOS")
private val gainOptions = arrayOf("LOW", "HIGH")
private val ampOptions = arrayOf("CLASS H", "CLASS AB")

@Composable
fun SettingsScreen(state: AppUiState, actions: AppActions) {
    val volumePercent by state.volumePercent.collectAsState()
    val balanceValue by state.balanceValue.collectAsState()
    val filterIndex by state.filterIndex.collectAsState()
    val gainModeIndex by state.gainModeIndex.collectAsState()
    val ampTopoIndex by state.ampTopoIndex.collectAsState()
    val micGainDb by state.micGainDb.collectAsState()
    val isSyncing by state.isSyncing.collectAsState()

    var showFactoryResetDialog by remember { mutableStateOf(false) }

    // Local drag state prevents polling-driven slider snaps while user is interacting
    var volDragging by remember { mutableStateOf(false) }
    var localVol by remember(volumePercent) { mutableStateOf(volumePercent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Black Pearl Control",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        // Output Control card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Output Control", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text("Master Volume", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = if (volDragging) localVol else volumePercent,
                    onValueChange = { v ->
                        if (!isSyncing) {
                            if (!volDragging) {
                                volDragging = true
                                actions.onVolumeStartDragging()
                            }
                            localVol = v
                            actions.onVolumeChange(v)
                        }
                    },
                    onValueChangeFinished = {
                        volDragging = false
                        actions.onVolumeStopDragging()
                    },
                    valueRange = 0f..100f,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Channel Balance", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = balanceValue,
                    onValueChange = { raw ->
                        val snapped = raw.roundToInt().coerceIn(-15, 15).toFloat()
                        actions.onBalanceChange(snapped)
                    },
                    valueRange = -15f..15f,
                    steps = 29,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(isSyncing) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (!isSyncing) actions.onBalanceChange(0f)
                                }
                            )
                        },
                )
            }
        }

        // DAC Settings card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DAC Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                SettingsDropdown(
                    label = "Digital Filter",
                    options = filterOptions,
                    selectedIndex = filterIndex,
                    enabled = !isSyncing,
                    onSelected = actions.onFilterSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                SettingsDropdown(
                    label = "Gain Mode",
                    options = gainOptions,
                    selectedIndex = gainModeIndex,
                    enabled = !isSyncing,
                    onSelected = actions.onGainModeSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                SettingsDropdown(
                    label = "Amp Topology",
                    options = ampOptions,
                    selectedIndex = ampTopoIndex,
                    enabled = !isSyncing,
                    onSelected = actions.onAmpTopoSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Microphone card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Microphone Gain", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = micGainDb,
                    onValueChange = { actions.onMicGainChange(it) },
                    valueRange = -15f..15f,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Factory Reset
        TextButton(
            onClick = { showFactoryResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Factory Reset All Settings",
                color = MaterialTheme.colorScheme.error,
            )
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

@Composable
private fun SettingsDropdown(
    label: String,
    options: Array<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.getOrElse(selectedIndex) { "" }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Text(if (expanded) "▲" else "▼") },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures(onTap = { expanded = true })
                    }
                },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    },
                )
            }
        }
    }
}
