package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetMatcherTest {
    private val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    @Test
    fun identify_preset_matches_custom_band_state() {
        val flat = Preset("Flat", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })
        val custom = Preset("Custom", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })
        custom.bands[2] = FilterBand(enabled = true, type = FilterType.LS, freq = 125, gain = 2.5f, q = 0.9f)
        val none = Preset("None", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })

        val hwBands = custom.bands.map { it.copy() }
        val match = PresetMatcher.identifyPreset(listOf(flat, custom, none), hwBands)

        assertEquals(1, match)
    }

    @Test
    fun identify_preset_ignores_filter_type_when_gain_is_zero() {
        val zeroGainTypeLS = Preset("ZeroTypeLS", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })
        zeroGainTypeLS.bands[0] = FilterBand(enabled = true, type = FilterType.LS, freq = 31, gain = 0f, q = 1.0f)
        val none = Preset("None", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })

        val hwBands = zeroGainTypeLS.bands.map { it.copy() }.toMutableList()
        hwBands[0] = hwBands[0].copy(type = FilterType.HS, gain = 0f)

        val match = PresetMatcher.identifyPreset(listOf(zeroGainTypeLS, none), hwBands)

        assertEquals(0, match)
    }
}
