package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher

class MainPresentationCoordinator(
    private val minRawVolume: Int,
    private val maxRawVolume: Int
) {
    fun calculateHeadroomDb(volumePercent: Float): Float {
        val currentRaw = (minRawVolume + (volumePercent / 100.0) * (maxRawVolume - minRawVolume)).toInt()
        val clampedRaw = currentRaw.coerceIn(minRawVolume, maxRawVolume)
        return (maxRawVolume - clampedRaw).toFloat() / 256f
    }

    fun identifyPreset(presets: List<Preset>, hwBands: List<FilterBand>): Int {
        return PresetMatcher.identifyPreset(presets, hwBands)
    }
}
