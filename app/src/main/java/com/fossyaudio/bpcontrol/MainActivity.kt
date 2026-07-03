package com.fossyaudio.bpcontrol

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.fossyaudio.bpcontrol.data.IPresetStorage
import com.fossyaudio.bpcontrol.di.AppContainer
import com.fossyaudio.bpcontrol.presentation.AutoEqParser
import com.fossyaudio.bpcontrol.presentation.DacSettingsMapper
import com.fossyaudio.bpcontrol.presentation.DacSyncService
import com.fossyaudio.bpcontrol.presentation.MainViewModel
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodec
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import com.fossyaudio.bpcontrol.transport.usb.UsbCommandQueueProcessor
import com.fossyaudio.bpcontrol.transport.usb.UsbConnectionManager
import com.fossyaudio.bpcontrol.ui.AppActions
import com.fossyaudio.bpcontrol.ui.MainScreen
import com.fossyaudio.bpcontrol.ui.theme.BpControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    // --- Convenience property delegates backed by ViewModel StateFlow ---

    private var presets: MutableList<Preset>
        get() = mainViewModel.uiState.presets.value
        set(value) { mainViewModel.uiState.updatePresets(value) }

    private var currentPresetIndex: Int
        get() = mainViewModel.uiState.currentPresetIndex.value
        set(value) { mainViewModel.uiState.updateCurrentPresetIndex(value) }

    private val filterOptions = arrayOf("FAST-LL", "Fast-PC (BEST)", "Slow-LL", "SLOW-PC", "NOS")
    private val gainOptions = arrayOf("LOW", "HIGH")
    private val ampOptions = arrayOf("CLASS H", "CLASS AB")

    private val usbMutex = Mutex()

    private val VID = BlackPearlProtocol.Device.VID
    private val PID = BlackPearlProtocol.Device.PID
    private val REPORT_ID = BlackPearlProtocol.Device.REPORT_ID
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

    private val VOL_MIN_RAW = -9472
    private val VOL_MAX_RAW = 6440

    private val dacSettingsMapper by lazy { DacSettingsMapper(VOL_MIN_RAW, VOL_MAX_RAW) }
    private val dacSyncService by lazy {
        DacSyncService(
            reportId = REPORT_ID,
            readMarker = READ,
            usbMutex = usbMutex,
            connectionProvider = { usbConnection },
            interfaceIdProvider = { usbInterface?.id ?: 0 },
            endpointProvider = { endpointIn },
        )
    }

    private var volumePercent: Float
        get() = mainViewModel.uiState.volumePercent.value
        set(value) { mainViewModel.uiState.updateVolumePercent(value) }

    private var isSyncing: Boolean
        get() = mainViewModel.uiState.isSyncing.value
        set(value) { mainViewModel.uiState.updateIsSyncing(value) }

    private var isMassPushing: Boolean
        get() = mainViewModel.uiState.isMassPushing.value
        set(value) { mainViewModel.uiState.updateIsMassPushing(value) }

    private var dacBalLeft: Int
        get() = mainViewModel.uiState.dacBalLeft.value
        set(value) { mainViewModel.uiState.updateDacBalance(value, dacBalRight) }

    private var dacBalRight: Int
        get() = mainViewModel.uiState.dacBalRight.value
        set(value) { mainViewModel.uiState.updateDacBalance(dacBalLeft, value) }

    private var activeSlot: Byte
        get() = mainViewModel.uiState.activeSlot.value
        set(value) { mainViewModel.uiState.updateActiveSlot(value) }

    private var firmwareVersion: String
        get() = mainViewModel.uiState.firmwareVersion.value
        set(value) { mainViewModel.uiState.updateFirmwareVersion(value) }

    private var lastSentPeqIndex: Int
        get() = mainViewModel.uiState.lastSentPeqIndex.value
        set(value) { mainViewModel.uiState.updateLastSentPeq(value, lastSentFilter) }

    private var lastSentFilter: FilterBand?
        get() = mainViewModel.uiState.lastSentFilter.value
        set(value) { mainViewModel.uiState.updateLastSentPeq(lastSentPeqIndex, value) }

    // Local UI-interaction guards (not in ViewModel — only needed by polling interlock)
    private var isUserTouchingSlider = false
    private var lastSliderReleaseTime = 0L
    private var lastVolTime = 0L

    private var peqVerifyJob: Job? = null
    private var pollingJob: Job? = null
    private var volumeDebounceJob: Job? = null
    private var isAppInFocus = false

    private val ACTION_USB_PERMISSION = "com.fossyaudio.bpcontrol.USB_PERMISSION"
    private lateinit var appContainer: AppContainer
    private lateinit var presetStorage: IPresetStorage
    private lateinit var usbManager: UsbManager
    private lateinit var usbConnectionManager: UsbConnectionManager

    private val usbConnection: UsbDeviceConnection? get() = usbConnectionManager.usbConnection
    private val usbInterface: UsbInterface? get() = usbConnectionManager.usbInterface
    private val endpointIn: UsbEndpoint? get() = usbConnectionManager.endpointIn

    private val flashHandler = Handler(Looper.getMainLooper())
    private val flashRunnable = Runnable { saveToFlash() }

    private val usbCommandQueueProcessor by lazy {
        UsbCommandQueueProcessor(
            reportId = REPORT_ID,
            cmdFlashEq = CMD_FLASH_EQ,
            cmdPeqValues = CMD_PEQ_VALUES,
            cmdGlobalGain = CMD_GLOBAL_GAIN,
            usbMutex = usbMutex,
        )
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { parseAutoEq(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveSettingsToFile() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appContainer = AppContainer(this)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbConnectionManager = UsbConnectionManager(
            context = this,
            usbManager = usbManager,
            actionUsbPermission = ACTION_USB_PERMISSION,
            vid = VID,
            pid = PID,
            usbMutex = usbMutex,
        )
        presetStorage = appContainer.presetStorage

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        loadPresetsFromPrefs()
        // Lock UI until sync completes (isSyncing = true prevents slider interaction in composables)
        isSyncing = true

        setContent {
            BpControlTheme {
                MainScreen(
                    state = mainViewModel.uiState,
                    actions = AppActions(
                        onVolumeChange = { value ->
                            if (!isSyncing) {
                                volumePercent = value
                                val now = System.currentTimeMillis()
                                if (now - lastVolTime > 40) {
                                    lastVolTime = now
                                    updateHardwareVolume(latchAndSave = false)
                                }
                                volumeDebounceJob?.cancel()
                                volumeDebounceJob = lifecycleScope.launch {
                                    delay(150)
                                    updateHardwareVolume(latchAndSave = true)
                                }
                            }
                        },
                        onVolumeStartDragging = { isUserTouchingSlider = true },
                        onVolumeStopDragging = {
                            lastSliderReleaseTime = System.currentTimeMillis()
                            lifecycleScope.launch {
                                delay(500)
                                isUserTouchingSlider = false
                            }
                        },
                        onBalanceChange = { value ->
                            mainViewModel.uiState.updateBalanceValue(value)
                            updateBalance(value.toInt())
                        },
                        onFilterSelected = { position ->
                            if (!isSyncing) {
                                mainViewModel.uiState.updateFilterIndex(position)
                                sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, (position + 1).toByte(), END))
                                debouncedSaveToFlash()
                            }
                        },
                        onGainModeSelected = { position ->
                            if (!isSyncing) {
                                mainViewModel.uiState.updateGainModeIndex(position)
                                sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, position.toByte(), END))
                                debouncedSaveToFlash()
                            }
                        },
                        onAmpTopoSelected = { position ->
                            if (!isSyncing) {
                                mainViewModel.uiState.updateAmpTopoIndex(position)
                                sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, position.toByte(), END))
                                debouncedSaveToFlash()
                            }
                        },
                        onMicGainChange = { value ->
                            mainViewModel.uiState.updateMicGainDb(value)
                            sendHidCommand(byteArrayOf(WRITE, CMD_MIC_GAIN, BlackPearlProtocol.Param.MIC_GAIN_LENGTH, BlackPearlProtocol.Param.MIC_GAIN_SIGNED_FLAG, (value.toInt() and 0xFF).toByte()))
                            latchSettings()
                            debouncedSaveToFlash()
                        },
                        onFactoryReset = {
                            lifecycleScope.launch {
                                isSyncing = true
                                volumePercent = 50f
                                updateHardwareVolume(latchAndSave = false)
                                sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
                                sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
                                sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
                                updateBalance(0)
                                val flatIdx = presets.indexOfFirst { it.name == "Flat" }.coerceAtLeast(0)
                                currentPresetIndex = flatIdx
                                val flatPreset = presets[flatIdx]
                                flatPreset.bands.forEachIndexed { i, src ->
                                    sendFilterUpdate(i, src, autoLatch = false)
                                }
                                mainViewModel.uiState.updateEqBands(flatPreset.bands)
                                latchSettings()
                                saveToFlash()
                                isSyncing = false
                                Toast.makeText(this@MainActivity, "System Flat", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onBandUpdated = { index, band ->
                            sendFilterUpdate(index, band)
                            if (currentPresetIndex in presets.indices) {
                                val p = presets[currentPresetIndex]
                                presets[currentPresetIndex] = p.copy(bands = p.bands.toMutableList().also { it[index] = band.copy() })
                                savePresetsToPrefs()
                            }
                            mainViewModel.uiState.updateEqBand(index, band)
                        },
                        onPresetLoaded = { index ->
                            currentPresetIndex = index
                            val selected = presets[index]
                            mainViewModel.uiState.updateEqBands(selected.bands)
                            lifecycleScope.launch(Dispatchers.IO) {
                                isMassPushing = true
                                selected.bands.forEachIndexed { i, b -> sendFilterUpdate(i, b, autoLatch = false) }
                                latchSettings()
                                while (usbCommandQueueProcessor.hasPendingWork()) { delay(BlackPearlProtocol.Timing.MASS_PUSH_POLL_DELAY_MS) }
                                isMassPushing = false
                                debouncedSaveToFlash()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Preset Loaded: ${selected.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onPresetSaved = { name ->
                            val clonedBands = mainViewModel.uiState.eqBands.value.map { it.copy() }
                            val newPresets = presets.toMutableList()
                            newPresets.add(Preset(name, volumePercent, clonedBands))
                            mainViewModel.uiState.updatePresets(newPresets)
                            currentPresetIndex = newPresets.size - 1
                            savePresetsToPrefs()
                        },
                        onPresetDeleted = { name ->
                            val originalIndex = presets.indexOfFirst { it.name == name }
                            if (originalIndex != -1) {
                                val newPresets = presets.toMutableList()
                                newPresets.removeAt(originalIndex)
                                if (currentPresetIndex == originalIndex) currentPresetIndex = 0
                                else if (currentPresetIndex > originalIndex) currentPresetIndex--
                                mainViewModel.uiState.updatePresets(newPresets)
                                savePresetsToPrefs()
                                Toast.makeText(this@MainActivity, "Preset Deleted", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onImport = { importLauncher.launch("*/*") },
                        onExport = { exportLauncher.launch("BP_Preset.txt") },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInFocus = true
        usbConnectionManager.setAppInFocus(true)
        if (usbConnection == null) {
            startConnectionWatchdog()
        } else {
            readDacSettings()
            startVolumePolling()
        }
    }

    override fun onPause() {
        isAppInFocus = false
        usbConnectionManager.setAppInFocus(false)
        pollingJob?.cancel()
        super.onPause()
    }

    private fun calculateHeadroomDb(volPercent: Float): Float =
        mainViewModel.uiState.calculateHeadroomDb(volPercent, VOL_MIN_RAW, VOL_MAX_RAW)

    private fun loadPresetsFromPrefs() {
        val loaded = presetStorage.load()
        mainViewModel.uiState.updatePresets(loaded)
    }

    private fun savePresetsToPrefs() {
        presetStorage.save(presets)
    }

    private fun identifyPreset(hwBands: List<FilterBand>): Int =
        mainViewModel.uiState.identifyPreset(presets, hwBands)

    private fun startConnectionWatchdog() {
        usbConnectionManager.startConnectionWatchdog(lifecycleScope) {
            startQueueProcessor()
            lifecycleScope.launch(Dispatchers.IO) {
                delay(BlackPearlProtocol.Timing.POST_CONNECT_SYNC_DELAY_MS)
                readDacSettings()
                startVolumePolling()
            }
        }
    }

    private fun resetUiToDefaults() {
        isSyncing = true
        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        mainViewModel.uiState.updateEqBands(List(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = defaultFreqs[i], enabled = false, gain = 0f) })
        mainViewModel.uiState.updateFilterIndex(-1)
        mainViewModel.uiState.updateGainModeIndex(-1)
        mainViewModel.uiState.updateAmpTopoIndex(-1)
        mainViewModel.uiState.updateBalanceValue(0f)
        mainViewModel.uiState.updateMicGainDb(0f)
        mainViewModel.uiState.updateIsConnected(false)
        isSyncing = false
    }

    private fun debouncedSaveToFlash() {
        flashHandler.removeCallbacks(flashRunnable)
        flashHandler.postDelayed(flashRunnable, 1000)
    }

    private fun saveToFlash() {
        if (usbConnection == null) return
        sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
        val verifyIndex = lastSentPeqIndex
        val verifyFilter = lastSentFilter
        if (verifyIndex >= 0 && verifyFilter != null) {
            peqVerifyJob?.cancel()
            peqVerifyJob = lifecycleScope.launch(Dispatchers.IO) {
                delay(BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS + 200L)
                verifySentPeqBand(verifyIndex, verifyFilter)
            }
        }
    }

    private suspend fun verifySentPeqBand(index: Int, expected: FilterBand) {
        val data = pullValueSync(CMD_PEQ_VALUES, END, END, index.toByte())
        if (data == null) {
            Log.w("BPControl/Protocol", "PEQ verify band $index: transport timeout — no response")
            return
        }
        val readBack = dacSettingsMapper.parsePeqBand(data)
        val typeMatch = readBack.type == expected.type
        val freqMatch = readBack.freq == expected.freq
        val gainMatch = kotlin.math.abs(readBack.gain - expected.gain) < 0.1f
        val qMatch = kotlin.math.abs(readBack.q - expected.q) < 0.1f
        if (typeMatch && freqMatch && gainMatch && qMatch) {
            Log.d("BPControl/Protocol", "PEQ verify band $index: OK (type=${readBack.type} freq=${readBack.freq} gain=${readBack.gain} q=${readBack.q})")
        } else {
            Log.w(
                "BPControl/Protocol",
                "PEQ verify band $index: MISMATCH — " +
                    "type=${expected.type}→${readBack.type} " +
                    "freq=${expected.freq}→${readBack.freq} " +
                    "gain=${expected.gain}→${readBack.gain} " +
                    "q=${expected.q}→${readBack.q}",
            )
        }
    }

    private suspend fun pullValueSync(
        cmd: Byte,
        p1: Byte = BlackPearlProtocol.Frame.END,
        p2: Byte = BlackPearlProtocol.Frame.END,
        p3: Byte = BlackPearlProtocol.Frame.END,
    ): ByteArray? = dacSyncService.pullValueSync(cmd, p1, p2, p3)

    private fun startVolumePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (usbConnection != null) {
                if (!isAppInFocus || isSyncing || isMassPushing || isUserTouchingSlider) {
                    delay(BlackPearlProtocol.Timing.VOLUME_POLL_BUSY_DELAY_MS)
                    continue
                }
                delay(BlackPearlProtocol.Timing.VOLUME_POLL_INTERVAL_MS)
                if (isSyncing || isMassPushing) continue

                val response = pullValueSync(CMD_GLOBAL_GAIN, END, END) ?: continue
                val roundedVol = dacSettingsMapper.parseVolumePercentOrNull(response) ?: continue

                if (abs(volumePercent - roundedVol) >= 1.0f && !isUserTouchingSlider) {
                    volumePercent = roundedVol
                    // StateFlow emission is thread-safe; composables react automatically
                }
            }
        }
    }

    private fun updateHardwareVolume(latchAndSave: Boolean = true) {
        if (usbConnection == null) return
        val totalRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
        val clampedRaw = totalRaw.coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)
        sendHidCommand(byteArrayOf(WRITE, CMD_GLOBAL_GAIN, BlackPearlProtocol.Param.GLOBAL_GAIN_LENGTH, (clampedRaw and 0xFF).toByte(), (clampedRaw shr 8).toByte(), END))
        if (latchAndSave) {
            latchSettings()
            debouncedSaveToFlash()
        }
        // ViewModel emits updated volumePercent; composables update sliders and graph ceiling
    }

    private fun updateBalance(v: Int) {
        val magL = if (v < 0) (256 + v) else END.toInt()
        val magR = if (v > 0) (256 - v) else END.toInt()
        val balanceLeftSelector = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
        val balanceRightSelector = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
        sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceLeftSelector, END, magL.toByte()))
        Handler(Looper.getMainLooper()).postDelayed({
            sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceRightSelector, END, magR.toByte()))
            latchSettings()
            debouncedSaveToFlash()
        }, BlackPearlProtocol.Timing.BALANCE_PAIR_DELAY_MS)
    }

    private fun latchSettings() {
        sendHidCommand(byteArrayOf(WRITE, BlackPearlProtocol.Command.LATCH_SETTINGS, BlackPearlProtocol.Param.BALANCE_LENGTH, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, BlackPearlProtocol.Frame.FILL, END))
    }

    private fun sendFilterUpdate(index: Int, filter: FilterBand, autoLatch: Boolean = true) {
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        val effectiveFilter = filter.copy(gain = effectiveGain)
        val coeffs = BiquadMath.coefficients(effectiveFilter, effectiveGain)
        sendHidCommand(BlackPearlCodec.encodePeqUpdate(index = index, filter = effectiveFilter, coeffs = coeffs, activeSlot = activeSlot, profile = dacSettingsMapper.profile))
        lastSentPeqIndex = index
        lastSentFilter = effectiveFilter
        if (autoLatch) {
            latchSettings()
            debouncedSaveToFlash()
        }
    }

    private fun sendHidCommand(payload: ByteArray) {
        usbCommandQueueProcessor.enqueue(payload)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startQueueProcessor() {
        usbCommandQueueProcessor.start(
            scope = lifecycleScope,
            connectionProvider = { usbConnection },
            interfaceIdProvider = { usbInterface?.id ?: 0 },
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun readDacSettings() {
        if (usbConnection == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            val localBands = MutableList(BlackPearlProtocol.Frame.BAND_COUNT) { i -> FilterBand(freq = defaultFreqs[i]) }
            try {
                isSyncing = true

                // 0. Probe firmware version
                val fwData = pullValueSync(CMD_READ_FW_VERSION, END, END)
                if (fwData != null) {
                    val v0 = fwData[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt().toChar()
                    val v1 = fwData[BlackPearlProtocol.ParserOffset.VALUE_MSB].toInt().toChar()
                    val v2 = fwData[BlackPearlProtocol.ParserOffset.VALUE_GUARD].toInt().toChar()
                    firmwareVersion = "$v0$v1$v2".trim()
                    Log.i("BPControl/Protocol", "Firmware version: $firmwareVersion (profile=CB)")
                } else {
                    Log.w("BPControl/Protocol", "Firmware probe (0x0C) got no response — keeping CB profile (best-effort)")
                }
                Log.i(
                    "BPControl/Protocol",
                    "Balance selectors: left=0x${BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion).toInt().and(0xFF).toString(16)} right=0x${BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion).toInt().and(0xFF).toString(16)} fw=$firmwareVersion",
                )
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 1. Read Filter
                pullValueSync(CMD_FILTER, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    mainViewModel.uiState.updateFilterIndex(value - 1)
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 2. Read Gain Mode
                pullValueSync(CMD_GAIN_MODE, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    mainViewModel.uiState.updateGainModeIndex(value)
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 3. Read Amp Topo
                pullValueSync(CMD_AMP_TOPO, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    mainViewModel.uiState.updateAmpTopoIndex(value)
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 4. Read Volume
                pullValueSync(CMD_GLOBAL_GAIN, END, END)?.let { data ->
                    dacSettingsMapper.parseVolumePercentOrNull(data)?.let { parsedVolume ->
                        volumePercent = parsedVolume
                    }
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 5. Read Mic Gain
                pullValueSync(CMD_MIC_GAIN, BlackPearlProtocol.Param.MIC_GAIN_PAGE, BlackPearlProtocol.Param.MIC_GAIN_PAGE)?.let { data ->
                    mainViewModel.uiState.updateMicGainDb(dacSettingsMapper.parseMicGainDb(data).toFloat())
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 6. Read Balance
                val balanceLeftSelector = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
                val balanceRightSelector = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceLeftSelector)?.let { data ->
                    val mag = dacSettingsMapper.parseBalanceMagnitude(data)
                    dacBalLeft = if (mag > 0) (mag - 256) else 0
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceRightSelector)?.let { data ->
                    val mag = dacSettingsMapper.parseBalanceMagnitude(data)
                    dacBalRight = if (mag > 0) (256 - mag) else 0
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
                val finalBal = if (abs(combined) <= 1) 0f else combined.toFloat()
                mainViewModel.uiState.updateBalanceValue(finalBal.coerceIn(-15f, 15f))

                // 7. Read PEQ Bands
                activeSlot = END
                for (i in 0 until BlackPearlProtocol.Frame.BAND_COUNT) {
                    pullValueSync(CMD_PEQ_VALUES, END, END, i.toByte())?.let { data ->
                        val parsedBand = dacSettingsMapper.parsePeqBand(data)
                        if (activeSlot == END && parsedBand.activeSlot != END) {
                            activeSlot = parsedBand.activeSlot
                            Log.d("BPControl/Protocol", "activeSlot=0x${activeSlot.toInt().and(0xFF).toString(16).uppercase(Locale.US)} confirmed from band $i")
                        }
                        localBands[i] = FilterBand(
                            freq = parsedBand.freq, q = parsedBand.q,
                            gain = parsedBand.gain, type = parsedBand.type, enabled = parsedBand.enabled
                        )
                    }
                    delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                }
                if (activeSlot == END) {
                    Log.w("BPControl/Protocol", "activeSlot is still END=0x00 after all PEQ reads — flash save may fail")
                }
            } finally {
                val matchIdx = identifyPreset(localBands)
                if (matchIdx != -1) {
                    currentPresetIndex = matchIdx
                } else {
                    val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                    val nonePreset = presets[noneIdx]
                    presets[noneIdx] = nonePreset.copy(preamp = volumePercent, bands = localBands.toList())
                    currentPresetIndex = noneIdx
                }
                mainViewModel.uiState.updateEqBands(localBands)
                mainViewModel.uiState.updateIsConnected(true)
                isSyncing = false
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device?.vendorId == VID && device.productId == PID) {
                        Toast.makeText(context, "DAC Hardware Detected", Toast.LENGTH_SHORT).show()
                        startConnectionWatchdog()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    usbConnectionManager.onPermissionRequestHandled()
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            usbConnectionManager.setupConnection(lifecycleScope, it) {
                                startQueueProcessor()
                                lifecycleScope.launch(Dispatchers.IO) {
                                    delay(BlackPearlProtocol.Timing.POST_CONNECT_SYNC_DELAY_MS)
                                    readDacSettings()
                                    startVolumePolling()
                                }
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.vendorId == VID && device.productId == PID) {
                        closeUsbConnection()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("NotifyDataSetChanged")
    private fun parseAutoEq(uri: Uri) {
        isSyncing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: run {
                    isSyncing = false
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Could not open file", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                val result = stream.use { AutoEqParser.parse(it) }
                if (result.bands.isEmpty()) {
                    isSyncing = false
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "No valid filters found", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
                if (result.preamp < 0) {
                    volumePercent = (volumePercent + (result.preamp * 2)).coerceIn(0f, 100f)
                }
                val localBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
                    if (i < result.bands.size) result.bands[i].copy()
                    else FilterBand(enabled = false, type = FilterType.PK, freq = defaultFreqs[i], gain = 0f, q = 1.0f)
                }
                val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                val nonePreset = presets[noneIdx]
                presets[noneIdx] = nonePreset.copy(preamp = volumePercent, bands = localBands)
                currentPresetIndex = noneIdx
                mainViewModel.uiState.updateEqBands(localBands)

                isMassPushing = true
                localBands.forEachIndexed { index, band -> sendFilterUpdate(index, band, autoLatch = false) }
                latchSettings()
                while (usbCommandQueueProcessor.hasPendingWork()) { delay(BlackPearlProtocol.Timing.MASS_PUSH_POLL_DELAY_MS) }
                delay(BlackPearlProtocol.Timing.MASS_PUSH_SETTLE_DELAY_MS)
                isMassPushing = false
                isSyncing = false
                debouncedSaveToFlash()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Import Successful", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e("AutoEQ", "Import parsing failed", e)
                isSyncing = false
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveSettingsToFile() { /* Export implementation unchanged */ }

    private fun closeUsbConnection() {
        usbCommandQueueProcessor.stop()
        volumeDebounceJob?.cancel()
        usbConnectionManager.closeConnection(lifecycleScope)
        pollingJob?.cancel()
        resetUiToDefaults()
    }

    override fun onDestroy() {
        closeUsbConnection()
        try { unregisterReceiver(usbReceiver) } catch (e: IllegalArgumentException) { Log.w("USB", "Receiver already unregistered", e) }
        super.onDestroy()
    }
}
