package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import kotlin.test.Test
import kotlin.test.assertEquals

class MainViewModelTest {

    private val minRawVolume = -9472
    private val maxRawVolume = 6440

    @Test
    fun calculate_headroom_matches_expected_for_50_percent() {
        val vm = MainViewModel()

        val actual = vm.calculateHeadroomDb(50f, minRawVolume, maxRawVolume)
        val expected = 31.078125f // ((6440 - -1516) / 256)

        assertEquals(expected, actual)
    }

    @Test
    fun calculate_headroom_clamps_when_volume_is_out_of_range() {
        val vm = MainViewModel()

        val belowMin = vm.calculateHeadroomDb(-20f, minRawVolume, maxRawVolume)
        val aboveMax = vm.calculateHeadroomDb(140f, minRawVolume, maxRawVolume)

        assertEquals((maxRawVolume - minRawVolume).toFloat() / 256f, belowMin)
        assertEquals(0f, aboveMax)
    }

    @Test
    fun identify_preset_delegates_to_preset_matcher_logic() {
        val vm = MainViewModel()
        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        val flat = Preset("Flat", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = "PK") })
        val custom = Preset("Custom", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = "PK") })
        val none = Preset("None", 0f, MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, type = "PK") })

        custom.bands[1] = FilterBand(enabled = true, type = "LS", freq = 63, gain = 2.0f, q = 0.8f)

        val match = vm.identifyPreset(listOf(flat, custom, none), custom.bands.map { it.copy() })

        assertEquals(1, match)
    }
}
