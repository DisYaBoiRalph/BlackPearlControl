package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.shared.eq.BiquadCoefficients
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import kotlin.math.log10
import kotlin.math.pow

/** Summed response of every contributing band at [freq], in dB. */
private fun totalGainDbAt(freq: Double, coeffs: List<BiquadCoefficients>): Double {
    var total = 0.0
    for (c in coeffs) total += BiquadMath.magnitudeDb(freq, c)
    return total
}

@Composable
fun EqGraphCanvas(
    bands: List<FilterBand>,
    preampDb: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val dbGridLevels = remember { listOf(12, 6, 0, -6, -12) }
    val freqGridLevels = remember { listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000) }

    val curveColor = Color(0xFF00BFFF)
    val ceilingColor = Color(0xFFFFA500)
    val gridColor = Color.White.copy(alpha = 0.12f)
    val labelStyle = TextStyle(color = Color(0xFFBBBBBB), fontSize = 9.sp)
    val ceilingDash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // dp, not raw pixels: as device pixels these insets were 30 dp at 2x and 20 dp at 3x,
        // so the grid moved with the screen density.
        val safePadX = with(density) { 20.dp.toPx() }
        val safePadY = with(density) { 14.dp.toPx() }
        val graphW = (widthPx - safePadX * 2).coerceAtLeast(1f)
        val graphH = (heightPx - safePadY * 2).coerceAtLeast(1f)
        val midY = heightPx / 2f
        // +/-14 dB window. Band gain clamps at +/-12, so the old /36f (+/-18 dB) wasted a third
        // of the card on range that can never be drawn.
        val dBScale = graphH / 28f

        fun xForFreq(f: Float): Float =
            safePadX + graphW * (log10(f / 20.0) / 3.0).toFloat()

        fun freqForX(x: Float): Double =
            20.0 * 10.0.pow(((x - safePadX) / graphW) * 3.0)

        val freqGridLines = remember(widthPx, graphW) {
            freqGridLevels.map { freq -> freq to xForFreq(freq.toFloat()) }
        }

        // Only the audible bands contribute; recomputed with the curve so both agree.
        val activeBandCoeffs = remember(bands) {
            bands.filter { it.enabled && kotlin.math.abs(it.gain) >= 0.1f }
                .map { BiquadMath.coefficients(it) }
        }

        // Cache expensive curve computation — only rebuild when bands or canvas size changes.
        val curvePath = remember(bands, widthPx, heightPx) {
            Path().apply {
                if (widthPx <= 1f || heightPx <= 1f || bands.isEmpty()) return@apply
                val sampleStepPx = 4
                for (px in 0..widthPx.toInt() step sampleStepPx) {
                    val freq = freqForX(px.toFloat()).coerceAtMost(22000.0)
                    val rawY = midY - (totalGainDbAt(freq, activeBandCoeffs).toFloat() * dBScale)
                    val y = if (rawY.isNaN() || rawY.isInfinite()) midY else rawY
                    if (px == 0) moveTo(px.toFloat(), y) else lineTo(px.toFloat(), y)
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 1f || h <= 1f) return@Canvas

            // Horizontal grid lines (dB levels)
            dbGridLevels.forEach { db ->
                val y = midY - (db * dBScale)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
                val label = "${if (db > 0) "+" else ""}$db dB"
                val measured = textMeasurer.measure(label, labelStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(4f, y - measured.size.height - 2f),
                    style = labelStyle,
                )
            }

            // Vertical grid lines (frequencies)
            freqGridLines.forEach { (freq, x) ->
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
                val label = if (freq >= 1000) "${freq / 1000}k" else "$freq"
                val measured = textMeasurer.measure(label, labelStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(x - measured.size.width / 2f, h - measured.size.height - 4f),
                    style = labelStyle,
                )
            }

            // Ceiling line (orange dashed)
            val ceilingY = midY - (preampDb * dBScale)
            if (!ceilingY.isNaN() && !ceilingY.isInfinite()) {
                drawLine(
                    color = ceilingColor,
                    start = Offset(0f, ceilingY),
                    end = Offset(w, ceilingY),
                    strokeWidth = 3f,
                    pathEffect = ceilingDash,
                )
                val clabel = "Ceiling"
                val measured = textMeasurer.measure(clabel, labelStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = clabel,
                    topLeft = Offset(w - measured.size.width - 20f, ceilingY - measured.size.height - 4f),
                    style = labelStyle,
                )
            }

            // EQ curve
            if (!curvePath.isEmpty) {
                drawPath(
                    path = curvePath,
                    color = curveColor,
                    style = Stroke(width = 5f),
                )
            }
        }
    }
}
