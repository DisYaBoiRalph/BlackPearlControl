package com.fossyaudio.bpcontrol

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.slider.Slider
import com.fossyaudio.bpcontrol.data.PresetRepository
import com.fossyaudio.bpcontrol.di.AppContainer
import com.fossyaudio.bpcontrol.presentation.DacSettingsMapper
import com.fossyaudio.bpcontrol.presentation.MainPresentationCoordinator
import com.fossyaudio.bpcontrol.shared.eq.BiquadMath
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodec
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import com.fossyaudio.bpcontrol.transport.usb.UsbCommandQueueProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private var presets = mutableListOf<Preset>()
    private var currentPresetIndex = 0

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

    private val VOL_MIN_RAW = -9472
    private val VOL_MAX_RAW = 6440
    private val presentationCoordinator by lazy {
        MainPresentationCoordinator(VOL_MIN_RAW, VOL_MAX_RAW)
    }
    private val dacSettingsMapper by lazy {
        DacSettingsMapper(VOL_MIN_RAW, VOL_MAX_RAW)
    }

    private var isUserTouchingSlider = false
    private var lastSliderReleaseTime = 0L

    private var volumePercent = 50f
    private var isSyncing = false
    private var isMassPushing = false
    private var dacBalLeft = 0   // Track Left side attenuation
    private var dacBalRight = 0
    private var activeSlot: Byte = END // Required to unlock Flash Saving

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
    private var isPermissionRequested = false
    private var isAppInFocus = false // FIX: Declare the focus tracker
    private var connectionWatchdogJob: Job? = null
    private var lastVolTime = 0L // Limits live dragging to 25 FPS
    private val eqBands = MutableList(10) { i ->
        val frequencies = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        FilterBand(freq = frequencies[i])
    }

    private val ACTION_USB_PERMISSION = "com.fossyaudio.bpcontrol.USB_PERMISSION"
    private lateinit var appContainer: AppContainer
    private lateinit var presetRepository: PresetRepository
    private lateinit var usbManager: UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null

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
        presetRepository = appContainer.presetRepository
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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

        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.setOnItemSelectedListener { item ->
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
        // Stop background polling to save battery, but DO NOT sever the USB connection
        pollingJob?.cancel()
        super.onPause()
    }

    private fun calculateHeadroomDb(volPercent: Float): Float {
        return presentationCoordinator.calculateHeadroomDb(volPercent)
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
        presets.addAll(presetRepository.load())
    }

    private fun savePresetsToPrefs() {
        presetRepository.save(presets)
    }

    private fun identifyPreset(hwBands: List<FilterBand>): Int {
        return presentationCoordinator.identifyPreset(presets, hwBands)
    }

    private fun startConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = lifecycleScope.launch(Dispatchers.IO) { // Shift to IO Thread
            // Keep trying every 1.5s until a hardware connection is established
            while (usbConnection == null && isAppInFocus) {
                findAndConnect()
                delay(BlackPearlProtocol.Timing.CONNECTION_WATCHDOG_INTERVAL_MS)
                if (usbConnection != null) {
                    Log.d("USB", "Watchdog: Connection successful.")
                    break
                }
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

    private fun performFactoryReset() {
        if (usbConnection == null) {
            Toast.makeText(this, "DAC not connected", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            isSyncing = true

            // 1. Reset Global Settings to Hardware Defaults
            volumePercent = 50f
            updateHardwareVolume(latchAndSave = false)

            // Filter: Fast-LL (1), Gain: Low (0), Amp: Class H (0), Balance: 0
            sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
            sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
            sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END, END))
            updateBalance(0)

            // 2. Reset EQ Bands to Flat
            val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            eqBands.forEachIndexed { i, band ->
                band.apply {
                    enabled = true; type = "PK"; freq = defaultFreqs[i]; gain = 0f; q = 1.0f
                }
                // FIX: Pass autoLatch = false to prevent queue flooding
                sendFilterUpdate(i, band, autoLatch = false)
                delay(40)
            }

            // 3. Commit to Flash
            latchSettings()
            saveToFlash()

            isSyncing = false

            // 4. Force a fresh UI sync from the hardware to confirm
            readDacSettings()
            Toast.makeText(this@MainActivity, "Factory Reset Complete", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToFlash() {
        if (usbConnection == null) return
        sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, BlackPearlProtocol.Frame.BASE_DATA_LENGTH, END))
    }

    // Added 'suspend' keyword to allow locking
    private suspend fun pullValueSync(
        cmd: Byte,
        p1: Byte = BlackPearlProtocol.Frame.END,
        p2: Byte = BlackPearlProtocol.Frame.END,
        p3: Byte = BlackPearlProtocol.Frame.END
    ): ByteArray? {
        val connection = usbConnection ?: return null
        val interfaceId = usbInterface?.id ?: 0
        val inEndpoint = endpointIn ?: return null

        return usbMutex.withLock {
            try {
                val outBuffer = BlackPearlCodec.encodeReadRequest(cmd, p1, p2, p3)

                // Clear any leftover packets first
                val dump = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
                while (
                    connection.bulkTransfer(
                        inEndpoint,
                        dump,
                        BlackPearlProtocol.Frame.REPORT_SIZE,
                        BlackPearlProtocol.Timing.READ_FLUSH_TIMEOUT_MS
                    ) > 0
                ) { /* flush */ }

                val writeResult = connection.controlTransfer(
                    BlackPearlProtocol.Transfer.REQUEST_TYPE_CLASS_INTERFACE_OUT,
                    BlackPearlProtocol.Transfer.REQUEST_SET_REPORT,
                    BlackPearlProtocol.Transfer.VALUE_OUTPUT_REPORT_BASE or REPORT_ID.toInt(),
                    interfaceId,
                    outBuffer,
                    BlackPearlProtocol.Frame.REPORT_SIZE,
                    BlackPearlProtocol.Timing.READ_TRANSFER_TIMEOUT_MS
                )
                if (writeResult < 0) return@withLock null

                val inBuffer = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < BlackPearlProtocol.Timing.READ_WINDOW_MS) {
                    val readResult = connection.bulkTransfer(
                        inEndpoint,
                        inBuffer,
                        BlackPearlProtocol.Frame.REPORT_SIZE,
                        BlackPearlProtocol.Timing.READ_POLL_TIMEOUT_MS
                    )
                    if (
                        readResult > 0 &&
                        inBuffer[BlackPearlProtocol.ParserOffset.DIRECTION] == READ &&
                        inBuffer[BlackPearlProtocol.ParserOffset.COMMAND] == cmd
                    ) {
                        return@withLock inBuffer.copyOf()
                    }
                    delay(BlackPearlProtocol.Timing.READ_POLL_INTERVAL_MS)
                }
                null
            } catch (e: Exception) { null }
        }
    }

    private fun findAndConnect() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == VID && device.productId == PID) {
                if (usbManager.hasPermission(device)) {
                    setupConnection(device)
                } else if (!isPermissionRequested && isAppInFocus) { // Gatekeeper added
                    isPermissionRequested = true
                    // FIX: Using FLAG_IMMUTABLE completely satisfies Android 14's strict security rules
                    val flags =
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

                    val intent = Intent(ACTION_USB_PERMISSION)
                    intent.setPackage(packageName)

                    val permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        intent,
                        flags
                    )

                    usbManager.requestPermission(device, permissionIntent)
                }
                break
            }
        }
    }

    private fun setupConnection(device: UsbDevice) {
        // Launch on IO thread to prevent UI freezing during hardware handshakes
        lifecycleScope.launch(Dispatchers.IO) {
            val connection = usbManager.openDevice(device) ?: return@launch

            var intf: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val tempIntf = device.getInterface(i)
                if (tempIntf.interfaceClass == UsbConstants.USB_CLASS_HID || tempIntf.interfaceClass == 255) {
                    intf = tempIntf
                    break
                }
            }
            if (intf == null) intf = device.getInterface(device.interfaceCount - 1)

            // Non-blocking wait for ALSA to settle
            delay(BlackPearlProtocol.Timing.USB_SETTLE_DELAY_MS)

            var claimed = false
            for (i: Int in 1..3) {
                if (connection.claimInterface(intf, true)) {
                    claimed = true
                    break
                }
                delay(BlackPearlProtocol.Timing.CLAIM_RETRY_DELAY_MS) // Non-blocking delay
            }

            if (claimed) {
                usbConnection = connection
                usbInterface = intf

                // CRITICAL FIX: Find the correct endpoint FIRST before attempting any transfers
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        endpointIn = ep
                    }
                }

                // CRITICAL FIX: Aggressively drain the hardware buffer of all stale packets
                // CRITICAL FIX: Lock the bus while aggressively draining stale packets
                endpointIn?.let { ep ->
                    val dump = ByteArray(64)
                    var bytesRead: Int
                    var flushAttempts = 0
                    usbMutex.withLock {
                        do {
                            bytesRead = connection.bulkTransfer(ep, dump, 64, 20)
                            flushAttempts++
                        } while (bytesRead > 0 && flushAttempts < 10)
                    }
                }

                startQueueProcessor()


                // Final sync once hardware is ready
                delay(BlackPearlProtocol.Timing.POST_CONNECT_SYNC_DELAY_MS)
                readDacSettings()
                startVolumePolling()
            }
        }
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
                activeSlot = activeSlot
            )
        )

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
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, BlackPearlProtocol.Param.BALANCE_LEFT)?.let { data -> // Left
                    val mag = dacSettingsMapper.parseBalanceMagnitude(data)
                    dacBalLeft = if (mag > 0) (mag - 256) else 0
                }
                delay(BlackPearlProtocol.Timing.SETTINGS_READ_STEP_DELAY_MS)
                pullValueSync(CMD_BALANCE, BlackPearlProtocol.Param.BALANCE_LENGTH, BlackPearlProtocol.Param.BALANCE_RIGHT)?.let { data -> // Right
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
                for (i in 0 until 10) {
                    pullValueSync(CMD_PEQ_VALUES, END, END, i.toByte())?.let { data ->
                        val parsedBand = dacSettingsMapper.parsePeqBand(data)
                        activeSlot = parsedBand.activeSlot
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
        sendHidCommand(
            byteArrayOf(
                WRITE,
                CMD_BALANCE,
                BlackPearlProtocol.Param.BALANCE_LENGTH,
                BlackPearlProtocol.Param.BALANCE_LEFT,
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
                    BlackPearlProtocol.Param.BALANCE_RIGHT,
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
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                // FIX: Automatically catch when the DAC is physically plugged in
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device?.vendorId == VID && device.productId == PID) {
                        Toast.makeText(context, "DAC Hardware Detected", Toast.LENGTH_SHORT).show()
                        startConnectionWatchdog() // Start hunting until permission and data sync are done
                    }
                }
                ACTION_USB_PERMISSION -> {
                    isPermissionRequested = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        // Safe to call directly as setupConnection now handles thread shifting
                        device?.let { setupConnection(it) }
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
        // 1. SET SYNC FLAG IMMEDIATELY to lock out hardware polling
        isSyncing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val lines = reader.readLines()
                    val tempBands = mutableListOf<FilterBand>()
                    var parsedPreamp = 0f

                    // CRITICAL FIX: Pre-compile Regex outside the loop.
                    // Bypasses R8 loop-hoisting bugs and drastically improves CPU performance.
                    val preampRegex = Regex("PREAMP\\s*[:=]?\\s*([-+.\\d]+)")
                    val fcRegex = Regex("FC\\s*[:=]?\\s*([\\d.]+)")
                    val gainRegex = Regex("GAIN\\s*[:=]?\\s*([-+.\\d]+)")
                    val qRegex = Regex("Q\\s*[:=]?\\s*([\\d.]+)")

                    // Standard for-loop is safer against R8 bytecode mangling than lambdas
                    val maxLines = minOf(lines.size, 200)
                    for (i in 0 until maxLines) {
                        val line = lines[i].trim().uppercase()

                        // --- Parse Preamp ---
                        if (line.contains("PREAMP")) {
                            preampRegex.find(line)?.let {
                                parsedPreamp = it.groupValues.getOrNull(1)?.toFloatOrNull() ?: 0f
                            }
                        }

                        // --- Parse Filter ---
                        if (line.contains("FILTER") && tempBands.size < 10) {
                            val fcMatch = fcRegex.find(line)
                            val gainMatch = gainRegex.find(line)
                            val qMatch = qRegex.find(line)

                            if (fcMatch != null) {
                                // .getOrNull(1) prevents IndexOutOfBounds crashes if R8 strips capture groups
                                val f = fcMatch.groupValues.getOrNull(1)?.toFloatOrNull()?.toInt()?.coerceIn(20, 20000) ?: 1000
                                val g = gainMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(-10f, 10f) ?: 0f
                                val q = qMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f

                                val t = when {
                                    line.contains("LS") -> "LS"
                                    line.contains("HS") -> "HS"
                                    else -> "PK"
                                }
                                val en = !line.contains("OFF")
                                tempBands.add(FilterBand(enabled = en, type = t, freq = f, gain = g, q = q))
                            }
                        }
                    }

                    if (tempBands.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            isSyncing = false // Release lock on failure
                            Toast.makeText(this@MainActivity, "No valid filters found", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    // 2. Update UI and Logic State
                    withContext(Dispatchers.Main) {
                        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

                        // Handle Preamp: AutoEQ preamps are usually negative (e.g. -6dB)
                        // We translate this to our 0-100% volume slider if possible
                        if (parsedPreamp < 0) {
                            // Simple mapping: Adjust master volume to accommodate preamp headroom
                            volumePercent = (volumePercent + (parsedPreamp * 2)).coerceIn(0f, 100f)
                        }

                        for (i in 0 until 10) {
                            if (i < tempBands.size) {
                                val src = tempBands[i]
                                eqBands[i].apply { enabled = src.enabled; type = src.type; freq = src.freq; gain = src.gain; this.q = src.q }
                            } else {
                                eqBands[i].apply { enabled = false; type = "PK"; freq = defaultFreqs[i]; gain = 0f; this.q = 1.0f }
                            }
                        }

                        // Sync to 'None' preset so the Identity System recognizes the import
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

                    // 3. Push to Hardware
                    isMassPushing = true
                    eqBands.forEachIndexed { index, band ->
                        sendFilterUpdate(index, band, autoLatch = false)
                    }
                    latchSettings()

                    // Wait for processor to finish
                    while (usbCommandQueueProcessor.hasPendingWork()) { delay(BlackPearlProtocol.Timing.MASS_PUSH_POLL_DELAY_MS) }
                    delay(BlackPearlProtocol.Timing.MASS_PUSH_SETTLE_DELAY_MS)

                    withContext(Dispatchers.Main) {
                        isMassPushing = false
                        isSyncing = false // Release lock
                        debouncedSaveToFlash()
                        Toast.makeText(this@MainActivity, "Import Successful", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
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
        connectionWatchdogJob?.cancel()
        volumeDebounceJob?.cancel()
        isPermissionRequested = false

        // 2. Move hardware release to a background thread to prevent UI freezing
        val connectionToClose = usbConnection
        val interfaceToRelease = usbInterface

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Wait for requestWait(100) in the read thread to naturally time out and release
                delay(150)

                connectionToClose?.apply {
                    interfaceToRelease?.let { releaseInterface(it) }
                    close()
                }
            } catch (e: Exception) {
                Log.e("USB", "Cleanup Error", e)
            }
        }

        // 3. Clear references immediately so the rest of the app knows we are disconnected
        usbConnection = null
        usbInterface = null
        endpointIn = null
        pollingJob?.cancel()

        resetUiToDefaults()
    }

    override fun onDestroy() {
        // This is now the ONLY place where we physically release the hardware
        closeUsbConnection()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
            Log.e("USB", "Receiver already unregistered")
        }
        super.onDestroy()
    }
}