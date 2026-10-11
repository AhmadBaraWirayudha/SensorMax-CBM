package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SensorMaxExperimentFileTest {

    private val fixture = """
        <phyphox version="1.20">
          <title>Accelerometer Profile</title>
          <category>Raw Sensors</category>
          <description>Field sensor profile.</description>

          <data-containers>
            <container size="0" init="1,2">accX</container>
            <container size="0">accY</container>
            <container size="0">accZ</container>
            <container size="0">acc_time</container>
          </data-containers>

          <input>
            <sensor type="accelerometer" rate="50">
              <output component="x">accX</output>
              <output component="y">accY</output>
              <output component="z">accZ</output>
              <output component="t">acc_time</output>
            </sensor>
          </input>

          <analysis
              sleep="0.1"
              requireFill="accX"
              requireFillThreshold="16"
              requireFillDynamic="window"
              timedRun="true">

            <abs>
              <input>accX</input>
              <output>accX_abs</output>
            </abs>

            <fft>
              <input>accX</input>
              <output>spectrum</output>
            </fft>

          </analysis>

          <views>
            <view label="Graph">
              <graph label="Accelerometer x">
                <input axis="x">acc_time</input>
                <input axis="y">accX</input>
              </graph>
            </view>
          </views>

          <network />

          <export>
            <set name="Raw Data">
              <data name="Time">acc_time</data>
              <data name="X">accX</data>
            </set>
          </export>
        </phyphox>
    """.trimIndent()

    @Test
    fun parsesStructureAndNativeMappings() {
        val result = SensorMaxExperimentFile.parse(
            ByteArrayInputStream(fixture.toByteArray()),
            "fixture.phyphox"
        )

        assertFalse(result.supported)

        assertEquals(
            "Accelerometer Profile",
            result.title
        )

        assertEquals(
            4,
            result.containers.size
        )

        assertEquals(
            2,
            result.containers.first().initialValueCount
        )

        assertEquals(
            1,
            result.sensors.size
        )

        assertEquals(
            50.0,
            result.requestedCaptureRateHz
                ?: error("missing requested capture rate"),
            0.0
        )

        assertEquals(
            android.hardware.Sensor.TYPE_ACCELEROMETER,
            result.requiredAndroidSensorTypes.single()
        )

        assertEquals(
            2,
            result.analysisModules.size
        )

        assertEquals(
            1,
            result.views.size
        )

        assertEquals(
            1,
            result.exportSets.size
        )

        assertEquals(
            0.1,
            result.analysisSleepSeconds
                ?: error("missing analysis sleep"),
            0.0
        )

        assertEquals(
            "accX",
            result.requireFillBuffer
        )

        assertEquals(
            16,
            result.requireFillThreshold
        )

        assertEquals(
            "window",
            result.requireFillDynamicBuffer
        )

        assertTrue(
            result.timedRun
        )

        assertEquals(
            1,
            result.networkBlocks
        )

        assertTrue(
            result.unsupportedFeatures.contains("analysis:fft")
        )

        assertTrue(
            result.unsupportedFeatures.contains("network")
        )

        assertFalse(
            result.sha256.isBlank()
        )
    }

    @Test
    fun versionUsesMajorMinorSemantics() {
        val v119 =
            "<phyphox version=\"1.19\"><title>A</title></phyphox>"

        val v120 =
            "<phyphox version=\"1.20\"><title>B</title></phyphox>"

        val v121 =
            "<phyphox version=\"1.21\"><title>C</title></phyphox>"

        assertTrue(
            SensorMaxExperimentFile.parse(
                ByteArrayInputStream(v119.toByteArray())
            ).supported
        )

        assertTrue(
            SensorMaxExperimentFile.parse(
                ByteArrayInputStream(v120.toByteArray())
            ).supported
        )

        assertFalse(
            SensorMaxExperimentFile.parse(
                ByteArrayInputStream(v121.toByteArray())
            ).supported
        )
    }

    @Test
    fun rejectsWrongRoot() {
        val xml =
            "<notPhyphox><title>No</title></notPhyphox>"

        try {
            SensorMaxExperimentFile.parse(
                ByteArrayInputStream(xml.toByteArray())
            )
        } catch (error: IllegalArgumentException) {
            return
        }

        error(
            "Expected invalid root to be rejected"
        )
    }

    @Test
    fun parsesSmallInputWithoutRegression() {
        val xml =
            "<phyphox version=\"1.20\"><title>${"x".repeat(32)}</title></phyphox>"

        val result =
            SensorMaxExperimentFile.parse(
                ByteArrayInputStream(xml.toByteArray())
            )

        assertEquals(
            "1.20",
            result.formatVersion
        )
    }
}