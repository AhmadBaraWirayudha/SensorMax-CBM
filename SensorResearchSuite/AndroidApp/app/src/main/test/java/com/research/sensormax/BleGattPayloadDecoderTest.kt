package com.research.sensormax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BleGattPayloadDecoderTest {
    @Test
    fun decodesMixedLittleEndianNumericChannels() {
        val payload = byteArrayOf(
            0xFE.toByte(), 0xFF.toByte(), // -2 int16 LE
            0xE8.toByte(), 0x03.toByte(), // 1000 uint16 LE
            0x03, 0x02, 0x01            // 0x010203 uint24 LE
        )

        val signed = BleGattPayloadDecoder.decode(
            payload,
            BleGattChannelDefinition(
                id = "signed",
                characteristicUuid = "0001",
                conversion = BleGattConversion.INT16_LITTLE_ENDIAN
            )
        )
        val unsigned = BleGattPayloadDecoder.decode(
            payload,
            BleGattChannelDefinition(
                id = "unsigned",
                characteristicUuid = "0001",
                conversion = BleGattConversion.UINT16_LITTLE_ENDIAN,
                offset = 2
            )
        )
        val wide = BleGattPayloadDecoder.decode(
            payload,
            BleGattChannelDefinition(
                id = "wide",
                characteristicUuid = "0001",
                conversion = BleGattConversion.UINT24_LITTLE_ENDIAN,
                offset = 4
            )
        )

        assertEquals(-2f, signed[0], 0f)
        assertEquals(1000f, unsigned[0], 0f)
        assertEquals(0x010203.toFloat(), wide[0], 0f)
    }

    @Test
    fun decodesRepeatingFloat32Channels() {
        val payload = byteArrayOf(
            0x00, 0x00, 0x80.toByte(), 0x3F, // 1.0f
            0x00, 0x00, 0x00, 0x40  // 2.0f
        )
        val channel = BleGattChannelDefinition(
            id = "temperature",
            characteristicUuid = "cddf1007-30f7-4671-8b43-5e40ba53514a",
            conversion = BleGattConversion.FLOAT32_LITTLE_ENDIAN,
            repeating = 2
        )
        assertArrayEquals(floatArrayOf(1f, 2f), BleGattPayloadDecoder.decode(payload, channel), 0f)
    }

    @Test
    fun rejectsTruncatedPayload() {
        val channel = BleGattChannelDefinition(
            id = "x",
            characteristicUuid = "0001",
            conversion = BleGattConversion.INT16_LITTLE_ENDIAN
        )
        try {
            BleGattPayloadDecoder.decode(byteArrayOf(0x01), channel)
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected payload-length validation")
    }
}
