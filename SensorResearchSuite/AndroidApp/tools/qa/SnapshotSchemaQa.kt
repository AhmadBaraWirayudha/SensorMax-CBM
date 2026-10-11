package com.research.sensormax

fun main() {
    check(SnapshotSchema.CURRENT_VERSION == 1)
    check(SnapshotSchema.PACKAGE_TYPE == "SensorMax snapshot metadata")
    check(SnapshotSchema.isSupported(1))
    check(!SnapshotSchema.isSupported(0))
    check(!SnapshotSchema.isSupported(999))

    val message = runCatching {
        SnapshotSchema.assertSupported(999)
    }.exceptionOrNull()?.message

    check(message == "Unsupported SensorMax snapshot schema version: 999")
    println("SNAPSHOT_SCHEMA_QA_PASS")
}
