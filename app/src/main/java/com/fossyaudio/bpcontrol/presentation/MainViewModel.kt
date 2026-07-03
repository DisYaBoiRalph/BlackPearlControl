package com.fossyaudio.bpcontrol.presentation

import androidx.lifecycle.ViewModel
import com.fossyaudio.bpcontrol.ui.AppUiState

class MainViewModel : ViewModel() {

    /** Shared platform-agnostic UI state. Compose screens consume this directly. */
    val uiState = AppUiState()
}
