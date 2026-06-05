package com.fossyaudio.bpcontrol.transport.protocol

import com.fossyaudio.bpcontrol.shared.eq.BiquadCoefficients
import com.fossyaudio.bpcontrol.shared.model.FilterBand

// Pure Kotlin LE write helpers — no java.nio dependency
private fun ByteArray.putShortLE(offset: Int, value: Short) {
    this[offset]     = (value.toInt() and 0xFF).toByte()
    this[offset + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putFloatLE(offset: Int, value: Float) {
    val bits = value.toBits()
    this[offset]     = (bits and 0xFF).toByte()
    this[offset + 1] = ((bits ushr 8)  and 0xFF).toByte()
    this[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
}

object BlackPearlCodec {
    fun encodeReadRequest(cmd: Byte, p1: Byte, p2: Byte, p3: Byte): ByteArray {
        return ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE).apply {
            this[0] = BlackPearlProtocol.Device.REPORT_ID
            this[BlackPearlProtocol.ParserOffset.DIRECTION] = BlackPearlProtocol.Frame.READ
            this[BlackPearlProtocol.ParserOffset.COMMAND] = cmd
            this[3] = p1
            this[BlackPearlProtocol.ParserOffset.VALUE_LSB] = p2
            this[BlackPearlProtocol.ParserOffset.VALUE_MSB] = p3
        }
    }

    fun encodePeqUpdate(
        index: Int,
        filter: FilterBand,
        coeffs: BiquadCoefficients,
        activeSlot: Byte,
        profile: BlackPearlProtocol.FirmwareProfile = BlackPearlProtocol.FirmwareProfile.CB
    ): ByteArray {
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        return ByteArray(BlackPearlProtocol.Frame.PEQ_PAYLOAD_SIZE).apply {
            var i = 0
            this[i++] = BlackPearlProtocol.Frame.WRITE
            this[i++] = BlackPearlProtocol.Command.PEQ_VALUES
            this[i++] = BlackPearlProtocol.Param.PEQ_LENGTH
            this[i++] = BlackPearlProtocol.Frame.END
            this[i++] = index.toByte()
            this[i++] = BlackPearlProtocol.Param.PEQ_INDEX_BASE
            this[i++] = BlackPearlProtocol.Param.PEQ_INDEX_BASE

            putFloatLE(i, coeffs.b0); i += 4
            putFloatLE(i, coeffs.b1); i += 4
            putFloatLE(i, coeffs.b2); i += 4
            putFloatLE(i, coeffs.a1); i += 4
            putFloatLE(i, coeffs.a2); i += 4

            putShortLE(i, filter.freq.toShort());                        i += 2
            putShortLE(i, (filter.q * 256).toInt().toShort());           i += 2
            putShortLE(i, (effectiveGain * 256).toInt().toShort());      i += 2
            this[i++] = BlackPearlProtocol.FilterType.codeOf(filter.type, profile)
            this[i++] = BlackPearlProtocol.Frame.END
            this[i++] = activeSlot
            this[i]   = BlackPearlProtocol.Frame.END
        }
    }

    fun readUnsigned16LE(bytes: ByteArray, lsbOffset: Int, msbOffset: Int): Int {
        return (bytes[lsbOffset].toInt() and 0xFF) or ((bytes[msbOffset].toInt() and 0xFF) shl 8)
    }

    fun readSigned16LE(bytes: ByteArray, lsbOffset: Int, msbOffset: Int): Int {
        return readUnsigned16LE(bytes, lsbOffset, msbOffset).toShort().toInt()
    }
}
