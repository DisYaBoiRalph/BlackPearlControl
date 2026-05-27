package com.fossyaudio.bpcontrol.transport.protocol

import com.fossyaudio.bpcontrol.shared.eq.BiquadCoefficients
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun encodePeqUpdate(index: Int, filter: FilterBand, coeffs: BiquadCoefficients, activeSlot: Byte): ByteArray {
        val effectiveGain = if (filter.enabled) filter.gain else 0f
        return ByteBuffer
            .allocate(BlackPearlProtocol.Frame.PEQ_PAYLOAD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(BlackPearlProtocol.Frame.WRITE)
                put(BlackPearlProtocol.Command.PEQ_VALUES)
                put(BlackPearlProtocol.Param.PEQ_LENGTH)
                put(BlackPearlProtocol.Frame.END)
                put(index.toByte())
                put(BlackPearlProtocol.Param.PEQ_INDEX_BASE)
                put(BlackPearlProtocol.Param.PEQ_INDEX_BASE)

                putFloat(coeffs.b0)
                putFloat(coeffs.b1)
                putFloat(coeffs.b2)
                putFloat(coeffs.a1)
                putFloat(coeffs.a2)

                putShort(filter.freq.toShort())
                putShort((filter.q * 256).toInt().toShort())
                putShort((effectiveGain * 256).toInt().toShort())
                put(BlackPearlProtocol.FilterType.codeOf(filter.type))
                put(BlackPearlProtocol.Frame.END)
                put(activeSlot)
                put(BlackPearlProtocol.Frame.END)
            }
            .array()
    }

    fun readUnsigned16LE(bytes: ByteArray, lsbOffset: Int, msbOffset: Int): Int {
        return (bytes[lsbOffset].toInt() and 0xFF) or ((bytes[msbOffset].toInt() and 0xFF) shl 8)
    }

    fun readSigned16LE(bytes: ByteArray, lsbOffset: Int, msbOffset: Int): Int {
        return readUnsigned16LE(bytes, lsbOffset, msbOffset).toShort().toInt()
    }
}
