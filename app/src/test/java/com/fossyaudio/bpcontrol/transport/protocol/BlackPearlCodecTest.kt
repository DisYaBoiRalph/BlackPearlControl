package com.fossyaudio.bpcontrol.transport.protocol

import com.fossyaudio.bpcontrol.shared.eq.BiquadCoefficients
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class BlackPearlCodecTest {
    @Test
    fun read_request_encodes_expected_header_bytes() {
        val encoded = BlackPearlCodec.encodeReadRequest(
            cmd = BlackPearlProtocol.Command.GLOBAL_GAIN,
            p1 = BlackPearlProtocol.Frame.END,
            p2 = BlackPearlProtocol.Frame.END,
            p3 = BlackPearlProtocol.Frame.END
        )

        assertEquals(BlackPearlProtocol.Frame.REPORT_SIZE, encoded.size)
        assertEquals(BlackPearlProtocol.Device.REPORT_ID, encoded[0])
        assertEquals(BlackPearlProtocol.Frame.READ, encoded[BlackPearlProtocol.ParserOffset.DIRECTION])
        assertEquals(BlackPearlProtocol.Command.GLOBAL_GAIN, encoded[BlackPearlProtocol.ParserOffset.COMMAND])
    }

    @Test
    fun signed_le_decode_handles_negative_values() {
        val payload = byteArrayOf(0x00, 0x80.toByte())
        val value = BlackPearlCodec.readSigned16LE(payload, 0, 1)
        assertEquals(-32768, value)
    }

    @Test
    fun peq_encode_uses_effective_gain_for_disabled_band() {
        val band = FilterBand(enabled = false, type = "HS", freq = 8000, gain = 6f, q = 1.2f)
        val coeffs = BiquadCoefficients(1f, 2f, 3f, 4f, 5f)
        val payload = BlackPearlCodec.encodePeqUpdate(index = 4, filter = band, coeffs = coeffs, activeSlot = 0x7F)

        val reader = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val encodedGain = reader.getShort(31)
        val encodedType = payload[33]
        val encodedSlot = payload[35]

        assertEquals(BlackPearlProtocol.Frame.PEQ_PAYLOAD_SIZE, payload.size)
        assertEquals(0, encodedGain.toInt())
        assertEquals(BlackPearlProtocol.FilterType.HS, encodedType)
        assertEquals(0x7F.toByte(), encodedSlot)
    }
}
