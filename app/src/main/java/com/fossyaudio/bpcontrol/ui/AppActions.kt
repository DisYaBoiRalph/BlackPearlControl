package com.fossyaudio.bpcontrol.ui

import com.fossyaudio.bpcontrol.shared.model.FilterBand

const val ROUTE_SETTINGS = "settings"
const val ROUTE_EQ = "eq"

/**
 * All user-action callbacks that flow from composable screens back to MainActivity
 * where the USB/HID commands are executed.
 */
data class AppActions(
    val onVolumeChange: (Float) -> Unit,
    val onVolumeStartDragging: () -> Unit,
    val onVolumeStopDragging: () -> Unit,
    val onBalanceChange: (Float) -> Unit,
    val onFilterSelected: (Int) -> Unit,
    val onGainModeSelected: (Int) -> Unit,
    val onAmpTopoSelected: (Int) -> Unit,
    val onMicGainChange: (Float) -> Unit,
    val onFactoryReset: () -> Unit,
    val onBandUpdated: (Int, FilterBand) -> Unit,
    val onPresetLoaded: (Int) -> Unit,
    val onPresetSaved: (String) -> Unit,
    val onPresetDeleted: (String) -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
)
