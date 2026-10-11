package com.research.sensormax.generated

import com.research.sensormax.SensorMaxExperimentSpec
import com.research.sensormax.SensorMaxSensorSpec
import com.research.sensormax.SensorMaxContainerSpec
import com.research.sensormax.SensorMaxAnalysisSpec
import com.research.sensormax.SensorMaxViewSpec
import com.research.sensormax.SensorMaxExportSpec

object PressureExperiment : SensorMaxExperimentSpec {
    override val sourceName = "pressure.legacy.xml"
    override val sourceSha256 = "66ef0859ca07571241de9e226d926cb17d23de49ced7162f1510ac382867af36"
    override val sourceVersion = "1.14"
    override val title = "Pressure"
    override val category = "Raw Sensors"
    override val description = "Get raw data from the barometer."
    override val sensors = listOf<SensorMaxSensorSpec>(
        SensorMaxSensorSpec(type = "pressure", rateHz = "".toDoubleOrNull(), preferUncalibrated = false, outputs = mapOf(
            "t" to "p_time",
            "x" to "pressure",
        )),
    )
    override val containers = listOf<SensorMaxContainerSpec>(
        SensorMaxContainerSpec(name = "pressure", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
        SensorMaxContainerSpec(name = "p_time", size = "0".toIntOrNull() ?: 1, clearGroup = "".ifBlank { null }, isStatic = false),
    )
    override val analysis = listOf<SensorMaxAnalysisSpec>(
    )
    override val views = listOf<SensorMaxViewSpec>(
        SensorMaxViewSpec(label = "Graph", elementTypes = listOf("graph")),
        SensorMaxViewSpec(label = "Simple", elementTypes = listOf("value")),
    )
    override val exports = listOf<SensorMaxExportSpec>(
        SensorMaxExportSpec(name = "Raw Data", data = mapOf("Time (s)" to "p_time", "Pressure (hPa)" to "pressure")),
    )
}
