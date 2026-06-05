package com.fossyaudio.bpcontrol.transport

interface IHidTransport {
    fun enqueue(payload: ByteArray)
    fun hasPendingWork(): Boolean
    fun stop()
}
