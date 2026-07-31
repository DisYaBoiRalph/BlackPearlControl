package com.fossyaudio.bpcontrol.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.AppUiState
import com.fossyaudio.bpcontrol.ui.MainScreen
import com.fossyaudio.bpcontrol.ui.theme.BpControlTheme
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(820.dp, 720.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Black Pearl Control",
        state = windowState,
    ) {
        val presetStorage = remember { DesktopPresetStorage() }
        val state = remember { AppUiState() }
        val controller = remember { DesktopController(state) }

        DisposableEffect(Unit) {
            // Load persisted presets into shared state before controller starts
            val presets = presetStorage.load()
            state.updatePresets(presets)
            // Start HID connection loop
            controller.start()
            onDispose { controller.stop() }
        }

        val actions = AppActions(
            onVolumeChange = { controller.onVolumeChange(it) },
            onVolumeStartDragging = { /* Desktop has no polling interlock */ },
            onVolumeStopDragging = { /* Desktop has no polling interlock */ },
            onBalanceChange = { controller.onBalanceChange(it) },
            onFilterSelected = { controller.onFilterSelected(it) },
            onGainModeSelected = { controller.onGainModeSelected(it) },
            onAmpTopoSelected = { controller.onAmpTopoSelected(it) },
            onMicGainChange = { controller.onMicGainChange(it) },
            onFactoryReset = { controller.onFactoryReset() },
            onBandUpdated = { index, band -> controller.onBandUpdated(index, band, presetStorage) },
            onBandDragUpdate = { index, band -> controller.onBandDragUpdate(index, band) },
            onPresetLoaded = { controller.onPresetLoaded(it) },
            onPresetSaved = { name -> controller.onPresetSaved(name, presetStorage) },
            onPresetDeleted = { name -> controller.onPresetDeleted(name, presetStorage) },
            onPresetRenamed = { index, newName -> controller.onPresetRenamed(index, newName, presetStorage) },
            onPresetDuplicated = { index -> controller.onPresetDuplicated(index, presetStorage) },
            onImport = {
                val dialog = FileDialog(window, "Import AutoEQ preset", FileDialog.LOAD)
                dialog.isVisible = true
                val fileName = dialog.file
                val dir = dialog.directory
                if (fileName != null && dir != null) {
                    val file = File(dir, fileName)
                    controller.onImport(file.readText(), file.nameWithoutExtension, presetStorage)
                }
            },
            onExport = { /* TODO: desktop file picker for preset export */ },
            onCopyLog = {
                val selection = StringSelection(controller.protocolLog.snapshot())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            },
        )

        BpControlTheme {
            MainScreen(state = state, actions = actions)
        }
    }
}

