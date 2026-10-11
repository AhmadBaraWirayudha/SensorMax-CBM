package com.research.sensormax

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat

/**
 * UI-independent coordinator for SensorCaptureService binding and deployment.
 * MainActivity remains responsible for validation and presentation; this class
 * owns the service lifecycle mechanics so Activity recreation cannot duplicate
 * that logic.
 */
class MeasurementDeploymentController(
    context: Context,
    private val observer: SensorCaptureService.Observer,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onServiceReady(engine: SensorEngine)
        fun onServiceLost()
        fun onDeploymentStateChanged(running: Boolean)
        fun onControllerLog(message: String)
    }

    private val appContext = context.applicationContext
    private val sourceFactory: AcquisitionSourceFactory =
        DefaultAcquisitionSourceFactory(
            appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        )
    private var service: SensorCaptureService? = null
    private var bound = false

    val isServiceReady: Boolean
        get() = service != null

    val isDeployed: Boolean
        get() = service?.engine?.isDeploymentRunning == true

    /** True when the current long-running deployment also streams to a remote endpoint. */
    val isStreamingDeployment: Boolean
        get() = service?.isStreamingDeployment == true

    /** Refresh active foreground-notification text after an app-language change. */
    fun refreshNotificationLocale(
        title: String,
        channelName: String,
        channelDescription: String,
        ready: String,
        measurementActiveStream: String,
        measurementActiveOffline: String,
        streamingActive: String,
        offlineActive: String
    ) {
        service?.refreshNotificationLocale(
            title = title,
            channelName = channelName,
            channelDescription = channelDescription,
            ready = ready,
            measurementActiveStream = measurementActiveStream,
            measurementActiveOffline = measurementActiveOffline,
            streamingActive = streamingActive,
            offlineActive = offlineActive
        )
    }

    val engine: SensorEngine?
        get() = service?.engine

    fun createBleGattSource(profile: BleGattProfile): AcquisitionSource =
        sourceFactory.createBleGattSource(
            context = appContext,
            profile = profile
        )

    fun createAudioSource(profile: AudioAcquisitionProfile = AudioAcquisitionProfile()): AcquisitionSource =
        sourceFactory.createAudioSource(
            context = appContext,
            profile = profile
        )

    fun createLocationSource(profile: LocationAcquisitionProfile = LocationAcquisitionProfile()): AcquisitionSource =
        sourceFactory.createLocationSource(
            context = appContext,
            profile = profile
        )

    fun createCameraSource(profile: CameraAcquisitionProfile = CameraAcquisitionProfile()): AcquisitionSource =
        sourceFactory.createCameraSource(
            context = appContext,
            profile = profile
        )

    fun createDepthSource(profile: DepthAcquisitionProfile = DepthAcquisitionProfile()): AcquisitionSource =
        sourceFactory.createDepthSource(
            context = appContext,
            profile = profile
        )

    fun createNetworkSource(profile: NetworkAcquisitionProfile = NetworkAcquisitionProfile()): AcquisitionSource =
        sourceFactory.createNetworkSource(profile)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: android.content.ComponentName?,
            binder: android.os.IBinder?
        ) {
            val localBinder = binder as? SensorCaptureService.LocalBinder
                ?: return

            service = localBinder.service
            localBinder.service.setObserver(observer)
            callbacks.onServiceReady(localBinder.service.engine)
            callbacks.onDeploymentStateChanged(localBinder.service.engine.isDeploymentRunning)
        }

        override fun onServiceDisconnected(
            name: android.content.ComponentName?
        ) {
            service = null
            callbacks.onServiceLost()
        }
    }

    fun onStart() {
        if (bound) return

        bound = appContext.bindService(
            Intent(appContext, SensorCaptureService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (!bound) {
            callbacks.onControllerLog("[SERVICE] Failed to bind measurement service.")
        }
    }

    fun onStop() {
        service?.setObserver(null)

        if (bound) {
            try {
                appContext.unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Already unbound by the framework.
            }
        }

        bound = false
        service = null
    }

    fun startDeployment(
        sensors: Set<Sensor>,
        intervalMs: Long,
        enableNetwork: Boolean,
        enableDisk: Boolean,
        endpoint: String?
    ): Boolean {
        val current = service ?: return false

        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, SensorCaptureService::class.java).apply {
                action = SensorCaptureService.ACTION_START_FOREGROUND
            }
        )

        val started = current.startDeployment(
            sensors = sensors,
            intervalMs = intervalMs,
            enableNetwork = enableNetwork,
            enableDisk = enableDisk,
            endpoint = endpoint
        )

        if (started) {
            callbacks.onDeploymentStateChanged(true)
        }

        return started
    }

    /**
     * Source-neutral deployment entry point. Provider construction remains outside
     * SensorEngine so BLE/audio/camera/location/depth/network providers can use the
     * same service lifecycle without teaching the controller their APIs.
     */
    fun startDeployment(
        request: AcquisitionDeploymentRequest,
        endpoint: String?
    ): Boolean {
        val current = service ?: return false

        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, SensorCaptureService::class.java).apply {
                action = SensorCaptureService.ACTION_START_FOREGROUND
            }
        )

        val started = current.startDeployment(
            request = request,
            endpoint = endpoint
        )

        if (started) {
            callbacks.onDeploymentStateChanged(true)
        }

        return started
    }

    fun stopDeployment() {
        service?.stopDeployment()
        callbacks.onDeploymentStateChanged(false)
    }
}
