package com.research.sensormax

/** Native, descriptor-only representation of translated experiment profiles. */
interface SensorMaxExperimentSpec {
    val sourceName: String
    val sourceSha256: String
    val sourceVersion: String
    val title: String
    val category: String
    val description: String
    val sensors: List<SensorMaxSensorSpec>
    val containers: List<SensorMaxContainerSpec>
    val analysis: List<SensorMaxAnalysisSpec>
    val views: List<SensorMaxViewSpec>
    val exports: List<SensorMaxExportSpec>
}

data class SensorMaxSensorSpec(
    val type: String,
    val rateHz: Double?,
    val preferUncalibrated: Boolean,
    val outputs: Map<String, String>
)

data class SensorMaxContainerSpec(
    val name: String,
    val size: Int,
    val clearGroup: String?,
    val isStatic: Boolean
)

data class SensorMaxAnalysisSpec(
    val type: String,
    val inputs: List<String>,
    val outputs: List<String>
)

data class SensorMaxViewSpec(
    val label: String,
    val elementTypes: List<String>
)

data class SensorMaxExportSpec(
    val name: String,
    val data: Map<String, String>
)
