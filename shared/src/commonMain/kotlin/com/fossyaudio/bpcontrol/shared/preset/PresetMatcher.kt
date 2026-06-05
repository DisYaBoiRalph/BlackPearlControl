package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import kotlin.math.abs

object PresetMatcher {
    fun identifyPreset(presets: List<Preset>, hwBands: List<FilterBand>): Int {
        for (i in presets.indices) {
            if (presets[i].name == "None") continue
            var match = true

            for (b in 0 until 10) {
                val hw = hwBands[b]
                val saved = presets[i].bands[b]

                val hwGain = if (hw.enabled) hw.gain else 0f
                val savedGain = if (saved.enabled) saved.gain else 0f
                if (abs(hwGain - savedGain) > 0.1f) {
                    match = false
                    break
                }

                val hwType = if (abs(hwGain) < 0.1f) FilterType.PK else hw.type
                val savedType = if (abs(savedGain) < 0.1f) FilterType.PK else saved.type
                if (hwType != savedType) {
                    match = false
                    break
                }

                if (abs(saved.freq - hw.freq) > 5) {
                    match = false
                    break
                }

                if (abs(saved.q - hw.q) > 0.2f) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
