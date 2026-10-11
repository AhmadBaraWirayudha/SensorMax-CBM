package com.research.sensormax

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * USB-OTG discovery and capability inspection.
 *
 * `list()` intentionally returns platform UsbDevice objects because MainActivity
 * passes the selected object to Android's permission and raw-reader APIs. USB
 * enumeration does not imply that a device's measurement protocol is understood.
 */
data class UsbOtgDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val interfaceCount: Int,
    val hasBulkIn: Boolean,
    val hasBulkOut: Boolean
)

object UsbOtgDeviceCatalog {
    fun list(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.toList()
    }

    fun inspect(context: Context): List<UsbOtgDeviceInfo> = list(context).map { device ->
        var bulkIn = false
        var bulkOut = false
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> bulkIn = true
                        UsbConstants.USB_DIR_OUT -> bulkOut = true
                    }
                }
            }
        }
        UsbOtgDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = runCatching { device.manufacturerName }.getOrNull(),
            product = runCatching { device.productName }.getOrNull(),
            interfaceCount = device.interfaceCount,
            hasBulkIn = bulkIn,
            hasBulkOut = bulkOut
        )
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }
}
