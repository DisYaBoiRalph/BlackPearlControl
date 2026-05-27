package com.fossyaudio.bpcontrol.shared.model

data class Preset(
    var name: String,
    var preamp: Float,
    val bands: MutableList<FilterBand>
)
