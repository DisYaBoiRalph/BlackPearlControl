package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals

class DacSyncServiceTest {

    @Test
    fun pull_value_sync_returns_null_when_connection_is_missing() = runBlocking {
        val service = DacSyncService(
            reportId = BlackPearlProtocol.Device.REPORT_ID,
            readMarker = BlackPearlProtocol.Frame.READ,
            usbMutex = Mutex(),
            connectionProvider = { null },
            interfaceIdProvider = { 0 },
            endpointProvider = { null }
        )

        val result = service.pullValueSync(BlackPearlProtocol.Command.GLOBAL_GAIN)

        assertEquals(null, result)
    }
}
