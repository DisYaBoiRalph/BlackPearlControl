package com.fossyaudio.bpcontrol.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun EqGraphCanvas(
    bands: List<FilterBand>,
    preampDb: Float,
    modifier: Modifier = Modifier,
) {
    val curvePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00BFFF")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
    }
    val ceilingPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FFA500")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
    }
    val gridPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = 30
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
    }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#BBBBBB")
            textSize = 24f
        }
    }
    val dbGridLevels = remember { listOf(12, 6, 0, -6, -12) }
    val freqGridLevels = remember { listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val safePadX = 60f
        val safePadY = 40f
        val graphW = (widthPx - safePadX * 2).coerceAtLeast(1f)
        val graphH = (heightPx - safePadY * 2).coerceAtLeast(1f)
        val midY = heightPx / 2f
        val dBScale = graphH / 36f

        fun xForFreq(f: Float): Float =
            safePadX + graphW * (log10(f / 20.0) / 3.0).toFloat()

        fun freqForX(x: Float): Double =
            20.0 * 10.0.pow(((x - safePadX) / graphW) * 3.0)

        val freqGridLines = remember(widthPx, graphW) {
            freqGridLevels.map { freq -> freq to xForFreq(freq.toFloat()) }
        }

        // Cache the expensive curve computation and only rebuild when bands or canvas size changes.
        val curvePath = remember(bands, widthPx, heightPx) {
            Path().apply {
                if (widthPx <= 1f || heightPx <= 1f || bands.isEmpty()) return@apply
                val sampleStepPx = 4
                for (px in 0..widthPx.toInt() step sampleStepPx) {
                    val freq = freqForX(px.toFloat()).coerceAtMost(22000.0)
                    var totalGainDb = 0.0
                    for (band in bands) {
                        if (band.enabled) totalGainDb += BiquadMath.magnitudeDb(freq, band)
                    }
                    val rawY = midY - (totalGainDb.toFloat() * dBScale)
                    val y = if (rawY.isNaN() || rawY.isInfinite()) midY else rawY
                    if (px == 0) moveTo(px.toFloat(), y) else lineTo(px.toFloat(), y)
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val w = size.width
                val h = size.height

                if (w <= 1f || h <= 1f) return@drawIntoCanvas

                // Horizontal grid lines (dB levels)
                dbGridLevels.forEach { db ->
                    val y = midY - (db * dBScale)
                    nc.drawLine(0f, y, w, y, gridPaint)
                    val label = "${if (db > 0) "+" else ""}$db dB"
                    nc.drawText(label, 20f, y - 10f, labelPaint)
                }

                // Vertical grid lines (frequencies)
                freqGridLines.forEach { (freq, x) ->
                    nc.drawLine(x, 0f, x, h, gridPaint)
                    val label = if (freq >= 1000) "${freq / 1000}k" else "$freq"
                    val lw = labelPaint.measureText(label)
                    nc.drawText(label, x - lw / 2f, h - 15f, labelPaint)
                }

                // Ceiling (orange dashed)
                val ceilingY = midY - (preampDb * dBScale)
                if (!ceilingY.isNaN() && !ceilingY.isInfinite()) {
                    nc.drawLine(0f, ceilingY, w, ceilingY, ceilingPaint)
                    val cl = "Ceiling"
                    val cw = labelPaint.measureText(cl)
                    nc.drawText(cl, w - cw - 20f, ceilingY - 10f, labelPaint)
                }

                if (!curvePath.isEmpty) {
                    nc.drawPath(curvePath, curvePaint)
                }
            }
        }
    }
}
