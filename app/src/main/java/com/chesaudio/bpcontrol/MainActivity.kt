package com.chesaudio.bpcontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    data class FilterBand(
        var enabled: Boolean = true,
        var type: String = "PK",
        var freq: Int = 1000,
        var gain: Float = 0.0f,
        var q: Float = 1.0f
    )

    // Move these to the top of the class
    private val filterOptions = arrayOf("FAST-LL", "Fast-PC (BEST)", "Slow-LL", "SLOW-PC", "NOS")
    private val gainOptions = arrayOf("LOW", "HIGH")
    private val ampOptions = arrayOf("CLASS H", "CLASS AB")

    private val VID = 0x3302
    private val PID = 0x43E8
    private val REPORT_ID = 0x4B
    private val WRITE = 0x01.toByte()
    private val READ = 0x80.toByte()
    private val END = 0x00.toByte()

    private val CMD_GLOBAL_GAIN = 0x03.toByte()
    private val CMD_FILTER = 0x11.toByte()
    private val CMD_MIC_GAIN = 0x02.toByte()
    private val CMD_GAIN_MODE = 0x19.toByte()
    private val CMD_AMP_TOPO = 0x1D.toByte()
    private val CMD_BALANCE = 0x16.toByte()
    private val CMD_PEQ_VALUES = 0x09.toByte()
    private val CMD_FLASH_EQ = 0x01.toByte()

    private val VOL_MIN_RAW = -9472
    private val VOL_MAX_RAW = 6440
    private val TYPE_CODES = mapOf("PK" to 0x02.toByte(), "LS" to 0x03.toByte(), "HS" to 0x04.toByte())

    private var volumePercent = 50f
    private var isSyncing = false
    private var isMassPushing = false
    private var dacBalLeft = 0   // Track Left side attenuation
    private var dacBalRight = 0
    private var activeSlot: Byte = 0x00 // Required to unlock Flash Saving

    private val commandQueue = kotlinx.coroutines.channels.Channel<ByteArray>(
        capacity = 100,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    private var queueProcessorJob: kotlinx.coroutines.Job? = null
    private var readThreadJob: kotlinx.coroutines.Job? = null
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var volumeDebounceJob: kotlinx.coroutines.Job? = null
    private var isPermissionRequested = false
    private var isAppInFocus = false // FIX: Declare the focus tracker
    private var connectionWatchdogJob: kotlinx.coroutines.Job? = null
    private var lastVolTime = 0L // Limits live dragging to 25 FPS
    private val eqBands = MutableList(10) { i ->
        val frequencies = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        FilterBand(freq = frequencies[i])
    }

    private val ACTION_USB_PERMISSION = "com.chesaudio.bpcontrol.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null

    // Flash Debouncer
    private val flashHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flashRunnable = Runnable { saveToFlash() }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { parseAutoEq(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveSettingsToFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        initUi()
        initEq() // FIX: Build the EQ page once in the background at startup

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)?.setOnItemSelectedListener { item ->
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
        isAppInFocus = true // Set immediately
        startConnectionWatchdog()
    }

    override fun onPause() {
        isAppInFocus = false // Set immediately to stop threads from queueing new requests
        pollingJob?.cancel()
        closeUsbConnection()
        super.onPause()
    }

    private fun debouncedSaveToFlash() {
        // Stop any pending save and schedule a new one after 1 second of silence
        flashHandler.removeCallbacks(flashRunnable)
        flashHandler.postDelayed(flashRunnable, 1000)
    }

    private fun startConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = lifecycleScope.launch(Dispatchers.IO) { // Shift to IO Thread
            // Keep trying every 1.5s until a hardware connection is established
            while (usbConnection == null && isAppInFocus) {
                findAndConnect()
                kotlinx.coroutines.delay(1500)
                if (usbConnection != null) {
                    Log.d("USB", "Watchdog: Connection successful.")
                    break
                }
            }
        }
    }

    private fun resetUiToDefaults() {
        runOnUiThread {
            isSyncing = true // Prevent phantom commands while clearing

            // CRITICAL FIX: Do NOT reset slider values to 0 here.
            // Setting values to 0 onPause causes Android to "save" that 0 state,
            // which then gets restored and pushed to the hardware onResume.
            // Instead, we just disable the controls so they can't be moved.
            findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.isEnabled = false
            findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)?.isEnabled = false
            findViewById<com.google.android.material.slider.Slider>(R.id.balanceSlider)?.isEnabled = false
            findViewById<com.google.android.material.slider.Slider>(R.id.micGainSlider)?.isEnabled = false

            // 2. Clear Dropdowns
            findViewById<AutoCompleteTextView>(R.id.filterSelector)?.setText("", false)
            findViewById<AutoCompleteTextView>(R.id.gainSelector)?.setText("", false)
            findViewById<AutoCompleteTextView>(R.id.ampSelector)?.setText("", false)

            // 3. Clear EQ List & Graph
            eqBands.forEach { it.enabled = false; it.gain = 0f }
            findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()
            findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                pathDirty = true
                postInvalidate()
            }

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
            sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, 0x01, 0x01, 0x00))
            sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, 0x01, 0x00, 0x00))
            sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, 0x01, 0x00, 0x00))
            updateBalance(0)

            // 2. Reset EQ Bands to Flat
            val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            eqBands.forEachIndexed { i, band ->
                band.apply {
                    enabled = true; type = "PK"; freq = defaultFreqs[i]; gain = 0f; q = 1.0f
                }
                sendFilterUpdate(i, band)
                kotlinx.coroutines.delay(40)
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
        sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, 0x01, END))
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
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

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
            kotlinx.coroutines.delay(300)

            var claimed = false
            for (i in 1..3) {
                if (connection.claimInterface(intf, true)) {
                    claimed = true
                    break
                }
                kotlinx.coroutines.delay(150) // Non-blocking delay
            }

            if (claimed) {
                usbConnection = connection
                usbInterface = intf

                endpointIn?.let { ep ->
                    val dump = ByteArray(64)
                    connection.bulkTransfer(ep, dump, 64, 50)
                }

                startQueueProcessor()
                for (i in 0 until intf!!.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        endpointIn = ep
                    }
                }
                startReadingThread()

                // Final sync once hardware is ready
                kotlinx.coroutines.delay(500)
                readDacSettings()
                startVolumePolling()
            }
        }
    }

    private fun startVolumePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (usbConnection != null) {
                kotlinx.coroutines.delay(1000)

                // TECHNIQUE: Stay silent if focus is lost, if we are mass pushing,
                // or if there are commands waiting in the queue.
                // This stops the "Read" thread from touching the USB bus
                // only during mass AutoEQ pushes, allowing Sync reads to properly catch data.
                if (!isAppInFocus || isMassPushing) {
                    kotlinx.coroutines.delay(200)
                    continue
                }

                if (!isSyncing && volumeDebounceJob?.isActive != true) {
                    sendHidCommand(byteArrayOf(READ, CMD_GLOBAL_GAIN, END, 0x00))
                }
            }
        }
    }

    private fun initUi() {

        // --- Setup Dropdown Menus ---
        findViewById<AutoCompleteTextView>(R.id.filterSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, filterOptions))
            inputType = android.text.InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, 0x01, (position + 1).toByte(), 0x00))
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.gainSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, gainOptions))
            inputType = android.text.InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, 0x01, position.toByte(), 0x00))
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.ampSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, ampOptions))
            inputType = android.text.InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, 0x01, position.toByte(), 0x00))
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.apply {
            stepSize = 1f // FIX: Snaps slider to whole numbers
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isSyncing) {
                    volumePercent = value // value is now always an integer like 50.0
                    val now = System.currentTimeMillis()

                    if (now - lastVolTime > 40) {
                        lastVolTime = now
                        updateHardwareVolume(latchAndSave = false)
                    }

                    volumeDebounceJob?.cancel()
                    volumeDebounceJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(150)
                        updateHardwareVolume(latchAndSave = true)
                    }
                }
            }
        }

        findViewById<com.google.android.material.slider.Slider>(R.id.balanceSlider)?.apply {
            valueFrom = -15f; valueTo = 15f; stepSize = 1f
            addOnChangeListener { _, value, fromUser -> if (fromUser) updateBalance(value.toInt()) }
        }

        findViewById<com.google.android.material.slider.Slider>(R.id.micGainSlider)?.apply {
            valueFrom = -15f; valueTo = 15f; stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_MIC_GAIN, 0x02, 0x80.toByte(), (value.toInt() and 0xFF).toByte()))
                    latchSettings()
                    debouncedSaveToFlash()
                }
            }
        }

        // --- Factory Reset Listener ---
        findViewById<Button>(R.id.btnFactoryReset)?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Factory Reset")
                .setMessage("This will wipe all EQ presets, volume, and hardware settings. Are you sure?")
                .setPositiveButton("Reset Everything") { _, _ -> performFactoryReset() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateHardwareVolume(latchAndSave: Boolean = true) {
        if (usbConnection == null) return

        val totalRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
        val clampedRaw = totalRaw.coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)

        sendHidCommand(byteArrayOf(WRITE, CMD_GLOBAL_GAIN, 0x03, (clampedRaw and 0xFF).toByte(), (clampedRaw shr 8).toByte(), 0x00))

        if (latchAndSave) {
            latchSettings()
            debouncedSaveToFlash()
        }

        findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.value = volumePercent
        findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)?.value = volumePercent

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

    private fun parseResponse(cmd: Byte, data: ByteArray) {
        val value = data[4].toInt()

        when (cmd) {
            CMD_GLOBAL_GAIN -> {
                // Correctly handle the 16-bit signed volume range (-9472 to 6440)
                val rawVol = ((data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)).toShort().toInt()

                // Safety: Ignore garbage "all-zero" packets that can occur during USB handshake
                if (rawVol == 0 && data[6].toInt() == 0) return

                val exactVol = ((rawVol - VOL_MIN_RAW).toFloat() / (VOL_MAX_RAW - VOL_MIN_RAW).toFloat() * 100).coerceIn(0f, 100f)
                val roundedVol = Math.round(exactVol).toFloat()

                // Only update if the integer value actually changed
                if (abs(volumePercent - roundedVol) >= 1.0f) {
                    volumePercent = roundedVol

                    findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.value = volumePercent
                    findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)?.value = volumePercent

                    val headroomDb = (VOL_MAX_RAW - rawVol.toShort()).toFloat() / 256f
                    findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                        this.preampDb = headroomDb
                        this.pathDirty = true
                        this.postInvalidate()
                    }
                }
            }

            CMD_PEQ_VALUES -> { // 0x09 Handle Parametric EQ
                val index = data[5].toInt()
                if (index in 0 until 10) {
                    // CRITICAL FIX: Prevent Native UI crashes!
                    // If the DAC returns empty data (0), the graph attempts log10(0) or divide-by-zero.
                    // This creates 'NaN' or '-Infinity', which instantly crashes Android's hardware renderer.
                    val rawF = (data[28].toInt() and 0xFF) or ((data[29].toInt() and 0xFF) shl 8)
                    val rawQ = ((data[30].toInt() and 0xFF) or ((data[31].toInt() and 0xFF) shl 8)) / 256.0f

                    val f = rawF.coerceIn(20, 20000)
                    val q = rawQ.coerceIn(0.1f, 10.0f)

                    // Correct negative signed math for Gain
                    var gRaw = (data[32].toInt() and 0xFF) or (data[33].toInt() shl 8)
                    if (gRaw > 32767) gRaw -= 65536
                    val g = gRaw / 256.0f

                    val typeCode = data[34]
                    val bandType = when (typeCode.toInt()) {
                        0x02 -> "PK"
                        0x03 -> "LS"
                        0x04 -> "HS"
                        else -> "PK"
                    }

                    activeSlot = data[36] // Capture the DAC's current memory slot

                    eqBands[index].apply { freq = f; this.q = q; gain = g; type = bandType; enabled = true }

                    // CRITICAL FIX: Prevent UI Thrashing (ANR)
                    // Do not force the heavy Graph math or RecyclerView to redraw 10 times in a row during a bulk sync.
                    // readDacSettings() will do one massive, efficient refresh at the very end.
                    if (!isSyncing) {
                        findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                            pathDirty = true
                            postInvalidate()
                        }
                        findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyItemChanged(index)
                    }
                }
            }

            CMD_BALANCE -> { // 0x16 Handle Channel Balance
                val sideFlag = data[4]
                val mag = data[6].toInt() and 0xFF

                // Logic directly adapted from your queryChannelBalance.py
                if (sideFlag == 0x01.toByte()) { // Left Channel
                    dacBalLeft = if (mag > 0) (mag - 256) else 0
                } else { // Right Channel
                    dacBalRight = if (mag > 0) (256 - mag) else 0
                }

                // Strength Check & Snap-to-Zero (Matching Python's ghosting logic)
                val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
                val finalBal = if (abs(combined) <= 1) 0f else combined.toFloat()

                findViewById<com.google.android.material.slider.Slider>(R.id.balanceSlider)?.value = finalBal.coerceIn(-15f, 15f)
            }

            CMD_MIC_GAIN -> { // 0x02 Handle Microphone Gain
                val micDb = data[5].toInt().coerceIn(-15, 15)
                findViewById<com.google.android.material.slider.Slider>(R.id.micGainSlider)?.value = micDb.toFloat()
            }

            // Handle Dropdown Selectors with safety checks
            CMD_FILTER -> {
                filterOptions.getOrNull(value - 1)?.let { text ->
                    findViewById<AutoCompleteTextView>(R.id.filterSelector)?.setText(text, false)
                }
            }
            CMD_GAIN_MODE -> {
                gainOptions.getOrNull(value)?.let { text ->
                    findViewById<AutoCompleteTextView>(R.id.gainSelector)?.setText(text, false)
                }
            }
            CMD_AMP_TOPO -> {
                ampOptions.getOrNull(value)?.let { text ->
                    findViewById<AutoCompleteTextView>(R.id.ampSelector)?.setText(text, false)
                }
            }
        }
    }

    private fun initEq() {
        val recyclerView = findViewById<RecyclerView>(R.id.eqRecyclerView)
        val graph = findViewById<EqGraphView>(R.id.eqGraph)

        // Fix for "stuck" line: Correct Headroom math for startup
        val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
        graph?.preampDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f

        // Pass a COPY of the bands to the graph.
        // Direct references crash when the background sync modifies values during a draw.
        graph?.bands = eqBands.map { it.copy() }

        if (recyclerView?.adapter == null) {
            recyclerView?.layoutManager = LinearLayoutManager(this)
            recyclerView?.adapter = EqAdapter(eqBands) { index, band ->
                sendFilterUpdate(index, band)

                // Calculate current headroom to ensure the ceiling line stays accurate
                val currentRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                val headroomDb = (VOL_MAX_RAW - currentRaw).toFloat() / 256f

                // Re-find the graph to ensure we aren't using a stale instance
                findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                    this.preampDb = headroomDb
                    this.bands = eqBands.map { it.copy() } // Snapshot the list
                    this.pathDirty = true
                    this.postInvalidate()
                }
            }
        }

        findViewById<Button>(R.id.btnPresets)?.setOnClickListener { view ->
            val popup = PopupMenu(this@MainActivity, view)
            popup.menu.add("Flat EQ (Reset)")
            popup.menu.add("Bass Boost")
            popup.menu.add("V-Shape")
            popup.menu.add("Treble Boost")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Flat EQ (Reset)" -> {
                        val freqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
                        eqBands.forEachIndexed { i, band ->
                            band.gain = 0f; band.q = 1.0f; band.type = "PK"; band.freq = freqs[i]
                        }
                    }
                    "Bass Boost" -> {
                        eqBands[0].apply { gain = 6f; type = "LS"; freq = 63 }
                        eqBands[1].apply { gain = 3f; type = "PK"; freq = 125 }
                    }
                    "V-Shape" -> {
                        eqBands[0].apply { gain = 5f; type = "LS"; freq = 63 }
                        eqBands[1].apply { gain = 2f; type = "PK"; freq = 125 }
                        eqBands[8].apply { gain = 3f; type = "PK"; freq = 8000 }
                        eqBands[9].apply { gain = 5f; type = "HS"; freq = 16000 }
                    }
                    "Treble Boost" -> {
                        eqBands[8].apply { gain = 3f; type = "PK"; freq = 8000 }
                        eqBands[9].apply { gain = 5f; type = "HS"; freq = 16000 }
                    }
                }

                // Force graph and list to visually refresh
                // 1. Update UI instantly
                isSyncing = true
                findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()
                findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                    pathDirty = true
                    postInvalidate()
                }

                // 2. Push hardware slowly in background to prevent crashes
                lifecycleScope.launch(Dispatchers.IO) {
                    isMassPushing = true
                    kotlinx.coroutines.delay(200)

                    eqBands.forEachIndexed { index, band ->
                        sendFilterUpdate(index, band, autoLatch = false)
                        kotlinx.coroutines.delay(20)
                    }

                    kotlinx.coroutines.delay(100)
                    latchSettings()
                    isMassPushing = false

                    withContext(Dispatchers.Main) {
                        isSyncing = false
                        debouncedSaveToFlash()
                    }
                }
                true
            }
            popup.show()
        }

        val eqMasterSlider = findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)
        eqMasterSlider?.apply {
            clearOnChangeListeners()
            stepSize = 1f // FIX: Snaps slider to whole numbers
            this.value = volumePercent
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
                        kotlinx.coroutines.delay(150)
                        updateHardwareVolume(latchAndSave = true)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnImport)?.setOnClickListener { importLauncher.launch("*/*") }
        findViewById<Button>(R.id.btnExport)?.setOnClickListener { exportLauncher.launch("BP_Preset.txt") }
    }

    private fun sendFilterUpdate(index: Int, filter: FilterBand, autoLatch: Boolean = true) {
        val fs = 48000.0
        // FIX: Calculate an effective gain (0f if disabled) and use it EVERYWHERE
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        val a = Math.pow(10.0, effectiveGain.toDouble() / 40.0)

        val w0 = 2.0 * Math.PI * filter.freq / fs
        val alpha = Math.sin(w0) / (2.0 * filter.q)
        val cosW0 = Math.cos(w0)

        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        var a0 = 0.0; var a1 = 0.0; var a2 = 0.0

        when (filter.type) {
            "LS", "HS" -> {
                val s = if (filter.type == "HS") 1.0 else -1.0
                val sqA = Math.sqrt(a)
                b0 = a * ((a + 1.0) + s * (a - 1.0) * cosW0 + 2.0 * sqA * alpha)
                b1 = -s * 2.0 * a * ((a - 1.0) + s * (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + s * (a - 1.0) * cosW0 - 2.0 * sqA * alpha)
                a0 = (a + 1.0) - s * (a - 1.0) * cosW0 + 2.0 * sqA * alpha
                a1 = s * 2.0 * ((a - 1.0) - s * (a + 1.0) * cosW0)
                a2 = (a + 1.0) - s * (a - 1.0) * cosW0 - 2.0 * sqA * alpha
            }
            else -> { // "PK"
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
        }

        val payload = java.nio.ByteBuffer.allocate(60).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        payload.put(WRITE).put(CMD_PEQ_VALUES).put(0x18.toByte()).put(0x00).put(index.toByte()).put(0x00).put(0x00)

        payload.putFloat((b0/a0).toFloat()); payload.putFloat((b1/a0).toFloat()); payload.putFloat((b2/a0).toFloat())
        payload.putFloat((a1/a0).toFloat()); payload.putFloat((a2/a0).toFloat())

        payload.putShort(filter.freq.toShort())
        payload.putShort((filter.q * 256).toInt().toShort())
        // FIX: Send effectiveGain (0 when off) instead of the raw slider value
        payload.putShort((effectiveGain * 256).toInt().toShort())
        payload.put(TYPE_CODES[filter.type] ?: 0x02)
        payload.put(0x00.toByte()).put(activeSlot).put(END)

        sendHidCommand(payload.array())

        // FIX: Only latch and save if we aren't in the middle of a mass update
        if (autoLatch) {
            latchSettings()
            debouncedSaveToFlash()
        }
    }

    private fun sendHidCommand(payload: ByteArray) {
        // Simply push to the queue; the processor handles the rest sequentially
        commandQueue.trySend(payload)
    }

    private fun startQueueProcessor() {
        queueProcessorJob?.cancel()
        queueProcessorJob = lifecycleScope.launch(Dispatchers.IO) {
            for (payload in commandQueue) {
                val connection = usbConnection ?: continue
                try {
                    val buffer = ByteArray(64)
                    buffer[0] = REPORT_ID.toByte()
                    System.arraycopy(payload, 0, buffer, 1, payload.size.coerceAtMost(63))

                    // Safety check: Ensure the connection is still valid before the transfer
                    // CRITICAL FIX: The 4th parameter MUST be the correct Interface ID, not 0.
                    val interfaceId = usbInterface?.id ?: 0

                    // Safety check: Ensure the connection is still valid before the transfer
                    val result = if (usbConnection != null) {
                        connection.controlTransfer(0x21, 0x09, 0x0200 or REPORT_ID.toInt(), interfaceId, buffer, 64, 1000)
                    } else -1

                    if (result < 0) {
                        Log.e("USB", "Transfer Failed. Attempting Clear Halt...")
                        // Attempt to clear the hardware stall before giving up
                        // 0x01 = CLEAR_FEATURE, 0x00 = ENDPOINT_HALT
                        connection.controlTransfer(0x02, 0x01, 0x00, interfaceId, null, 0, 500)

                        // Wait a moment for hardware to reset its buffer
                        kotlinx.coroutines.delay(100)
                        continue
                    }

                    // HARDENING: Drastically increase delays for Bulk Imports
                    // HARDENING: Drastically increase delays for Bulk Imports
                    val delayTime = when {
                        payload.size > 1 && payload[1] == CMD_FLASH_EQ -> 500L // Flash needs half a second
                        // FIX: Only apply the 150ms delay to PEQ WRITES. PEQ READS should be fast.
                        payload.size > 1 && payload[0] == WRITE && payload[1] == CMD_PEQ_VALUES -> 150L
                        else -> 40L // Volume/Others
                    }
                    kotlinx.coroutines.delay(delayTime)
                } catch (e: Exception) {
                    Log.e("USB", "Queue Crash", e)
                }
            }
        }
    }

    private fun startReadingThread() {
        readThreadJob?.cancel()
        readThreadJob = lifecycleScope.launch(Dispatchers.IO) {
            val ep = endpointIn ?: return@launch
            val connection = usbConnection ?: return@launch

            val request = UsbRequest()
            request.initialize(connection, ep)
            val byteBuffer = java.nio.ByteBuffer.allocate(64)

            // CRITICAL: Tracks if a request is currently "in-flight" in the kernel.
            // This prevents the IllegalStateException (Double Queue) crash.
            var isRequestPending = false

            while (usbConnection != null && readThreadJob?.isActive == true) {
                if (!isAppInFocus || isMassPushing) {
                    // If we lose focus, we wait. If a request is pending,
                    // we must eventually reap it before closing the app.
                    kotlinx.coroutines.delay(200); continue
                }

                // 1. Only queue a new request if the kernel isn't already holding one.
                if (!isRequestPending) {
                    byteBuffer.clear()
                    if (request.queue(byteBuffer)) {
                        isRequestPending = true
                    } else {
                        // If queueing fails (e.g. device disconnected), exit the loop.
                        break
                    }
                }

                // 2. Wait for the kernel to signal completion.
                // We wrap this in a catch block to handle the TimeoutException gracefully.
                val finishedRequest = try {
                    connection.requestWait(100)
                } catch (e: Exception) {
                    null
                }

                // 3. If the request finished, mark it as ready to be re-queued.
                if (finishedRequest == request) {
                    isRequestPending = false

                    val data = byteBuffer.array()
                    if (data.size >= 8 && data[1] == READ) {
                        val dataCopy = data.copyOf()
                        withContext(Dispatchers.Main) { parseResponse(dataCopy[2], dataCopy) }
                    }
                }

                // NOTE: If finishedRequest is null (timeout), isRequestPending stays TRUE.
                // The loop will skip the queue() step and go straight back to requestWait().
            }

            // Cleanup: Only close the request if the coroutine wasn't cancelled.
            // This prevents a JNI deadlock/freeze where request.close() and
            // connection.close() collide during focus loss.
            try {
                if (readThreadJob?.isActive == true) {
                    request.close()
                }
            } catch (e: Exception) {
                Log.e("USB", "Request Close Error", e)
            }
        }
    }

    private fun readDacSettings() {
        if (usbConnection == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isSyncing = true

                // 1. Read Global Settings (Filter, Gain Mode, Amp, Volume)
                val globalCmds = byteArrayOf(CMD_FILTER, CMD_GAIN_MODE, CMD_AMP_TOPO, CMD_GLOBAL_GAIN)
                for (cmd in globalCmds) {
                    sendHidCommand(byteArrayOf(READ, cmd, END, 0x00))
                    kotlinx.coroutines.delay(80)
                }

                // 2. Read Mic Gain
                sendHidCommand(byteArrayOf(READ, CMD_MIC_GAIN, 0x02, 0x02))
                kotlinx.coroutines.delay(80)

                // 3. Read Balance (Matches your Python Logic: sf=0x01 for Left, 0x00 for Right)
                sendHidCommand(byteArrayOf(READ, CMD_BALANCE, 0x04, 0x01, 0x00, 0x00)) // Query Left
                kotlinx.coroutines.delay(80)
                sendHidCommand(byteArrayOf(READ, CMD_BALANCE, 0x04, 0x00, 0x00, 0x00)) // Query Right
                kotlinx.coroutines.delay(80)

                // 4. Read all 10 PEQ Bands
                for (i in 0 until 10) {
                    sendHidCommand(byteArrayOf(READ, CMD_PEQ_VALUES, 0x00, 0x00, i.toByte(), END))
                    kotlinx.coroutines.delay(60)
                }

            } finally {
                isSyncing = false
                // Final UI Latch: Force a complete refresh once all data is trickled in
                // Final UI Latch: Force a complete refresh once all data is trickled in
                withContext(Dispatchers.Main) {
                    // Re-enable controls once sync is complete
                    findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.isEnabled = true
                    findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)?.isEnabled = true
                    findViewById<com.google.android.material.slider.Slider>(R.id.balanceSlider)?.isEnabled = true
                    findViewById<com.google.android.material.slider.Slider>(R.id.micGainSlider)?.isEnabled = true

                    findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()
                    findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                        this.bands = eqBands.map { it.copy() } // Snapshot for safety
                        this.pathDirty = true
                        this.postInvalidate()
                    }
                    Toast.makeText(this@MainActivity, "DAC Synced", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateBalance(v: Int) {
        val magL = if (v < 0) (256 + v) else 0x00
        val magR = if (v > 0) (256 - v) else 0x00
        sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, 0x04, 0x01, 0x00, magL.toByte()))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, 0x04, 0x00, 0x00, magR.toByte()))
            latchSettings()
            debouncedSaveToFlash()
        }, 50)
    }

    private fun latchSettings() {
        sendHidCommand(byteArrayOf(WRITE, 0x0A.toByte(), 0x04, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00))
    }

    // Update the receiver to listen for NEW attachments
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                // FIX: Automatically catch when the DAC is physically plugged in
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device?.vendorId == VID && device?.productId == PID) {
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
                    if (device?.vendorId == VID && device?.productId == PID) {
                        closeUsbConnection()
                    }
                }
            }
        }
    }


    // --- RECYCLERVIEW ADAPTER (MOVED OUT OF FUNCTION TO PREVENT CRASHES) ---
    class EqAdapter(private val bands: List<FilterBand>, private val onUpdate: (Int, FilterBand) -> Unit) : RecyclerView.Adapter<EqAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.bandEnable)
            val type: AutoCompleteTextView = v.findViewById(R.id.typeSelector)
            val freq: EditText = v.findViewById(R.id.editFreq)
            val gain: EditText = v.findViewById(R.id.editGain)
            val q: EditText = v.findViewById(R.id.editQ)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_eq_band, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val band = bands[position]

            // FIX: Clear listeners BEFORE updating UI so recycled views don't trigger hardware spam
            holder.check.setOnCheckedChangeListener(null)
            holder.freq.setOnEditorActionListener(null)
            holder.gain.setOnEditorActionListener(null)
            holder.q.setOnEditorActionListener(null)

            // 1. Populate UI with current model data
            holder.check.isChecked = band.enabled
            holder.freq.setText(band.freq.toString())
            holder.gain.setText(String.format(java.util.Locale.US, "%.2f", band.gain))
            holder.q.setText(String.format(java.util.Locale.US, "%.2f", band.q))

            // 2. Fix Dropdown
            val types = arrayOf("PK", "LS", "HS")
            val typeAdapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_list_item_1, types)
            holder.type.setAdapter(typeAdapter)
            holder.type.inputType = android.text.InputType.TYPE_NULL
            holder.type.setText(band.type, false)

            holder.type.setOnItemClickListener { _, _, pos, _ ->
                band.type = types[pos]
                onUpdate(position, band)
            }

            holder.check.setOnCheckedChangeListener { _, isChecked ->
                band.enabled = isChecked
                onUpdate(position, band)
            }

            // 3. Update Action: Rounding & Bounce Back Logic
            val updateAction = TextView.OnEditorActionListener { v, _, _ ->
                try {
                    // Check Freq (20 to 20,000)
                    val fInput = holder.freq.text.toString().toIntOrNull()
                    if (fInput != null && fInput in 20..20000) {
                        band.freq = fInput
                    } else {
                        holder.freq.setText(band.freq.toString())
                    }

                    // Check Gain (-10.0 to 10.0) and round to 2 decimals
                    val gInput = holder.gain.text.toString().toFloatOrNull()
                    if (gInput != null && gInput in -10f..10f) {
                        band.gain = Math.round(gInput * 100) / 100f
                        holder.gain.setText(String.format(java.util.Locale.US, "%.2f", band.gain))
                    } else {
                        holder.gain.setText(String.format(java.util.Locale.US, "%.2f", band.gain))
                    }

                    // Check Q Factor (0.1 to 10.0) and round to 2 decimals
                    val qInput = holder.q.text.toString().toFloatOrNull()
                    if (qInput != null && qInput in 0.1f..10f) {
                        band.q = Math.round(qInput * 100) / 100f
                        holder.q.setText(String.format(java.util.Locale.US, "%.2f", band.q))
                    } else {
                        holder.q.setText(String.format(java.util.Locale.US, "%.2f", band.q))
                    }

                    onUpdate(position, band)
                } catch (e: Exception) {
                    // Failsafe
                    holder.freq.setText(band.freq.toString())
                    holder.gain.setText(String.format(java.util.Locale.US, "%.2f", band.gain))
                    holder.q.setText(String.format(java.util.Locale.US, "%.2f", band.q))
                }
                v.clearFocus()
                false
            }

            holder.freq.setOnEditorActionListener(updateAction)
            holder.gain.setOnEditorActionListener(updateAction)
            holder.q.setOnEditorActionListener(updateAction)
        }
        override fun getItemCount() = bands.size
    }

    private fun parseAutoEq(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) { // Move entire operation off Main Thread
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                var bandIdx = 0

                // 1. SILENT DATA PARSE (No UI updates yet)
            // Wipe existing bands so a smaller EQ import doesn't leave ghost bands active
            eqBands.forEachIndexed { i, band ->
                val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
                band.apply { enabled = false; type = "PK"; freq = defaultFreqs[i]; gain = 0f; q = 1.0f }
            }

            // Use readLines() to prevent Android URI stream cutoffs and uppercase everything to fix case-sensitivity
            reader.readLines().forEach { rawLine ->
                val line = rawLine.trim().uppercase()

                if (line.contains("PREAMP:")) {
                    val match = Regex("PREAMP:\\s*([-+]?[\\d.]+)").find(line)
                    if (match != null) {
                        val importedDb = match.groupValues[1].toFloatOrNull() ?: 0f
                        val rawVal = (VOL_MAX_RAW + (importedDb * 256)).toInt()
                        val calculatedPercent = ((rawVal - VOL_MIN_RAW).toFloat() / (VOL_MAX_RAW - VOL_MIN_RAW).toFloat() * 100)
                        volumePercent = Math.round(calculatedPercent.coerceIn(0f, 100f)).toFloat()
                    }
                } else if (line.contains("FILTER") && bandIdx < 10) {
                    val fcMatch = Regex("FC\\s+([\\d.]+)").find(line)
                    val gainMatch = Regex("GAIN\\s+([-+.\\d]+)").find(line)
                    val qMatch = Regex("Q\\s+([\\d.]+)").find(line)

                    eqBands[bandIdx].apply {
                        freq = fcMatch?.groupValues?.get(1)?.toFloatOrNull()?.toInt()?.coerceIn(20, 20000) ?: 1000
                        gain = gainMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(-10f, 10f) ?: 0f
                        q = qMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
                        type = if (line.contains("LS")) "LS" else if (line.contains("HS")) "HS" else "PK"
                        enabled = line.contains("ON")
                    }
                    bandIdx++
                }
            }

                // 2. HARDWARE PUSH
                // (Already in background thread, no new launch needed)
                isSyncing = true
                isMassPushing = true

                // Give the system a moment to settle after file I/O
                kotlinx.coroutines.delay(300)

                // Push Volume
                val totalRaw = (VOL_MIN_RAW + (volumePercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
                val clampedRaw = totalRaw.coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)
                sendHidCommand(byteArrayOf(WRITE, CMD_GLOBAL_GAIN, 0x03, (clampedRaw and 0xFF).toByte(), (clampedRaw shr 8).toByte(), 0x00))
                kotlinx.coroutines.delay(100)

                // Push Filters in Bulk Mode
                eqBands.forEachIndexed { index, band ->
                    sendFilterUpdate(index, band, autoLatch = false)
                    kotlinx.coroutines.delay(50) // Pacing the queue entries
                }

                // Single Latch
                kotlinx.coroutines.delay(200)
                latchSettings()

                isMassPushing = false

                // 3. UI UPDATE (Final Step - only after hardware is done)
                withContext(Dispatchers.Main) {
                    findViewById<com.google.android.material.slider.Slider>(R.id.volumeSlider)?.value = volumePercent
                    findViewById<com.google.android.material.slider.Slider>(R.id.eqMasterVolume)?.value = volumePercent

                    findViewById<RecyclerView>(R.id.eqRecyclerView)?.adapter?.notifyDataSetChanged()

                    findViewById<EqGraphView>(R.id.eqGraph)?.apply {
                        pathDirty = true // Recalculate graph math
                        postInvalidate() // Redraw
                    }

                    isSyncing = false
                    debouncedSaveToFlash()
                    Toast.makeText(this@MainActivity, "Import Successful & Stable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun saveSettingsToFile(uri: Uri) { /* Keep existing implementation */ }

    private fun closeUsbConnection() {
        // 1. Signal all jobs to stop immediately
        readThreadJob?.cancel()
        queueProcessorJob?.cancel()
        connectionWatchdogJob?.cancel()
        volumeDebounceJob?.cancel()
        isPermissionRequested = false

        // 2. Move hardware release to a background thread to prevent UI freezing
        val connectionToClose = usbConnection
        val interfaceToRelease = usbInterface

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Wait for requestWait(100) in the read thread to naturally time out and release
                kotlinx.coroutines.delay(150)

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
        pollingJob?.cancel()

        resetUiToDefaults()
    }

    override fun onDestroy() {
        // This is now the ONLY place where we physically release the hardware
        closeUsbConnection()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e("USB", "Receiver already unregistered")
        }
        super.onDestroy()
    }
}