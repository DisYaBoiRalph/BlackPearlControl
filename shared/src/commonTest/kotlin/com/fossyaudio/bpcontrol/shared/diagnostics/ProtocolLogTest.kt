package com.fossyaudio.bpcontrol.shared.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolLogTest {

    @Test
    fun snapshot_is_empty_with_no_recorded_frames() {
        assertEquals("", ProtocolLog().snapshot())
    }

    @Test
    fun records_direction_and_hex_bytes() {
        val log = ProtocolLog()
        log.record("OUT", byteArrayOf(0x4B, 0x01, 0xFF.toByte()))
        assertEquals("OUT 4B 01 FF", log.snapshot())
    }

    @Test
    fun keeps_frames_in_order() {
        val log = ProtocolLog()
        log.record("OUT", byteArrayOf(0x01))
        log.record("IN", byteArrayOf(0x02))
        assertEquals("OUT 01\nIN 02", log.snapshot())
    }

    @Test
    fun drops_oldest_frame_once_capacity_is_exceeded() {
        val log = ProtocolLog(capacity = 2)
        log.record("OUT", byteArrayOf(0x01))
        log.record("OUT", byteArrayOf(0x02))
        log.record("OUT", byteArrayOf(0x03))
        val lines = log.snapshot().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("01") }, "Expected the oldest frame to be dropped")
        assertTrue(lines.any { it.contains("02") })
        assertTrue(lines.any { it.contains("03") })
    }

    @Test
    fun clear_empties_the_log() {
        val log = ProtocolLog()
        log.record("OUT", byteArrayOf(0x01))
        log.clear()
        assertEquals("", log.snapshot())
    }
}
