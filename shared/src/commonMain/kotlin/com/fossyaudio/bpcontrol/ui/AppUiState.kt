package com.fossyaudio.bpcontrol.ui

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic UI state holder. Shared between Android (wrapped by MainViewModel) and Desktop
 * (used directly in App composable). Contains all StateFlows consumed by shared Compose screens.
 */
class AppUiState {

    private val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    // --- Preset state ---
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()
    fun updatePresets(list: List<Preset>) { _presets.value = list }

    private val _currentPresetIndex = MutableStateFlow(0)
    val currentPresetIndex: StateFlow<Int> = _currentPresetIndex.asStateFlow()
    fun updateCurrentPresetIndex(v: Int) { _currentPresetIndex.value = v }

    // --- Volume / balance ---
    private val _volumePercent = MutableStateFlow(50f)
    val volumePercent: StateFlow<Float> = _volumePercent.asStateFlow()
    fun updateVolumePercent(v: Float) { _volumePercent.value = v }

    private val _dacBalLeft = MutableStateFlow(0)
    val dacBalLeft: StateFlow<Int> = _dacBalLeft.asStateFlow()

    private val _dacBalRight = MutableStateFlow(0)
    val dacBalRight: StateFlow<Int> = _dacBalRight.asStateFlow()
    fun updateDacBalance(left: Int, right: Int) { _dacBalLeft.value = left; _dacBalRight.value = right }

    private val _balanceValue = MutableStateFlow(0f)
    val balanceValue: StateFlow<Float> = _balanceValue.asStateFlow()
    fun updateBalanceValue(v: Float) { _balanceValue.value = v }

    // --- DAC settings dropdowns (index into options arrays, -1 = unread) ---
    private val _filterIndex = MutableStateFlow(-1)
    val filterIndex: StateFlow<Int> = _filterIndex.asStateFlow()
    fun updateFilterIndex(v: Int) { _filterIndex.value = v }

    private val _gainModeIndex = MutableStateFlow(-1)
    val gainModeIndex: StateFlow<Int> = _gainModeIndex.asStateFlow()
    fun updateGainModeIndex(v: Int) { _gainModeIndex.value = v }

    private val _ampTopoIndex = MutableStateFlow(-1)
    val ampTopoIndex: StateFlow<Int> = _ampTopoIndex.asStateFlow()
    fun updateAmpTopoIndex(v: Int) { _ampTopoIndex.value = v }

    // --- Mic gain (dB) ---
    private val _micGainDb = MutableStateFlow(0f)
    val micGainDb: StateFlow<Float> = _micGainDb.asStateFlow()
    fun updateMicGainDb(v: Float) { _micGainDb.value = v }

    // --- EQ bands ---
    private val _eqBands = MutableStateFlow<List<FilterBand>>(
        List(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = defaultFreqs[i]) }
    )
    val eqBands: StateFlow<List<FilterBand>> = _eqBands.asStateFlow()
    fun updateEqBands(bands: List<FilterBand>) { _eqBands.value = bands.map { it.copy() } }
    fun updateEqBand(index: Int, band: FilterBand) {
        val updated = _eqBands.value.toMutableList()
        if (index in updated.indices) updated[index] = band.copy()
        _eqBands.value = updated
    }

    // --- Connection ---
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    fun updateIsConnected(v: Boolean) { _isConnected.value = v }

    // --- Sync / push interlocks ---
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    fun updateIsSyncing(v: Boolean) { _isSyncing.value = v }

    private val _isMassPushing = MutableStateFlow(false)
    val isMassPushing: StateFlow<Boolean> = _isMassPushing.asStateFlow()
    fun updateIsMassPushing(v: Boolean) { _isMassPushing.value = v }

    // --- Hardware bookkeeping ---
    private val _activeSlot = MutableStateFlow<Byte>(BlackPearlProtocol.Frame.END)
    val activeSlot: StateFlow<Byte> = _activeSlot.asStateFlow()
    fun updateActiveSlot(v: Byte) { _activeSlot.value = v }

    private val _firmwareVersion = MutableStateFlow("unknown")
    val firmwareVersion: StateFlow<String> = _firmwareVersion.asStateFlow()
    fun updateFirmwareVersion(v: String) { _firmwareVersion.value = v }

    private val _lastSentPeqIndex = MutableStateFlow(-1)
    val lastSentPeqIndex: StateFlow<Int> = _lastSentPeqIndex.asStateFlow()

    private val _lastSentFilter = MutableStateFlow<FilterBand?>(null)
    val lastSentFilter: StateFlow<FilterBand?> = _lastSentFilter.asStateFlow()
    fun updateLastSentPeq(index: Int, filter: FilterBand?) {
        _lastSentPeqIndex.value = index
        _lastSentFilter.value = filter
    }

    // --- Pure helper functions ---
    fun calculateHeadroomDb(volumePercent: Float, minRawVolume: Int, maxRawVolume: Int): Float {
        val currentRaw = (minRawVolume + (volumePercent / 100.0) * (maxRawVolume - minRawVolume)).toInt()
        val clampedRaw = currentRaw.coerceIn(minRawVolume, maxRawVolume)
        return (maxRawVolume - clampedRaw).toFloat() / 256f
    }
}
