package com.fossyaudio.bpcontrol.shared.eq

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import kotlin.math.abs
import kotlin.math.pow

/**
 * Coefficients for the bands that actually shape the curve. A disabled band, or one with
 * negligible gain, contributes nothing — filtering them here keeps the curve and the per-band
 * handles agreeing on which bands are "on".
 */
fun activeCoefficients(bands: List<FilterBand>): List<BiquadCoefficients> =
    bands.filter { it.enabled && abs(it.gain) >= 0.1f }.map { BiquadMath.coefficients(it) }

/** Summed response of every contributing band at [freq], in dB. */
fun totalGainDbAt(freq: Double, coeffs: List<BiquadCoefficients>): Double {
    var total = 0.0
    for (c in coeffs) total += BiquadMath.magnitudeDb(freq, c)
    return total
}

/** Maps [t] in 0..1 to a frequency across the app's standard 20 Hz - 20 kHz, 3-decade log window. */
fun freqAtFraction(t: Float): Double = 20.0 * 10.0.pow(t * 3.0)
