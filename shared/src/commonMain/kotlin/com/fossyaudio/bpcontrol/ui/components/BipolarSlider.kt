package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Resets the value on double tap.
 *
 * [androidx.compose.foundation.gestures.detectTapGestures] does not work for this: [Slider]
 * consumes the pointer-down during the main pass, before an outer detector is offered it, so the
 * callback never fires. This watches the initial pass instead and consumes only the second tap of
 * a pair, leaving single taps and drags to the slider as usual.
 */
fun Modifier.doubleTapToReset(enabled: Boolean, onReset: () -> Unit): Modifier =
    this.pointerInput(enabled, onReset) {
        if (!enabled) return@pointerInput
        var previousTapUptime = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val isSecondTap =
                down.uptimeMillis - previousTapUptime <= viewConfiguration.doubleTapTimeoutMillis
            previousTapUptime = down.uptimeMillis
            if (!isSecondTap) return@awaitEachGesture

            previousTapUptime = 0L
            down.consume()
            onReset()
            // Swallow the rest of the gesture, or the slider seeks to the tap position and
            // immediately overwrites the reset.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }

/**
 * A slider for a value centred on zero.
 *
 * Differs from the stock [Slider] in two ways that matter for bipolar values: the active fill
 * grows outward from the midpoint toward the thumb rather than from the left edge, and a notch
 * marks the centre so zero is findable without looking at the readout.
 *
 * Deliberately never sets `steps`. Discrete behaviour comes from rounding in `onValueChange`;
 * `steps` would additionally draw a tick per position, which is unreadable past a handful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BipolarSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    trackHeight: Dp = 4.dp,
    thumbSize: Dp = 20.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()

    val activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val thumbColor = if (enabled) colors.thumbColor else colors.disabledThumbColor
    val notchColor = MaterialTheme.colorScheme.outline

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Surface(
                modifier = Modifier.size(thumbSize),
                shape = CircleShape,
                color = thumbColor,
                content = {},
            )
        },
        track = { state ->
            // SliderState.coercedValueAsFraction is internal to Material3, so derive it from the
            // public value and range instead.
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span == 0f) {
                0.5f
            } else {
                ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbSize),
            ) {
                val centerY = size.height / 2f
                val trackPx = trackHeight.toPx()
                // Inset by half a thumb so the track ends where the thumb centre can reach.
                val inset = thumbSize.toPx() / 2f
                val usableWidth = (size.width - inset * 2f).coerceAtLeast(0f)
                val left = inset
                val right = left + usableWidth
                val midX = left + usableWidth / 2f
                val thumbX = left + usableWidth * fraction

                drawLine(
                    color = inactiveColor,
                    start = Offset(left, centerY),
                    end = Offset(right, centerY),
                    strokeWidth = trackPx,
                )

                // Active fill grows out from the centre, in whichever direction the thumb sits.
                drawLine(
                    color = activeColor,
                    start = Offset(midX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackPx,
                )

                val notchHeight = 12.dp.toPx()
                drawLine(
                    color = notchColor,
                    start = Offset(midX, centerY - notchHeight / 2f),
                    end = Offset(midX, centerY + notchHeight / 2f),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        },
        valueRange = valueRange,
    )
}
