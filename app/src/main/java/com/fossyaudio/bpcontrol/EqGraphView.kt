package com.fossyaudio.bpcontrol

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import kotlin.math.*

class EqGraphView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFFF") // Cyan curve
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val ceilingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA500") // Orange
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) // Dashed line
    }

    var preampDb: Float = 0f
    var bands: List<FilterBand> = emptyList()

    private val cachedPath = Path()
    var pathDirty = true

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // CRITICAL: If view is not yet laid out, abort.
        // Division by zero here results in NaN coordinates which instantly crashes Android.
        if (w <= 1f || h <= 1f || w.isNaN() || h.isNaN()) return

        // Mathematical "Safe Zone" mapping for labels and grid
        val safePadX = 60f
        val safePadY = 40f
        val graphW = (w - (safePadX * 2)).coerceAtLeast(1f)
        val graphH = (h - (safePadY * 2)).coerceAtLeast(1f)

        val midY = h / 2f
        val dBScale = graphH / 36f // Visual range of 36dB (+18 to -18)

        // Precise Logarithmic Mapping helpers
        fun xForFreq(f: Float): Float = safePadX + graphW * (log10(f / 20.0) / 3.0).toFloat()
        fun freqForX(x: Float): Double = 20.0 * 10.0.pow(((x - safePadX) / graphW) * 3.0)

        // 1. Setup Paints
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; alpha = 30; strokeWidth = 1f; style = Paint.Style.STROKE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBBBBB"); textSize = 24f
        }

        // 2. Draw Horizontal Grid Lines (Gain dB) Edge-to-Edge
        val gainLevels = listOf(12, 6, 0, -6, -12)
        gainLevels.forEach { db ->
            val y = midY - (db * dBScale)
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = "${if (db > 0) "+" else ""}$db dB"
            canvas.drawText(label, 20f, y - 10f, labelPaint)
        }

        // 3. Draw Vertical Grid Lines (Professional Logarithmic Progression)
        val freqLines = listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        freqLines.forEach { f ->
            val x = xForFreq(f.toFloat())
            canvas.drawLine(x, 0f, x, h, gridPaint)

            val label = if (f >= 1000) "${f / 1000}k" else "$f"
            val labelWidth = labelPaint.measureText(label)
            // Center label perfectly under its grid line
            canvas.drawText(label, x - (labelWidth / 2f), h - 15f, labelPaint)
        }

        // 4. Draw Digital Ceiling (Orange) Edge-to-Edge
        val ceilingY = midY - (preampDb * dBScale)
        if (!ceilingY.isNaN() && !ceilingY.isInfinite()) {
            canvas.drawLine(0f, ceilingY, w, ceilingY, ceilingPaint)
            val ceilingLabel = "Ceiling"
            val ceilingWidth = labelPaint.measureText(ceilingLabel)
            canvas.drawText(ceilingLabel, w - ceilingWidth - 20f, ceilingY - 10f, labelPaint)
        }

        // 5. Draw the EQ Curve (Cyan) Edge-to-Edge
        if (bands.isEmpty()) return
        if (pathDirty) {
            cachedPath.reset()
            for (x in 0..w.toInt() step 3) {
                // Map the screen pixel X to a frequency based on the Safe Zone.
                // Clamp to 22kHz to prevent mathematical "ringing" or spikes as we approach the 24kHz Nyquist limit.
                val freq = freqForX(x.toFloat()).coerceAtMost(22000.0)
                var totalGainDb = 0.0
                for (band in bands) {
                    if (band.enabled) totalGainDb += BiquadMath.magnitudeDb(freq, band)
                }

                // Sanitize coordinates to prevent native hardware renderer crashes (no NaN or Infinity)
                val rawY = midY - (totalGainDb.toFloat() * dBScale)
                val y = if (rawY.isNaN() || rawY.isInfinite()) midY else rawY

                if (x == 0) cachedPath.moveTo(x.toFloat(), y) else cachedPath.lineTo(x.toFloat(), y)
            }
            pathDirty = false
        }
        canvas.drawPath(cachedPath, paint)
    }
}