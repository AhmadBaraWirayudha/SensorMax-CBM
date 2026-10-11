package com.research.sensormax

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Supported acquisition families in the SensorMax multi-interface platform. */
enum class AcquisitionSourceKind {
    ANDROID_SENSOR,
    BLE_GATT,
    AUDIO,
    CAMERA,
    LOCATION,
    DEPTH,
    NETWORK
}

enum class AcquisitionSourceState {
    IDLE,
    STARTING,
    SCANNING,
    CONNECTING,
    DISCOVERING_SERVICES,
    NEGOTIATING_MTU,
    READY,
    DISCONNECTED,
    RECONNECTING,
    ERROR,
    STOPPED
}

data class AcquisitionSourceDescriptor(
    val id: String,
    val kind: AcquisitionSourceKind,
    val displayName: String,
    val vendor: String = "",
    val channels: List<String> = emptyList()
)

data class AcquisitionSourceConfig(
    val requestedIntervalMs: Long
) {
    init {
        require(requestedIntervalMs > 0L) { "requestedIntervalMs must be > 0" }
    }
}

/**
 * Source-neutral deployment request. Provider-specific construction happens outside
 * the SensorEngine core; the engine only consumes this acquisition contract.
 */
data class AcquisitionDeploymentRequest(
    val source: AcquisitionSource,
    val intervalMs: Long,
    val enableNetwork: Boolean,
    val enableDisk: Boolean
) {
    init {
        require(intervalMs > 0L) { "intervalMs must be > 0" }
    }
}

data class AcquisitionSample(
    val sourceId: String,
    val sourceKind: AcquisitionSourceKind,
    val channelId: String,
    val name: String,
    val vendor: String,
    val timestampNs: Long,
    val wallClockTimestampMs: Long,
    val values: FloatArray,
    val androidSensorType: Int? = null,
    val unit: String = ""
) {
    fun copySnapshot(): AcquisitionSample = copy(values = values.copyOf())
}

interface AcquisitionSourceListener {
    fun onSample(sample: AcquisitionSample)
    fun onError(sourceId: String, message: String)
    fun onStateChanged(sourceId: String, state: AcquisitionSourceState, detail: String = "") = Unit
    fun onLog(sourceId: String, message: String) = Unit
}

/**
 * Source-neutral lifecycle boundary. Providers own their hardware/API resources;
 * SensorEngine owns session state, analysis, persistence, and downstream routing.
 */
interface AcquisitionSource {
    val descriptor: AcquisitionSourceDescriptor
    val isRunning: Boolean

    fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean

    fun stop()
}

/** Android SensorManager provider behind the source-neutral acquisition contract. */
class AndroidSensorAcquisitionSource(
    private val sensorManager: SensorManager,
    sensors: Set<Sensor>
) : AcquisitionSource {

    private val sensors = sensors.toSet()

    override val descriptor = AcquisitionSourceDescriptor(
        id = "android-sensors",
        kind = AcquisitionSourceKind.ANDROID_SENSOR,
        displayName = "Android Sensor Framework",
        channels = this.sensors.map { sensor ->
            "${sensor.type}|${sensor.vendor}|${sensor.name}"
        }
    )

    override var isRunning: Boolean = false
        private set

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var sensorListener: SensorEventListener? = null
    private var sourceListener: AcquisitionSourceListener? = null

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        // Android delivers at the fastest supported rate; SensorEngine applies
        // the requested interval using the normalized sample timestamp.
        if (isRunning || sensors.isEmpty()) return false

        listener.onStateChanged(descriptor.id, AcquisitionSourceState.STARTING)

        val thread = HandlerThread("SensorMax-AndroidSensorSource")
        thread.start()
        val handler = thread.looper?.let(::Handler)
        if (handler == null) {
            thread.quitSafely()
            listener.onStateChanged(descriptor.id, AcquisitionSourceState.ERROR, "HandlerThread initialization failed")
            return false
        }

        val eventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isEmpty()) return

                val sensor = event.sensor
                listener.onSample(
                    AcquisitionSample(
                        sourceId = descriptor.id,
                        sourceKind = descriptor.kind,
                        channelId = channelId(sensor),
                        name = sensor.name,
                        vendor = sensor.vendor,
                        timestampNs = event.timestamp,
                        wallClockTimestampMs = System.currentTimeMillis(),
                        values = event.values.copyOf(),
                        androidSensorType = sensor.type,
                        unit = SensorTelemetryFormatter.unit(sensor.type)
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var registeredCount = 0
        for (sensor in sensors) {
            val registered = runCatching {
                sensorManager.registerListener(
                    eventListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    handler
                )
            }.getOrElse {
                listener.onError(
                    descriptor.id,
                    "Failed to register ${sensor.name}: ${it.message ?: it::class.java.simpleName}"
                )
                false
            }

            if (registered) {
                registeredCount++
            } else {
                listener.onError(
                    descriptor.id,
                    "SensorManager rejected registration: ${sensor.name} | ${sensor.vendor}"
                )
            }
        }

        if (registeredCount == 0) {
            runCatching { sensorManager.unregisterListener(eventListener) }
            thread.quitSafely()
            listener.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.ERROR,
                "No Android sensor registration succeeded"
            )
            return false
        }

        this.sensorListener = eventListener
        this.sourceListener = listener
        workerThread = thread
        workerHandler = handler
        isRunning = true
        listener.onStateChanged(descriptor.id, AcquisitionSourceState.READY)
        return true
    }

    override fun stop() {
        val currentListener = sensorListener
        if (currentListener != null) {
            runCatching { sensorManager.unregisterListener(currentListener) }
        }
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        sourceListener?.onStateChanged(descriptor.id, AcquisitionSourceState.STOPPED)
        sensorListener = null
        sourceListener = null
        isRunning = false
    }

    private fun channelId(sensor: Sensor): String =
        "${sensor.type}|${sensor.vendor}|${sensor.name}"
}
