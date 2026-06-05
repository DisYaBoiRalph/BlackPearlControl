package com.chesaudio.bpcontrol

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    data class FilterBand(
        var enabled: Boolean = true,
        var type: String = "PK",
        var freq: Int = 1000,
        var gain: Float = 0.0f,
        var q: Float = 1.0f
    )

    data class Preset(
        var name: String,
        var preamp: Float,
        val bands: MutableList<FilterBand>
    )

    private var presets = mutableListOf<Preset>()
    private var currentPresetIndex = 0

    // Move these to the top of the class
    private val filterOptions = arrayOf("FAST-LL", "Fast-PC (BEST)", "Slow-LL", "SLOW-PC", "NOS")
    private val gainOptions = arrayOf("LOW", "HIGH")
    private val ampOptions = arrayOf("CLASS H", "CLASS AB")

    private val usbMutex = Mutex() // CRITICAL: Prevents thread collision kernel panics

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

    private var isUserTouchingSlider = false
    private var lastSliderReleaseTime = 0L

    private var volumePercent = 50f
    private var isSyncing = false
    private var isMassPushing = false
    @Volatile private var isQueueActive = false
        set(value) {
            field = value
            updateStatusDot()
        }
    private var dacBalLeft = 0   // Track Left side attenuation
    private var dacBalRight = 0
    private var activeSlot: Byte = 0x00 // Required to unlock Flash Saving

    private var greenDelayJob: Job? = null

    private val commandQueue = Channel<ByteArray>(
        capacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var queueProcessorJob: Job? = null
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

    private val ACTION_USB_PERMISSION = "com.chesaudio.bpcontrol.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null

    private var isFlashSavePending = false
    private val flashHandler = Handler(Looper.getMainLooper())
    private val flashRunnable = Runnable {
        isFlashSavePending = false
        saveToFlash()
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
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
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
        val currentRaw = (VOL_MIN_RAW + (volPercent / 100.0) * (VOL_MAX_RAW - VOL_MIN_RAW)).toInt()
        val clampedRaw = currentRaw.coerceIn(VOL_MIN_RAW, VOL_MAX_RAW)
        return (VOL_MAX_RAW - clampedRaw).toFloat() / 256f
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

    private fun updateStatusDot() {
        runOnUiThread {
            val dots = listOfNotNull(
                findViewById<View>(R.id.statusDotSettings),
                findViewById<View>(R.id.statusDotEq)
            )
            if (dots.isEmpty()) return@runOnUiThread

            val isDisconnected = usbConnection == null
            val isBusy = isQueueActive || !commandQueue.isEmpty || isFlashSavePending || isSyncing || isMassPushing

            if (isDisconnected) {
                greenDelayJob?.cancel()
                setDotUI(dots, R.color.status_error)
            } else if (isBusy) {
                greenDelayJob?.cancel()
                setDotUI(dots, R.color.status_pending)
            } else {
                // If not busy, wait a short moment before turning green to prevent blinking during rapid updates
                if (greenDelayJob?.isActive != true) {
                    greenDelayJob = lifecycleScope.launch {
                        delay(400) // 400ms "Settle" time
                        setDotUI(dots, R.color.status_success)
                    }
                }
            }
        }
    }

    private fun setDotUI(dots: List<View>, colorRes: Int) {
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(getColor(colorRes))
        }
        dots.forEach { it.background = drawable }
    }

    private fun debouncedSaveToFlash() {
        // Stop any pending save and schedule a new one after 1 second of silence
        isFlashSavePending = true
        updateStatusDot()
        flashHandler.removeCallbacks(flashRunnable)
        flashHandler.postDelayed(flashRunnable, 1000)
    }

    private fun loadPresetsFromPrefs() {
        val prefs = getSharedPreferences("BP_PRESETS", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("presets_data", null)
        presets.clear()

        val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        if (jsonStr != null) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val pObj = array.getJSONObject(i)
                    val name = pObj.getString("name")

                    // CRITICAL: Never load "Flat" or "None" from disk to ensure they stay clean/scratchpad
                    if (name == "Flat" || name == "None") continue

                    val preamp = pObj.optDouble("preamp", 0.0).toFloat()
                    val bArray = pObj.getJSONArray("filters")
                    val bList = mutableListOf<FilterBand>()
                    for (b in 0 until 10) {
                        if (b < bArray.length()) {
                            val bObj = bArray.getJSONObject(b)
                            bList.add(FilterBand(
                                enabled = bObj.getBoolean("enabled"),
                                type = bObj.getString("type"),
                                freq = bObj.getInt("freq"),
                                gain = bObj.getDouble("gain").toFloat(),
                                q = bObj.getDouble("q").toFloat()
                            ))
                        } else {
                            bList.add(FilterBand(freq = defaultFreqs[b]))
                        }
                    }
                    presets.add(Preset(name, preamp, bList))
                }
            } catch (e: Exception) { Log.e("Presets", "JSON Parse Error", e) }
        }

        // --- Permanent System Presets ---
        // Ensure "Flat" exists at index 0 and is ALWAYS clean (0dB, default freqs)
        val flatBands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, enabled = true) }
        presets.add(0, Preset("Flat", 0f, flatBands))

        // Ensure "None" exists (scratchpad for unknown hardware states or temporary tweaks)
        val noneBands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i]) }
        presets.add(Preset("None", 0f, noneBands))
    }

    private fun savePresetsToPrefs() {
        val prefs = getSharedPreferences("BP_PRESETS", Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        for (p in presets) {
            // System presets are recreated at runtime, so we don't need to save them
            if (p.name == "Flat" || p.name == "None") continue

            val pObj = org.json.JSONObject()
            pObj.put("name", p.name)
            pObj.put("preamp", p.preamp.toDouble())
            val bArray = org.json.JSONArray()
            for (b in p.bands) {
                val bObj = org.json.JSONObject()
                bObj.put("enabled", b.enabled)
                bObj.put("type", b.type)
                bObj.put("freq", b.freq)
                bObj.put("gain", b.gain.toDouble())
                bObj.put("q", b.q.toDouble())
                bArray.put(bObj)
            }
            pObj.put("filters", bArray)
            array.put(pObj)
        }
        prefs.edit().putString("presets_data", array.toString()).apply()
    }

    private fun identifyPreset(hwBands: List<FilterBand>): Int {
        for (i in presets.indices) {
            if (presets[i].name == "None") continue
            var match = true

            for (b in 0 until 10) {
                val hw = hwBands[b]
                val saved = presets[i].bands[b]

                // Compare effective gains
                val hwGain = if (hw.enabled) hw.gain else 0f
                val savedGain = if (saved.enabled) saved.gain else 0f

                if (abs(hwGain - savedGain) > 0.1f) { match = false; break }

                // If the band is active, check specific parameters
                if (abs(hwGain) > 0.1f || abs(savedGain) > 0.1f) {
                    if (abs(hw.freq - saved.freq) > 1.0f) { match = false; break }
                    if (abs(hw.q - saved.q) > 0.05f) { match = false; break }
                    if (hw.type != saved.type) { match = false; break }
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun startConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = lifecycleScope.launch(Dispatchers.IO) { // Shift to IO Thread
            // Keep trying every 1.5s until a hardware connection is established
            while (usbConnection == null && isAppInFocus) {
                findAndConnect()
                delay(1500)
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
            updateStatusDot()

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
        sendHidCommand(byteArrayOf(WRITE, CMD_FLASH_EQ, 0x01, END))
    }

    // Added 'suspend' keyword to allow locking
    private suspend fun pullValueSync(cmd: Byte, p1: Byte = 0x00, p2: Byte = 0x00, p3: Byte = 0x00): ByteArray? {
        val connection = usbConnection ?: return null
        val interfaceId = usbInterface?.id ?: 0
        val inEndpoint = endpointIn ?: return null

        return usbMutex.withLock {
            try {
                val outBuffer = ByteArray(64).apply {
                    this[0] = REPORT_ID.toByte()
                    this[1] = READ
                    this[2] = cmd
                    this[3] = p1
                    this[4] = p2
                    this[5] = p3
                }

                // Clear any leftover packets first
                val dump = ByteArray(64)
                while (connection.bulkTransfer(inEndpoint, dump, 64, 2) > 0) { /* flush */ }

                val writeResult = connection.controlTransfer(0x21, 0x09, 0x0200 or REPORT_ID, interfaceId, outBuffer, 64, 200)
                if (writeResult < 0) return@withLock null

                val inBuffer = ByteArray(64)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 400) {
                    val readResult = connection.bulkTransfer(inEndpoint, inBuffer, 64, 50)
                    if (readResult > 0 && inBuffer[1] == READ && inBuffer[2] == cmd) {
                        return@withLock inBuffer.copyOf()
                    }
                    delay(5)
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
            delay(300)

            var claimed = false
            for (i: Int in 1..3) {
                if (connection.claimInterface(intf, true)) {
                    claimed = true
                    break
                }
                delay(150) // Non-blocking delay
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
                delay(500)
                readDacSettings()
                startVolumePolling()
                updateStatusDot()
            }
        }
    }

    private fun startVolumePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (usbConnection != null) {
                // Check flags: If we are syncing EQ, back off the volume poller
                if (!isAppInFocus || isSyncing || isMassPushing || isUserTouchingSlider) {
                    delay(500) // Longer delay when busy
                    continue
                }

                delay(1000)

                // Re-check just before the USB call
                if (isSyncing || isMassPushing) continue

                val response = pullValueSync(CMD_GLOBAL_GAIN, END, 0x00) ?: continue

                // 2. Parse volume safely
                val rawVol = ((response[4].toInt() and 0xFF) or ((response[5].toInt() and 0xFF) shl 8)).toShort().toInt()
                if (rawVol == 0 && response[6].toInt() == 0) continue // Ignore garbage

                val exactVol = ((rawVol - VOL_MIN_RAW).toFloat() / (VOL_MAX_RAW - VOL_MIN_RAW).toFloat() * 100).coerceIn(0f, 100f)
                val roundedVol = Math.round(exactVol).toFloat()

                // 3. Apply to UI safely
                if (abs(volumePercent - roundedVol) >= 1.0f) {// 3. Apply to UI safely
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
    }

    private fun initUi() {

        // --- Setup Dropdown Menus ---
        findViewById<AutoCompleteTextView>(R.id.filterSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, filterOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, 0x01, (position + 1).toByte(), 0x00))
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.gainSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, gainOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, 0x01, position.toByte(), 0x00))
                    debouncedSaveToFlash()
                }
            }
        }

        findViewById<AutoCompleteTextView>(R.id.ampSelector)?.apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, ampOptions))
            inputType = InputType.TYPE_NULL
            setOnItemClickListener { _, _, position, _ ->
                if (!isSyncing) {
                    sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, 0x01, position.toByte(), 0x00))
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
                    sendHidCommand(byteArrayOf(WRITE, CMD_MIC_GAIN, 0x02, 0x80.toByte(), (value.toInt() and 0xFF).toByte()))
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
                        updateStatusDot()

                        // 1. Reset Global Settings
                        volumePercent = 50f
                        updateHardwareVolume(latchAndSave = false)
                        sendHidCommand(byteArrayOf(WRITE, CMD_FILTER, 0x01, 0x01, 0x00))
                        sendHidCommand(byteArrayOf(WRITE, CMD_GAIN_MODE, 0x01, 0x00, 0x00))
                        sendHidCommand(byteArrayOf(WRITE, CMD_AMP_TOPO, 0x01, 0x00, 0x00))
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

        sendHidCommand(byteArrayOf(WRITE, CMD_GLOBAL_GAIN, 0x03, (clampedRaw and 0xFF).toByte(), (clampedRaw shr 8).toByte(), 0x00))

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
                    val currentPreset = presets[currentPresetIndex]

                    if (currentPreset.name == "Flat") {
                        // If user edits "Flat", switch them to "None" scratchpad to keep Flat clean
                        val noneIdx = presets.indexOfFirst { it.name == "None" }.coerceAtLeast(0)
                        currentPresetIndex = noneIdx
                        findViewById<Button>(R.id.btnPresets)?.text = "None"

                        // Sync None with current state
                        presets[noneIdx].bands.clear()
                        presets[noneIdx].bands.addAll(eqBands.map { it.copy() })
                    } else {
                        // Regular preset or "None" - update single band
                        presets[currentPresetIndex].bands[index] = band.copy()
                    }
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
                        while (!commandQueue.isEmpty || isQueueActive) { delay(50) }
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
        val fs = 48000.0
        // FIX: Calculate an effective gain (0f if disabled) and use it EVERYWHERE
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        val a = Math.pow(10.0, effectiveGain.toDouble() / 40.0)

        val w0 = 2.0 * Math.PI * filter.freq / fs
        val alpha = Math.sin(w0) / (2.0 * filter.q)
        val cosW0 = Math.cos(w0)

        var b0: Double; var b1: Double; var b2: Double
        var a0: Double; var a1: Double; var a2: Double

        // "PK" only
        b0 = 1.0 + alpha * a
        b1 = -2.0 * cosW0
        b2 = 1.0 - alpha * a
        a0 = 1.0 + alpha / a
        a1 = -2.0 * cosW0
        a2 = 1.0 - alpha / a

        val payload = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(WRITE).put(CMD_PEQ_VALUES).put(0x18.toByte()).put(0x00).put(index.toByte()).put(0x00).put(0x00)

        payload.putFloat((b0/a0).toFloat()); payload.putFloat((b1/a0).toFloat()); payload.putFloat((b2/a0).toFloat())
        payload.putFloat((a1/a0).toFloat()); payload.putFloat((a2/a0).toFloat())

        payload.putShort(filter.freq.toShort())
        payload.putShort((filter.q * 256).toInt().toShort())
        // FIX: Send effectiveGain (0 when off) instead of the raw slider value
        payload.putShort((effectiveGain * 256).toInt().toShort())
        payload.put(0x02.toByte()) // Always PK type
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
        updateStatusDot()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startQueueProcessor() {
        queueProcessorJob?.cancel()
        queueProcessorJob = lifecycleScope.launch(Dispatchers.IO) {
            for (payload in commandQueue) {
                isQueueActive = true // Mark processor as actively working
                val connection = usbConnection
                if (connection == null) {
                    isQueueActive = false
                    continue
                }
                try {
                    val buffer = ByteArray(64)
                    buffer[0] = REPORT_ID.toByte()
                    System.arraycopy(payload, 0, buffer, 1, payload.size.coerceAtMost(63))
                    val interfaceId = usbInterface?.id ?: 0

                    var result = -1
                    var retryCount = 0

                    // CRITICAL FIX: Retry loop ensures hardware drops are recovered instead of silently failing
                    while (result < 0 && retryCount < 3) {
                        result = usbMutex.withLock {
                            if (usbConnection != null) {
                                connection.controlTransfer(0x21, 0x09, 0x0200 or REPORT_ID, interfaceId, buffer, 64, 200)
                            } else -1
                        }

                        if (result < 0) {
                            Log.e("USB", "Transfer Failed. Attempting Clear Halt and Retry...")
                            usbMutex.withLock {
                                usbConnection?.controlTransfer(0x02, 0x01, 0x00, interfaceId, null, 0, 100)
                            }
                            delay(20)
                            retryCount++
                        }
                    }

                    val delayTime = when {
                        payload[1] == CMD_FLASH_EQ -> 300L     // Reduced flash wait
                        payload[1] == CMD_PEQ_VALUES -> 100L   // Reduced coefficient wait
                        payload[1] == CMD_GLOBAL_GAIN -> 20L    // Fast volume
                        else -> 20L
                    }
                    delay(delayTime)
                } catch (e: Exception) {
                    Log.e("USB", "Queue Crash", e)
                } finally {
                    // Only release active state if there is nothing waiting in the buffer
                    if (commandQueue.isEmpty) {
                        isQueueActive = false
                    }
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun readDacSettings() {
        if (usbConnection == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                isSyncing = true
                updateStatusDot()

                // 1. Read Filter
                pullValueSync(CMD_FILTER, END, 0x00)?.let { data ->
                    val value = data[4].toInt()
                    withContext(Dispatchers.Main) {
                        filterOptions.getOrNull(value - 1)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.filterSelector)?.setText(text, false)
                        }
                    }
                }
                delay(60)

                // 2. Read Gain Mode
                pullValueSync(CMD_GAIN_MODE, END, 0x00)?.let { data ->
                    val value = data[4].toInt()
                    withContext(Dispatchers.Main) {
                        gainOptions.getOrNull(value)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.gainSelector)?.setText(text, false)
                        }
                    }
                }
                delay(60)

                // 3. Read Amp Topo
                pullValueSync(CMD_AMP_TOPO, END, 0x00)?.let { data ->
                    val value = data[4].toInt()
                    withContext(Dispatchers.Main) {
                        ampOptions.getOrNull(value)?.let { text ->
                            findViewById<AutoCompleteTextView>(R.id.ampSelector)?.setText(text, false)
                        }
                    }
                }
                delay(60)

                // 4. Read Volume
                pullValueSync(CMD_GLOBAL_GAIN, END, 0x00)?.let { data ->
                    val rawVol = ((data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)).toShort().toInt()
                    if (!(rawVol == 0 && data[6].toInt() == 0)) { // Ignore garbage
                        val exactVol = ((rawVol - VOL_MIN_RAW).toFloat() / (VOL_MAX_RAW - VOL_MIN_RAW).toFloat() * 100).coerceIn(0f, 100f)
                        volumePercent = Math.round(exactVol).toFloat()
                    }
                }
                delay(60)

                // 5. Read Mic Gain
                pullValueSync(CMD_MIC_GAIN, 0x02, 0x02)?.let { data ->
                    val micDb = data[5].toInt().coerceIn(-15, 15)
                    withContext(Dispatchers.Main) {
                        findViewById<Slider>(R.id.micGainSlider)?.value = micDb.toFloat()
                    }
                }
                delay(60)

                // 6. Read Balance
                pullValueSync(CMD_BALANCE, 0x04, 0x01)?.let { data -> // Left
                    val mag = data[6].toInt() and 0xFF
                    dacBalLeft = if (mag > 0) (mag - 256) else 0
                }
                delay(60)
                pullValueSync(CMD_BALANCE, 0x04, 0x00)?.let { data -> // Right
                    val mag = data[6].toInt() and 0xFF
                    dacBalRight = if (mag > 0) (256 - mag) else 0
                }
                delay(60)

                val combined = if (abs(dacBalLeft) > abs(dacBalRight)) dacBalLeft else dacBalRight
                val finalBal = if (abs(combined) <= 1) 0f else combined.toFloat()
                withContext(Dispatchers.Main) {
                    findViewById<Slider>(R.id.balanceSlider)?.value = finalBal.coerceIn(-15f, 15f)
                }

                // 7. Read PEQ Bands
                for (i in 0 until 10) {
                    pullValueSync(CMD_PEQ_VALUES, 0x00, 0x00, i.toByte())?.let { data ->
                        val rawF = (data[28].toInt() and 0xFF) or ((data[29].toInt() and 0xFF) shl 8)
                        val rawQ = ((data[30].toInt() and 0xFF) or ((data[31].toInt() and 0xFF) shl 8)) / 256.0f
                        val f = rawF.coerceIn(20, 20000)
                        val q = rawQ.coerceIn(0.1f, 10.0f)
                        val gRaw = ((data[32].toInt() and 0xFF) or ((data[33].toInt() and 0xFF) shl 8)).toShort().toInt()
                        var g = gRaw / 256.0f

                        // SNAP LOGIC: Treat gains below 0.25dB as flat zero
                        if (abs(g) < 0.25f) g = 0.0f

                        activeSlot = data[36]
                        eqBands[i].apply {
                            freq = f;
                            this.q = q;
                            gain = g;
                            type = "PK";
                            // FIX: Only force-enable if hardware has gain. 
                            // If gain is 0, we preserve the current UI toggle state (checked/unchecked).
                            if (abs(g) > 0.01f) enabled = true
                        }
                    }
                    delay(60)
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
                    updateStatusDot()
                }
            }
        }
    }

    private fun updateBalance(v: Int) {
        val magL = if (v < 0) (256 + v) else 0x00
        val magR = if (v > 0) (256 - v) else 0x00
        sendHidCommand(byteArrayOf(WRITE, CMD_BALANCE, 0x04, 0x01, 0x00, magL.toByte()))
        Handler(Looper.getMainLooper()).postDelayed({
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


    // --- RECYCLERVIEW ADAPTER (MOVED OUT OF FUNCTION TO PREVENT CRASHES) ---
    class EqAdapter(private val bands: List<FilterBand>, private val onUpdate: (Int, FilterBand) -> Unit) : RecyclerView.Adapter<EqAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.bandEnable)
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
            holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
            holder.q.setText(String.format(Locale.US, "%.2f", band.q))

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
                        holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                    } else {
                        holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                    }

                    // Check Q Factor (0.1 to 10.0) and round to 2 decimals
                    val qInput = holder.q.text.toString().toFloatOrNull()
                    if (qInput != null && qInput in 0.1f..10f) {
                        band.q = Math.round(qInput * 100) / 100f
                        holder.q.setText(String.format(Locale.US, "%.2f", band.q))
                    } else {
                        holder.q.setText(String.format(Locale.US, "%.2f", band.q))
                    }

                    onUpdate(position, band)
                } catch (_: Exception) {
                    // Failsafe
                    holder.freq.setText(band.freq.toString())
                    holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                    holder.q.setText(String.format(Locale.US, "%.2f", band.q))
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

                                val en = !line.contains("OFF")
                                tempBands.add(FilterBand(enabled = en, type = "PK", freq = f, gain = g, q = q))
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
                    while (!commandQueue.isEmpty || isQueueActive) { delay(50) }
                    delay(100)

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
        updateStatusDot()
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