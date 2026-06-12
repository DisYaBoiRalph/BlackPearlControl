package com.fossyaudio.bpcontrol.desktop

import com.fossyaudio.bpcontrol.transport.IHidTransport
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Queue-compatible desktop transport scaffold.
 * Actual HID IO implementation will replace the TODO block in processPayload.
 */
class DesktopHidTransport : IHidTransport {

    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<ByteArray>(BlackPearlProtocol.Timing.QUEUE_CAPACITY)
    private val worker = Thread({
        while (running.get() || queue.isNotEmpty()) {
            val payload = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            processPayload(payload)
        }
    }, "desktop-hid-queue").apply {
        isDaemon = true
        start()
    }

    override fun enqueue(payload: ByteArray) {
        if (!running.get()) return
        queue.offer(payload.copyOf())
    }

    override fun hasPendingWork(): Boolean = queue.isNotEmpty()

    override fun stop() {
        running.set(false)
        worker.interrupt()
    }

    private fun processPayload(payload: ByteArray) {
        // Placeholder pacing to keep desktop semantics close to Android queue timing.
        val delayMs = when (payload.getOrNull(1)) {
            BlackPearlProtocol.Command.FLASH_EQ -> BlackPearlProtocol.Timing.QUEUE_DELAY_FLASH_EQ_MS
            BlackPearlProtocol.Command.PEQ_VALUES -> BlackPearlProtocol.Timing.QUEUE_DELAY_PEQ_MS
            BlackPearlProtocol.Command.GLOBAL_GAIN -> BlackPearlProtocol.Timing.QUEUE_DELAY_GLOBAL_GAIN_MS
            else -> BlackPearlProtocol.Timing.QUEUE_DELAY_DEFAULT_MS
        }
        Thread.sleep(delayMs)

        // TODO: Integrate HID backend for write/read operations.
    }
}
