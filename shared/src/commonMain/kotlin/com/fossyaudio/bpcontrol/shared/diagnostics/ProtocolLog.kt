package com.fossyaudio.bpcontrol.shared.diagnostics

/**
 * Keeps the most recent HID frames for a bug-report "Copy log" button. Nothing else in the app
 * retains protocol traffic — the existing Log.i/println calls only reach logcat, which an app
 * cannot read back on a modern Android build.
 */
class ProtocolLog(private val capacity: Int = 200) {
    private val frames = ArrayDeque<String>()

    fun record(direction: String, bytes: ByteArray) {
        if (frames.size >= capacity) frames.removeFirst()
        frames.addLast("$direction ${bytes.toHexLine()}")
    }

    fun snapshot(): String = frames.joinToString("\n")

    fun clear() {
        frames.clear()
    }
}

private fun ByteArray.toHexLine(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
