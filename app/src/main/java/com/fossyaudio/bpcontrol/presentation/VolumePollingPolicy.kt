package com.fossyaudio.bpcontrol.presentation

import kotlin.math.abs

class VolumePollingPolicy(
    private val minUiDeltaPercent: Float = 1.0f
) {
    fun skipReason(
        isAppInFocus: Boolean,
        isSyncing: Boolean,
        isMassPushing: Boolean,
        isUserTouchingSlider: Boolean
    ): String? {
        return when {
            !isAppInFocus -> "app not in focus"
            isSyncing -> "sync in progress"
            isMassPushing -> "mass push in progress"
            isUserTouchingSlider -> "user touching slider"
            else -> null
        }
    }

    fun shouldApplyPolledVolume(currentVolumePercent: Float, roundedPolledVolume: Float): Boolean {
        return abs(currentVolumePercent - roundedPolledVolume) >= minUiDeltaPercent
    }
}
