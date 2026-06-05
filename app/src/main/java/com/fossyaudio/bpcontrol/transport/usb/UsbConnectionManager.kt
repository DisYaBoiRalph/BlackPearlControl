package com.fossyaudio.bpcontrol.transport.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol

class UsbConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val actionUsbPermission: String,
    private val vid: Int,
    private val pid: Int,
    private val usbMutex: Mutex
) {
    var usbConnection: UsbDeviceConnection? = null
        private set

    var usbInterface: UsbInterface? = null
        private set

    var endpointIn: UsbEndpoint? = null
        private set

    private var connectionWatchdogJob: Job? = null
    private var isPermissionRequested = false
    private var isAppInFocus = false

    fun setAppInFocus(inFocus: Boolean) {
        isAppInFocus = inFocus
        if (!inFocus) {
            connectionWatchdogJob?.cancel()
        }
    }

    fun onPermissionRequestHandled() {
        isPermissionRequested = false
    }

    fun startConnectionWatchdog(scope: CoroutineScope, onConnected: () -> Unit) {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch(Dispatchers.IO) {
            while (usbConnection == null && isAppInFocus) {
                findAndConnect(scope, onConnected)
                delay(BlackPearlProtocol.Timing.CONNECTION_WATCHDOG_INTERVAL_MS)
                if (usbConnection != null) {
                    Log.d("USB", "Watchdog: Connection successful.")
                    break
                }
            }
        }
    }

    fun setupConnection(scope: CoroutineScope, device: UsbDevice, onConnected: () -> Unit) {
        scope.launch(Dispatchers.IO) {
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

            delay(BlackPearlProtocol.Timing.USB_SETTLE_DELAY_MS)

            var claimed = false
            for (i in 1..3) {
                if (connection.claimInterface(intf, true)) {
                    claimed = true
                    break
                }
                delay(BlackPearlProtocol.Timing.CLAIM_RETRY_DELAY_MS)
            }

            if (!claimed) return@launch

            usbConnection = connection
            usbInterface = intf
            endpointIn = null

            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (
                    ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) {
                    endpointIn = ep
                }
            }

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

            onConnected()
        }
    }

    fun closeConnection(scope: CoroutineScope) {
        connectionWatchdogJob?.cancel()
        isPermissionRequested = false

        val connectionToClose = usbConnection
        val interfaceToRelease = usbInterface

        usbConnection = null
        usbInterface = null
        endpointIn = null

        scope.launch(Dispatchers.IO) {
            try {
                delay(BlackPearlProtocol.Timing.USB_CLOSE_DELAY_MS)
                connectionToClose?.apply {
                    interfaceToRelease?.let { releaseInterface(it) }
                    close()
                }
            } catch (e: Exception) {
                Log.e("USB", "Cleanup Error", e)
            }
        }
    }

    private fun findAndConnect(scope: CoroutineScope, onConnected: () -> Unit) {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == vid && device.productId == pid) {
                if (usbManager.hasPermission(device)) {
                    setupConnection(scope, device, onConnected)
                } else if (!isPermissionRequested && isAppInFocus) {
                    isPermissionRequested = true

                    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    val intent = Intent(actionUsbPermission)
                    intent.setPackage(context.packageName)

                    val permissionIntent = PendingIntent.getBroadcast(
                        context,
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
}
