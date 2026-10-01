package com.cellshare.phone

import java.util.concurrent.atomic.AtomicInteger

object DeviceTracker {

    data class Device(
        val id: Int,
        val address: String,
        var bytesIn: Long = 0,
        var bytesOut: Long = 0,
        var activeConns: Int = 0,
        var lastSeen: Long = System.currentTimeMillis(),
        var lastTarget: String = ""
    )

    private val devices = LinkedHashMap<Int, Device>()
    private val nextId = AtomicInteger(1)
    private val lock = Any()

    fun track(address: String): Device = synchronized(lock) {
        val ip = address.substringBefore(':')
        devices.values.firstOrNull { it.address == ip }
            ?: Device(nextId.getAndIncrement(), ip).also { devices[it.id] = it }
    }

    fun record(d: Device, inBytes: Int, outBytes: Int) = synchronized(lock) {
        d.bytesIn += inBytes
        d.bytesOut += outBytes
        d.lastSeen = System.currentTimeMillis()
    }

    fun setTarget(d: Device, target: String) = synchronized(lock) {
        d.lastTarget = target
    }

    fun bump(d: Device, delta: Int) = synchronized(lock) {
        d.activeConns += delta
        d.lastSeen = System.currentTimeMillis()
    }

    fun snapshot(): List<Device> = synchronized(lock) {
        devices.values.sortedByDescending { it.lastSeen }.toList()
    }

    fun clear() = synchronized(lock) {
        devices.clear()
    }
}
