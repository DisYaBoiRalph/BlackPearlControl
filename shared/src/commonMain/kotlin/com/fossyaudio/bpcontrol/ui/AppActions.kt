package com.fossyaudio.bpcontrol.ui

import com.fossyaudio.bpcontrol.shared.model.FilterBand

const val ROUTE_SETTINGS = "settings"
const val ROUTE_EQ = "eq"
const val ROUTE_PRESETS = "presets"
const val ROUTE_DEVICE = "device"

/**
 * All user-action callbacks that flow from composable screens back to the platform controller
 * (MainActivity on Android, DesktopController on Desktop) where HID commands are executed.
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
    /** Commits a band: writes it, latches, saves to flash and persists the preset. */
    val onBandUpdated: (Int, FilterBand) -> Unit,
    /**
     * Writes a band mid-drag. Wire only — no latch, no flash, no preset write, because a drag
     * fires this many times a second. [onBandUpdated] commits the final value on release.
     */
    val onBandDragUpdate: (Int, FilterBand) -> Unit,
    val onPresetLoaded: (Int) -> Unit,
    val onPresetSaved: (String) -> Unit,
    val onPresetDeleted: (String) -> Unit,
    val onPresetRenamed: (Int, String) -> Unit,
    val onPresetDuplicated: (Int) -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onCopyLog: () -> Unit,
    val onResync: () -> Unit,
)
