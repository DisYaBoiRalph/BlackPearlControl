package com.fossyaudio.bpcontrol.shared.model

data class Preset(
    val name: String,
    val preamp: Float,
    val bands: List<FilterBand>
)
