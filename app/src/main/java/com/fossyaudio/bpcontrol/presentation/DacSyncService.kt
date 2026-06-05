package com.fossyaudio.bpcontrol.presentation

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodec
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DacSyncService(
    private val reportId: Byte,
    private val readMarker: Byte,
    private val usbMutex: Mutex,
    private val connectionProvider: () -> UsbDeviceConnection?,
    private val interfaceIdProvider: () -> Int,
    private val endpointProvider: () -> UsbEndpoint?
) {
    suspend fun pullValueSync(
        cmd: Byte,
        p1: Byte = BlackPearlProtocol.Frame.END,
        p2: Byte = BlackPearlProtocol.Frame.END,
        p3: Byte = BlackPearlProtocol.Frame.END
    ): ByteArray? {
        val connection = connectionProvider() ?: return null
        val interfaceId = interfaceIdProvider()
        val inEndpoint = endpointProvider() ?: return null

        return usbMutex.withLock {
            try {
                val outBuffer = BlackPearlCodec.encodeReadRequest(cmd, p1, p2, p3)

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
                    BlackPearlProtocol.Transfer.VALUE_OUTPUT_REPORT_BASE or reportId.toInt(),
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
                        inBuffer[BlackPearlProtocol.ParserOffset.DIRECTION] == readMarker &&
                        inBuffer[BlackPearlProtocol.ParserOffset.COMMAND] == cmd
                    ) {
                        return@withLock inBuffer.copyOf()
                    }
                    delay(BlackPearlProtocol.Timing.READ_POLL_INTERVAL_MS)
                }
                null
            } catch (e: Exception) {
                Log.e("USB", "Read command failed for cmd=${cmd.toUByte().toString(16)}", e)
                null
            }
        }
    }
}
