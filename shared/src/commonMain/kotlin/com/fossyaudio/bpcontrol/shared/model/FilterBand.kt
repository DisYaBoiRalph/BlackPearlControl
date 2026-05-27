package com.fossyaudio.bpcontrol.shared.model

data class FilterBand(
    var enabled: Boolean = true,
    var type: String = "PK",
    var freq: Int = 1000,
    var gain: Float = 0.0f,
    var q: Float = 1.0f
)
