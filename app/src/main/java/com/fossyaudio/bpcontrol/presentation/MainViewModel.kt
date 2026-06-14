package com.fossyaudio.bpcontrol.presentation

import androidx.lifecycle.ViewModel
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.ui.AppUiState
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {

    /** Shared platform-agnostic UI state. Compose screens consume this directly. */
    val uiState = AppUiState()

    // --- Forwarding delegates so MainActivity call-sites remain unchanged ---

    val presets: StateFlow<MutableList<Preset>> get() = uiState.presets
    fun updatePresets(list: MutableList<Preset>) = uiState.updatePresets(list)

    val currentPresetIndex: StateFlow<Int> get() = uiState.currentPresetIndex
    fun updateCurrentPresetIndex(v: Int) = uiState.updateCurrentPresetIndex(v)

    val volumePercent: StateFlow<Float> get() = uiState.volumePercent
    fun updateVolumePercent(v: Float) = uiState.updateVolumePercent(v)

    val dacBalLeft: StateFlow<Int> get() = uiState.dacBalLeft
    val dacBalRight: StateFlow<Int> get() = uiState.dacBalRight
    fun updateDacBalance(left: Int, right: Int) = uiState.updateDacBalance(left, right)

    val balanceValue: StateFlow<Float> get() = uiState.balanceValue
    fun updateBalanceValue(v: Float) = uiState.updateBalanceValue(v)

    val filterIndex: StateFlow<Int> get() = uiState.filterIndex
    fun updateFilterIndex(v: Int) = uiState.updateFilterIndex(v)

    val gainModeIndex: StateFlow<Int> get() = uiState.gainModeIndex
    fun updateGainModeIndex(v: Int) = uiState.updateGainModeIndex(v)

    val ampTopoIndex: StateFlow<Int> get() = uiState.ampTopoIndex
    fun updateAmpTopoIndex(v: Int) = uiState.updateAmpTopoIndex(v)

    val micGainDb: StateFlow<Float> get() = uiState.micGainDb
    fun updateMicGainDb(v: Float) = uiState.updateMicGainDb(v)

    val eqBands: StateFlow<List<FilterBand>> get() = uiState.eqBands
    fun updateEqBands(bands: List<FilterBand>) = uiState.updateEqBands(bands)
    fun updateEqBand(index: Int, band: FilterBand) = uiState.updateEqBand(index, band)

    val isConnected: StateFlow<Boolean> get() = uiState.isConnected
    fun updateIsConnected(v: Boolean) = uiState.updateIsConnected(v)

    val isSyncing: StateFlow<Boolean> get() = uiState.isSyncing
    fun updateIsSyncing(v: Boolean) = uiState.updateIsSyncing(v)

    val isMassPushing: StateFlow<Boolean> get() = uiState.isMassPushing
    fun updateIsMassPushing(v: Boolean) = uiState.updateIsMassPushing(v)

    val activeSlot: StateFlow<Byte> get() = uiState.activeSlot
    fun updateActiveSlot(v: Byte) = uiState.updateActiveSlot(v)

    val firmwareVersion: StateFlow<String> get() = uiState.firmwareVersion
    fun updateFirmwareVersion(v: String) = uiState.updateFirmwareVersion(v)

    val lastSentPeqIndex: StateFlow<Int> get() = uiState.lastSentPeqIndex
    val lastSentFilter: StateFlow<FilterBand?> get() = uiState.lastSentFilter
    fun updateLastSentPeq(index: Int, filter: FilterBand?) = uiState.updateLastSentPeq(index, filter)

    fun calculateHeadroomDb(volumePercent: Float, minRawVolume: Int, maxRawVolume: Int): Float =
        uiState.calculateHeadroomDb(volumePercent, minRawVolume, maxRawVolume)

    fun identifyPreset(presets: List<Preset>, hwBands: List<FilterBand>): Int =
        uiState.identifyPreset(presets, hwBands)
}
