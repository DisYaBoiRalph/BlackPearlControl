package com.fossyaudio.bpcontrol.shared.eq

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadMathTest {
    @Test
    fun magnitude_is_zero_for_flat_gain() {
        val flatBand = FilterBand(enabled = true, type = FilterType.PK, freq = 1000, gain = 0f, q = 1f)
        val magnitude = BiquadMath.magnitudeDb(1000.0, flatBand)
        assertEquals(0.0, magnitude, 1e-6)
    }

    @Test
    fun peak_boost_is_stronger_near_center_frequency() {
        val boostedBand = FilterBand(enabled = true, type = FilterType.PK, freq = 1000, gain = 6f, q = 1f)
        val nearCenter = BiquadMath.magnitudeDb(1000.0, boostedBand)
        val farFromCenter = BiquadMath.magnitudeDb(80.0, boostedBand)

        assertTrue(nearCenter > 1.0, "Expected meaningful boost near center frequency")
        assertTrue(nearCenter > farFromCenter, "Expected center boost to exceed far frequency response")
    }
}
