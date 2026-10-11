package com.research.sensormax

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

enum class UsbOtgReaderState { READING, STOPPED, DISCONNECTED, ERROR }

/** One raw USB transfer. Bytes are uninterpreted until a device protocol is configured. */
data class UsbOtgRawChunk(val timestampMs: Long, val bytes: ByteArray)

/**
 * Diagnostic reader for USB host devices that expose a bulk-IN endpoint.
 * It intentionally emits bytes, not engineering measurements: an arbitrary USB
 * device has no universal packet format, scale, byte order, or calibration.
 */
class UsbOtgRawBulkReader(
    context: Context,
    private val device: UsbDevice,
    private val onChunk: (UsbOtgRawChunk) -> Unit,
    private val onState: (UsbOtgReaderState, String?) -> Unit
) : Closeable {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        check(worker?.isAlive != true) { "The previous USB read is still stopping; retry shortly." }
        check(manager.hasPermission(device)) { "USB permission has not been granted." }
        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Android could not open this USB device.")

        var selectedInterface: UsbInterface? = null
        var selectedEndpoint: UsbEndpoint? = null
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_IN
                ) {
                    selectedInterface = usbInterface
                    selectedEndpoint = endpoint
                    break
                }
            }
            if (selectedEndpoint != null) break
        }

        if (selectedInterface == null || selectedEndpoint == null) {
            connection.close()
            throw IllegalArgumentException("No bulk-IN endpoint found. This reader does not support this device's protocol/class.")
        }
        if (!connection.claimInterface(selectedInterface, true)) {
            connection.close()
            throw IllegalStateException("Could not claim the USB interface.")
        }

        val usbInterface = selectedInterface
        val endpoint = selectedEndpoint
        running = true
        val thread = Thread({
            onState(UsbOtgReaderState.READING, null)
            val buffer = ByteArray(4096)
            var readFailed = false
            var deviceDisconnected = false
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val count = connection.bulkTransfer(endpoint, buffer, buffer.size, 500)
                    if (count > 0) {
                        onChunk(UsbOtgRawChunk(System.currentTimeMillis(), buffer.copyOf(count)))
                    } else if (!manager.deviceList.containsKey(device.deviceName)) {
                        // bulkTransfer may return -1 on timeout as well as disconnect;
                        // confirm removal through UsbManager before terminating.
                        deviceDisconnected = true
                        break
                    }
                }
            } catch (failure: Exception) {
                if (running) {
                    readFailed = true
                    onState(UsbOtgReaderState.ERROR, failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                running = false
                runCatching { connection.releaseInterface(usbInterface) }
                runCatching { connection.close() }
                if (!readFailed) {
                    onState(
                        if (deviceDisconnected || !manager.deviceList.containsKey(device.deviceName)) {
                            UsbOtgReaderState.DISCONNECTED
                        } else {
                            UsbOtgReaderState.STOPPED
                        },
                        null
                    )
                }
                synchronized(this) { worker = null }
            }
        }, "SensorMax-UsbOtgRawReader")
        worker = thread
        thread.start()
    }

    fun stop() {
        running = false
        val activeWorker = worker
        activeWorker?.interrupt()
        // A bounded wait lets bulkTransfer's 500 ms timeout finish and release
        // the claimed interface before a refresh/read attempt reopens the device.
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(600) }
        }
    }

    override fun close() = stop()
}
