package com.fossyaudio.bpcontrol.shared.eq

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqCurveTest {

    @Test
    fun active_coefficients_excludes_disabled_and_silent_bands() {
        val bands = listOf(
            FilterBand(enabled = true, type = FilterType.PK, freq = 1000, gain = 6f, q = 1f),
            FilterBand(enabled = false, type = FilterType.PK, freq = 200, gain = 6f, q = 1f),
            FilterBand(enabled = true, type = FilterType.PK, freq = 500, gain = 0f, q = 1f),
        )

        assertEquals(1, activeCoefficients(bands).size)
    }

    @Test
    fun total_gain_sums_every_contributing_band() {
        val boosted = FilterBand(enabled = true, type = FilterType.PK, freq = 1000, gain = 6f, q = 1f)
        val coeffs = activeCoefficients(listOf(boosted, boosted))

        val single = totalGainDbAt(1000.0, activeCoefficients(listOf(boosted)))
        val doubled = totalGainDbAt(1000.0, coeffs)

        assertEquals(single * 2, doubled, 1e-6)
    }

    @Test
    fun total_gain_is_zero_with_no_coefficients() {
        assertEquals(0.0, totalGainDbAt(1000.0, emptyList()))
    }

    @Test
    fun freq_at_fraction_spans_20hz_to_20khz() {
        assertEquals(20.0, freqAtFraction(0f), 1e-6)
        assertEquals(20000.0, freqAtFraction(1f), 1e-3)
    }

    @Test
    fun freq_at_fraction_is_monotonically_increasing() {
        val low = freqAtFraction(0.3f)
        val high = freqAtFraction(0.7f)
        assertTrue(high > low, "Expected frequency to increase with fraction")
    }
}
