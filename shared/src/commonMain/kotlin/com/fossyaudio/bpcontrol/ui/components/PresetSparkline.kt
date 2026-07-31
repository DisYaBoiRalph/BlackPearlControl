package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.shared.eq.activeCoefficients
import com.fossyaudio.bpcontrol.shared.eq.freqAtFraction
import com.fossyaudio.bpcontrol.shared.eq.totalGainDbAt
import com.fossyaudio.bpcontrol.shared.model.FilterBand

private const val SAMPLE_COUNT = 28
private const val GAIN_RANGE_DB = 12f

/**
 * A preset's response curve, drawn tiny. A glyph, not a graph — no axes, no labels, no fill, just
 * the same [totalGainDbAt] math the PEQ graph uses, so a row's thumbnail and the live curve can
 * never disagree.
 */
@Composable
fun PresetSparkline(bands: List<FilterBand>, stroke: Color, modifier: Modifier = Modifier) {
    val gains = remember(bands) {
        val coeffs = activeCoefficients(bands)
        FloatArray(SAMPLE_COUNT) { i ->
            val freq = freqAtFraction(i / (SAMPLE_COUNT - 1).toFloat())
            totalGainDbAt(freq, coeffs).toFloat().coerceIn(-GAIN_RANGE_DB, GAIN_RANGE_DB)
        }
    }

    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val midY = size.height / 2f
        val dBScale = size.height / (GAIN_RANGE_DB * 2f)
        val path = Path().apply {
            gains.forEachIndexed { i, gain ->
                val x = size.width * i / (SAMPLE_COUNT - 1)
                val y = midY - gain * dBScale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path = path, color = stroke, style = Stroke(width = 1.6.dp.toPx()))
    }
}
