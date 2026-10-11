package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SensorMaxExperimentTranslatorTest {
    @Test
    fun translatesAndRoundTripsSensorMaxXml() {
        val source = """
            <phyphox version="1.20">
              <title>Acceleration Spectrum</title>
              <category>Tools</category>
              <description>Sensor test</description>
              <data-containers>
                <container size="1024">accX</container>
              </data-containers>
              <input>
                <sensor type="accelerometer" rate="100">
                  <output component="x">accX</output>
                </sensor>
              </input>
              <analysis sleep="0.1">
                <fft><input>accX</input><output>fft</output></fft>
              </analysis>
            </phyphox>
        """.trimIndent()

        val original = SensorMaxExperimentFile.parse(
            ByteArrayInputStream(source.toByteArray()),
            "acc_spectrum.legacy.xml"
        )
        val translated = SensorMaxExperimentTranslator.translate(original)

        assertTrue(translated.xml.startsWith("<?xml version=\"1.0\""))
        assertTrue(translated.xml.contains("<sensormax-experiment"))
        assertTrue(translated.kotlin.contains("object AccSpectrumExperiment"))
        assertTrue(!translated.kotlin.contains("phyphox", ignoreCase = true))

        val roundTrip = SensorMaxExperimentFile.parse(
            ByteArrayInputStream(translated.xml.toByteArray()),
            translated.xmlFileName
        )
        assertEquals("sensormax-experiment", roundTrip.sourceFormat)
        assertEquals(original.title, roundTrip.title)
        assertEquals(original.sensors.size, roundTrip.sensors.size)
        assertEquals(original.containers.size, roundTrip.containers.size)
    }
}
