package com.research.sensormax

/**
 * Explicit snapshot-schema compatibility contract.
 *
 * Keep this separate from the application version: old evidence packages
 * must remain interpretable even when SensorMax itself evolves.
 */
object SnapshotSchema {
    const val CURRENT_VERSION: Int = 1
    const val PACKAGE_TYPE: String = "SensorMax snapshot metadata"

    fun isSupported(version: Int): Boolean =
        version == CURRENT_VERSION

    fun assertSupported(version: Int) {
        require(isSupported(version)) {
            "Unsupported SensorMax snapshot schema version: $version"
        }
    }
}
