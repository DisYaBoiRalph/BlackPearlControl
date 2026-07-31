package com.fossyaudio.bpcontrol.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fossyaudio.bpcontrol.ui.screens.DeviceScreen
import com.fossyaudio.bpcontrol.ui.screens.EqScreen
import com.fossyaudio.bpcontrol.ui.screens.PresetsScreen
import com.fossyaudio.bpcontrol.ui.screens.SettingsScreen

@Composable
fun MainScreen(state: AppUiState, actions: AppActions) {
    var currentRoute by rememberSaveable { mutableStateOf(ROUTE_SETTINGS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == ROUTE_SETTINGS,
                    onClick = { currentRoute = ROUTE_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_EQ,
                    onClick = { currentRoute = ROUTE_EQ },
                    icon = { Icon(Icons.Filled.Equalizer, contentDescription = "PEQ") },
                    label = { Text("PEQ") },
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_PRESETS,
                    onClick = { currentRoute = ROUTE_PRESETS },
                    icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = "Presets") },
                    label = { Text("Presets") },
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_DEVICE,
                    onClick = { currentRoute = ROUTE_DEVICE },
                    icon = { Icon(Icons.Filled.Memory, contentDescription = "Device") },
                    label = { Text("Device") },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Keep every tab mounted so switching is instantaneous and never re-initializes PEQ.
            // Four tabs is the ceiling for this approach — a fifth should move to a nav graph.
            Box(modifier = Modifier.tabLayer(visible = currentRoute == ROUTE_SETTINGS)) {
                SettingsScreen(state = state, actions = actions)
            }
            Box(modifier = Modifier.tabLayer(visible = currentRoute == ROUTE_EQ)) {
                EqScreen(state = state, actions = actions)
            }
            Box(modifier = Modifier.tabLayer(visible = currentRoute == ROUTE_PRESETS)) {
                PresetsScreen(state = state, actions = actions)
            }
            Box(modifier = Modifier.tabLayer(visible = currentRoute == ROUTE_DEVICE)) {
                DeviceScreen(state = state, actions = actions)
            }
        }
    }
}

private fun Modifier.tabLayer(visible: Boolean): Modifier {
    val semanticsModifier = if (visible) Modifier else Modifier.clearAndSetSemantics { }
    return this
        .fillMaxSize()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            if (visible) {
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }
            } else {
                // Keep content composed/measured for warm state, but do not place it in layout.
                layout(0, 0) {}
            }
        }
        .then(semanticsModifier)
}
