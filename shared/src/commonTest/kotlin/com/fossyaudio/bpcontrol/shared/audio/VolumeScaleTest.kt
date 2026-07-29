package com.fossyaudio.bpcontrol.shared.audio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeScaleTest {

    @Test
    fun endpoints_match_the_vendor_range() {
        assertEquals(-8.0f, volPctToDb(0f))
        assertEquals(4.0f, volPctToDb(100f))
    }

    @Test
    fun raw_endpoints_are_q8_8_of_the_db_endpoints() {
        assertEquals(VOL_MIN_RAW, (VOL_DB_MIN * 256).toInt())
        assertEquals(VOL_MAX_RAW, (VOL_DB_MAX * 256).toInt())
    }

    @Test
    fun pct_and_db_round_trip() {
        for (pct in 0..100) {
            val roundTripped = volDbToPct(volPctToDb(pct.toFloat()))
            assertTrue(
                abs(roundTripped - pct) < 0.001f,
                "round trip of $pct% gave $roundTripped",
            )
        }
    }

    /**
     * The percentage->raw mapping used on the wire and the percentage->dB mapping shown in the UI
     * must describe the same scale, or the readout drifts from what the DAC was told.
     */
    @Test
    fun raw_mapping_agrees_with_db_mapping() {
        for (pct in 0..100) {
            val raw = VOL_MIN_RAW + (pct / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)
            val dbFromRaw = (raw / 256.0).toFloat()
            assertTrue(
                abs(dbFromRaw - volPctToDb(pct.toFloat())) < 0.001f,
                "at $pct% raw says $dbFromRaw dB but volPctToDb says ${volPctToDb(pct.toFloat())}",
            )
        }
    }

    @Test
    fun snapping_lands_on_half_db_steps() {
        assertEquals(-7.5f, snapVolDb(-7.6f))
        assertEquals(-7.5f, snapVolDb(-7.4f))
        assertEquals(0.0f, snapVolDb(0.2f))
        assertEquals(4.0f, snapVolDb(3.9f))
    }

    /** 0.5 dB is 128 in Q8.8, so every snapped value is exactly representable — no rounding drift. */
    @Test
    fun snapped_values_are_whole_raw_values() {
        var db = VOL_DB_MIN
        while (db <= VOL_DB_MAX) {
            val raw = snapVolDb(db) * 256f
            assertEquals(raw.roundToInt().toFloat(), raw, "$db dB is not a whole raw value")
            assertEquals(0, raw.roundToInt() % 128, "$db dB is not a multiple of 128 raw")
            db += 0.5f
        }
    }
}
