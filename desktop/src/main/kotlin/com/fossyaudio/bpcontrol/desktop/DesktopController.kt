package com.fossyaudio.bpcontrol.desktop

import com.fossyaudio.bpcontrol.presentation.DacSettingsMapper
import com.fossyaudio.bpcontrol.shared.audio.BALANCE_DB_LIMIT
import com.fossyaudio.bpcontrol.shared.audio.VOL_MAX_RAW
import com.fossyaudio.bpcontrol.shared.audio.VOL_MIN_RAW
import com.fossyaudio.bpcontrol.shared.audio.volDbToPct
import com.fossyaudio.bpcontrol.shared.audio.volPctToDb
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.model.PresetSource
import com.fossyaudio.bpcontrol.shared.preset.AutoEqParser
import com.fossyaudio.bpcontrol.shared.preset.DEFAULT_BAND_FREQS
import com.fossyaudio.bpcontrol.shared.preset.PresetMatcher
import com.fossyaudio.bpcontrol.shared.preset.uniqueName
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodec
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import com.fossyaudio.bpcontrol.ui.AppUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.hid4java.HidDevice
import org.hid4java.HidManager
import org.hid4java.HidServices
import org.hid4java.HidServicesSpecification
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Desktop platform controller. Mirrors the USB handling logic in MainActivity, using hid4java
 * instead of Android's UsbManager. Shared [AppUiState] is updated as readback results arrive.
 */
class DesktopController(private val state: AppUiState) {

    private val WRITE = BlackPearlProtocol.Frame.WRITE
    private val READ = BlackPearlProtocol.Frame.READ
    private val END = BlackPearlProtocol.Frame.END

    private val CMD_GLOBAL_GAIN = BlackPearlProtocol.Command.GLOBAL_GAIN
    private val CMD_FILTER = BlackPearlProtocol.Command.FILTER
    private val CMD_MIC_GAIN = BlackPearlProtocol.Command.MIC_GAIN
    private val CMD_GAIN_MODE = BlackPearlProtocol.Command.GAIN_MODE
    private val CMD_AMP_TOPO = BlackPearlProtocol.Command.AMP_TOPO
    private val CMD_BALANCE = BlackPearlProtocol.Command.BALANCE
    private val CMD_PEQ_VALUES = BlackPearlProtocol.Command.PEQ_VALUES
    private val CMD_FLASH_EQ = BlackPearlProtocol.Command.FLASH_EQ
    private val CMD_READ_FW_VERSION = BlackPearlProtocol.Command.READ_FW_VERSION

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapper = DacSettingsMapper(VOL_MIN_RAW, VOL_MAX_RAW)
    private val sendQueue = LinkedBlockingQueue<ByteArray>(BlackPearlProtocol.Timing.QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)

    private var hidServices: HidServices? = null
    private var device: HidDevice? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var sendJob: Job? = null
    private var flashJob: Job? = null

    private var firmwareVersion: String
        get() = state.firmwareVersion.value
        set(v) { state.updateFirmwareVersion(v) }

    private var activeSlot: Byte
        get() = state.activeSlot.value
        set(v) { state.updateActiveSlot(v) }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        running.set(true)
        val spec = HidServicesSpecification().also { it.isAutoShutdown = false }
        hidServices = HidManager.getHidServices(spec)
        startConnectLoop()
    }

    fun stop() {
        running.set(false)
        connectJob?.cancel()
        pollJob?.cancel()
        sendJob?.cancel()
        flashJob?.cancel()
        device?.close()
        device = null
        hidServices?.shutdown()
        state.updateIsConnected(false)
    }

    // ─── Connection loop ─────────────────────────────────────────────────────

    private fun startConnectLoop() {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (isActive && running.get()) {
                if (device == null || device?.isOpen == false) {
                    tryConnect()
                }
                delay(2000L)
            }
        }
    }

    private fun tryConnect() {
        val services = hidServices ?: return
        val dev = services.getHidDevice(BlackPearlProtocol.Device.VID, BlackPearlProtocol.Device.PID, null)
            ?: return
        if (!dev.isOpen) {
            dev.open()
        }
        if (dev.isOpen) {
            device = dev
            println("[BPControl/Desktop] Connected to TRN Black Pearl")
            startSendQueue()
            scope.launch {
                delay(BlackPearlProtocol.Timing.POST_CONNECT_SYNC_DELAY_MS)
                readDacSettings()
                startVolumePolling()
            }
        }
    }

    // ─── Send queue ──────────────────────────────────────────────────────────

    fun enqueue(payload: ByteArray) {
        if (running.get()) sendQueue.offer(payload.copyOf())
    }

    private fun startSendQueue() {
        sendJob?.cancel()
        sendJob = scope.launch {
            while (isActive && running.get()) {
                val payload = sendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val dev = device ?: continue
                if (!dev.isOpen) { device = null; break }

                val delayMs = when (payload.getOrNull(1)) {
                    CMD_FLASH_EQ -> BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS
                    CMD_PEQ_VALUES -> BlackPearlProtocol.Timing.QUEUE_DELAY_PEQ_MS
                    CMD_GLOBAL_GAIN -> BlackPearlProtocol.Timing.QUEUE_DELAY_GLOBAL_GAIN_MS
                    else -> BlackPearlProtocol.Timing.QUEUE_DELAY_DEFAULT_MS
                }

                // hid4java prepends the reportId parameter itself — pass payload directly.
                dev.write(payload, payload.size, BlackPearlProtocol.Device.REPORT_ID)
                delay(delayMs)
            }
        }
    }

    // ─── Synchronous read (blocks up to timeout) ─────────────────────────────

    private fun pullValueSync(
        cmd: Byte,
        p1: Byte = END,
        p2: Byte = END,
        p3: Byte = END,
    ): ByteArray? {
        val dev = device ?: run { println("[BPControl/HID] pullValueSync cmd=0x${cmd.toInt().and(0xFF).toString(16)}: no device"); return null }
        if (!dev.isOpen) { println("[BPControl/HID] pullValueSync cmd=0x${cmd.toInt().and(0xFF).toString(16)}: device not open"); return null }

        // encodeReadRequest puts REPORT_ID at [0]. hid4java prepends reportId itself,
        // so we strip [0] and let hid4java add it — otherwise device gets a double report ID.
        val request = BlackPearlCodec.encodeReadRequest(cmd, p1, p2, p3)
        val payload = request.copyOfRange(1, request.size)
        val written = dev.write(payload, payload.size, BlackPearlProtocol.Device.REPORT_ID)
        if (written < 0) {
            println("[BPControl/HID] write failed cmd=0x${cmd.toInt().and(0xFF).toString(16)} err=${dev.lastErrorMessage}")
            return null
        }

        val response = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
        val read = dev.read(response, BlackPearlProtocol.Timing.READ_TRANSFER_TIMEOUT_MS)
        println("[BPControl/HID] cmd=0x${cmd.toInt().and(0xFF).toString(16)} p1=0x${p1.toInt().and(0xFF).toString(16)} written=$written read=$read" +
            if (read > 0) " resp=[${response.take(8).joinToString { "0x${it.toInt().and(0xFF).toString(16)}" }}...]" else " (null response)")
        return if (read > 0) response else null
    }

    // ─── Initial settings readback ───────────────────────────────────────────

    private suspend fun readDacSettings() {
        val localBands = MutableList(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = DEFAULT_BAND_FREQS[i]) }
        try {
            state.updateIsSyncing(true)
            println("[BPControl/Desktop] Starting readDacSettings")

            // Firmware version
            pullValueSync(CMD_READ_FW_VERSION)?.let { data ->
                val v0 = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt().toChar()
                val v1 = data[BlackPearlProtocol.ParserOffset.VALUE_MSB].toInt().toChar()
                val v2 = data[BlackPearlProtocol.ParserOffset.VALUE_GUARD].toInt().toChar()
                firmwareVersion = "$v0$v1$v2".trim()
                println("[BPControl/Desktop] Firmware: $firmwareVersion (profile=CB)")
            } ?: println("[BPControl/Desktop] FW version read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Filter
            pullValueSync(CMD_FILTER)?.let { data ->
                val idx = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt() - 1
                println("[BPControl/Desktop] Filter index=$idx")
                state.updateFilterIndex(idx)
            } ?: println("[BPControl/Desktop] Filter read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Gain mode
            pullValueSync(CMD_GAIN_MODE)?.let { data ->
                val idx = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                println("[BPControl/Desktop] GainMode index=$idx")
                state.updateGainModeIndex(idx)
            } ?: println("[BPControl/Desktop] GainMode read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Amp topo
            pullValueSync(CMD_AMP_TOPO)?.let { data ->
                val idx = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                println("[BPControl/Desktop] AmpTopo index=$idx")
                state.updateAmpTopoIndex(idx)
            } ?: println("[BPControl/Desktop] AmpTopo read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Volume
            pullValueSync(CMD_GLOBAL_GAIN)?.let { data ->
                val vol = mapper.parseVolumePercentOrNull(data)
                println("[BPControl/Desktop] Volume=$vol")
                vol?.let { state.updateVolumePercent(it) }
            } ?: println("[BPControl/Desktop] Volume read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Mic gain
            pullValueSync(CMD_MIC_GAIN, BlackPearlProtocol.Param.MIC_GAIN_PAGE, BlackPearlProtocol.Param.MIC_GAIN_PAGE)?.let { data ->
                val db = mapper.parseMicGainDb(data)
                println("[BPControl/Desktop] MicGain=$db dB")
                state.updateMicGainDb(db.toFloat())
            } ?: println("[BPControl/Desktop] MicGain read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Balance
            val balL = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
            val balR = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
            var dacBalLeft = 0; var dacBalRight = 0
            pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balL)?.let { data ->
                val mag = mapper.parseBalanceMagnitude(data)
                dacBalLeft = if (mag > 0) (mag - 256) else 0
                println("[BPControl/Desktop] BalanceLeft mag=$mag dacBalLeft=$dacBalLeft")
            } ?: println("[BPControl/Desktop] BalanceLeft read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balR)?.let { data ->
                val mag = mapper.parseBalanceMagnitude(data)
                dacBalRight = if (mag > 0) (256 - mag) else 0
                println("[BPControl/Desktop] BalanceRight mag=$mag dacBalRight=$dacBalRight")
            } ?: println("[BPControl/Desktop] BalanceRight read returned null")
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            state.updateDacBalance(dacBalLeft, dacBalRight)
            val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
            state.updateBalanceValue(
                (if (abs(combined) <= 1) 0f else combined.toFloat())
                    .coerceIn(-BALANCE_DB_LIMIT.toFloat(), BALANCE_DB_LIMIT.toFloat())
            )

            // PEQ bands
            activeSlot = END
            for (i in 0 until BlackPearlProtocol.Frame.BAND_COUNT) {
                pullValueSync(CMD_PEQ_VALUES, END, END, i.toByte())?.let { data ->
                    val parsed = mapper.parsePeqBand(data)
                    if (activeSlot == END && parsed.activeSlot != END) activeSlot = parsed.activeSlot
                    localBands[i] = FilterBand(
                        freq = parsed.freq, q = parsed.q,
                        gain = parsed.gain, type = parsed.type, enabled = parsed.enabled
                    )
                    println("[BPControl/Desktop] PEQ[$i] freq=${parsed.freq} gain=${parsed.gain} type=${parsed.type} enabled=${parsed.enabled}")
                } ?: println("[BPControl/Desktop] PEQ[$i] read returned null")
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            }
            println("[BPControl/Desktop] readDacSettings complete, activeSlot=0x${activeSlot.toInt().and(0xFF).toString(16)}")
        } finally {
            val presets = state.presets.value
            val matchIdx = PresetMatcher.identifyPreset(presets, localBands)
            if (matchIdx != -1) {
                state.updateCurrentPresetIndex(matchIdx)
            } else {
                val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                val nonePreset = presets.getOrNull(noneIdx)
                if (nonePreset != null) {
                    val updatedNone = nonePreset.copy(bands = localBands.toList())
                    state.updatePresets(presets.toMutableList().also { it[noneIdx] = updatedNone })
                }
                state.updateCurrentPresetIndex(noneIdx)
            }
            state.updateEqBands(localBands)
            state.updateIsConnected(true)
            state.updateIsSyncing(false)
        }
    }

    // ─── Volume polling ───────────────────────────────────────────────────────

    private fun startVolumePolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && running.get()) {
                if (state.isSyncing.value || state.isMassPushing.value) {
                    delay(BlackPearlProtocol.Timing.VOLUME_POLL_BUSY_DELAY_MS)
                    continue
                }
                delay(BlackPearlProtocol.Timing.VOLUME_POLL_INTERVAL_MS)

                val response = pullValueSync(CMD_GLOBAL_GAIN) ?: continue
                val roundedVol = mapper.parseVolumePercentOrNull(response) ?: continue
                if (abs(state.volumePercent.value - roundedVol) >= 1.0f) {
                    state.updateVolumePercent(roundedVol)
                }
            }
        }
    }

    // ─── HID command helpers ─────────────────────────────────────────────────

    private fun sendHidCommand(payload: ByteArray) = enqueue(payload)

    private fun latchSettings() {
        sendHidCommand(byteArrayOf(WRITE, BlackPearlProtocol.Command.LATCH_SETTINGS, BlackPearlProtocol.Param.BALANCE_LENGTH, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, END))
    }

    fun debouncedSaveToFlash() {
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(1000L)
            sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
        }
    }

    private fun sendFilterUpdate(index: Int, filter: FilterBand, autoLatch: Boolean = true) {
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        val effectiveFilter = filter.copy(gain = effectiveGain)
        val coeffs = BiquadMath.coefficients(effectiveFilter, effectiveGain)
        sendHidCommand(BlackPearlCodec.encodePeqUpdate(index = index, filter = effectiveFilter, coeffs = coeffs, activeSlot = activeSlot, profile = mapper.profile))
        state.updateLastSentPeq(index, effectiveFilter)
        if (autoLatch) {
            latchSettings()
            debouncedSaveToFlash()
        }
    }

    // ─── AppActions callbacks ─────────────────────────────────────────────────

    fun onVolumeChange(value: Float) {
        if (state.isSyncing.value) return
        state.updateVolumePercent(value)
        val totalRaw = (VOL_MIN_RAW + (value / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt().coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)
        sendHidCommand(byteArrayOf(WRITE, CMD_GLOBAL_GAIN, BlackPearlProtocol.Param.GLOBAL_GAIN_LENGTH, (totalRaw and 0xFF).toByte(), (totalRaw shr 8).toByte(), END))
        latchSettings()
        debouncedSaveToFlash()
    }

    fun onBalanceChange(value: Float) {
        state.updateBalanceValue(value)
        val v = value.toInt()
        val magL = if (v < 0) (256 + v) else 0
        val magR = if (v > 0) (256 - v) else 0
        val balL = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
        val balR = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
        sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balL, END, magL.toByte()))
        sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balR, END, magR.toByte()))
        latchSettings()
        debouncedSaveToFlash()
    }

    fun onFilterSelected(position: Int) {
        if (state.isSyncing.value) return
        state.updateFilterIndex(position)
        sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, (position + 1).toByte(), END))
        debouncedSaveToFlash()
    }

    fun onGainModeSelected(position: Int) {
        if (state.isSyncing.value) return
        state.updateGainModeIndex(position)
        sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, position.toByte(), END))
        debouncedSaveToFlash()
    }

    fun onAmpTopoSelected(position: Int) {
        if (state.isSyncing.value) return
        state.updateAmpTopoIndex(position)
        sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, position.toByte(), END))
        debouncedSaveToFlash()
    }

    fun onMicGainChange(value: Float) {
        state.updateMicGainDb(value)
        sendHidCommand(byteArrayOf(WRITE, CMD_MIC_GAIN, BlackPearlProtocol.Param.MIC_GAIN_LENGTH, BlackPearlProtocol.Param.MIC_GAIN_SIGNED_FLAG, (value.toInt() and 0xFF).toByte()))
        latchSettings()
        debouncedSaveToFlash()
    }

    fun onFactoryReset() {
        scope.launch {
            state.updateIsSyncing(true)
            state.updateVolumePercent(50f)
            onVolumeChange(50f)
            sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
            sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
            sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
            onBalanceChange(0f)
            val presets = state.presets.value
            val flatIdx = presets.indexOfFirst { it.name == "Flat" }.coerceAtLeast(0)
            state.updateCurrentPresetIndex(flatIdx)
            val flatPreset = presets.getOrNull(flatIdx)
            val flatBands = flatPreset?.bands ?: emptyList()
            flatBands.forEachIndexed { i, src ->
                sendFilterUpdate(i, src, autoLatch = false)
            }
            state.updateEqBands(flatBands)
            latchSettings()
            sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
            state.updateIsSyncing(false)
        }
    }

    fun onBandUpdated(index: Int, band: FilterBand, presetStorage: DesktopPresetStorage) {
        sendFilterUpdate(index, band)
        val presets = state.presets.value
        val idx = state.currentPresetIndex.value
        if (idx in presets.indices) {
            val p = presets[idx]
            val updated = p.copy(bands = p.bands.toMutableList().also { it[index] = band.copy() })
            val newPresets = presets.toMutableList().also { it[idx] = updated }
            state.updatePresets(newPresets)
            presetStorage.save(newPresets)
        }
        state.updateEqBand(index, band)
    }

    /** Mid-drag write: no latch, no flash, no preset persistence. See AppActions. */
    fun onBandDragUpdate(index: Int, band: FilterBand) {
        sendFilterUpdate(index, band, autoLatch = false)
    }

    fun onPresetLoaded(index: Int) {
        val presets = state.presets.value
        state.updateCurrentPresetIndex(index)
        val selected = presets.getOrNull(index) ?: return
        state.updateEqBands(selected.bands)
        scope.launch {
            state.updateIsMassPushing(true)
            selected.bands.forEachIndexed { i, b -> sendFilterUpdate(i, b, autoLatch = false) }
            latchSettings()
            delay(BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS * 10)
            state.updateIsMassPushing(false)
            debouncedSaveToFlash()
        }
    }

    fun onPresetSaved(name: String, presetStorage: DesktopPresetStorage) {
        val newPresets = state.presets.value.toMutableList()
        newPresets.add(Preset(name, state.eqBands.value.map { it.copy() }))
        state.updatePresets(newPresets)
        state.updateCurrentPresetIndex(newPresets.size - 1)
        presetStorage.save(newPresets)
    }

    fun onPresetDeleted(name: String, presetStorage: DesktopPresetStorage) {
        val originalIndex = state.presets.value.indexOfFirst { it.name == name }
        if (originalIndex == -1) return
        val newPresets = state.presets.value.toMutableList()
        newPresets.removeAt(originalIndex)
        var current = state.currentPresetIndex.value
        if (current == originalIndex) current = 0
        else if (current > originalIndex) current--
        state.updatePresets(newPresets)
        state.updateCurrentPresetIndex(current)
        presetStorage.save(newPresets)
    }

    fun onPresetRenamed(index: Int, newName: String, presetStorage: DesktopPresetStorage) {
        val presets = state.presets.value
        if (index !in presets.indices) return
        val newPresets = presets.toMutableList()
        newPresets[index] = newPresets[index].copy(name = newName)
        state.updatePresets(newPresets)
        presetStorage.save(newPresets)
    }

    fun onPresetDuplicated(index: Int, presetStorage: DesktopPresetStorage) {
        val presets = state.presets.value
        val source = presets.getOrNull(index) ?: return
        // Duplicating "None" is how a live hardware read gets saved as a real preset, so it
        // copies the live eqBands, not None's own bands.
        val bands = if (source.name == "None") {
            state.eqBands.value.map { it.copy() }
        } else {
            source.bands.map { it.copy() }
        }
        val name = uniqueName(source.name, presets)
        val newPresets = presets.toMutableList()
        newPresets.add(
            Preset(
                name = name,
                bands = bands,
                source = PresetSource.MANUAL,
                savedAt = System.currentTimeMillis(),
            )
        )
        state.updatePresets(newPresets)
        presetStorage.save(newPresets)
    }

    /**
     * Parses AutoEQ text picked from disk and appends it as a new IMPORTED preset — never an
     * overwrite of "None", which is the live-hardware slot and has nowhere durable to keep it.
     */
    fun onImport(text: String, suggestedName: String, presetStorage: DesktopPresetStorage) {
        val result = AutoEqParser.parse(text)
        if (result.bands.isEmpty()) return

        if (result.preamp < 0) {
            val volumePercent = state.volumePercent.value
            val newPercent = volDbToPct(volPctToDb(volumePercent) + result.preamp).coerceIn(0f, 100f)
            onVolumeChange(newPercent)
        }

        val localBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            if (i < result.bands.size) result.bands[i].copy()
            else FilterBand(enabled = false, type = FilterType.PK, freq = DEFAULT_BAND_FREQS[i], gain = 0f, q = 1.0f)
        }

        val presets = state.presets.value
        val name = uniqueName(suggestedName, presets)
        val newPresets = presets.toMutableList()
        newPresets.add(
            Preset(
                name = name,
                bands = localBands,
                source = PresetSource.IMPORTED,
                savedAt = System.currentTimeMillis(),
            )
        )
        state.updatePresets(newPresets)
        state.updateCurrentPresetIndex(newPresets.size - 1)
        state.updateEqBands(localBands)
        presetStorage.save(newPresets)

        scope.launch {
            state.updateIsMassPushing(true)
            localBands.forEachIndexed { i, b -> sendFilterUpdate(i, b, autoLatch = false) }
            latchSettings()
            delay(BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS * 10)
            state.updateIsMassPushing(false)
            debouncedSaveToFlash()
        }
    }
}
