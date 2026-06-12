package com.fossyaudio.bpcontrol.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumePollingPolicyTest {

    @Test
    fun skip_reason_returns_app_not_in_focus_first() {
        val policy = VolumePollingPolicy()

        val reason = policy.skipReason(
            isAppInFocus = false,
            isSyncing = true,
            isMassPushing = true,
            isUserTouchingSlider = true
        )

        assertEquals("app not in focus", reason)
    }

    @Test
    fun skip_reason_returns_sync_before_other_busy_states() {
        val policy = VolumePollingPolicy()

        val reason = policy.skipReason(
            isAppInFocus = true,
            isSyncing = true,
            isMassPushing = true,
            isUserTouchingSlider = true
        )

        assertEquals("sync in progress", reason)
    }

    @Test
    fun skip_reason_returns_null_when_poll_is_allowed() {
        val policy = VolumePollingPolicy()

        val reason = policy.skipReason(
            isAppInFocus = true,
            isSyncing = false,
            isMassPushing = false,
            isUserTouchingSlider = false
        )

        assertNull(reason)
    }

    @Test
    fun should_apply_polled_volume_when_delta_is_at_least_one_percent() {
        val policy = VolumePollingPolicy()

        assertTrue(policy.shouldApplyPolledVolume(currentVolumePercent = 50f, roundedPolledVolume = 51f))
        assertTrue(policy.shouldApplyPolledVolume(currentVolumePercent = 50f, roundedPolledVolume = 49f))
    }

    @Test
    fun should_not_apply_polled_volume_when_delta_is_below_one_percent() {
        val policy = VolumePollingPolicy()

        assertFalse(policy.shouldApplyPolledVolume(currentVolumePercent = 50f, roundedPolledVolume = 50.9f))
        assertFalse(policy.shouldApplyPolledVolume(currentVolumePercent = 50f, roundedPolledVolume = 49.1f))
    }
}
