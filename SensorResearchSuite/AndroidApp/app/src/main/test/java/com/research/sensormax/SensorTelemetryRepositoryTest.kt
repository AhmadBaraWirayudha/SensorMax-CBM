package com.research.sensormax

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTelemetryRepositoryTest {
    @Test
    fun keeps_latest_samples_per_sensor() {
        val repo = SensorTelemetryRepository(capacityPerSensor = 2)
        repeat(3) { index ->
            repo.append(
                SensorTelemetrySample(
                    Sensor.TYPE_ACCELEROMETER,
                    "Accelerometer A",
                    "Vendor A",
                    index.toLong(),
                    floatArrayOf(index.toFloat(), 0f, 0f)
                )
            )
        }
        assertEquals(1, repo.latestSnapshot().size)
        assertEquals(2, repo.historySnapshot()["1|Vendor A|Accelerometer A"]?.size)
    }

    @Test
    fun keeps_same_type_sensors_separate() {
        val repo = SensorTelemetryRepository()
        repo.append(SensorTelemetrySample(Sensor.TYPE_GYROSCOPE, "Gyro A", "A", 1L, floatArrayOf(1f)))
        repo.append(SensorTelemetrySample(Sensor.TYPE_GYROSCOPE, "Gyro B", "B", 2L, floatArrayOf(2f)))
        assertEquals(2, repo.latestSnapshot().size)
    }

    @Test
    fun keeps_different_source_channels_separate() {
        val repo = SensorTelemetryRepository()
        repo.append(
            SensorTelemetrySample(
                type = -1,
                name = "Temperature",
                vendor = "BLE Device",
                timestampMs = 1L,
                values = floatArrayOf(20f),
                sourceKind = AcquisitionSourceKind.BLE_GATT,
                sourceId = "ble-1",
                channelId = "temperature"
            )
        )
        repo.append(
            SensorTelemetrySample(
                type = -1,
                name = "Temperature",
                vendor = "BLE Device",
                timestampMs = 2L,
                values = floatArrayOf(21f),
                sourceKind = AcquisitionSourceKind.NETWORK,
                sourceId = "network-1",
                channelId = "temperature"
            )
        )
        assertEquals(2, repo.latestSnapshot().size)
    }

    @Test
    fun since_filter_excludes_old_sensor_values() {
        val repo = SensorTelemetryRepository()
        repo.append(SensorTelemetrySample(Sensor.TYPE_PRESSURE, "Barometer", "A", 1L, floatArrayOf(1000f)))
        repo.append(SensorTelemetrySample(Sensor.TYPE_PRESSURE, "Barometer", "A", 10L, floatArrayOf(1010f)))
        val latest = repo.latestSnapshotSince(5L)
        assertEquals(1, latest.size)
        assertTrue(latest.single().values[0] == 1010f)
    }
}
