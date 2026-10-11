package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object LinearAccelerometerExperiment : SensorMaxExperimentSpec {
    override val sourceName = "linear_accelerometer.legacy.xml"
    override val sourceSha256 = "b8e7068d067ab4793d66077b24c595d7e837de56605898bc558ee926ffff2517"
    override val sourceVersion = "1.14"
    override val title = "Acceleration (without g)"
    override val category = "Raw Sensors"
    override val description = "Get raw data from the so called linear accelerometer, which gives the actual acceleration without the gravitational acceleration. In contrast to \"acceleration with g\", this sensor reports 0 when the device is resting."
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "linear_acceleration", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "x" to "accX",
            "y" to "accY",
            "z" to "accZ",
            "t" to "acc_time",
            "abs" to "acc",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "acc_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "accX", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "accY", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "accZ", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "acc", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
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
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "acc_time", "Linear Acceleration x (m/s^2)" to "accX", "Linear Acceleration y (m/s^2)" to "accY", "Linear Acceleration z (m/s^2)" to "accZ", "Absolute acceleration (m/s^2)" to "acc")),
    )
}
