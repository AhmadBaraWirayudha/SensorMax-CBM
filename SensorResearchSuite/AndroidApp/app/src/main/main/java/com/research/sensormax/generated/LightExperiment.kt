package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object LightExperiment : SensorMaxExperimentSpec {
    override val sourceName = "light.legacy.xml"
    override val sourceSha256 = "e1ffd831cb1ee3aa71fbf57869e8516fad5d3076f37632d69358cc993614516b"
    override val sourceVersion = "1.14"
    override val title = ""
    override val category = ""
    override val description = ""
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "light", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "x" to "illum",
            "t" to "illum_time",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "illum", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "illum_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
    )
    override val analysis = listOf<SensorMaxAnalysisSpec>(
    )
    override val views = listOf<SensorMaxViewSpec>(
        SensorMaxViewSpec(label = "Graph", elementTypes = listOf("graph", "separator", "info")),
        SensorMaxViewSpec(label = "Simple", elementTypes = listOf("value")),
    )
    override val exports = listOf<SensorMaxExportSpec>(
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "illum_time", "Illuminance (lx)" to "illum")),
    )
}
