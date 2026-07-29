package com.fossyaudio.bpcontrol.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DragWriteThrottleTest {

    @Test
    fun first_write_is_always_allowed() {
        val throttle = DragWriteThrottle(40, TestTimeSource())
        assertTrue(throttle.shouldWrite())
    }

    @Test
    fun writes_inside_the_interval_are_suppressed() {
        val time = TestTimeSource()
        val throttle = DragWriteThrottle(40, time)

        assertTrue(throttle.shouldWrite())
        time += 39.milliseconds
        assertFalse(throttle.shouldWrite())
    }

    @Test
    fun writes_resume_once_the_interval_elapses() {
        val time = TestTimeSource()
        val throttle = DragWriteThrottle(40, time)

        assertTrue(throttle.shouldWrite())
        time += 40.milliseconds
        assertTrue(throttle.shouldWrite())
    }

    @Test
    fun reset_lets_the_next_gesture_write_immediately() {
        val time = TestTimeSource()
        val throttle = DragWriteThrottle(40, time)

        assertTrue(throttle.shouldWrite())
        throttle.reset()
        assertTrue(throttle.shouldWrite())
    }

    /**
     * The acceptance bound: a 5 s drag at 60 Hz offers 300 events, and must not send 300 frames.
     */
    @Test
    fun five_second_drag_at_60hz_stays_within_the_frame_budget() {
        val time = TestTimeSource()
        val throttle = DragWriteThrottle(40, time)

        var writes = 0
        repeat(300) {
            if (throttle.shouldWrite()) writes++
            time += (1000.0 / 60.0).toLong().milliseconds
        }

        assertTrue(writes <= 125, "expected at most 125 writes for a 5 s drag, got $writes")
        assertTrue(writes >= 100, "expected the drag to still feel live, got only $writes writes")
    }

    @Test
    fun a_slow_drag_is_never_throttled() {
        val time = TestTimeSource()
        val throttle = DragWriteThrottle(40, time)

        var writes = 0
        repeat(10) {
            if (throttle.shouldWrite()) writes++
            time += 1.seconds
        }

        assertEquals(10, writes)
    }
}
