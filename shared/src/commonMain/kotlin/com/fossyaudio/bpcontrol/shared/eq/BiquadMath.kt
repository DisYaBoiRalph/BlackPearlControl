package com.fossyaudio.bpcontrol.shared.eq

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float
)

object BiquadMath {
    fun coefficients(filter: FilterBand, gainDb: Float = filter.gain, fs: Double = 48000.0): BiquadCoefficients {
        val a = 10.0.pow(gainDb.toDouble() / 40.0)
        val w0 = 2.0 * PI * filter.freq / fs
        val alpha = sin(w0) / (2.0 * filter.q)
        val cosW0 = cos(w0)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (filter.type) {
            "LS", "HS" -> {
                val s = if (filter.type == "HS") 1.0 else -1.0
                val sqA = sqrt(a)
                b0 = a * ((a + 1.0) + s * (a - 1.0) * cosW0 + 2.0 * sqA * alpha)
                b1 = -s * 2.0 * a * ((a - 1.0) + s * (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + s * (a - 1.0) * cosW0 - 2.0 * sqA * alpha)
                a0 = (a + 1.0) - s * (a - 1.0) * cosW0 + 2.0 * sqA * alpha
                a1 = s * 2.0 * ((a - 1.0) - s * (a + 1.0) * cosW0)
                a2 = (a + 1.0) - s * (a - 1.0) * cosW0 - 2.0 * sqA * alpha
            }
            else -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
        }

        return BiquadCoefficients(
            b0 = (b0 / a0).toFloat(),
            b1 = (b1 / a0).toFloat(),
            b2 = (b2 / a0).toFloat(),
            a1 = (a1 / a0).toFloat(),
            a2 = (a2 / a0).toFloat()
        )
    }

    fun magnitudeDb(freq: Double, band: FilterBand, fs: Double = 48000.0): Double {
        if (kotlin.math.abs(band.gain) < 0.1f) return 0.0

        val coeffs = coefficients(band, band.gain, fs)
        val w = 2.0 * PI * freq / fs
        val b0 = coeffs.b0.toDouble()
        val b1 = coeffs.b1.toDouble()
        val b2 = coeffs.b2.toDouble()
        val a1 = coeffs.a1.toDouble()
        val a2 = coeffs.a2.toDouble()
        val numRe = b0 + b1 * cos(w) + b2 * cos(2.0 * w)
        val numIm = -(b1 * sin(w) + b2 * sin(2.0 * w))
        val denRe = 1.0 + a1 * cos(w) + a2 * cos(2.0 * w)
        val denIm = -(a1 * sin(w) + a2 * sin(2.0 * w))

        val denominator = denRe * denRe + denIm * denIm
        if (denominator == 0.0) return 0.0

        val magSquared = (numRe * numRe + numIm * numIm) / denominator
        val result = 10.0 * log10(max(1e-10, magSquared))
        return if (result.isNaN() || result.isInfinite()) 0.0 else result
    }
}
