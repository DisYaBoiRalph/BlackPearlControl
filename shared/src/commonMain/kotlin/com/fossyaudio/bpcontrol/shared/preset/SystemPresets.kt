package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol

val DEFAULT_BAND_FREQS = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/**
 * Guarantees "Flat" (index 0) and "None" (last) exist in the library. "Flat" is a real preset;
 * "None" is the live-hardware slot every reconnect or unmatched read writes into.
 */
fun ensureSystemPresets(presets: List<Preset>): List<Preset> {
    val result = presets.toMutableList()

    if (result.none { it.name == "Flat" }) {
        val flatBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            FilterBand(freq = DEFAULT_BAND_FREQS[i], gain = 0f, enabled = true)
        }
        result.add(0, Preset("Flat", flatBands))
    }

    if (result.none { it.name == "None" }) {
        val noneBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            FilterBand(freq = DEFAULT_BAND_FREQS[i])
        }
        result.add(Preset("None", noneBands))
    }

    return result
}
