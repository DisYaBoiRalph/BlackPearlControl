package com.fossyaudio.bpcontrol.desktop

import com.fossyaudio.bpcontrol.presentation.DacSettingsMapper
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
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

    private val VOL_MIN_RAW = -9472
    private val VOL_MAX_RAW = 6440

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

    // Working mutable EQ bands list (same pattern as MainActivity)
    private val eqBands = MutableList(10) { i ->
        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        FilterBand(freq = defaultFreqs[i])
    }

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

                // hid4java write: prepend report ID as first byte
                val buf = ByteArray(payload.size + 1)
                buf[0] = BlackPearlProtocol.Device.REPORT_ID
                payload.copyInto(buf, destinationOffset = 1)
                dev.write(buf, buf.size, BlackPearlProtocol.Device.REPORT_ID)
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
        val dev = device ?: return null
        if (!dev.isOpen) return null

        val request = BlackPearlCodec.encodeReadRequest(cmd, p1, p2, p3)
        val buf = ByteArray(request.size + 1)
        buf[0] = BlackPearlProtocol.Device.REPORT_ID
        request.copyInto(buf, destinationOffset = 1)
        dev.write(buf, buf.size, BlackPearlProtocol.Device.REPORT_ID)

        val response = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
        val read = dev.read(response, BlackPearlProtocol.Timing.READ_TRANSFER_TIMEOUT_MS)
        return if (read > 0) response else null
    }

    // ─── Initial settings readback ───────────────────────────────────────────

    private suspend fun readDacSettings() {
        try {
            state.updateIsSyncing(true)

            // Firmware version
            pullValueSync(CMD_READ_FW_VERSION)?.let { data ->
                val v0 = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt().toChar()
                val v1 = data[BlackPearlProtocol.ParserOffset.VALUE_MSB].toInt().toChar()
                val v2 = data[BlackPearlProtocol.ParserOffset.VALUE_GUARD].toInt().toChar()
                firmwareVersion = "$v0$v1$v2".trim()
                println("[BPControl/Desktop] Firmware: $firmwareVersion (profile=CB)")
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Filter
            pullValueSync(CMD_FILTER)?.let { data ->
                state.updateFilterIndex(data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt() - 1)
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Gain mode
            pullValueSync(CMD_GAIN_MODE)?.let { data ->
                state.updateGainModeIndex(data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt())
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Amp topo
            pullValueSync(CMD_AMP_TOPO)?.let { data ->
                state.updateAmpTopoIndex(data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt())
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Volume
            pullValueSync(CMD_GLOBAL_GAIN)?.let { data ->
                mapper.parseVolumePercentOrNull(data)?.let { state.updateVolumePercent(it) }
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Mic gain
            pullValueSync(CMD_MIC_GAIN, BlackPearlProtocol.Param.MIC_GAIN_PAGE, BlackPearlProtocol.Param.MIC_GAIN_PAGE)?.let { data ->
                state.updateMicGainDb(mapper.parseMicGainDb(data).toFloat())
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

            // Balance
            val balL = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
            val balR = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
            var dacBalLeft = 0; var dacBalRight = 0
            pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balL)?.let { data ->
                val mag = mapper.parseBalanceMagnitude(data)
                dacBalLeft = if (mag > 0) (mag - 256) else 0
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balR)?.let { data ->
                val mag = mapper.parseBalanceMagnitude(data)
                dacBalRight = if (mag > 0) (256 - mag) else 0
            }
            delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            state.updateDacBalance(dacBalLeft, dacBalRight)
            val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
            state.updateBalanceValue((if (abs(combined) <= 1) 0f else combined.toFloat()).coerceIn(-15f, 15f))

            // PEQ bands
            activeSlot = END
            for (i in 0 until 10) {
                pullValueSync(CMD_PEQ_VALUES, END, END, i.toByte())?.let { data ->
                    val parsed = mapper.parsePeqBand(data)
                    if (activeSlot == END && parsed.activeSlot != END) activeSlot = parsed.activeSlot
                    eqBands[i].apply {
                        freq = parsed.freq; q = parsed.q
                        gain = parsed.gain; type = parsed.type; enabled = parsed.enabled
                    }
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
            }
        } finally {
            val presets = state.presets.value
            val matchIdx = state.identifyPreset(presets, eqBands)
            if (matchIdx != -1) {
                state.updateCurrentPresetIndex(matchIdx)
            } else {
                val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                val nonePreset = presets.getOrNull(noneIdx)
                if (nonePreset != null) {
                    for (i in 0 until 10) nonePreset.bands[i] = eqBands[i].copy()
                }
                state.updateCurrentPresetIndex(noneIdx)
            }
            state.updateEqBands(eqBands)
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
            eqBands.forEachIndexed { i, band ->
                val src = flatPreset?.bands?.get(i) ?: return@forEachIndexed
                band.apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; q = src.q }
                sendFilterUpdate(i, band, autoLatch = false)
            }
            state.updateEqBands(eqBands)
            latchSettings()
            sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
            state.updateIsSyncing(false)
        }
    }

    fun onBandUpdated(index: Int, band: FilterBand, presetStorage: DesktopPresetStorage) {
        eqBands[index] = band.copy()
        sendFilterUpdate(index, band)
        val presets = state.presets.value
        val idx = state.currentPresetIndex.value
        if (idx in presets.indices) {
            presets[idx].bands[index] = band.copy()
            presetStorage.save(presets)
        }
        state.updateEqBand(index, band)
    }

    fun onPresetLoaded(index: Int) {
        val presets = state.presets.value
        state.updateCurrentPresetIndex(index)
        val selected = presets.getOrNull(index) ?: return
        eqBands.forEachIndexed { i, band ->
            val src = selected.bands[i]
            band.apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; q = src.q }
        }
        state.updateEqBands(eqBands)
        scope.launch {
            state.updateIsMassPushing(true)
            eqBands.forEachIndexed { i, b -> sendFilterUpdate(i, b, autoLatch = false) }
            latchSettings()
            delay(BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS * 10)
            state.updateIsMassPushing(false)
            debouncedSaveToFlash()
        }
    }

    fun onPresetSaved(name: String, presetStorage: DesktopPresetStorage) {
        val newPresets = state.presets.value.toMutableList()
        newPresets.add(Preset(name, state.volumePercent.value, eqBands.map { it.copy() }.toMutableList()))
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
}
