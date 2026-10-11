package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object GyroscopeExperiment : SensorMaxExperimentSpec {
    override val sourceName = "gyroscope.legacy.xml"
    override val sourceSha256 = "cd5cdece6a03a748451503feccc4ec826d97ca11de199c795b746e7f151afb78"
    override val sourceVersion = "1.14"
    override val title = "Gyroscope (Rotation Rate)"
    override val category = "Raw Sensors"
    override val description = "Get raw data from the gyroscope."
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "gyroscope", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "x" to "gyrX",
            "y" to "gyrY",
            "z" to "gyrZ",
            "t" to "gyr_time",
            "abs" to "gyr",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "gyrX", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "gyrY", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "gyrZ", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "gyr", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "gyr_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
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
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "gyr_time", "Gyroscope x (rad/s)" to "gyrX", "Gyroscope y (rad/s)" to "gyrY", "Gyroscope z (rad/s)" to "gyrZ", "Absolute (rad/s)" to "gyr")),
    )
}
