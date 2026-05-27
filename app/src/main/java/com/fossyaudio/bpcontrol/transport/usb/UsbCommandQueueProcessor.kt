package com.fossyaudio.bpcontrol.transport.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UsbCommandQueueProcessor(
    private val reportId: Byte,
    private val cmdFlashEq: Byte,
    private val cmdPeqValues: Byte,
    private val cmdGlobalGain: Byte,
    private val usbMutex: Mutex
) {
    private val commandQueue = Channel<ByteArray>(
        capacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var processorJob: Job? = null

    @Volatile
    private var queueActive: Boolean = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun hasPendingWork(): Boolean = !commandQueue.isEmpty || queueActive

    fun stop() {
        processorJob?.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun enqueue(payload: ByteArray) {
        commandQueue.trySend(payload)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        scope: CoroutineScope,
        connectionProvider: () -> UsbDeviceConnection?,
        interfaceIdProvider: () -> Int
    ) {
        processorJob?.cancel()
        processorJob = scope.launch(Dispatchers.IO) {
            for (payload in commandQueue) {
                queueActive = true
                val connection = connectionProvider()
                if (connection == null) {
                    queueActive = false
                    continue
                }

                try {
                    val buffer = ByteArray(64)
                    buffer[0] = reportId
                    System.arraycopy(payload, 0, buffer, 1, payload.size.coerceAtMost(63))
                    val interfaceId = interfaceIdProvider()

                    var result = -1
                    var retryCount = 0
                    while (result < 0 && retryCount < 3) {
                        result = usbMutex.withLock {
                            if (connectionProvider() != null) {
                                connection.controlTransfer(0x21, 0x09, 0x0200 or reportId.toInt(), interfaceId, buffer, 64, 1000)
                            } else {
                                -1
                            }
                        }

                        if (result < 0) {
                            Log.e("USB", "Transfer Failed. Attempting Clear Halt and Retry...")
                            usbMutex.withLock {
                                connectionProvider()?.controlTransfer(0x02, 0x01, 0x00, interfaceId, null, 0, 500)
                            }
                            delay(50)
                            retryCount++
                        }
                    }

                    val delayTime = when {
                        payload[1] == cmdFlashEq -> 600L
                        payload[1] == cmdPeqValues -> 180L
                        payload[1] == cmdGlobalGain -> 60L
                        else -> 50L
                    }
                    delay(delayTime)
                } catch (e: Exception) {
                    Log.e("USB", "Queue Crash", e)
                } finally {
                    if (commandQueue.isEmpty) {
                        queueActive = false
                    }
                }
            }
        }
    }
}
