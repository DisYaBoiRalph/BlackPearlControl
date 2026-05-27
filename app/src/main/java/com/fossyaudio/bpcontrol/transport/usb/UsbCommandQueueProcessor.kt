package com.fossyaudio.bpcontrol.transport.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
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
        capacity = BlackPearlProtocol.Timing.QUEUE_CAPACITY,
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
                    val buffer = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
                    buffer[0] = reportId
                    System.arraycopy(payload, 0, buffer, 1, payload.size.coerceAtMost(BlackPearlProtocol.Frame.QUEUED_PAYLOAD_MAX_SIZE))
                    val interfaceId = interfaceIdProvider()

                    var result = -1
                    var retryCount = 0
                    while (result < 0 && retryCount < BlackPearlProtocol.Timing.QUEUE_RETRY_COUNT) {
                        result = usbMutex.withLock {
                            if (connectionProvider() != null) {
                                connection.controlTransfer(
                                    BlackPearlProtocol.Transfer.REQUEST_TYPE_CLASS_INTERFACE_OUT,
                                    BlackPearlProtocol.Transfer.REQUEST_SET_REPORT,
                                    BlackPearlProtocol.Transfer.VALUE_OUTPUT_REPORT_BASE or reportId.toInt(),
                                    interfaceId,
                                    buffer,
                                    BlackPearlProtocol.Frame.REPORT_SIZE,
                                    BlackPearlProtocol.Timing.QUEUE_TRANSFER_TIMEOUT_MS
                                )
                            } else {
                                -1
                            }
                        }

                        if (result < 0) {
                            Log.e("USB", "Transfer Failed. Attempting Clear Halt and Retry...")
                            usbMutex.withLock {
                                connectionProvider()?.controlTransfer(
                                    BlackPearlProtocol.Transfer.REQUEST_TYPE_STANDARD_ENDPOINT_OUT,
                                    BlackPearlProtocol.Transfer.REQUEST_CLEAR_FEATURE,
                                    BlackPearlProtocol.Transfer.FEATURE_ENDPOINT_HALT,
                                    interfaceId,
                                    null,
                                    0,
                                    BlackPearlProtocol.Timing.QUEUE_CLEAR_HALT_TIMEOUT_MS
                                )
                            }
                            delay(BlackPearlProtocol.Timing.QUEUE_RETRY_DELAY_MS)
                            retryCount++
                        }
                    }

                    val delayTime = when {
                        payload.size > BlackPearlProtocol.ParserOffset.DIRECTION &&
                            payload[BlackPearlProtocol.ParserOffset.DIRECTION] == cmdFlashEq -> BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS
                        payload.size > BlackPearlProtocol.ParserOffset.DIRECTION &&
                            payload[BlackPearlProtocol.ParserOffset.DIRECTION] == cmdPeqValues -> BlackPearlProtocol.Timing.QUEUE_DELAY_PEQ_MS
                        payload.size > BlackPearlProtocol.ParserOffset.DIRECTION &&
                            payload[BlackPearlProtocol.ParserOffset.DIRECTION] == cmdGlobalGain -> BlackPearlProtocol.Timing.QUEUE_DELAY_GLOBAL_GAIN_MS
                        else -> BlackPearlProtocol.Timing.QUEUE_DELAY_DEFAULT_MS
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
