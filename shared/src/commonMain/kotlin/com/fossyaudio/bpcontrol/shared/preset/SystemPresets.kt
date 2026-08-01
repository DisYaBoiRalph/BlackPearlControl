package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.model.PresetSource
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol

val DEFAULT_BAND_FREQS = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

const val FLAT_PRESET_NAME = "Flat"

/** The live-hardware slot every reconnect or unmatched read writes into — not a saved preset. */
const val CURRENT_PRESET_NAME = "Current"

/** Legacy name for [CURRENT_PRESET_NAME], migrated transparently on load. See [migrateLegacyName]. */
private const val LEGACY_CURRENT_PRESET_NAME = "None"

/**
 * Guarantees [FLAT_PRESET_NAME] (index 0) and [CURRENT_PRESET_NAME] (last) exist in the library.
 * "Flat" is a real preset; "Current" is the live-hardware slot every reconnect or unmatched read
 * writes into.
 */
fun ensureSystemPresets(presets: List<Preset>): List<Preset> {
    val result = presets.toMutableList()

    if (result.none { it.name == FLAT_PRESET_NAME }) {
        val flatBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            FilterBand(freq = DEFAULT_BAND_FREQS[i], gain = 0f, enabled = true)
        }
        result.add(0, Preset(FLAT_PRESET_NAME, flatBands, source = PresetSource.BUILT_IN))
    }

    if (result.none { it.name == CURRENT_PRESET_NAME }) {
        val currentBands = List(BlackPearlProtocol.Frame.BAND_COUNT) { i ->
            FilterBand(freq = DEFAULT_BAND_FREQS[i])
        }
        result.add(Preset(CURRENT_PRESET_NAME, currentBands, source = PresetSource.BUILT_IN))
    }

    return result
}

/**
 * Renames a preset loaded under the old name ("None") to [CURRENT_PRESET_NAME], so an existing
 * install migrates in place on next load instead of ending up with a stale "None" row alongside
 * a freshly-seeded "Current" one.
 */
fun migrateLegacyName(name: String): String =
    if (name == LEGACY_CURRENT_PRESET_NAME) CURRENT_PRESET_NAME else name

/**
 * Reads a persisted preset's source, defaulting older JSON (which never wrote this field) to
 * [PresetSource.BUILT_IN] for the two system names and [PresetSource.MANUAL] otherwise.
 */
fun parsePresetSource(name: String, raw: String?): PresetSource =
    raw?.let { runCatching { PresetSource.valueOf(it) }.getOrNull() }
        ?: if (name == FLAT_PRESET_NAME || name == CURRENT_PRESET_NAME) PresetSource.BUILT_IN else PresetSource.MANUAL
