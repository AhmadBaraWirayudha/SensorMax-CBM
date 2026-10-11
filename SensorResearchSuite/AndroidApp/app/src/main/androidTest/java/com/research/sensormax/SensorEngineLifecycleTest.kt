package com.research.sensormax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith

/**
 * Device/emulator regression tests for SensorEngine resource ownership.
 *
 * These tests deliberately exercise lifecycle cleanup rather than measurement
 * accuracy. Accuracy is covered by the pure-Kotlin QA suite.
 */
@RunWith(AndroidJUnit4::class)
class SensorEngineLifecycleTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var engine: SensorEngine? = null

    @After
    fun tearDown() {
        engine?.shutdown()
        engine = null
    }

    @Test
    fun deploymentShutdownReleasesHardwareWorker() {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assumeNotNull(accelerometer)
        val activeAccelerometer = accelerometer ?: return

        engine = SensorEngine(
            context = context,
            streamClient = StreamClient { },
            uiLogger = {},
            onSensorSample = { _ -> }
        )

        val activeEngine = engine ?: error("SensorEngine was not created")
        activeEngine.startDeployment(
            sensors = setOf(activeAccelerometer),
            intervalMs = 20L,
            enableNetwork = false,
            enableDisk = false
        )
        activeEngine.stopDeployment()

        waitForEngineThreadsToStop()
    }

    @Test
    fun shutdownCancelsMountingAndCalibrationWorkers() {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assumeNotNull(accelerometer)
        val activeAccelerometer = accelerometer ?: return

        engine = SensorEngine(
            context = context,
            streamClient = StreamClient { },
            uiLogger = {},
            onSensorSample = { _ -> }
        )

        val activeEngine = engine ?: error("SensorEngine was not created")
        activeEngine.runMountingCheck(
            sensors = setOf(activeAccelerometer),
            durationMs = 60_000L
        ) {}
        activeEngine.runCalibration(
            sensors = setOf(activeAccelerometer),
            operatorName = "TEST",
            durationMs = 60_000L
        ) {}

        activeEngine.shutdown()
        waitForEngineThreadsToStop()
    }

    private fun waitForEngineThreadsToStop() {
        repeat(10) {
            if (engineThreadNames().none { it == "SensorMax-HardwareWorker" || it == "MountingCheckThread" || it == "CalibrationThread" }) {
                return
            }
            Thread.sleep(50L)
        }

        val remaining = engineThreadNames()
        assertFalse(
            "SensorEngine leaked worker thread(s): $remaining",
            remaining.any {
                it == "SensorMax-HardwareWorker" ||
                    it == "MountingCheckThread" ||
                    it == "CalibrationThread"
            }
        )
    }

    private fun engineThreadNames(): Set<String> =
        Thread.getAllStackTraces()
            .keys
            .filter { it.isAlive }
            .mapTo(mutableSetOf()) { it.name }
}
