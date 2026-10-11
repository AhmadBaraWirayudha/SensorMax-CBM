package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSensorFrameParserTest {
    @Test
    fun parsesVersionOneFrame() {
        val frame = NetworkSensorFrameParser.parse(
            """{"version":1,"source_id":"gateway-01","channel_id":"accel_x","name":"Acceleration X","vendor":"LabGateway","values":[1.25,-2.5],"unit":"m/s^2","sequence":42,"timestamp_ms":1234567890}"""
        )

        assertEquals(1, frame.version)
        assertEquals("gateway-01", frame.sourceId)
        assertEquals("accel_x", frame.channelId)
        assertEquals(2, frame.values.size)
        assertEquals(1.25f, frame.values[0], 0.0001f)
        assertEquals(42L, frame.sequence)
    }

    @Test
    fun defaultsNameToChannelAndVendorToEmpty() {
        val frame = NetworkSensorFrameParser.parse(
            """{"version":1,"source_id":"gw","channel_id":"temp","values":[24.0]}"""
        )

        assertEquals("temp", frame.name)
        assertTrue(frame.vendor.isEmpty())
        assertTrue(frame.unit.isEmpty())
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSensorFrameParser.parse(
                """{"version":2,"source_id":"gw","channel_id":"x","values":[1]}"""
            )
        }
    }

    @Test
    fun rejectsNonFiniteValuesAndExcessChannels() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSensorFrameParser.parse(
                """{"version":1,"source_id":"gw","channel_id":"x","values":[NaN]}"""
            )
        }

        val values = (1..33).joinToString(",")
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSensorFrameParser.parse(
                "{" +
                        "\"version\":1,\"source_id\":\"gw\",\"channel_id\":\"x\",\"values\":[" +
                        values + "]}"
            )
        }
    }

    @Test
    fun rejectsOversizedFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSensorFrameParser.parse(
                """{"version":1,"source_id":"gw","channel_id":"x","values":[1],"name":"${"x".repeat(2000)}"}""",
                maxFrameBytes = 256
            )
        }
    }
}
