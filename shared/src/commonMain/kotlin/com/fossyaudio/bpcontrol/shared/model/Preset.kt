package com.fossyaudio.bpcontrol.shared.model

data class Preset(
    val name: String,
    val bands: List<FilterBand>
)
