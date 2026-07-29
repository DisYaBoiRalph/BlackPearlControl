package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.shared.audio.VOL_MAX_RAW
import com.fossyaudio.bpcontrol.shared.audio.VOL_MIN_RAW
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.components.EqBandRow
import com.fossyaudio.bpcontrol.ui.components.EqGraphCanvas

@Composable
fun EqScreen(state: AppUiState, actions: AppActions) {
    val eqBands by state.eqBands.collectAsState()
    val bandsListState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        EqTopSection(
            state = state,
            actions = actions,
            eqBands = eqBands,
            isBandsListScrolling = bandsListState.isScrollInProgress,
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        EqBandsList(
            eqBands = eqBands,
            state = bandsListState,
            onBandUpdated = actions.onBandUpdated,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EqTopSection(
    state: AppUiState,
    actions: AppActions,
    eqBands: List<FilterBand>,
    isBandsListScrolling: Boolean,
) {
    val volumePercent by state.volumePercent.collectAsState()
    val presets by state.presets.collectAsState()
    val currentPresetIndex by state.currentPresetIndex.collectAsState()
    val isSyncing by state.isSyncing.collectAsState()

    val headroomDb = state.calculateHeadroomDb(volumePercent, VOL_MIN_RAW, VOL_MAX_RAW)
    val currentPresetName = presets.getOrNull(currentPresetIndex)?.name ?: "—"

    var presetMenuExpanded by remember { mutableStateOf(false) }
    var showNewPresetDialog by remember { mutableStateOf(false) }
    var showDeletePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    // Volume drag interlock (same as SettingsScreen)
    var volDragging by remember { mutableStateOf(false) }
    var localVol by remember(volumePercent) { mutableStateOf(volumePercent) }

    var frozenHeadroomDb by remember { mutableFloatStateOf(headroomDb) }
    LaunchedEffect(headroomDb, isBandsListScrolling) {
        if (!isBandsListScrolling) {
            frozenHeadroomDb = headroomDb
        }
    }
    val graphHeadroomDb = if (isBandsListScrolling) frozenHeadroomDb else headroomDb

    Text(
        text = "Parametric EQ",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    )

    // EQ Graph card (dark background)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(16.dp, 0.dp, 16.dp, 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)),
    ) {
        EqGraphCanvas(
            bands = eqBands,
            preampDb = graphHeadroomDb,
            modifier = Modifier.fillMaxSize(),
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Master volume (EQ page ceiling)
        Text("Master Volume (Digital Ceiling)", style = MaterialTheme.typography.bodyMedium)
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

        // Import / Export / Presets row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = actions.onImport) { Text("Import AutoEQ") }
            TextButton(onClick = actions.onExport) { Text("Export Preset") }

            // Presets button with dropdown
            TextButton(onClick = { presetMenuExpanded = true }) {
                Text(currentPresetName)
            }
            DropdownMenu(
                expanded = presetMenuExpanded,
                onDismissRequest = { presetMenuExpanded = false },
            ) {
                presets.forEachIndexed { index, preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            presetMenuExpanded = false
                            actions.onPresetLoaded(index)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Save as New Preset") },
                    onClick = {
                        presetMenuExpanded = false
                        newPresetName = ""
                        showNewPresetDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete a Preset", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        presetMenuExpanded = false
                        showDeletePresetDialog = true
                    },
                )
            }
        }
    }

    // New preset dialog
    if (showNewPresetDialog) {
        AlertDialog(
            onDismissRequest = { showNewPresetDialog = false },
            title = { Text("New Preset") },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newPresetName.isNotBlank(),
                    onClick = {
                        actions.onPresetSaved(newPresetName.trim())
                        showNewPresetDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPresetDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Delete preset dialog
    if (showDeletePresetDialog) {
        val deletable = presets.filter { it.name != "Flat" && it.name != "None" }
        if (deletable.isEmpty()) {
            showDeletePresetDialog = false
        } else {
            AlertDialog(
                onDismissRequest = { showDeletePresetDialog = false },
                title = { Text("Delete Preset") },
                text = {
                    Column {
                        deletable.forEach { preset ->
                            TextButton(
                                onClick = {
                                    showDeletePresetDialog = false
                                    actions.onPresetDeleted(preset.name)
                                },
                            ) { Text(preset.name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDeletePresetDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun EqBandsList(
    eqBands: List<FilterBand>,
    state: ScrollState,
    onBandUpdated: (Int, FilterBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(state),
    ) {
        eqBands.forEachIndexed { index, band ->
            val onUpdate: (FilterBand) -> Unit = remember(index, onBandUpdated) {
                { updated -> onBandUpdated(index, updated) }
            }
            EqBandRow(
                band = band,
                onUpdate = onUpdate,
                modifier = Modifier.fillMaxWidth(),
            )
            if (index < eqBands.lastIndex) HorizontalDivider()
        }
    }
}
