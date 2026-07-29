package com.fossyaudio.bpcontrol.ui.components

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Rate-limits wire writes during a continuous gesture.
 *
 * A drag produces pointer events at display refresh rate. Forwarding each one floods the HID
 * queue and desyncs the DAC, so callers ask [shouldWrite] before sending and render from local
 * state in between.
 *
 * [timeSource] is injectable so the interval can be tested without sleeping.
 */
class DragWriteThrottle(
    private val intervalMs: Long,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var lastWrite: TimeMark? = null

    /** True if enough time has passed to send again; records the write when it returns true. */
    fun shouldWrite(): Boolean {
        val previous = lastWrite
        if (previous == null || previous.elapsedNow().inWholeMilliseconds >= intervalMs) {
            lastWrite = timeSource.markNow()
            return true
        }
        return false
    }

    /** Call when a gesture ends, so the next one is free to write immediately. */
    fun reset() {
        lastWrite = null
    }
}
