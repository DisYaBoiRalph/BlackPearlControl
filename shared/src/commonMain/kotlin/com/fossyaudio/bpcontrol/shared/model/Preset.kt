package com.fossyaudio.bpcontrol.shared.model

data class Preset(
    val name: String,
    val bands: List<FilterBand>,
    val source: PresetSource = PresetSource.MANUAL,
    /** Epoch millis. 0 = unknown (e.g. a preset loaded from pre-existing JSON). */
    val savedAt: Long = 0L,
)
