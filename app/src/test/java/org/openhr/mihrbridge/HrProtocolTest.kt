package org.openhr.mihrbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrProtocolTest {
    @Test fun testAdvertisedServiceUuid() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", HrProtocol.SERVICE.toString())
    }

    @Test fun testEncodeEightBitHeartRate() {
        assertArrayEquals(byteArrayOf(0x00, 0x5E), HrProtocol.encodeBpm(94))
        assertEquals(94, HrProtocol.decodeBpm(byteArrayOf(0x04, 0x5E)))
    }

    @Test fun testDecodeSixteenBitHeartRate() {
        assertEquals(200, HrProtocol.decodeBpm(byteArrayOf(0x01, 0xC8.toByte(), 0x00)))
        assertNull(HrProtocol.decodeBpm(byteArrayOf(0x01, 0x18, 0x01))) // above 255 rejected
    }

    @Test fun testRejectInvalidPayload() {
        assertNull(HrProtocol.decodeBpm(byteArrayOf()))
        assertNull(HrProtocol.decodeBpm(byteArrayOf(0x01, 0x01)))
        assertNull(HrProtocol.decodeBpm(byteArrayOf(0x00, 0x00)))
    }
}