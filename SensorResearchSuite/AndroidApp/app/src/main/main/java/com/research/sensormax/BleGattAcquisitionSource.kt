package com.research.sensormax

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SensorMax-owned Android BLE/GATT acquisition provider.
 *
 * The provider owns Bluetooth scanning, GATT callbacks, notification/read
 * scheduling, reconnect attempts, and permission/capability errors.
 * SensorEngine only sees AcquisitionSample through the source-neutral contract.
 */
class BleGattAcquisitionSource(
    private val context: Context,
    private val profile: BleGattProfile,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS
) : AcquisitionSource {

    companion object {
        private const val DEFAULT_SCAN_TIMEOUT_MS = 10_000L
        private const val DEFAULT_RECONNECT_DELAY_MS = 1_000L
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override val descriptor = AcquisitionSourceDescriptor(
        id = "ble-gatt:${profile.id}",
        kind = AcquisitionSourceKind.BLE_GATT,
        displayName = profile.displayName,
        vendor = profile.deviceName ?: "BLE/GATT",
        channels = profile.channels.map { it.id }
    )

    override var isRunning: Boolean = false
        private set

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
    }
    private val mainAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var workerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var listener: AcquisitionSourceListener? = null
    private var startConfig: AcquisitionSourceConfig? = null
    private var reconnectAttempt = 0
    private val stopped = AtomicBoolean(true)

    private val pendingNotificationCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()
    private val readCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()
    private var readLoopScheduled = false

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false

        val missing = BleGattPermissions.missingPermissions(context)
        if (missing.isNotEmpty()) {
            listener.onError(
                descriptor.id,
                "Missing BLE permission(s): ${missing.joinToString()}"
            )
            return false
        }

        val adapter = runCatching { mainAdapter }.getOrNull()
        if (adapter == null) {
            listener.onError(descriptor.id, "Bluetooth adapter is unavailable")
            return false
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            listener.onError(descriptor.id, "Bluetooth is disabled")
            return false
        }

        if (profile.deviceAddress == null &&
            profile.deviceName == null &&
            profile.serviceUuid == null
        ) {
            listener.onError(
                descriptor.id,
                "BLE profile needs deviceAddress, deviceName, or serviceUuid for discovery"
            )
            return false
        }

        val thread = HandlerThread("SensorMax-BleGattSource").also { it.start() }
        val localHandler = Handler(thread.looper)

        this.listener = listener
        listener.onStateChanged(descriptor.id, AcquisitionSourceState.STARTING)
        this.startConfig = config
        this.workerThread = thread
        this.handler = localHandler
        this.reconnectAttempt = 0
        this.stopped.set(false)
        this.isRunning = true

        val directDevice = profile.deviceAddress?.let {
            runCatching { adapter.getRemoteDevice(it) }.getOrElse {
                listener.onError(descriptor.id, "Invalid BLE device address: $it")
                null
            }
        }

        if (directDevice != null) {
            connect(directDevice)
        } else {
            startScan()
        }

        return true
    }

    override fun stop() {
        if (!isRunning && workerThread == null) return

        stopped.set(true)
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()

        pendingNotificationCharacteristics.clear()
        readCharacteristics.clear()
        readLoopScheduled = false
        currentDevice = null
        startConfig = null
        listener?.onStateChanged(descriptor.id, AcquisitionSourceState.STOPPED)
        listener = null

        workerThread?.quitSafely()
        workerThread = null
        handler = null
    }

    private fun startScan() {
        val adapter = runCatching { mainAdapter }.getOrNull() ?: run {
            fail("Bluetooth adapter disappeared")
            return
        }
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull() ?: run {
            fail("BLE scanner is unavailable")
            return
        }

        stopScan()

        val filter = ScanFilter.Builder().apply {
            profile.deviceName?.let(::setDeviceName)
            profile.serviceUuid?.let { uuid ->
                runCatching { setServiceUuid(ParcelUuid(UUID.fromString(uuid))) }
                    .onFailure {
                        fail("Invalid BLE service UUID: $uuid")
                    }
            }
        }.build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                val nameMatches = profile.deviceName.isNullOrBlank() || name == profile.deviceName
                if (!nameMatches) return

                stopScan()
                connect(device)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE scan failed: errorCode=$errorCode")
            }
        }

        scanCallback = callback
        onStateChanged(AcquisitionSourceState.SCANNING)
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure {
                scanCallback = null
                fail("BLE scan start failed: ${it.message ?: it::class.java.simpleName}")
                return
            }

        handler?.postDelayed({
            if (isRunning && scanCallback === callback) {
                stopScan()
                onListenerError("BLE scan timed out after ${DEFAULT_SCAN_TIMEOUT_MS} ms")
                scheduleReconnectOrStop()
            }
        }, DEFAULT_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!isRunning) return

        stopScan()
        closeGatt()
        currentDevice = device
        onStateChanged(AcquisitionSourceState.CONNECTING)

        val connection = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        }.getOrElse {
            fail("BLE GATT connection failed: ${it.message ?: it::class.java.simpleName}")
            scheduleReconnectOrStop()
            return
        }

        gatt = connection
        onListenerLog("[BLE] Connecting to ${profile.displayName}")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isRunning) return

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttempt = 0
                onStateChanged(AcquisitionSourceState.DISCOVERING_SERVICES)
                onListenerLog("[BLE] Connected to ${profile.displayName}")
                val discovered = runCatching { gatt.discoverServices() }.getOrDefault(false)
                if (!discovered) {
                    fail("BLE service discovery request failed")
                    scheduleReconnectOrStop()
                }
                return
            }

            onStateChanged(AcquisitionSourceState.DISCONNECTED, "status=$status state=$newState")
            onListenerError("BLE disconnected: status=$status state=$newState")
            closeGatt()
            scheduleReconnectOrStop()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isRunning) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE service discovery failed: status=$status")
                scheduleReconnectOrStop()
                return
            }

            if (profile.mtu != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                onStateChanged(AcquisitionSourceState.NEGOTIATING_MTU, "requested=${profile.mtu}")
                val requested = runCatching { gatt.requestMtu(profile.mtu) }.getOrDefault(false)
                if (!requested) {
                    fail("BLE MTU request rejected: ${profile.mtu}")
                    configureDataPath(gatt)
                }
            } else {
                configureDataPath(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isRunning) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE MTU negotiation failed: requested=${profile.mtu} status=$status")
            }
            configureDataPath(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isRunning) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE notification descriptor write failed: status=$status")
            }
            configureNextNotification(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                emitCharacteristicPayload(characteristic, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emitCharacteristicPayload(characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    emitCharacteristicPayload(characteristic, value)
                } else {
                    fail("BLE characteristic read failed: status=$status")
                }
                scheduleNextRead()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    emitCharacteristicPayload(characteristic, characteristic.value ?: ByteArray(0))
                } else {
                    fail("BLE characteristic read failed: status=$status")
                }
                scheduleNextRead()
            }
        }
    }

    private fun configureDataPath(gatt: BluetoothGatt) {
        val characteristics = profile.channels
            .mapNotNull { findCharacteristic(gatt, it.characteristicUuid) }
            .distinctBy { it.uuid }

        if (characteristics.isEmpty()) {
            fail("BLE profile has no matching characteristics after service discovery")
            scheduleReconnectOrStop()
            return
        }

        when (profile.transferMode) {
            BleGattTransferMode.NOTIFICATION -> {
                pendingNotificationCharacteristics.clear()
                pendingNotificationCharacteristics.addAll(characteristics)
                configureNextNotification(gatt)
            }

            BleGattTransferMode.READ -> {
                readCharacteristics.clear()
                readCharacteristics.addAll(characteristics)
                readLoopScheduled = false
                scheduleNextRead(immediate = true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureNextNotification(gatt: BluetoothGatt) {
        if (!isRunning) return
        if (pendingNotificationCharacteristics.isEmpty()) {
            onStateChanged(AcquisitionSourceState.READY)
            onListenerLog("[BLE] Notification path ready: ${profile.displayName}")
            return
        }
        val characteristic = pendingNotificationCharacteristics.removeFirst()

        val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            fail("BLE characteristic ${characteristic.uuid} does not support notification/indication")
            configureNextNotification(gatt)
            return
        }

        val localSet = runCatching { gatt.setCharacteristicNotification(characteristic, true) }.getOrDefault(false)
        if (!localSet) {
            fail("BLE setCharacteristicNotification failed: ${characteristic.uuid}")
            configureNextNotification(gatt)
            return
        }

        val ccc = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (ccc == null) {
            fail("BLE CCC descriptor missing: ${characteristic.uuid}")
            configureNextNotification(gatt)
            return
        }

        val enableValue = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val writeStarted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(ccc, enableValue) == 0
            } else {
                @Suppress("DEPRECATION")
                run {
                    ccc.value = enableValue
                    gatt.writeDescriptor(ccc)
                }
            }
        }.getOrDefault(false)

        if (!writeStarted) {
            fail("BLE CCC descriptor write rejected: ${characteristic.uuid}")
            configureNextNotification(gatt)
        }
    }

    private fun scheduleNextRead(immediate: Boolean = false) {
        if (!isRunning || readLoopScheduled) return
        readLoopScheduled = true
        val delay = if (immediate) 0L else startConfig?.requestedIntervalMs ?: 100L
        handler?.postDelayed({
            readLoopScheduled = false
            readNext()
        }, delay)
    }

    @SuppressLint("MissingPermission")
    private fun readNext() {
        if (!isRunning) return
        val connection = gatt ?: return
        val characteristic = if (readCharacteristics.isEmpty()) {
            readCharacteristics.addAll(
                profile.channels.mapNotNull { findCharacteristic(connection, it.characteristicUuid) }.distinctBy { it.uuid }
            )
            if (readCharacteristics.isEmpty()) null else readCharacteristics.removeFirst()
        } else {
            val next = readCharacteristics.removeFirst()
            readCharacteristics.addLast(next)
            next
        } ?: run {
            fail("BLE read profile has no matching characteristic")
            return
        }

        val started = runCatching { connection.readCharacteristic(characteristic) }.getOrDefault(false)
        if (!started) {
            fail("BLE characteristic read rejected: ${characteristic.uuid}")
            scheduleNextRead()
        }
    }

    private fun emitCharacteristicPayload(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        if (!isRunning) return

        val decoded = runCatching {
            BleGattPayloadDecoder.decodeCharacteristic(
                payload = payload,
                characteristicUuid = characteristic.uuid.toString(),
                profile = profile
            )
        }.getOrElse {
            fail("BLE payload decode failed for ${characteristic.uuid}: ${it.message ?: it::class.java.simpleName}")
            return
        }

        val timestampNs = SystemClock.elapsedRealtimeNanos()
        val wallClockMs = System.currentTimeMillis()
        decoded.forEach { (channelId, values) ->
            val channel = profile.channels.first { it.id == channelId }
            listener?.onSample(
                AcquisitionSample(
                    sourceId = descriptor.id,
                    sourceKind = AcquisitionSourceKind.BLE_GATT,
                    channelId = channel.id,
                    name = channel.id,
                    vendor = profile.displayName,
                    timestampNs = timestampNs,
                    wallClockTimestampMs = wallClockMs,
                    values = values.copyOf(),
                    androidSensorType = null,
                    unit = channel.unit
                )
            )
        }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        characteristicUuid: String
    ): BluetoothGattCharacteristic? {
        val target = runCatching { UUID.fromString(characteristicUuid) }.getOrNull() ?: return null
        val serviceFilter = profile.serviceUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val services = if (serviceFilter != null) {
            gatt.services.filter { it.uuid == serviceFilter }
        } else {
            gatt.services
        }
        return services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { it.uuid == target }
    }

    private fun scheduleNextRead() {
        readLoopScheduled = false
        scheduleNextRead(immediate = false)
    }

    private fun scheduleReconnectOrStop() {
        if (!isRunning) return
        if (reconnectAttempt >= maxReconnectAttempts) {
            fail("BLE reconnect limit reached: $maxReconnectAttempts")
            stop()
            return
        }

        reconnectAttempt++
        onStateChanged(AcquisitionSourceState.RECONNECTING, "attempt=$reconnectAttempt")
        handler?.postDelayed({
            if (!isRunning) return@postDelayed
            val device = currentDevice
            if (device != null) {
                connect(device)
            } else {
                startScan()
            }
        }, DEFAULT_RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        val scanner = runCatching { mainAdapter?.bluetoothLeScanner }.getOrNull()
        if (scanner != null) {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { connection ->
            runCatching { connection.disconnect() }
            runCatching { connection.close() }
        }
        gatt = null
    }

    private fun fail(message: String) {
        onListenerError(message)
    }

    private fun onListenerError(message: String) {
        onStateChanged(AcquisitionSourceState.ERROR, message)
        listener?.onError(descriptor.id, message)
    }

    private fun onListenerLog(message: String) {
        listener?.onLog(descriptor.id, message)
    }

    private fun onStateChanged(state: AcquisitionSourceState, detail: String = "") {
        listener?.onStateChanged(descriptor.id, state, detail)
    }

}
