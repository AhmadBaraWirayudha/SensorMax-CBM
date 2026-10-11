package com.research.sensormax.qa

import com.research.sensormax.BleGattChannelDefinition
import com.research.sensormax.BleGattConversion
import com.research.sensormax.BleGattProfile
import com.research.sensormax.BleGattTransferMode
import com.research.sensormax.AcquisitionSourceKind

/** Deterministic provider-configuration checks; Android Bluetooth is exercised on device. */
object BleGattProviderQa {
    fun run() {
        val profile = BleGattProfile(
            id = "qa-profile",
            displayName = "QA BLE",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            serviceUuid = "0000181a-0000-1000-8000-00805f9b34fb",
            transferMode = BleGattTransferMode.NOTIFICATION,
            mtu = 185,
            channels = listOf(
                BleGattChannelDefinition(
                    id = "temp",
                    characteristicUuid = "00002a6e-0000-1000-8000-00805f9b34fb",
                    conversion = BleGattConversion.INT16_LITTLE_ENDIAN,
                    unit = "°C"
                )
            )
        )
        check(profile.id == "qa-profile")
        check(profile.transferMode == BleGattTransferMode.NOTIFICATION)
        check(profile.channels.single().unit == "°C")
        check(AcquisitionSourceKind.BLE_GATT.name == "BLE_GATT")
    }
}
