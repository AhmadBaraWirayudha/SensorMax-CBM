package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object MagnetometerExperiment : SensorMaxExperimentSpec {
    override val sourceName = "magnetometer.legacy.xml"
    override val sourceSha256 = "b0acac01030a01c7b19e58194aeaace36f627bbf7ae7123cc5ba18ba8f6b0ca8"
    override val sourceVersion = "1.14"
    override val title = "Magnetometer"
    override val category = "Raw Sensors"
    override val description = "Get raw data from the magnetometer.\n\n        The magnetometer is quite sensitive as it is designed to measure the earth's magnetic field to act as a compass. However, it is always exposed to internal fields from the electronics in your phone, which lead to incorrect results. Therefore SensorMax tries to get the calibrated magnetic field from the system. In some cases, you might not get any data, if it is not yet calibrated (for example after the phone has just started). In this case you should rotate your phone a few times while the experiment is running. This helps the system to calibrate the sensor. Also, on many devices (not all) you can switch to uncalibrated magnetometer data via the menu in the top right.\n\n        Do not expose your phone to strong magnetic fields!"
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "magnetic_field", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "x" to "magX",
            "y" to "magY",
            "z" to "magZ",
            "t" to "mag_time",
            "abs" to "mag",
            "accuracy" to "magAccuracy",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "mag_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "magX", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "magY", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "magZ", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "mag", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "magAccuracy", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
    )
    override val analysis = listOf<SensorMaxAnalysisSpec>(
    )
    override val views = listOf<SensorMaxViewSpec>(
        SensorMaxViewSpec(label = "Graph", elementTypes = listOf("value", "separator", "graph", "graph", "graph")),
        SensorMaxViewSpec(label = "Absolute", elementTypes = listOf("value", "separator", "graph", "value")),
        SensorMaxViewSpec(label = "Multi", elementTypes = listOf("value", "graph", "value", "value", "value", "value")),
        SensorMaxViewSpec(label = "Simple", elementTypes = listOf("value", "separator", "value", "value", "value", "value")),
        SensorMaxViewSpec(label = "Help", elementTypes = listOf("info")),
    )
    override val exports = listOf<SensorMaxExportSpec>(
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "mag_time", "Magnetic Field x (µT)" to "magX", "Magnetic Field y (µT)" to "magY", "Magnetic Field z (µT)" to "magZ", "Absolute field (µT)" to "mag")),
    )
}
