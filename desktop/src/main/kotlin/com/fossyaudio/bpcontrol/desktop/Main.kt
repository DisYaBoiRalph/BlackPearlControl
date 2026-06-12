package com.fossyaudio.bpcontrol.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(700.dp, 520.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Black Pearl Control",
        state = windowState
    ) {
        App()
    }
}

@Composable
fun App() {
    val presetStorage = remember { DesktopPresetStorage() }
    var presets by remember { mutableStateOf(presetStorage.load()) }
    var statusMessage by remember { mutableStateOf("Desktop MVP running (transport scaffold active)") }
    var selectedIndex by remember { mutableStateOf(-1) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateName by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Status: $statusMessage",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Presets: ${presets.size}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    itemsIndexed(presets) { index, preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .background(
                                    if (selectedIndex == index)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "preamp ${"%.1f".format(preset.preamp)} dB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        presets = presetStorage.load()
                        selectedIndex = -1
                        statusMessage = "Presets reloaded"
                    }) {
                        Text("Reload")
                    }

                    Button(onClick = {
                        presetStorage.save(presets)
                        statusMessage = "Presets saved"
                    }) {
                        Text("Save")
                    }

                    Button(
                        enabled = selectedIndex in presets.indices,
                        onClick = {
                            duplicateName = "${presets[selectedIndex].name} Copy"
                            showDuplicateDialog = true
                        }
                    ) {
                        Text("Duplicate")
                    }

                    Button(
                        enabled = selectedIndex in presets.indices,
                        onClick = {
                            val match = PresetMatcher.identifyPreset(presets, presets[selectedIndex].bands)
                            statusMessage = if (match >= 0) {
                                "Matcher resolved '${presets[match].name}'"
                            } else {
                                "Matcher found no match"
                            }
                        }
                    ) {
                        Text("Identify Selected")
                    }
                }
            }
        }

        if (showDuplicateDialog) {
            AlertDialog(
                onDismissRequest = { showDuplicateDialog = false },
                title = { Text("New Preset Name") },
                text = {
                    OutlinedTextField(
                        value = duplicateName,
                        onValueChange = { duplicateName = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = duplicateName.isNotBlank(),
                        onClick = {
                            val source = presets[selectedIndex]
                            val copy = source.copy(
                                name = duplicateName.trim(),
                                bands = source.bands.map { it.copy() }.toMutableList()
                            )
                            presets = (presets + copy).toMutableList()
                            statusMessage = "Added preset '${duplicateName.trim()}'"
                            showDuplicateDialog = false
                        }
                    ) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showDuplicateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
