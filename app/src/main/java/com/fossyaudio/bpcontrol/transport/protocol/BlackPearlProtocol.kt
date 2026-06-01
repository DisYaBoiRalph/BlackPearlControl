package com.fossyaudio.bpcontrol.transport.protocol

object BlackPearlProtocol {
    /** Protocol path discriminator. TRN Black Pearl (VID=0x3302, PID=0x43E8, SchemeNo=16) uses CB. */
    enum class FirmwareProfile { CB, LEGACY }

    object Device {
        const val VID = 0x3302
        const val PID = 0x43E8
        const val REPORT_ID: Byte = 0x4B
    }

    object Frame {
        const val REPORT_SIZE = 64
        const val QUEUED_PAYLOAD_MAX_SIZE = 63
        const val PEQ_PAYLOAD_SIZE = 60
        const val END: Byte = 0x00
        const val FILL: Byte = 0xFF.toByte()
        const val WRITE: Byte = 0x01
        const val READ: Byte = 0x80.toByte()
        const val BASE_DATA_LENGTH: Byte = 0x01
    }

    object Command {
        const val FLASH_EQ: Byte = 0x01
        const val MIC_GAIN: Byte = 0x02
        const val GLOBAL_GAIN: Byte = 0x03
        const val PEQ_VALUES: Byte = 0x09
        const val LATCH_SETTINGS: Byte = 0x0A
        const val READ_FW_VERSION: Byte = 0x0C
        const val FILTER: Byte = 0x11
        const val BALANCE: Byte = 0x16
        const val GAIN_MODE: Byte = 0x19
        const val AMP_TOPO: Byte = 0x1D
    }

    object FilterType {
        // CB profile codes — correct for Black Pearl (SchemeNo=16)
        const val PK: Byte = 0x02
        const val LS: Byte = 0x01
        const val HS: Byte = 0x03
        // CB-only codes; reserved for internal protocol use, not surfaced in UI
        internal const val LP: Byte = 0x04
        internal const val HP: Byte = 0x05

        // Legacy/KT profile codes — kept for compatibility fallback
        private const val LEGACY_PK: Byte = 0x00
        private const val LEGACY_LS: Byte = 0x03
        private const val LEGACY_HS: Byte = 0x04

        fun codeOf(type: String, profile: FirmwareProfile = FirmwareProfile.CB): Byte =
            when (profile) {
                FirmwareProfile.CB -> when (type) {
                    "LS" -> LS
                    "HS" -> HS
                    else -> PK
                }
                FirmwareProfile.LEGACY -> when (type) {
                    "LS" -> LEGACY_LS
                    "HS" -> LEGACY_HS
                    else -> LEGACY_PK
                }
            }

        fun nameOf(code: Int, profile: FirmwareProfile = FirmwareProfile.CB): String =
            when (profile) {
                FirmwareProfile.CB -> when (code) {
                    PK.toInt() -> "PK"
                    LS.toInt() -> "LS"
                    HS.toInt() -> "HS"
                    else -> "PK"
                }
                FirmwareProfile.LEGACY -> when (code) {
                    LEGACY_PK.toInt() -> "PK"
                    LEGACY_LS.toInt() -> "LS"
                    LEGACY_HS.toInt() -> "HS"
                    else -> "PK"
                }
            }
    }

    object Param {
        const val GLOBAL_GAIN_LENGTH: Byte = 0x03
        const val MIC_GAIN_LENGTH: Byte = 0x02
        const val BALANCE_LENGTH: Byte = 0x04
        const val PEQ_LENGTH: Byte = 0x18
        const val MIC_GAIN_PAGE: Byte = 0x02
        const val MIC_GAIN_SIGNED_FLAG: Byte = 0x80.toByte()
        // CB channel selector: 0 = left, 1 = right (Walkplay CB WPCBDataSender cmd 0x16)
        const val BALANCE_LEFT: Byte = 0x00
        const val BALANCE_RIGHT: Byte = 0x01
        const val PEQ_INDEX_BASE: Byte = 0x00
    }

    object ParserOffset {
        const val DIRECTION = 1
        const val COMMAND = 2
        const val VALUE_LSB = 4
        const val VALUE_MSB = 5
        const val VALUE_GUARD = 6

        const val PEQ_FREQ_LSB = 28
        const val PEQ_FREQ_MSB = 29
        const val PEQ_Q_LSB = 30
        const val PEQ_Q_MSB = 31
        const val PEQ_GAIN_LSB = 32
        const val PEQ_GAIN_MSB = 33
        const val PEQ_TYPE = 34
        const val PEQ_ACTIVE_SLOT = 36
    }

    object Transfer {
        const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        const val REQUEST_SET_REPORT = 0x09
        const val VALUE_OUTPUT_REPORT_BASE = 0x0200
        const val REQUEST_TYPE_STANDARD_ENDPOINT_OUT = 0x02
        const val REQUEST_CLEAR_FEATURE = 0x01
        const val FEATURE_ENDPOINT_HALT = 0x00
    }

    object Timing {
        const val QUEUE_CAPACITY = 100
        const val QUEUE_RETRY_COUNT = 3
        const val QUEUE_RETRY_DELAY_MS = 50L
        const val QUEUE_TRANSFER_TIMEOUT_MS = 1000
        const val QUEUE_CLEAR_HALT_TIMEOUT_MS = 500
        const val QUEUE_DELAY_FLASH_EQ_MS = 600L
        const val QUEUE_DELAY_PEQ_MS = 180L
        const val QUEUE_DELAY_GLOBAL_GAIN_MS = 60L
        const val QUEUE_DELAY_DEFAULT_MS = 50L

        const val READ_FLUSH_TIMEOUT_MS = 2
        const val READ_TRANSFER_TIMEOUT_MS = 200
        const val READ_WINDOW_MS = 400L
        const val READ_POLL_TIMEOUT_MS = 50
        const val READ_POLL_INTERVAL_MS = 5L
        const val SETTINGS_READ_STEP_DELAY_MS = 60L
        const val VOLUME_POLL_BUSY_DELAY_MS = 500L
        const val VOLUME_POLL_INTERVAL_MS = 1000L

        const val CONNECTION_WATCHDOG_INTERVAL_MS = 1500L
        const val USB_SETTLE_DELAY_MS = 300L
        const val CLAIM_RETRY_DELAY_MS = 150L
        const val POST_CONNECT_SYNC_DELAY_MS = 500L
        const val MASS_PUSH_POLL_DELAY_MS = 50L
        const val MASS_PUSH_SETTLE_DELAY_MS = 100L
        const val BALANCE_PAIR_DELAY_MS = 50L
        const val USB_CLOSE_DELAY_MS = 150L
    }
}
