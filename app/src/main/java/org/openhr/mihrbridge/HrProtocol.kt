package org.openhr.mihrbridge

import java.util.UUID

/** Bluetooth SIG Heart Rate Service: 16-bit HR measurement, as specified by 0x2A37. */
object HrProtocol {
    val SERVICE: UUID = uuid16(0x180D)
    val MEASUREMENT: UUID = uuid16(0x2A37)
    val BODY_SENSOR_LOCATION: UUID = uuid16(0x2A38)
    val CCCD: UUID = uuid16(0x2902)

    fun uuid16(shortId: Int): UUID =
        UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(shortId))

    /** 8-bit heart-rate measurement, no contact detection information asserted. */
    fun encodeBpm(bpm: Int): ByteArray {
        require(bpm in 1..255) { "BPM must be 1..255" }
        return byteArrayOf(0x00, bpm.toByte())
    }

    /** Returns null on invalid length or out-of-range value. */
    fun decodeBpm(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xff
        val is16bit = (flags and 0x01) != 0
        val bpm = if (is16bit) {
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8)
        } else bytes[1].toInt() and 0xff
        return bpm.takeIf { it in 1..255 }
    }
}