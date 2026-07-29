package com.fossyaudio.bpcontrol.shared.audio

import kotlin.math.roundToInt

/**
 * Master volume (DAC offset, `cmd 0x03`) scale.
 *
 * The register is Q8.8 dB — the vendor app writes `round(dB * 256)` as a little-endian short —
 * and its UI exposes -8.0 … +4.0 dB. The app's percentage-based slider maps linearly onto that
 * range, so `raw / 256` and [volPctToDb] agree at every point, and each 0.5 dB step lands on a
 * whole raw value (128).
 */

const val VOL_MIN_RAW = -2048 // -8.0 dB
const val VOL_MAX_RAW = 1024 // +4.0 dB

const val VOL_DB_MIN = -8.0f
const val VOL_DB_MAX = 4.0f
const val VOL_DB_PER_PCT = (VOL_DB_MAX - VOL_DB_MIN) / 100f

fun volPctToDb(percent: Float): Float = VOL_DB_MIN + percent * VOL_DB_PER_PCT

fun volDbToPct(db: Float): Float = (db - VOL_DB_MIN) / VOL_DB_PER_PCT

/** Snaps to the nearest 0.5 dB, the slider's step. */
fun snapVolDb(db: Float): Float = (db * 2f).roundToInt() / 2f
