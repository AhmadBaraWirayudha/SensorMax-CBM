package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object AccelerometerExperiment : SensorMaxExperimentSpec {
    override val sourceName = "accelerometer.legacy.xml"
    override val sourceSha256 = "d7a2d6a62a7178fcd669770e8a5918f6a0d2bc87bc39c84e1d2d93e181a3d574"
    override val sourceVersion = "1.14"
    override val title = "Acceleration with g"
    override val category = "Raw Sensors"
    override val description = "Get raw data from the accelerometer. This sensor will not subtract the gravitational force, so it will report an acceleration of 9.81 m/s² even when the phone is resting."
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "accelerometer", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "x" to "accX",
            "y" to "accY",
            "z" to "accZ",
            "t" to "acc_time",
            "abs" to "acc",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "accX", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "accY", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "accZ", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "acc", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "acc_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
    )
    override val analysis = listOf<SensorMaxAnalysisSpec>(
    )
    override val views = listOf<SensorMaxViewSpec>(
        SensorMaxViewSpec(label = "Graph", elementTypes = listOf("graph", "graph", "graph")),
        SensorMaxViewSpec(label = "Absolute", elementTypes = listOf("graph", "value")),
        SensorMaxViewSpec(label = "Multi", elementTypes = listOf("graph", "value", "value", "value", "value")),
        SensorMaxViewSpec(label = "Simple", elementTypes = listOf("value", "value", "value", "value")),
    )
    override val exports = listOf<SensorMaxExportSpec>(
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "acc_time", "Acceleration x (m/s^2)" to "accX", "Acceleration y (m/s^2)" to "accY", "Acceleration z (m/s^2)" to "accZ", "Absolute acceleration (m/s^2)" to "acc")),
    )
}
