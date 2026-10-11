package com.research.sensormax

/**
 * Runnable BLE profiles whose current SensorMax provider can execute without
 * provider-specific configuration-write or protocol parsing code.
 *
 * Profiles recovered from the external reference that need configuration writes,
 * flag-dependent decoding, string parsing, or other unsupported setup remain
 * reference-only and are intentionally not exposed here as runnable choices.
 */
object BleGattProfileCatalog {
    val runtimeProfiles: List<BleGattProfile> = listOf(
        BleGattProfile(
            id = "veml6030-light",
            displayName = "VEML6030 Light (reference-backed)",
            transferMode = BleGattTransferMode.NOTIFICATION,
            channels = listOf(
                BleGattChannelDefinition(
                    id = "light",
                    characteristicUuid = "cddf1015-30f7-4671-8b43-5e40ba53514a",
                    conversion = BleGattConversion.UINT16_LITTLE_ENDIAN,
                    offset = 0,
                    length = 2,
                    unit = "raw"
                ),
                BleGattChannelDefinition(
                    id = "white_light",
                    characteristicUuid = "cddf1015-30f7-4671-8b43-5e40ba53514a",
                    conversion = BleGattConversion.UINT16_LITTLE_ENDIAN,
                    offset = 2,
                    length = 2,
                    unit = "raw"
                )
            )
        )
    )

    fun find(id: String): BleGattProfile? =
        runtimeProfiles.firstOrNull { it.id == id }
}
