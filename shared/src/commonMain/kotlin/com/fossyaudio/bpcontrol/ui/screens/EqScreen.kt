package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.shared.audio.VOL_MAX_RAW
import com.fossyaudio.bpcontrol.shared.audio.VOL_MIN_RAW
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.components.EqBandRow
import com.fossyaudio.bpcontrol.ui.components.BandInspector
import com.fossyaudio.bpcontrol.ui.components.DragWriteThrottle
import com.fossyaudio.bpcontrol.ui.components.EqGraphCanvas
import com.fossyaudio.bpcontrol.ui.theme.Sp

/** One wire frame per this many ms while dragging, matching the volume path's throttle. */
private const val DRAG_WRITE_INTERVAL_MS = 40L

@Composable
fun EqScreen(state: AppUiState, actions: AppActions) {
    val committedBands by state.eqBands.collectAsState()
    val bandsListState = rememberScrollState()

    // View state only, so it lives here rather than in AppUiState. Saveable so both survive
    // rotation.
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var listExpanded by rememberSaveable { mutableStateOf(false) }

    // While a graph drag is live the screen renders from this local copy, so the curve tracks the
    // finger instead of waiting on the round trip through the controller.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragBand by remember { mutableStateOf<FilterBand?>(null) }
    val dragThrottle = remember { DragWriteThrottle(DRAG_WRITE_INTERVAL_MS) }

    val eqBands = remember(committedBands, dragIndex, dragBand) {
        val inFlight = dragBand
        if (dragIndex in committedBands.indices && inFlight != null) {
            committedBands.toMutableList().also { it[dragIndex] = inFlight }
        } else {
            committedBands
        }
    }

    val onBandGainDrag: (Int, Float) -> Unit = { index, gain ->
        val updated = committedBands.getOrNull(index)?.copy(gain = gain)
        if (updated != null) {
            dragIndex = index
            dragBand = updated
            // onBandUpdated writes a frame, latches, schedules a flash save and rewrites the
            // preset to disk. At 60 events a second that floods the queue, so mid-drag writes
            // go through the wire-only path and are throttled on top of that.
            if (dragThrottle.shouldWrite()) actions.onBandDragUpdate(index, updated)
        }
    }

    val onBandGainDragEnd: (Int, Float) -> Unit = { index, gain ->
        committedBands.getOrNull(index)?.let { actions.onBandUpdated(index, it.copy(gain = gain)) }
        dragIndex = -1
        dragBand = null
        dragThrottle.reset()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EqTopSection(
            state = state,
            actions = actions,
            eqBands = eqBands,
            isBandsListScrolling = bandsListState.isScrollInProgress,
            selectedIndex = selectedIndex,
            onBandSelected = { selectedIndex = it },
            onBandGainDrag = onBandGainDrag,
            onBandGainDragEnd = onBandGainDragEnd,
        )

        FilterListDisclosure(
            expanded = listExpanded,
            onToggle = { listExpanded = !listExpanded },
            modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s),
        )

        AnimatedVisibility(
            visible = listExpanded,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Column {
                HorizontalDivider()
                EqBandsList(
                    eqBands = eqBands,
                    state = bandsListState,
                    onBandUpdated = actions.onBandUpdated,
                    selectedIndex = selectedIndex,
                    onBandSelected = { selectedIndex = it },
                )
            }
        }
    }
}

/**
 * Toggles the filter list.
 *
 * Collapsed by default, which is the point of the redesign — the graph and inspector cover
 * everything most sessions need, and ten rows of four fields never fit at 360 dp anyway.
 */
@Composable
private fun FilterListDisclosure(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .then(
                if (expanded) {
                    Modifier.background(Color(0xFF4F378B))
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                }
            )
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor =
            if (expanded) Color(0xFFEADDFF) else MaterialTheme.colorScheme.primary
        Text(
            text = if (expanded) "Hide Filter List" else "Show Filter List",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.padding(start = Sp.xs),
        )
    }
}

@Composable
private fun EqTopSection(
    state: AppUiState,
    actions: AppActions,
    eqBands: List<FilterBand>,
    isBandsListScrolling: Boolean,
    selectedIndex: Int,
    onBandSelected: (Int) -> Unit,
    onBandGainDrag: (Int, Float) -> Unit,
    onBandGainDragEnd: (Int, Float) -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Sp.l, end = Sp.l, top = Sp.l, bottom = Sp.m),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Parametric EQ",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = currentPresetName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // EQ Graph card (dark background)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .padding(Sp.l, 0.dp, Sp.l, Sp.s),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)),
    ) {
        EqGraphCanvas(
            bands = eqBands,
            preampDb = graphHeadroomDb,
            modifier = Modifier.fillMaxSize(),
            selectedIndex = selectedIndex,
            onBandSelected = onBandSelected,
            onBandGainDrag = onBandGainDrag,
            onBandGainDragEnd = onBandGainDragEnd,
        )
    }

    eqBands.getOrNull(selectedIndex)?.let { selectedBand ->
        Spacer(Modifier.height(Sp.s))
        BandInspector(
            band = selectedBand,
            index = selectedIndex,
            enabled = !isSyncing,
            onUpdate = { actions.onBandUpdated(selectedIndex, it) },
            onGainDrag = { onBandGainDrag(selectedIndex, it) },
            onGainDragEnd = { onBandGainDragEnd(selectedIndex, it) },
        )
        Spacer(Modifier.height(Sp.s))
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
    selectedIndex: Int,
    onBandSelected: (Int) -> Unit,
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
                selected = index == selectedIndex,
                onSelect = { onBandSelected(index) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (index < eqBands.lastIndex) HorizontalDivider()
        }
    }
}
