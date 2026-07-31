package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.Preset

/**
 * The first name in "base", "base (2)", "base (3)", ... not already used by [existing]. Keeps
 * name unique without needing a stable id — Duplicate and Import both call this.
 */
fun uniqueName(base: String, existing: List<Preset>): String {
    if (existing.none { it.name == base }) return base
    var n = 2
    while (existing.any { it.name == "$base ($n)" }) n++
    return "$base ($n)"
}
