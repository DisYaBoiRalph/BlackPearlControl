package com.fossyaudio.bpcontrol.di

import android.content.Context
import com.fossyaudio.bpcontrol.data.IPresetStorage
import com.fossyaudio.bpcontrol.data.PresetRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val presetStorage: IPresetStorage by lazy {
        PresetRepository(appContext)
    }
}
