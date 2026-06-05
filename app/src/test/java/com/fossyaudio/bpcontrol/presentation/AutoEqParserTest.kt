package com.fossyaudio.bpcontrol.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoEqParserTest {

    private fun parse(text: String) =
        AutoEqParser.parse(text.trimIndent().byteInputStream())

    @Test
    fun parses_standard_10_band_autoeq_file() {
        val input = """
            Preamp: -6.5 dB
            Filter 1: ON PK Fc 31 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON PK Fc 63 Hz Gain -2.0 dB Q 1.41
            Filter 3: ON PK Fc 125 Hz Gain 1.5 dB Q 1.41
            Filter 4: ON PK Fc 250 Hz Gain -1.0 dB Q 1.41
            Filter 5: ON PK Fc 500 Hz Gain 0.5 dB Q 1.41
            Filter 6: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.41
            Filter 7: ON PK Fc 2000 Hz Gain -3.0 dB Q 1.41
            Filter 8: ON PK Fc 4000 Hz Gain 1.0 dB Q 1.41
            Filter 9: ON PK Fc 8000 Hz Gain -1.5 dB Q 1.41
            Filter 10: ON PK Fc 16000 Hz Gain 0.5 dB Q 1.41
        """
        val result = parse(input)
        assertEquals(10, result.bands.size)
        assertEquals(-6.5f, result.preamp)
        assertEquals(31, result.bands[0].freq)
        assertEquals(3.0f, result.bands[0].gain)
        assertEquals("PK", result.bands[0].type)
        assertTrue(result.bands[0].enabled)
    }

    @Test
    fun partial_file_under_10_bands_fills_only_parsed_count() {
        val input = """
            Filter 1: ON PK Fc 1000 Hz Gain 2.5 dB Q 1.0
            Filter 2: ON LS Fc 80 Hz Gain 3.0 dB Q 0.7
        """
        val result = parse(input)
        assertEquals(2, result.bands.size)
    }

    @Test
    fun detects_ls_and_hs_filter_types() {
        val input = """
            Filter 1: ON LS Fc 100 Hz Gain 2.0 dB Q 0.7
            Filter 2: ON HS Fc 10000 Hz Gain -1.5 dB Q 0.7
        """
        val result = parse(input)
        assertEquals("LS", result.bands[0].type)
        assertEquals("HS", result.bands[1].type)
    }

    @Test
    fun off_flag_marks_band_disabled() {
        val input = "Filter 1: OFF PK Fc 500 Hz Gain 1.0 dB Q 1.0"
        val result = parse(input)
        assertEquals(1, result.bands.size)
        assertEquals(false, result.bands[0].enabled)
    }

    @Test
    fun negative_preamp_is_parsed_correctly() {
        val input = "Preamp: -8.3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0"
        val result = parse(input)
        assertEquals(-8.3f, result.preamp, 0.01f)
    }

    @Test
    fun malformed_input_returns_empty_bands_without_throwing() {
        val result = parse("this is not an eq file at all")
        assertTrue(result.bands.isEmpty())
        assertEquals(0f, result.preamp)
    }

    @Test
    fun caps_bands_at_10() {
        val filters = (1..15).joinToString("\n") {
            "Filter $it: ON PK Fc ${it * 100} Hz Gain 1.0 dB Q 1.0"
        }
        val result = parse(filters)
        assertEquals(10, result.bands.size)
    }

    @Test
    fun freq_is_clamped_to_20_to_20000() {
        val input = """
            Filter 1: ON PK Fc 5 Hz Gain 1.0 dB Q 1.0
            Filter 2: ON PK Fc 99999 Hz Gain 1.0 dB Q 1.0
        """
        val result = parse(input)
        assertEquals(20, result.bands[0].freq)
        assertEquals(20000, result.bands[1].freq)
    }
}
