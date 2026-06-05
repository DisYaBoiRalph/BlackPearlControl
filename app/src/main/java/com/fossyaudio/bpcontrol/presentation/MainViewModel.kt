package com.fossyaudio.bpcontrol.presentation

import androidx.lifecycle.ViewModel
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol

class MainViewModel : ViewModel() {
    var presets = mutableListOf<Preset>()
    var currentPresetIndex: Int = 0

    var volumePercent: Float = 50f
    var isSyncing: Boolean = false
    var isMassPushing: Boolean = false
    var dacBalLeft: Int = 0
    var dacBalRight: Int = 0
    var activeSlot: Byte = BlackPearlProtocol.Frame.END
    var firmwareVersion: String = "unknown"
    var lastSentPeqIndex: Int = -1
    var lastSentFilter: FilterBand? = null

    fun calculateHeadroomDb(volumePercent: Float, minRawVolume: Int, maxRawVolume: Int): Float {
        val currentRaw = (minRawVolume + (volumePercent / 100.0) * (maxRawVolume - minRawVolume)).toInt()
        val clampedRaw = currentRaw.coerceIn(minRawVolume, maxRawVolume)
        return (maxRawVolume - clampedRaw).toFloat() / 256f
    }

    fun identifyPreset(presets: List<Preset>, hwBands: List<FilterBand>): Int {
        return PresetMatcher.identifyPreset(presets, hwBands)
    }
}
