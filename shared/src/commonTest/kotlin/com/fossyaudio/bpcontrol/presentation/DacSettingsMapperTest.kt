package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte layout of a PEQ response (based on ParserOffset):
 *  [28..29] freq LE, [30..31] q LE (Q8.8), [32..33] gain LE (Q8.8 signed),
 *  [34]     filter type code, [36] activeSlot
 */
class DacSettingsMapperTest {

    private fun buildPeqResponse(
        freqLe: Short,
        qLe: Short,
        gainLe: Short,
        typeCode: Byte,
        activeSlot: Byte
    ): ByteArray {
        val data = ByteArray(BlackPearlProtocol.Frame.REPORT_SIZE)
        data[28] = (freqLe.toInt() and 0xFF).toByte()
        data[29] = ((freqLe.toInt() shr 8) and 0xFF).toByte()
        data[30] = (qLe.toInt() and 0xFF).toByte()
        data[31] = ((qLe.toInt() shr 8) and 0xFF).toByte()
        data[32] = (gainLe.toInt() and 0xFF).toByte()
        data[33] = ((gainLe.toInt() shr 8) and 0xFF).toByte()
        data[34] = typeCode
        data[36] = activeSlot
        return data
    }

    @Test
    fun cb_profile_parses_hs_code_0x03_as_HS() {
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440,
            profile = BlackPearlProtocol.FirmwareProfile.CB)
        // HS in CB = 0x03; gain Q8.8 = 3*256 = 768 → 3.0 dB
        val data = buildPeqResponse(
            freqLe = 8000.toShort(),
            qLe = (1.2f * 256).toInt().toShort(),
            gainLe = (3f * 256).toInt().toShort(),
            typeCode = BlackPearlProtocol.FilterType.HS, // 0x03
            activeSlot = 0x01
        )
        val band = mapper.parsePeqBand(data)
        assertEquals("HS", band.type)
    }

    @Test
    fun cb_profile_parses_ls_code_0x01_as_LS() {
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440,
            profile = BlackPearlProtocol.FirmwareProfile.CB)
        val data = buildPeqResponse(
            freqLe = 100.toShort(),
            qLe = (0.7f * 256).toInt().toShort(),
            gainLe = (2f * 256).toInt().toShort(),
            typeCode = BlackPearlProtocol.FilterType.LS, // 0x01
            activeSlot = 0x01
        )
        val band = mapper.parsePeqBand(data)
        assertEquals("LS", band.type)
    }

    @Test
    fun cb_profile_parses_pk_code_0x02_as_PK() {
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440,
            profile = BlackPearlProtocol.FirmwareProfile.CB)
        val data = buildPeqResponse(
            freqLe = 1000.toShort(),
            qLe = (1.0f * 256).toInt().toShort(),
            gainLe = (4f * 256).toInt().toShort(),
            typeCode = BlackPearlProtocol.FilterType.PK, // 0x02
            activeSlot = 0x01
        )
        val band = mapper.parsePeqBand(data)
        assertEquals("PK", band.type)
    }

    @Test
    fun legacy_profile_parses_hs_code_0x04_as_HS() {
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440,
            profile = BlackPearlProtocol.FirmwareProfile.LEGACY)
        // HS in LEGACY = 0x04
        val data = buildPeqResponse(
            freqLe = 8000.toShort(),
            qLe = (1.0f * 256).toInt().toShort(),
            gainLe = (3f * 256).toInt().toShort(),
            typeCode = 0x04,
            activeSlot = 0x01
        )
        val band = mapper.parsePeqBand(data)
        assertEquals("HS", band.type)
    }

    @Test
    fun mapper_profile_is_mutable() {
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440)
        assertEquals(BlackPearlProtocol.FirmwareProfile.CB, mapper.profile)
        mapper.profile = BlackPearlProtocol.FirmwareProfile.LEGACY
        assertEquals(BlackPearlProtocol.FirmwareProfile.LEGACY, mapper.profile)
    }

    @Test
    fun cb_profile_code_0x04_falls_back_to_PK() {
        // 0x04 is LP in CB (internal) — not a named UI filter; should fall back to PK
        val mapper = DacSettingsMapper(minRawVolume = -9472, maxRawVolume = 6440,
            profile = BlackPearlProtocol.FirmwareProfile.CB)
        val data = buildPeqResponse(
            freqLe = 1000.toShort(),
            qLe = (1.0f * 256).toInt().toShort(),
            gainLe = (1f * 256).toInt().toShort(),
            typeCode = 0x04,
            activeSlot = 0x01
        )
        val band = mapper.parsePeqBand(data)
        assertEquals("PK", band.type)
    }
}
