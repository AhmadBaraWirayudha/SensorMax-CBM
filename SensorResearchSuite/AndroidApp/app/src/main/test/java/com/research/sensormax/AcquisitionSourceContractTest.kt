package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class AcquisitionSourceContractTest {
    @Test
    fun sample_snapshot_copies_values() {
        val source = AcquisitionSample(
            sourceId = "test-source",
            sourceKind = AcquisitionSourceKind.BLE_GATT,
            channelId = "temperature",
            name = "Temperature",
            vendor = "Test",
            timestampNs = 10L,
            wallClockTimestampMs = 20L,
            values = floatArrayOf(25.0f)
        )

        val snapshot = source.copySnapshot()
        assertNotSame(source.values, snapshot.values)
        assertEquals(source.values[0], snapshot.values[0], 0.0f)
    }

    @Test
    fun source_config_rejects_non_positive_interval() {
        try {
            AcquisitionSourceConfig(0L)
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    @Test
    fun sourceContractContainsAndroidProviderKind() {
        check(AcquisitionSourceKind.BLE_GATT != AcquisitionSourceKind.ANDROID_SENSOR)
        check(AcquisitionSourceKind.AUDIO != AcquisitionSourceKind.ANDROID_SENSOR)
    }
}

