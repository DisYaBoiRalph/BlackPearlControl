package com.fossyaudio.bpcontrol.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.components.PresetRow
import com.fossyaudio.bpcontrol.ui.theme.Sp

@Composable
fun PresetsScreen(state: AppUiState, actions: AppActions) {
    val presets by state.presets.collectAsState()
    val eqBands by state.eqBands.collectAsState()
    val isSyncing by state.isSyncing.collectAsState()
    val isMassPushing by state.isMassPushing.collectAsState()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Which row is "active" tracks the hardware, not the tap order — a hardware read or a hand
    // edit on PEQ can move it out from under whatever was tapped last.
    val activeIndex by remember(presets, eqBands) {
        derivedStateOf { PresetMatcher.identifyPreset(presets, eqBands) }
    }

    val filtered = remember(presets, query) {
        presets.withIndex().filter { (_, p) -> query.isBlank() || p.name.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().alpha(if (isSyncing) 0.38f else 1f)) {
        Column(modifier = Modifier.padding(start = Sp.l, end = Sp.l, top = Sp.l, bottom = Sp.xl)) {
            Text("EQ Presets", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${presets.size} presets · stored on device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isMassPushing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Sp.l, end = Sp.l, bottom = Sp.m),
            horizontalArrangement = Arrangement.spacedBy(Sp.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = actions.onImport,
                enabled = !isSyncing,
                modifier = Modifier.weight(1f),
            ) {
                Text("Import from file")
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = Sp.xs).size(18.dp),
                )
            }
            IconButton(
                onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                enabled = !isSyncing,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search presets")
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search presets") },
                singleLine = true,
                enabled = !isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Sp.l)
                    .padding(bottom = Sp.m),
            )
        }

        if (filtered.isEmpty() && query.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "No presets match \"$query\"",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = Sp.m),
            ) {
                items(filtered, key = { it.index }) { (index, preset) ->
                    PresetRow(
                        preset = preset,
                        active = if (activeIndex == -1) preset.name == "None" else index == activeIndex,
                        enabled = !isSyncing,
                        onLoad = { actions.onPresetLoaded(index) },
                    )
                }
            }
        }
    }
}
