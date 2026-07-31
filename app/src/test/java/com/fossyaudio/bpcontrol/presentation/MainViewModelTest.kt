package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.shared.audio.VOL_MAX_RAW
import com.fossyaudio.bpcontrol.shared.audio.VOL_MIN_RAW
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class MainViewModelTest {

    private val minRawVolume = VOL_MIN_RAW
    private val maxRawVolume = VOL_MAX_RAW

    @Test
    fun calculate_headroom_matches_expected_for_50_percent() {
        val vm = MainViewModel()

        val actual = vm.uiState.calculateHeadroomDb(50f, minRawVolume, maxRawVolume)
        val expected = 6.0f // ((1024 - -512) / 256)

        assertEquals(expected, actual)
    }

    @Test
    fun calculate_headroom_clamps_when_volume_is_out_of_range() {
        val vm = MainViewModel()

        val belowMin = vm.uiState.calculateHeadroomDb(-20f, minRawVolume, maxRawVolume)
        val aboveMax = vm.uiState.calculateHeadroomDb(140f, minRawVolume, maxRawVolume)

        assertEquals((maxRawVolume - minRawVolume).toFloat() / 256f, belowMin)
        assertEquals(0f, aboveMax)
    }

    @Test
    fun identify_preset_delegates_to_preset_matcher_logic() {
        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        val flat = Preset("Flat", List(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })
        val customBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            if (i == 1) FilterBand(enabled = true, type = FilterType.LS, freq = 63, gain = 2.0f, q = 0.8f)
            else FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK)
        }
        val custom = Preset("Custom", customBands)
        val none = Preset("None", List(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = FilterType.PK) })

        val match = PresetMatcher.identifyPreset(listOf(flat, custom, none), custom.bands.map { it.copy() })

        assertEquals(1, match)
    }
}
