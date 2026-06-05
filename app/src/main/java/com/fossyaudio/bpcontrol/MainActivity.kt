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
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.slider.Slider
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
import com.fossyaudio.bpcontrol.transport.usb.UsbConnectionManager
import com.fossyaudio.bpcontrol.transport.usb.UsbCommandQueueProcessor
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

    private var presets: MutableList<Preset>
        get() = mainViewModel.presets
        set(value) {
            mainViewModel.presets = value
        }

    private var currentPresetIndex: Int
        get() = mainViewModel.currentPresetIndex
        set(value) {
            mainViewModel.currentPresetIndex = value
        }

    // Move these to the top of the class
    private val filterOptions = arrayOf("FAST-LL", "Fast-PC (BEST)", "Slow-LL", "SLOW-PC", "NOS")
    private val gainOptions = arrayOf("LOW", "HIGH")
    private val ampOptions = arrayOf("CLASS H", "CLASS AB")

    private val usbMutex = Mutex() // CRITICAL: Prevents thread collision kernel panics

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
    private val dacSettingsMapper by lazy {
        DacSettingsMapper(VOL_MIN_RAW, VOL_MAX_RAW)
    }
    private val dacSyncService by lazy {
        DacSyncService(
            reportId = REPORT_ID,
            readMarker = READ,
            usbMutex = usbMutex,
            connectionProvider = { usbConnection },
            interfaceIdProvider = { usbInterface?.id ?: 0 },
            endpointProvider = { endpointIn }
        )
    }

    private var isUserTouchingSlider = false
    private var lastSliderReleaseTime = 0L

    private var volumePercent: Float
        get() = mainViewModel.volumePercent
        set(value) {
            mainViewModel.volumePercent = value
        }

    private var isSyncing: Boolean
        get() = mainViewModel.isSyncing
        set(value) {
            mainViewModel.isSyncing = value
        }

    private var isMassPushing: Boolean
        get() = mainViewModel.isMassPushing
        set(value) {
            mainViewModel.isMassPushing = value
        }

    private var dacBalLeft: Int
        get() = mainViewModel.dacBalLeft
        set(value) {
            mainViewModel.dacBalLeft = value
        }

    private var dacBalRight: Int
        get() = mainViewModel.dacBalRight
        set(value) {
            mainViewModel.dacBalRight = value
        }

    private var activeSlot: Byte
        get() = mainViewModel.activeSlot
        set(value) {
            mainViewModel.activeSlot = value
        }

    private var firmwareVersion: String
        get() = mainViewModel.firmwareVersion
        set(value) {
            mainViewModel.firmwareVersion = value
        }

    private var lastSentPeqIndex: Int
        get() = mainViewModel.lastSentPeqIndex
        set(value) {
            mainViewModel.lastSentPeqIndex = value
        }

    private var lastSentFilter: FilterBand?
        get() = mainViewModel.lastSentFilter
        set(value) {
            mainViewModel.lastSentFilter = value
        }

    private var peqVerifyJob: Job? = null

    private val usbCommandQueueProcessor by lazy {
        UsbCommandQueueProcessor(
            reportId = REPORT_ID,
            cmdFlashEq = CMD_FLASH_EQ,
            cmdPeqValues = CMD_PEQ_VALUES,
            cmdGlobalGain = CMD_GLOBAL_GAIN,
            usbMutex = usbMutex
        )
    }
    private var pollingJob: Job? = null
    private var volumeDebounceJob: Job? = null
    private var isAppInFocus = false // FIX: Declare the focus tracker
    private var lastVolTime = 0L // Limits live dragging to 25 FPS
    private val eqBands = MutableList(10) { i ->
        val frequencies = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        FilterBand(freq = frequencies[i])
    }

    private val ACTION_USB_PERMISSION = "com.fossyaudio.bpcontrol.USB_PERMISSION"
    private lateinit var appContainer: AppContainer
    private lateinit var presetStorage: IPresetStorage
    private lateinit var usbManager: UsbManager
    private lateinit var usbConnectionManager: UsbConnectionManager
    private val usbConnection: UsbDeviceConnection?
        get() = usbConnectionManager.usbConnection
    private val usbInterface: UsbInterface?
        get() = usbConnectionManager.usbInterface
    private val endpointIn: UsbEndpoint?
        get() = usbConnectionManager.endpointIn

    // Flash Debouncer
    private val flashHandler = Handler(Looper.getMainLooper())
    private val flashRunnable = Runnable { saveToFlash() }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { parseAutoEq(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveSettingsToFile() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        appContainer = AppContainer(this)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbConnectionManager = UsbConnectionManager(
            context = this,
            usbManager = usbManager,
            actionUsbPermission = ACTION_USB_PERMISSION,
            vid = VID,
            pid = PID,
            usbMutex = usbMutex
        )
        presetStorage = appContainer.presetStorage
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            bottomNavigation?.setPadding(
                bottomNavigation.paddingLeft,
                bottomNavigation.paddingTop,
                bottomNavigation.paddingRight,
                systemBars.bottom
            )
            insets
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)

        // FIX: Let AndroidX handle the API level branching and exact flag mapping
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadPresetsFromPrefs()

        initUi()
        initEq() // FIX: Build the EQ page once in the background at startup

        // CRITICAL FIX: Lock the UI immediately on launch.
        // It will be automatically unlocked by readDacSettings() once sync completes.
        findViewById<Slider>(R.id.volumeSlider)?.isEnabled = false
        findViewById<Slider>(R.id.eqMasterVolume)?.isEnabled = false
        findViewById<Slider>(R.id.balanceSlider)?.isEnabled = false
        findViewById<Slider>(R.id.micGainSlider)?.isEnabled = false

        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    findViewById<View>(R.id.settingsContainer)?.visibility = View.VISIBLE
                    findViewById<View>(R.id.eqContainer)?.visibility = View.GONE
                    true
                }
                R.id.nav_eq -> {
                    findViewById<View>(R.id.settingsContainer)?.visibility = View.GONE
                    findViewById<View>(R.id.eqContainer)?.visibility = View.VISIBLE
                    // Removed initEq() so it just reveals the pre-loaded page instantly
                    true
                }
                else -> false
            }
        }

    }

    override fun onResume() {
        super.onResume()
        isAppInFocus = true
        usbConnectionManager.setAppInFocus(true)

        if (usbConnection == null) {
            // App just launched or was fully killed, start hunting for the DAC
            startConnectionWatchdog()
        } else {
            // App came back from background, connection is already alive
            // Just refresh the UI and restart the volume listener
            readDacSettings()
            startVolumePolling()
        }
    }

    override fun onPause() {
        isAppInFocus = false
        usbConnectionManager.setAppInFocus(false)
        // Stop background polling to save battery, but DO NOT sever the USB connection
        pollingJob?.cancel()
        super.onPause()
    }

    private fun calculateHeadroomDb(volPercent: Float): Float {
        return mainViewModel.calculateHeadroomDb(volPercent, VOL_MIN_RAW, VOL_MAX_RAW)
    }

    private fun showDeletePresetDialog() {
        // Filter out system presets: "Flat" and "None" should not be deletable
        val deletablePresets = presets.filter { it.name != "Flat" && it.name != "None" }

        if (deletablePresets.isEmpty()) {
            Toast.makeText(this, "No user presets to delete", Toast.LENGTH_SHORT).show()
            return
        }

        val names = deletablePresets.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Delete Preset")
            .setItems(names) { _, which ->
                val selectedName = names[which]

                // Double confirmation for safety
                AlertDialog.Builder(this)
                    .setTitle("Confirm Deletion")
                    .setMessage("Are you sure you want to delete '$selectedName'?")
                    .setPositiveButton("Delete") { _, _ ->
                        val originalIndex = presets.indexOfFirst { it.name == selectedName }
                        if (originalIndex != -1) {
                            presets.removeAt(originalIndex)

                            // Adjust current index so the app doesn't crash or point to the wrong data
                            if (currentPresetIndex == originalIndex) {
                                // If we deleted the active preset, default to "Flat" (index 0)
                                currentPresetIndex = 0
                                findViewById<Button>(R.id.btnPresets)?.text = presets[0].name
                            } else if (currentPresetIndex > originalIndex) {
                                // Shift index down if we deleted something above it in the list
                                currentPresetIndex--
                            }

                            savePresetsToPrefs()
                            Toast.makeText(this, "Preset Deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun debouncedSaveToFlash() {
        // Stop any pending save and schedule a new one after 1 second of silence
        flashHandler.removeCallbacks(flashRunnable)
        flashHandler.postDelayed(flashRunnable, 1000)
    }

    private fun loadPresetsFromPrefs() {
        presets.clear()
        presets.addAll(presetStorage.load())
    }

    private fun savePresetsToPrefs() {
        presetStorage.save(presets)
    }

    private fun identifyPreset(hwBands: List<FilterBand>): Int {
        return mainViewModel.identifyPreset(presets, hwBands)
    }

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

    @SuppressLint("NotifyDataSetChanged")
    private fun resetUiToDefaults() {
        runOnUiThread {
            isSyncing = true // Prevent phantom commands while clearing

            // CRITICAL FIX: Do NOT reset slider values to 0 here.
            // Setting values to 0 onPause causes Android to "save" that 0 state,
            // which then gets restored and pushed to the hardware onResume.
            // Instead, we just disable the controls so they can't be moved.
            findViewById<Slider>(R.id.volumeSlider)?.isEnabled = false
            findViewById<Slider>(R.id.eqMasterVolume)?.isEnabled = false
            findViewById<Slider>(R.id.balanceSlider)?.isEnabled = false
            findViewById<Slider>(R.id.micGainSlider)?.isEnabled = false

            // 2. Clear Dropdowns
            findViewById<AutoCompleteTextView>(R.id.filterSelector)?.setText("", false)
            findViewById<AutoCompleteTextView>(R.id.gainSelector)?.setText("", false)
            findViewById<AutoCompleteTextView>(R.id.ampSelector)?.setText("", false)

            // 3. Clear EQ List & Graph
            eqBands.forEach { it.enabled = false; it.gain = 0f }
            // ... inside readDacSettings() -> finally -> withContext(Dispatchers.Main)
            findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()

            findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                this.bands = eqBands.map { it.copy() }
                // NEW: Calculate and set the graph ceiling based on the freshly synced hardware volume
                this.preampDb = calculateHeadroomDb(volumePercent)
                pathDirty = true
                postInvalidate()
            }

            // Release the sync flag LAST
            isSyncing = false
        }
    }

    private fun saveToFlash() {
        if (usbConnection == null) return
        sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
        val verifyIndex = lastSentPeqIndex
        val verifyFilter = lastSentFilter
        if (verifyIndex >= 0 && verifyFilter != null) {
            peqVerifyJob?.cancel()
            peqVerifyJob = lifecycleScope.launch(Dispatchers.IO) {
                // Wait for flash to settle: queue flush delay (600ms) + buffer
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
                    "q=${expected.q}→${readBack.q}"
            )
        }
    }

    // Added 'suspend' keyword to allow locking
    private suspend fun pullValueSync(
        cmd: Byte,
        p1: Byte = BlackPearlProtocol.Frame.END,
        p2: Byte = BlackPearlProtocol.Frame.END,
        p3: Byte = BlackPearlProtocol.Frame.END
    ): ByteArray? {
        return dacSyncService.pullValueSync(cmd, p1, p2, p3)
    }

    private fun startVolumePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (usbConnection != null) {
                // Check flags: If we are syncing EQ, back off the volume poller
                if (!isAppInFocus || isSyncing || isMassPushing || isUserTouchingSlider) {
                    delay(BlackPearlProtocol.Timing.VOLUME_POLL_BUSY_DELAY_MS) // Longer delay when busy
                    continue
                }

                delay(BlackPearlProtocol.Timing.VOLUME_POLL_INTERVAL_MS)

                // Re-check just before the USB call
                if (isSyncing || isMassPushing) continue

                val response = pullValueSync(CMD_GLOBAL_GAIN, END, END) ?: continue
                val roundedVol = dacSettingsMapper.parseVolumePercentOrNull(response) ?: continue

                // 3. Apply to UI safely
                if (abs(volumePercent - roundedVol) >= 1.0f) {
                    volumePercent = roundedVol
                    withContext(Dispatchers.Main) {
                        // Double check interlock right before UI update
                        if (!isUserTouchingSlider) {
                            findViewById<Slider>(R.id.volumeSlider)?.value = volumePercent
                            findViewById<Slider>(R.id.eqMasterVolume)?.value = volumePercent

                            // NEW: Keep graph ceiling in sync with live hardware volume changes
                            findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                                this.preampDb = calculateHeadroomDb(volumePercent)
                                this.pathDirty = true
                                this.postInvalidate()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initUi() {

        // --- Setup Dropdown Menus ---
        findViewById<AutoCompleteTextView>(R.id.filterSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, filterOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(
                        byteArrayOf(
                            WRITE,
                            CMD_FILTER,
                            BlackPearlProtocol.Frame.BASE_DATA_LENGTH,
                            (position + 1).toByte(),
                            END
                        )
                    )
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.gainSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, gainOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(
                        byteArrayOf(
                            WRITE,
                            CMD_GAIN_MODE,
                            BlackPearlProtocol.Frame.BASE_DATA_LENGTH,
                            position.toByte(),
                            END
                        )
                    )
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.ampSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, ampOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(
                        byteArrayOf(
                            WRITE,
                            CMD_AMP_TOPO,
                            BlackPearlProtocol.Frame.BASE_DATA_LENGTH,
                            position.toByte(),
                            END
                        )
                    )
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<Slider>(R.id.volumeSlider)?.apply {
            stepSize = 1f

            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    isUserTouchingSlider = true
                }
                override fun onStopTrackingTouch(slider: Slider) {
                    lastSliderReleaseTime = System.currentTimeMillis()
                    lifecycleScope.launch {
                        delay(500)
                        isUserTouchingSlider = false
                    }
                }
            })

            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isSyncing) {
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
            }
        }

        findViewById<Slider>(R.id.balanceSlider)?.apply {
            valueFrom = -15f; valueTo = 15f; stepSize = 1f
            addOnChangeListener { _, value, fromUser -> if (fromUser) updateBalance(value.toInt()) }
        }

        findViewById<Slider>(R.id.micGainSlider)?.apply {
            valueFrom = -15f; valueTo = 15f; stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    sendHidCommand(
                        byteArrayOf(
                            WRITE,
                            CMD_MIC_GAIN,
                            BlackPearlProtocol.Param.MIC_GAIN_LENGTH,
                            BlackPearlProtocol.Param.MIC_GAIN_SIGNED_FLAG,
                            (value.toInt() and 0xFF).toByte()
                        )
                    )
                    latchSettings()
                    debouncedSaveToFlash()
                }
            }
        }

        // --- Factory Reset Listener ---
        findViewById<Button>(R.id.btnFactoryReset)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Factory Reset")
                .setMessage("Restore all hardware settings and load the Flat EQ profile?")
                .setPositiveButton("Reset") { _, _ ->
                    lifecycleScope.launch {
                        isSyncing = true

                        // 1. Reset Global Settings
                        volumePercent = 50f
                        updateHardwareVolume(latchAndSave = false)
                        sendHidCommand(
                            byteArrayOf(
                                WRITE,
                                CMD_FILTER,
                                BlackPearlProtocol.Frame.BASE_DATA_LENGTH,
                                BlackPearlProtocol.Frame.BASE_DATA_LENGTH,
                                END
                            )
                        )
                        sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
                        sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
                        updateBalance(0)

                        // 2. Load and Apply the Permanent "Flat" Preset
                        val flatIdx = presets.indexOfFirst { it.name == "Flat" }.coerceAtLeast(0)
                        currentPresetIndex = flatIdx
                        val flatPreset = presets[flatIdx]

                        eqBands.forEachIndexed { i, band ->
                            val src = flatPreset.bands[i]
                            band.apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; q = src.q }
                            sendFilterUpdate(i, band, autoLatch = false)
                        }

                        latchSettings()
                        saveToFlash()
                        isSyncing = false

                        // Refresh UI
                        findViewById<Button>(R.id.btnPresets)?.text = "Flat"
                        findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()
                        findViewById<EqGraphView>(R.id.eqGraph)?.postInvalidate()

                        Toast.makeText(this@MainActivity, "System Flat", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateHardwareVolume(latchAndSave: Boolean = true) {
        if (usbConnection == null) return

        val totalRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
        val clampedRaw = totalRaw.coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)

        sendHidCommand(
            byteArrayOf(
                WRITE,
                CMD_GLOBAL_GAIN,
                BlackPearlProtocol.Param.GLOBAL_GAIN_LENGTH,
                (clampedRaw and 0xFF).toByte(),
                (clampedRaw shr 8).toByte(),
                END
            )
        )

        if (latchAndSave) {
            latchSettings()
            debouncedSaveToFlash()
        }

        findViewById<Slider>(R.id.volumeSlider)?.value = volumePercent
        findViewById<Slider>(R.id.eqMasterVolume)?.value = volumePercent

        val headroomDb = (VOL_MAX_RAW - clampedRaw).toFloat() / 256f
        findViewById<EqGraphView>(R.id.eqGraph)?.apply {
            // Snapshot the bands to prevent the graph thread from reading
            // data while the USB thread is writing to it.
            this.bands = eqBands.map { it.copy() }
            this.preampDb = headroomDb
            this.pathDirty = true
            this.postInvalidate()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun initEq() {
        val recyclerView = findViewById<RecyclerView>(R.id.eqRecyclerView)
        val graph = findViewById<EqGraphView>(R.id.eqGraph)

        // Fix for "stuck" line: Correct Headroom math for startup
        // Fix for "stuck" line: Correct Headroom math for startup
        val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()

        graph?.apply {
            this.preampDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f
            this.bands = eqBands.map { it.copy() }
            this.pathDirty = true
            this.postInvalidate() // CRITICAL: Force the UI to actually draw the line on launch
        }

        if (recyclerView?.adapter == null) {
            recyclerView?.layoutManager = LinearLayoutManager(this)
            recyclerView?.adapter = EqAdapter(eqBands) { index, band ->
                // 1. Send to DAC
                sendFilterUpdate(index, band)

                // 2. SAVE TO PRESET LIST
                if (presets.indices.contains(currentPresetIndex)) {
                    presets[currentPresetIndex].bands[index] = band.copy()
                    savePresetsToPrefs() // Commit to SharedPreferences
                }

                // 3. Update Headroom & Graph
                val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                val headroomDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f

                findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                    this.preampDb = headroomDb
                    this.bands = eqBands.map { it.copy() }
                    this.pathDirty = true
                    this.postInvalidate()
                }
            }
        }

        findViewById<Button>(R.id.btnPresets)?.setOnClickListener { view ->
            val popup = PopupMenu(this@MainActivity, view)

            // 1. Show existing presets
            presets.forEachIndexed { index, preset ->
                popup.menu.add(0, index, index, preset.name)
            }

            // 2. Add 'Save New' option
            popup.menu.add(1, 999, presets.size, "+ Save as New Preset")

            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == 999) {
                    // Show dialog to name new preset
                    val input = EditText(this)
                    AlertDialog.Builder(this)
                        .setTitle("New Preset")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                val clonedBands = eqBands.map { it.copy() }.toMutableList()
                                presets.add(Preset(name, volumePercent, clonedBands))
                                currentPresetIndex = presets.size - 1
                                savePresetsToPrefs()
                                findViewById<Button>(R.id.btnPresets)?.text = name
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                } else {
                    // Load the selected preset
                    currentPresetIndex = item.itemId
                    val selected = presets[currentPresetIndex]
                    findViewById<Button>(R.id.btnPresets)?.text = selected.name

                    eqBands.forEachIndexed { i, band ->
                        val src = selected.bands[i]
                        band.apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; q = src.q }
                    }

                    // Trigger hardware update
                    lifecycleScope.launch(Dispatchers.IO) {
                        isMassPushing = true
                        eqBands.forEachIndexed { i, b -> sendFilterUpdate(i, b, autoLatch = false) }
                        latchSettings()
                        while (usbCommandQueueProcessor.hasPendingWork()) { delay(BlackPearlProtocol.Timing.MASS_PUSH_POLL_DELAY_MS) }
                        withContext(Dispatchers.Main) {
                            isMassPushing = false
                            debouncedSaveToFlash()

                            // Refresh list
                            findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()

                            // Update Graph with new snapshot and headroom
                            findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                                this.bands = eqBands.map { it.copy() }
                                val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                                this.preampDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f
                                this.pathDirty = true
                                this.postInvalidate()
                            }
                            Toast.makeText(this@MainActivity, "Preset Loaded: ${selected.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            popup.show()
        }

        // --- NEW: Long-Click to Delete Presets ---
        findViewById<Button>(R.id.btnPresets)?.setOnLongClickListener {
            showDeletePresetDialog()
            true
        }

        val eqMasterSlider = findViewById<Slider>(R.id.eqMasterVolume)
        eqMasterSlider?.apply {
            clearOnChangeListeners()
            stepSize = 1f
            this.value = volumePercent

            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    isUserTouchingSlider = true
                }
                override fun onStopTrackingTouch(slider: Slider) {
                    lastSliderReleaseTime = System.currentTimeMillis()
                    lifecycleScope.launch {
                        delay(500)
                        isUserTouchingSlider = false
                    }
                }
            })

            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isSyncing) {
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
            }
        }

        findViewById<Button>(R.id.btnImport)?.setOnClickListener { importLauncher.launch("*/*") }
        findViewById<Button>(R.id.btnExport)?.setOnClickListener { exportLauncher.launch("BP_Preset.txt") }
    }

    private fun sendFilterUpdate(index: Int, filter: FilterBand, autoLatch: Boolean = true) {
        // FIX: Calculate an effective gain (0f if disabled) and use it EVERYWHERE
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        val effectiveFilter = filter.copy(gain = effectiveGain)
        val coeffs = BiquadMath.coefficients(effectiveFilter, effectiveGain)
        sendHidCommand(
            BlackPearlCodec.encodePeqUpdate(
                index = index,
                filter = effectiveFilter,
                coeffs = coeffs,
                activeSlot = activeSlot,
                profile = dacSettingsMapper.profile
            )
        )

        lastSentPeqIndex = index
        lastSentFilter = effectiveFilter

        // FIX: Only latch and save if we aren't in the middle of a mass update
        if (autoLatch) {
            latchSettings()
            debouncedSaveToFlash()
        }
    }

    private fun sendHidCommand(payload: ByteArray) {
        // Simply push to the queue; the processor handles the rest sequentially
        usbCommandQueueProcessor.enqueue(payload)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startQueueProcessor() {
        usbCommandQueueProcessor.start(
            scope = lifecycleScope,
            connectionProvider = { usbConnection },
            interfaceIdProvider = { usbInterface?.id ?: 0 }
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun readDacSettings() {
        if (usbConnection == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isSyncing = true

                // 0. Probe firmware version (CB cmd 0x0C) — best-effort; failure keeps CB profile
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
                    "Balance selectors: left=0x${BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion).toInt().and(0xFF).toString(16)} right=0x${BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion).toInt().and(0xFF).toString(16)} fw=$firmwareVersion"
                )
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 1. Read Filter
                pullValueSync(CMD_FILTER, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    withContext(Dispatchers.Main) {
                        filterOptions.getOrNull(value - 1)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.filterSelector)?.setText(text, false)
                        }
                    }
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 2. Read Gain Mode
                pullValueSync(CMD_GAIN_MODE, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    withContext(Dispatchers.Main) {
                        gainOptions.getOrNull(value)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.gainSelector)?.setText(text, false)
                        }
                    }
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 3. Read Amp Topo
                pullValueSync(CMD_AMP_TOPO, END, END)?.let { data ->
                    val value = data[BlackPearlProtocol.ParserOffset.VALUE_LSB].toInt()
                    withContext(Dispatchers.Main) {
                        ampOptions.getOrNull(value)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.ampSelector)?.setText(text, false)
                        }
                    }
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
                pullValueSync(
                    CMD_MIC_GAIN,
                    BlackPearlProtocol.Param.MIC_GAIN_PAGE,
                    BlackPearlProtocol.Param.MIC_GAIN_PAGE
                )?.let { data ->
                    val micDb = dacSettingsMapper.parseMicGainDb(data)
                    withContext(Dispatchers.Main) {
                        findViewById<Slider>(R.id.micGainSlider)?.value = micDb.toFloat()
                    }
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                // 6. Read Balance
                val balanceLeftSelector = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
                val balanceRightSelector = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceLeftSelector)?.let { data -> // Left
                    val mag = dacSettingsMapper.parseBalanceMagnitude(data)
                    dacBalLeft = if (mag > 0) (mag - 256) else 0
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, balanceRightSelector)?.let { data -> // Right
                    val mag = dacSettingsMapper.parseBalanceMagnitude(data)
                    dacBalRight = if (mag > 0) (256 - mag) else 0
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)

                val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
                val finalBal = if (abs(combined) <= 1) 0f else combined.toFloat()
                withContext(Dispatchers.Main) {
                    findViewById<Slider>(R.id.balanceSlider)?.value = finalBal.coerceIn(-15f, 15f)
                }

                // 7. Read PEQ Bands
                activeSlot = END // Reset before sync so stale value doesn't carry over
                for (i in 0 until 10) {
                    pullValueSync(CMD_PEQ_VALUES, END, END, i.toByte())?.let { data ->
                        val parsedBand = dacSettingsMapper.parsePeqBand(data)
                        // Accept the first non-zero slot; all bands should report the same slot
                        if (activeSlot == END && parsedBand.activeSlot != END) {
                            activeSlot = parsedBand.activeSlot
                            Log.d("BPControl/Protocol", "activeSlot=0x${activeSlot.toInt().and(0xFF).toString(16).uppercase(Locale.US)} confirmed from band $i")
                        }
                        eqBands[i].apply {
                            freq = parsedBand.freq
                            q = parsedBand.q
                            gain = parsedBand.gain
                            type = parsedBand.type
                            enabled = parsedBand.enabled
                        }
                    }
                    delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                }
                if (activeSlot == END) {
                    Log.w("BPControl/Protocol", "activeSlot is still END=0x00 after all PEQ reads — flash save may fail")
                }

            } finally {
                // --- PEQ IDENTITY LOGIC ---
                // 1. Identify if hardware matches a known preset
                val matchIdx = identifyPreset(eqBands)

                withContext(Dispatchers.Main) {
                    if (matchIdx != -1) {
                        currentPresetIndex = matchIdx
                    } else {
                        // 2. No match? Load into "None" preset index
                        val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                        val nonePreset = presets[noneIdx]

                        // Update the 'None' data structure with what we just read from hardware
                        nonePreset.preamp = volumePercent // Use the hardware volume we just read
                        for (i in 0 until 10) {
                            nonePreset.bands[i] = eqBands[i].copy()
                        }
                        currentPresetIndex = noneIdx
                    }

                    // 3. UI Updates
                    findViewById<Button>(R.id.btnPresets)?.text = presets[currentPresetIndex].name

                    // Re-enable and set UI elements
                    findViewById<Slider>(R.id.volumeSlider)?.apply { isEnabled = true; value = volumePercent }
                    findViewById<Slider>(R.id.eqMasterVolume)?.apply { isEnabled = true; value = volumePercent }
                    findViewById<Slider>(R.id.balanceSlider)?.isEnabled = true
                    findViewById<Slider>(R.id.micGainSlider)?.isEnabled = true

                    findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()

                    findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                        this.bands = eqBands.map { it.copy() }
                        // CRITICAL: Hydrate the graph ceiling with the newly synced hardware volume
                        val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                        this.preampDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f
                        this.pathDirty = true
                        this.postInvalidate()
                    }

                    // Release the sync flag LAST
                    isSyncing = false
                }
            }
        }
    }

    private fun updateBalance(v: Int) {
        val magL = if (v < 0) (256 + v) else END.toInt()
        val magR = if (v > 0) (256 - v) else END.toInt()
        val balanceLeftSelector = BlackPearlProtocol.BalanceSelector.leftChannelSelector(firmwareVersion)
        val balanceRightSelector = BlackPearlProtocol.BalanceSelector.rightChannelSelector(firmwareVersion)
        sendHidCommand(
            byteArrayOf(
                WRITE,
                CMD_BALANCE,
                BlackPearlProtocol.Param.BALANCE_LENGTH,
                balanceLeftSelector,
                END,
                magL.toByte()
            )
        )
        Handler(Looper.getMainLooper()).postDelayed({
            sendHidCommand(
                byteArrayOf(
                    WRITE,
                    CMD_BALANCE,
                    BlackPearlProtocol.Param.BALANCE_LENGTH,
                    balanceRightSelector,
                    END,
                    magR.toByte()
                )
            )
            latchSettings()
            debouncedSaveToFlash()
        }, BlackPearlProtocol.Timing.BALANCE_PAIR_DELAY_MS)
    }

    private fun latchSettings() {
        sendHidCommand(
            byteArrayOf(
                WRITE,
                BlackPearlProtocol.Command.LATCH_SETTINGS,
                BlackPearlProtocol.Param.BALANCE_LENGTH,
                BlackPearlProtocol.Frame.FILL,
                BlackPearlProtocol.Frame.FILL,
                BlackPearlProtocol.Frame.FILL,
                BlackPearlProtocol.Frame.FILL,
                END
            )
        )
    }

    // Update the receiver to listen for NEW attachments
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                // FIX: Automatically catch when the DAC is physically plugged in
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device?.vendorId == VID && device.productId == PID) {
                        Toast.makeText(context, "DAC Hardware Detected", Toast.LENGTH_SHORT).show()
                        startConnectionWatchdog() // Start hunting until permission and data sync are done
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
                    withContext(Dispatchers.Main) {
                        isSyncing = false
                        Toast.makeText(this@MainActivity, "Could not open file", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val result = stream.use { AutoEqParser.parse(it) }

                if (result.bands.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isSyncing = false
                        Toast.makeText(this@MainActivity, "No valid filters found", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

                withContext(Dispatchers.Main) {
                    if (result.preamp < 0) {
                        volumePercent = (volumePercent + (result.preamp * 2)).coerceIn(0f, 100f)
                    }

                    for (i in 0 until 10) {
                        if (i < result.bands.size) {
                            val src = result.bands[i]
                            eqBands[i].apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; this.q = src.q }
                        } else {
                            eqBands[i].apply { enabled = false; type = FilterType.PK; freq = defaultFreqs[i]; gain = 0f; this.q = 1.0f }
                        }
                    }

                    val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                    val nonePreset = presets[noneIdx]
                    nonePreset.preamp = volumePercent
                    for (i in 0 until 10) nonePreset.bands[i] = eqBands[i].copy()
                    currentPresetIndex = noneIdx
                    findViewById<Button>(R.id.btnPresets)?.text = "None"

                    findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()
                    findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                        this.bands = eqBands.map { it.copy() }
                        val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                        this.preampDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f
                        this.pathDirty = true
                        this.postInvalidate()
                    }
                }

                isMassPushing = true
                eqBands.forEachIndexed { index, band -> sendFilterUpdate(index, band, autoLatch = false) }
                latchSettings()

                while (usbCommandQueueProcessor.hasPendingWork()) { delay(BlackPearlProtocol.Timing.MASS_PUSH_POLL_DELAY_MS) }
                delay(BlackPearlProtocol.Timing.MASS_PUSH_SETTLE_DELAY_MS)

                withContext(Dispatchers.Main) {
                    isMassPushing = false
                    isSyncing = false
                    debouncedSaveToFlash()
                    Toast.makeText(this@MainActivity, "Import Successful", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("AutoEQ", "Import parsing failed", e)
                withContext(Dispatchers.Main) {
                    isSyncing = false
                    Toast.makeText(this@MainActivity, "File Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun saveSettingsToFile() { /* Keep existing implementation */ }

    private fun closeUsbConnection() {
        // 1. Signal all jobs to stop immediately
        //readThreadJob?.cancel()
        usbCommandQueueProcessor.stop()
        volumeDebounceJob?.cancel()
        usbConnectionManager.closeConnection(lifecycleScope)
        pollingJob?.cancel()

        resetUiToDefaults()
    }

    override fun onDestroy() {
        // This is now the ONLY place where we physically release the hardware
        closeUsbConnection()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("USB", "Receiver already unregistered", e)
        }
        super.onDestroy()
    }
}