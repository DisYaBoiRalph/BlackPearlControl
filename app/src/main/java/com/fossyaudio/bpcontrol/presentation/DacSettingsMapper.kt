package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodec
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlin.math.abs

data class ParsedPeqBand(
    val freq: Int,
    val q: Float,
    val gain: Float,
    val type: String,
    val activeSlot: Byte,
    val enabled: Boolean
)

class DacSettingsMapper(
    private val minRawVolume: Int,
    private val maxRawVolume: Int,
    var profile: BlackPearlProtocol.FirmwareProfile = BlackPearlProtocol.FirmwareProfile.CB
) {
    fun parseVolumePercentOrNull(data: ByteArray): Float? {
        val rawVol = BlackPearlCodec.readSigned16LE(
            data,
            BlackPearlProtocol.ParserOffset.VALUE_LSB,
            BlackPearlProtocol.ParserOffset.VALUE_MSB
        )
        if (rawVol == 0 && data[BlackPearlProtocol.ParserOffset.VALUE_GUARD].toInt() == 0) return null

        val exactVol = ((rawVol - minRawVolume).toFloat() / (maxRawVolume - minRawVolume).toFloat() * 100)
            .coerceIn(0f, 100f)
        return Math.round(exactVol).toFloat()
    }

    fun parseMicGainDb(data: ByteArray): Int {
        return data[BlackPearlProtocol.ParserOffset.VALUE_MSB].toInt().coerceIn(-15, 15)
    }

    fun parseBalanceMagnitude(data: ByteArray): Int {
        return data[BlackPearlProtocol.ParserOffset.VALUE_GUARD].toInt() and 0xFF
    }

    fun parsePeqBand(data: ByteArray): ParsedPeqBand {
        val rawFreq = BlackPearlCodec.readUnsigned16LE(
            data,
            BlackPearlProtocol.ParserOffset.PEQ_FREQ_LSB,
            BlackPearlProtocol.ParserOffset.PEQ_FREQ_MSB
        )
        val rawQ = BlackPearlCodec.readUnsigned16LE(
            data,
            BlackPearlProtocol.ParserOffset.PEQ_Q_LSB,
            BlackPearlProtocol.ParserOffset.PEQ_Q_MSB
        ) / 256.0f
        val rawGain = BlackPearlCodec.readSigned16LE(
            data,
            BlackPearlProtocol.ParserOffset.PEQ_GAIN_LSB,
            BlackPearlProtocol.ParserOffset.PEQ_GAIN_MSB
        )

        var gain = rawGain / 256.0f
        if (abs(gain) < 0.25f) gain = 0.0f

        return ParsedPeqBand(
            freq = rawFreq.coerceIn(20, 20000),
            q = rawQ.coerceIn(0.1f, 10.0f),
            gain = gain,
            type = BlackPearlProtocol.FilterType.nameOf(data[BlackPearlProtocol.ParserOffset.PEQ_TYPE].toInt(), profile),
            activeSlot = data[BlackPearlProtocol.ParserOffset.PEQ_ACTIVE_SLOT],
            enabled = abs(gain) > 0.01f
        )
    }
}
