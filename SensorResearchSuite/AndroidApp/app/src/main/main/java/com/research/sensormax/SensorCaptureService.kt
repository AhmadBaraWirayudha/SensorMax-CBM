package com.research.sensormax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Process-lifetime owner for long-running SensorMax acquisition.
 *
 * MainActivity remains the UI/controller. SensorEngine remains the only owner
 * of sensor registration, DSP, files, wake-lock handling, and network transport.
 * This service only owns the lifetime boundary and forwards engine events to
 * the currently attached UI observer.
 */
class SensorCaptureService : Service() {

    interface Observer {
        fun onLog(message: String)
        fun onSensorSample(sample: AcquisitionSample)
        fun onAnalysis(update: AnalysisUpdate)
        fun onImpact(event: ImpactEvent)
        fun onSessionState(session: RecordingSession, history: List<AnalysisUpdate>)
        fun onTelemetryState(samples: List<SensorTelemetrySample>)
        fun onHealthState(snapshot: AcquisitionHealthSnapshot)
    }

    companion object {
        const val ACTION_START_FOREGROUND =
            "com.research.sensormax.action.START_FOREGROUND"

        private const val CHANNEL_ID = "sensormax_measurement"
        private const val NOTIFICATION_ID = 2401
    }

    private enum class NotificationMessage {
        READY,
        MEASUREMENT_ACTIVE_STREAM,
        MEASUREMENT_ACTIVE_OFFLINE,
        STREAMING_ACTIVE,
        OFFLINE_ACTIVE
    }

    private var notificationTitleText = ""
    private var notificationChannelNameText = ""
    private var notificationChannelDescriptionText = ""
    private var notificationReadyText = ""
    private var notificationActiveStreamText = ""
    private var notificationActiveOfflineText = ""
    private var notificationStreamingText = ""
    private var notificationOfflineText = ""
    private var currentNotificationMessage = NotificationMessage.READY

    /** Retained by the service so a locale change can refresh the visible session UI. */
    @Volatile
    var isStreamingDeployment: Boolean = false
        private set

    private val observerLock = Any()

    @Volatile
    private var observer: Observer? = null

    lateinit var engine: SensorEngine
        private set

    private var foregroundStarted = false
    private var foregroundServiceType = 0

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        val service: SensorCaptureService
            get() = this@SensorCaptureService
    }

    override fun onCreate() {
        super.onCreate()

        loadNotificationStringsFromResources()
        createNotificationChannel()

        val streamClient = StreamClient { message ->
            notifyObserver { it.onLog(message) }
        }

        engine = SensorEngine(
            context = applicationContext,
            streamClient = streamClient,
            uiLogger = { message ->
                notifyObserver { it.onLog(message) }
            },
            onSensorSample = { sample ->
                notifyObserver {
                    it.onSensorSample(sample)
                }
            },
            onAnalysis = { update ->
                notifyObserver { it.onAnalysis(update) }
            },
            onImpact = { event ->
                notifyObserver { it.onImpact(event) }
            }
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_START_FOREGROUND) {
            ensureForeground(summary = NotificationMessage.READY)
        }

        // A measurement must not silently restart after process death.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setObserver(newObserver: Observer?) {
        synchronized(observerLock) {
            observer = newObserver
        }

        if (newObserver != null && ::engine.isInitialized) {
            newObserver.onSessionState(
                engine.currentRecordingSession,
                engine.analysisHistorySnapshot()
            )
            newObserver.onTelemetryState(engine.sensorTelemetrySnapshot())
            newObserver.onHealthState(engine.acquisitionHealthSnapshot())
        }
    }

    fun startDeployment(
        sensors: Set<Sensor>,
        intervalMs: Long,
        enableNetwork: Boolean,
        enableDisk: Boolean,
        endpoint: String?
    ): Boolean {
        ensureForeground(
            sourceKind = AcquisitionSourceKind.ANDROID_SENSOR,
            summary = if (enableNetwork) NotificationMessage.MEASUREMENT_ACTIVE_STREAM
            else NotificationMessage.MEASUREMENT_ACTIVE_OFFLINE
        )

        val networkEndpoint = endpoint?.takeIf { it.isNotBlank() }

        val started = engine.startDeployment(
            sensors = sensors,
            intervalMs = intervalMs,
            enableNetwork = enableNetwork,
            enableDisk = enableDisk
        )

        if (!started) {
            notifyObserver { it.onLog("[SERVICE] Deployment start rejected; no usable acquisition session was created.") }
            if (engine.isDeploymentRunning) {
                updateNotification(
                    if (isStreamingDeployment) NotificationMessage.STREAMING_ACTIVE
                    else NotificationMessage.OFFLINE_ACTIVE
                )
            } else if (foregroundStarted) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                foregroundServiceType = 0
                currentNotificationMessage = NotificationMessage.READY
            }
            return false
        }

        if (enableNetwork && networkEndpoint != null) {
            engine.connectNetworkEndpoint(networkEndpoint)
        }

        notifyObserver {
            it.onSessionState(
                engine.currentRecordingSession,
                engine.analysisHistorySnapshot()
            )
            it.onTelemetryState(engine.sensorTelemetrySnapshot())
            it.onHealthState(engine.acquisitionHealthSnapshot())
        }

        isStreamingDeployment = enableNetwork
        updateNotification(
            if (enableNetwork) NotificationMessage.STREAMING_ACTIVE
            else NotificationMessage.OFFLINE_ACTIVE
        )

        return true
    }

    /**
     * Source-neutral deployment entry point for providers that are not Android
     * SensorManager-backed. The existing Set<Sensor> overload remains as the
     * Android UI compatibility path.
     */
    fun startDeployment(
        request: AcquisitionDeploymentRequest,
        endpoint: String?
    ): Boolean {
        ensureForeground(
            sourceKind = request.source.descriptor.kind,
            summary = if (request.enableNetwork) NotificationMessage.MEASUREMENT_ACTIVE_STREAM
            else NotificationMessage.MEASUREMENT_ACTIVE_OFFLINE
        )

        val networkEndpoint = endpoint?.takeIf { it.isNotBlank() }
        val started = engine.startDeployment(request)

        if (!started) {
            notifyObserver {
                it.onLog("[SERVICE] Source-neutral deployment start rejected; no usable acquisition session was created.")
            }
            if (engine.isDeploymentRunning) {
                updateNotification(
                    if (isStreamingDeployment) NotificationMessage.STREAMING_ACTIVE
                    else NotificationMessage.OFFLINE_ACTIVE
                )
            } else if (foregroundStarted) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                foregroundServiceType = 0
                currentNotificationMessage = NotificationMessage.READY
            }
            return false
        }

        if (request.enableNetwork && networkEndpoint != null) {
            engine.connectNetworkEndpoint(networkEndpoint)
        }

        notifyObserver {
            it.onSessionState(
                engine.currentRecordingSession,
                engine.analysisHistorySnapshot()
            )
            it.onTelemetryState(engine.sensorTelemetrySnapshot())
            it.onHealthState(engine.acquisitionHealthSnapshot())
        }

        isStreamingDeployment = request.enableNetwork
        updateNotification(
            if (request.enableNetwork) NotificationMessage.STREAMING_ACTIVE
            else NotificationMessage.OFFLINE_ACTIVE
        )

        return true
    }

    fun stopDeployment() {
        engine.stopDeployment()
        isStreamingDeployment = false
        currentNotificationMessage = NotificationMessage.READY

        notifyObserver {
            it.onSessionState(
                engine.currentRecordingSession,
                engine.analysisHistorySnapshot()
            )
            it.onTelemetryState(engine.sensorTelemetrySnapshot())
            it.onHealthState(engine.acquisitionHealthSnapshot())
        }

        if (foregroundStarted) {
            ServiceCompat.stopForeground(
                this,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            foregroundStarted = false
            foregroundServiceType = 0
        }

        stopSelf()
    }

    override fun onDestroy() {
        synchronized(observerLock) {
            observer = null
        }

        if (::engine.isInitialized) {
            engine.shutdown()
        }

        super.onDestroy()
    }

    private inline fun notifyObserver(action: (Observer) -> Unit) {
        val current = synchronized(observerLock) { observer }
        current?.let(action)
    }

    private fun ensureForeground(
        sourceKind: AcquisitionSourceKind = AcquisitionSourceKind.ANDROID_SENSOR,
        summary: NotificationMessage?
    ) {
        val requestedType = foregroundServiceTypeFor(sourceKind)
        if (summary != null) currentNotificationMessage = summary
        if (foregroundStarted && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || foregroundServiceType == requestedType)) {
            updateNotification(currentNotificationMessage)
            return
        }

        val notification = buildNotification(notificationText(currentNotificationMessage))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                requestedType
            )
            foregroundServiceType = requestedType
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!foregroundStarted) {
            foregroundStarted = true
            notifyObserver {
                it.onLog("[SERVICE] Foreground measurement service active (${sourceKind.name}).")
            }
        } else {
            notifyObserver {
                it.onLog("[SERVICE] Foreground service type switched to ${sourceKind.name}.")
            }
        }
    }

    private fun foregroundServiceTypeFor(sourceKind: AcquisitionSourceKind): Int =
        when (sourceKind) {
            AcquisitionSourceKind.AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            AcquisitionSourceKind.BLE_GATT -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            AcquisitionSourceKind.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            AcquisitionSourceKind.CAMERA,
            AcquisitionSourceKind.DEPTH -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }

    private fun notificationText(message: NotificationMessage): String =
        when (message) {
            NotificationMessage.READY -> notificationReadyText
            NotificationMessage.MEASUREMENT_ACTIVE_STREAM -> notificationActiveStreamText
            NotificationMessage.MEASUREMENT_ACTIVE_OFFLINE -> notificationActiveOfflineText
            NotificationMessage.STREAMING_ACTIVE -> notificationStreamingText
            NotificationMessage.OFFLINE_ACTIVE -> notificationOfflineText
        }

    private fun updateNotification(message: NotificationMessage) {
        currentNotificationMessage = message
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            NOTIFICATION_ID,
            buildNotification(notificationText(message))
        )
    }

    private fun buildNotification(summary: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sensormax)
            .setContentTitle(notificationTitleText)
            .setContentText(summary)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        ),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun loadNotificationStringsFromResources() {
        notificationTitleText = getString(R.string.notification_title)
        notificationChannelNameText = getString(R.string.notification_channel_name)
        notificationChannelDescriptionText = getString(R.string.notification_channel_description)
        notificationReadyText = getString(R.string.notification_ready_summary)
        notificationActiveStreamText = getString(R.string.notification_measurement_active_stream)
        notificationActiveOfflineText = getString(R.string.notification_measurement_active_offline)
        notificationStreamingText = getString(R.string.notification_streaming_active)
        notificationOfflineText = getString(R.string.notification_offline_active)
    }

    /** Called after Activity rebinding so the notification follows the selected app locale. */
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
        notificationTitleText = title
        notificationChannelNameText = channelName
        notificationChannelDescriptionText = channelDescription
        notificationReadyText = ready
        notificationActiveStreamText = measurementActiveStream
        notificationActiveOfflineText = measurementActiveOffline
        notificationStreamingText = streamingActive
        notificationOfflineText = offlineActive
        createNotificationChannel()
        if (foregroundStarted) updateNotification(currentNotificationMessage)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            notificationChannelNameText,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = notificationChannelDescriptionText
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
