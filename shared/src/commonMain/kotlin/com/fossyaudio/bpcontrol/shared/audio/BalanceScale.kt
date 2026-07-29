package com.fossyaudio.bpcontrol.shared.audio

/**
 * Channel balance (`cmd 0x16`) trim limit, in dB.
 *
 * Balance attenuates exactly one channel and leaves the other at 0 — it never boosts, and never
 * splits the trim across both. The register is a 16-bit big-endian Q8.8 dB value; this app writes
 * only its high byte, which makes one slider unit 256 raw = 1.0 dB.
 *
 * The vendor app's own slider is -12..+12 at 512 raw (2.0 dB) per step, so its full-scale tick is
 * -6144 raw = -24.0 dB. Because 256 divides 512, +/-24 at 1 dB steps reaches exactly that value
 * (byte 0xE8) while also offering the odd dB in between.
 */
const val BALANCE_DB_LIMIT = 24
