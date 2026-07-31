package com.fossyaudio.bpcontrol.data

import com.fossyaudio.bpcontrol.shared.model.Preset

interface IPresetStorage {
    fun load(): List<Preset>
    fun save(presets: List<Preset>)
}
