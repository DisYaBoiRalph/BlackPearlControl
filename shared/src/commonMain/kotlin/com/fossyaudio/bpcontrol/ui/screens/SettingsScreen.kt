package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.shared.audio.BALANCE_DB_LIMIT
import com.fossyaudio.bpcontrol.shared.audio.snapVolDb
import com.fossyaudio.bpcontrol.shared.audio.volDbToPct
import com.fossyaudio.bpcontrol.shared.audio.volPctToDb
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.components.BipolarSlider
import com.fossyaudio.bpcontrol.ui.components.doubleTapToReset
import com.fossyaudio.bpcontrol.ui.theme.Sp
import kotlin.math.roundToInt

// Display strings only. The index is the wire value, so the order must not change.
private val filterOptions = arrayOf(
    "Fast roll-off, linear phase",
    "Fast roll-off, phase compensated",
    "Slow roll-off, linear phase",
    "Slow roll-off, phase compensated",
    "Non-oversampling (NOS)",
)
private val gainOptions = arrayOf("Low", "High")
private val ampOptions = arrayOf("Class H", "Class AB")

private const val MIC_GAIN_DB_LIMIT = 15f

/** Negative favours the left channel — verified by ear against the device. */
private fun balanceLabel(value: Float): String = when {
    value == 0f -> "Centered"
    value < 0f -> "L %.1f dB".format(-value)
    else -> "R %.1f dB".format(value)
}

@Composable
fun SettingsScreen(state: AppUiState, actions: AppActions) {
    val volumePercent by state.volumePercent.collectAsState()
    val balanceValue by state.balanceValue.collectAsState()
    val filterIndex by state.filterIndex.collectAsState()
    val gainModeIndex by state.gainModeIndex.collectAsState()
    val ampTopoIndex by state.ampTopoIndex.collectAsState()
    val micGainDb by state.micGainDb.collectAsState()
    val isSyncing by state.isSyncing.collectAsState()

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
                val shownVol = if (volDragging) localVol else volumePercent
                SliderReadout(
                    label = "Master Volume",
                    value = "%+.1f dB".format(volPctToDb(shownVol)),
                )
                Slider(
                    value = shownVol,
                    onValueChange = { v ->
                        if (!isSyncing) {
                            if (!volDragging) {
                                volDragging = true
                                actions.onVolumeStartDragging()
                            }
                            // Snap on the dB scale, where the step is meaningful, rather than on
                            // the percentage. Not via `steps`, which would draw 25 tick dots.
                            val snapped = volDbToPct(snapVolDb(volPctToDb(v))).coerceIn(0f, 100f)
                            localVol = snapped
                            actions.onVolumeChange(snapped)
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
                Spacer(Modifier.height(Sp.s))
                SliderReadout(
                    label = "Channel Balance",
                    value = balanceLabel(balanceValue),
                )
                BipolarSlider(
                    value = balanceValue,
                    onValueChange = { raw ->
                        val snapped = raw.roundToInt()
                            .coerceIn(-BALANCE_DB_LIMIT, BALANCE_DB_LIMIT)
                            .toFloat()
                        actions.onBalanceChange(snapped)
                    },
                    valueRange = -BALANCE_DB_LIMIT.toFloat()..BALANCE_DB_LIMIT.toFloat(),
                    enabled = !isSyncing,
                    modifier = Modifier.doubleTapToReset(enabled = !isSyncing) {
                        actions.onBalanceChange(0f)
                    },
                )
                Text(
                    text = "Double-tap to center",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
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
                    label = "Amp Class",
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
                Spacer(Modifier.height(Sp.s))
                SliderReadout(
                    label = "Gain",
                    value = "%+.1f dB".format(micGainDb),
                )
                BipolarSlider(
                    value = micGainDb,
                    onValueChange = { actions.onMicGainChange(it) },
                    valueRange = -MIC_GAIN_DB_LIMIT..MIC_GAIN_DB_LIMIT,
                    enabled = !isSyncing,
                )
            }
        }
    }
}

/** Label on the left, current value on the right, sitting directly above its slider. */
@Composable
private fun SliderReadout(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
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
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            expanded = true
                        }
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
