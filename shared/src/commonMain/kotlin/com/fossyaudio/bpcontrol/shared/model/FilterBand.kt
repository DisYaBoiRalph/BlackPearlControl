package com.fossyaudio.bpcontrol.shared.model

data class FilterBand(
    val enabled: Boolean = true,
    val type: FilterType = FilterType.PK,
    val freq: Int = 1000,
    val gain: Float = 0.0f,
    val q: Float = 1.0f
)
