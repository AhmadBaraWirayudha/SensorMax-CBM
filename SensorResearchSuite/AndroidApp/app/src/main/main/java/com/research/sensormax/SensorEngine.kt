package com.research.sensormax

import com.google.gson.GsonBuilder
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One detected shock/impact (design-debt Section 5.5). Carries enough
 * context to judge the event on its own, not just a bare number.
 */
data class ImpactEvent(
    /** Resultant deviation from the slow-moving baseline, m/s^2. */
    val magnitude: Float,
    /** Sensor-clock ms (same monotonic base as event.timestamp) -- NOT wall-clock epoch time. */
    val timestampMs: Long,
    /** Which axis contributed the largest share of the deviation. */
    val axisContribution: Axis,
    val baselineMagnitude: Float,
    val thresholdUsed: Float,
    val cooldownMs: Long
)

/** One completed analysis window, paired with how trustworthy that window's measurement was (design-debt Section 16). */
data class AnalysisUpdate(
    val spectrum: SpectrumResult,
    val quality: QualityScore
)

data class RawVibrationSample(
    val sensorTimestampNs: Long,
    val wallClockTimestampMs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

/** Persistent condition summary for one completed recording session. */
data class SessionConditionSummary(
    val sessionId: String,
    val asset: String,
    val point: String,
    val customLabel: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Double,
    val analysisWindows: Long,
    val shockCount: Long,
    val actualSampleRateHz: Double,
    val overallRmsAccelMs2: Float?,
    val overallRmsVelocityMmS: Float?,
    val overallRmsDisplacementUm: Float?,
    val dominantAxis: String?,
    val dominantFreqHz: Float?,
    val isoZone: String,
    val severity: String,
    val qualityScore0to100: Int?,
    val sampleJitterMs: Float?,
    val maxSampleGapMs: Float?,
    val calibrationId: String?,
    val mountingReady: Boolean?,
    val mountingMethod: String,
    val axisRoleX: String,
    val axisRoleY: String,
    val axisRoleZ: String,
    val acquisitionIntegrity: String = "UNKNOWN",
    val vibrationRawEvents: Long = 0L,
    val vibrationAcceptedEvents: Long = 0L,
    val vibrationThrottledEvents: Long = 0L,
    val maxVibrationGapMs: Double = 0.0,
    val networkDroppedPackets: Long = 0L
)

class SensorEngine(
    private val context: Context,
    private val streamClient: StreamClient,
    private val uiLogger: (String) -> Unit,
    private val onSensorSample: (AcquisitionSample) -> Unit,
    private val onAnalysis: (AnalysisUpdate) -> Unit = {},
    private val onImpact: (ImpactEvent) -> Unit = {}
) {

    companion object {
        const val FFT_WINDOW_SIZE = 256

        // Shock / impact detector
        private const val IMPACT_COOLDOWN_MS = 300L
        private const val IMPACT_MIN_DELTA_MS2 = 0.75f
        private const val IMPACT_BASELINE_ALPHA = 0.02f

        private const val WAKE_LOCK_TIMEOUT_MS =
            6L * 60L * 60L * 1000L // 6h safety cap

        const val DEFAULT_WARN_MS2 = 2.0f
        const val DEFAULT_ALERT_MS2 = 4.0f

        // Repeatability history (design-debt Section 56): how many past
        // readings to keep per machineId/pointTag combination.
        private const val REPEATABILITY_HISTORY_CAP = 10
        private const val ANALYSIS_HISTORY_CAPACITY = 600
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    private var isRunning = false

    /** True while a deployment owns the acquisition engine. */
    val isDeploymentRunning: Boolean
        get() = isRunning

    val currentRecordingSession: RecordingSession
        get() = recordingSessionLifecycle.current

    /** Returns a snapshot that the UI may render without owning the engine state. */
    fun analysisHistorySnapshot(): List<AnalysisUpdate> =
        spectrumHistoryRepository.snapshot()

    fun trendHistorySnapshot(): FloatArray =
        trendHistoryRepository.snapshot()

    /** Latest immutable raw-vibration snapshot retained independently of FFT. */
    fun rawVibrationSnapshot(): List<RawVibrationSample> =
        rawVibrationBuffer.snapshot()

    /** Maps an Android SensorEvent monotonic timestamp to the session wall clock. */
    fun sessionWallClockMsFor(monotonicNs: Long): Long? =
        experimentClock.wallClockMsFor(monotonicNs)

    fun diagnosticHistorySnapshot(): List<String> =
        diagnosticRepository.snapshot()

    fun sensorTelemetrySnapshot(): List<SensorTelemetrySample> =
        sensorTelemetryRepository.latestSnapshot()

    /** Immutable operational health; safe to call from the UI/service thread. */
    @Synchronized
    fun acquisitionHealthSnapshot(): AcquisitionHealthSnapshot {
        val firstNs = firstAcceptedVibrationTimestampNs
        val lastNs = lastAcceptedVibrationTimestampNs
        val durationSec = if (firstNs != null && lastNs != null && lastNs > firstNs) {
            (lastNs - firstNs).toDouble() / 1_000_000_000.0
        } else {
            0.0
        }
        val effectiveRate = if (durationSec > 0.0 && vibrationAcceptedEventCount > 1L) {
            (vibrationAcceptedEventCount - 1L).toDouble() / durationSec
        } else {
            0.0
        }
        val network = streamClient.healthSnapshot()
        return AcquisitionHealthSnapshot(
            sessionId = sessionId,
            running = isRunning,
            elapsedMs = if (sessionStartMs > 0L) (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L) else 0L,
            activeSensorCount = if (activeSensors.isNotEmpty()) {
                activeSensors.size
            } else {
                acquisitionSource?.descriptor?.channels?.size ?: 0
            },
            requestedIntervalMs = captureIntervalMs,
            diskLoggingEnabled = isDiskLoggingEnabled,
            diskWriteHealthy = !isDiskLoggingEnabled || (
                    rawRepository.lastErrorMessage == null &&
                            analysisRepository.lastErrorMessage == null
                    ),
            vibrationRawEvents = vibrationRawEventCount,
            vibrationAcceptedEvents = vibrationAcceptedEventCount,
            vibrationThrottledEvents = (vibrationRawEventCount - vibrationAcceptedEventCount).coerceAtLeast(0L),
            effectiveVibrationRateHz = effectiveRate,
            lastVibrationGapMs = lastVibrationGapNs / 1_000_000.0,
            maxVibrationGapMs = maxVibrationGapNs / 1_000_000.0,
            networkConnected = network.connected,
            queuedRawPackets = network.queuedRawPackets,
            queuedAnalysisPackets = network.queuedAnalysisPackets,
            droppedRawPackets = network.droppedRawPackets,
            droppedAnalysisPackets = network.droppedAnalysisPackets,
            reconnectAttempt = network.reconnectAttempt,
            activeSourceCount = if (acquisitionSource != null) 1 else 0,
            sourceStates = acquisitionSourceStates.toMap()
        )
    }


    @Volatile
    private var isNetworkEnabled = false

    @Volatile
    private var isDiskLoggingEnabled = false


    // Auxiliary pre-flight sensor tasks. These are tracked explicitly so an
    // Activity teardown cannot strand a HandlerThread after a mounting or
    // calibration burst is cancelled.
    private var mountingCheckThread: HandlerThread? = null
    private var mountingCheckHandler: Handler? = null
    private var mountingCheckListener: SensorEventListener? = null

    private var calibrationThread: HandlerThread? = null
    private var calibrationHandler: Handler? = null
    private var calibrationListener: SensorEventListener? = null

    /** Invalidates stale mounting callbacks after cancellation. */
    private val mountingCheckGeneration = AtomicLong(0L)

    /** Invalidates stale calibration callbacks after cancellation. */
    private val calibrationGeneration = AtomicLong(0L)

    private var wakeLock: PowerManager.WakeLock? = null

    private var sampleCounter = 0L
    private var analysisSampleCounter = 0L

    // Vibration-source events seen vs. actually used for FFT after capture
    // throttling (design-debt Section 5.3, "dropped/late samples if
    // detectable"). Only touched from the sensor thread's callback.
    @Volatile
    private var vibrationRawEventCount = 0L

    @Volatile
    private var vibrationAcceptedEventCount = 0L

    @Volatile
    private var firstVibrationTimestampNs: Long? = null

    @Volatile
    private var firstAcceptedVibrationTimestampNs: Long? = null

    @Volatile
    private var lastVibrationTimestampNs: Long? = null

    @Volatile
    private var lastAcceptedVibrationTimestampNs: Long? = null

    @Volatile
    private var lastVibrationGapNs: Long = 0L

    @Volatile
    private var maxVibrationGapNs: Long = 0L

    private val activeSensors = mutableSetOf<Sensor>()

    /** Current source-neutral acquisition provider. */
    private var acquisitionSource: AcquisitionSource? = null
    private val acquisitionSourceStates = mutableMapOf<String, AcquisitionSourceState>()
    private val acquisitionSourceFactory: AcquisitionSourceFactory =
        DefaultAcquisitionSourceFactory(sensorManager)

    private val acquisitionSourceListener = object : AcquisitionSourceListener {
        override fun onSample(sample: AcquisitionSample) {
            handleAcquisitionSample(sample)
        }

        override fun onError(sourceId: String, message: String) {
            synchronized(this@SensorEngine) {
                acquisitionSourceStates[sourceId] = AcquisitionSourceState.ERROR
            }
            emitDiagnostic("[WARN] $sourceId: $message")
        }

        override fun onStateChanged(
            sourceId: String,
            state: AcquisitionSourceState,
            detail: String
        ) {
            synchronized(this@SensorEngine) {
                acquisitionSourceStates[sourceId] = state
            }
            val suffix = detail.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""
            emitDiagnostic("[SOURCE] $sourceId -> $state$suffix")
        }

        override fun onLog(sourceId: String, message: String) {
            emitDiagnostic(message)
        }
    }

    // Requested capture interval.
    private var captureIntervalMs = 20L

    // Track each actual Sensor independently.
    private val lastCaptureTimeNs = mutableMapOf<String, Long>()

    // Actual sensor used for vibration/FFT analysis.
    // Prefer raw accelerometer; fall back to linear acceleration.
    @Volatile
    private var vibrationSource: Sensor? = null

    private val vibrationAnalyzer =
        VibrationAnalyzer(FFT_WINDOW_SIZE)

    @Volatile
    private var lastResult: SpectrumResult? = null

    @Volatile
    private var lastQualityScore: QualityScore? = null

    // Session Record + Repeatability (design-debt Sections 27, 56).
    // In-memory only, for the lifetime of this app process -- nothing new
    // is persisted to disk beyond what the existing CSV writer covers.
    private var sessionId: String = ""
    private val recordingSessionLifecycle = RecordingSessionLifecycle()

    /** Engine-owned completed analysis frames retained independently of UI/chart state. */
    private var sessionStartMs: Long = 0L
    private var sessionWindowCount: Long = 0L

    // Cached copy of startDeployment()'s local baseDir so captureSnapshotNow()
    // can also write into it (design-debt Section 26). Only ever set when
    // disk logging is enabled -- see startDeployment().
    private var sessionBaseDir: File? = null

    private val rawRepository: RawRecordingRepository = CsvRawRecordingRepository()
    // Acquisition history stays independent of FFT.
    // of the FFT consumer. FFT advances its own window without deleting raw samples.
    private val rawVibrationBuffer = SignalBuffer<RawVibrationSample>(8192)
    private val acquisitionClockMapper = AcquisitionClockMapper()
    private val experimentClock = ExperimentClock()
    private val analysisRepository: AnalysisRepository = CsvAnalysisRepository()
    private val sensorTelemetryRepository = SensorTelemetryRepository(300)
    private val spectrumHistoryRepository = SpectrumHistoryRepository(ANALYSIS_HISTORY_CAPACITY)
    private val trendHistoryRepository = TrendHistoryRepository(600)
    private val diagnosticRepository = DiagnosticEventRepository()
    private val conditionSummaryRepository = ConditionSummaryRepository()

    // Cumulative shocks detected this deployment (design-debt Section 26,
    // "Shock count") -- distinct from impactInCurrentWindow, which only
    // covers the window currently being built.
    @Volatile
    private var sessionShockCount: Long = 0L

    // Whichever saved label (design-debt Section 57/58-adjacent tag
    // management) the operator picked for this deployment, if any.
    @Volatile
    var customLabel: String = ""

    // Keyed "machineId|pointTag" -> recent overall RMS velocity readings
    // (mm/s), most recent last, capped at REPEATABILITY_HISTORY_CAP.
    // Deliberately NOT cleared in startDeployment() -- it persists across
    // multiple start/stop cycles in this session so an operator can
    // remount and re-measure the same point for comparison.
    private val repeatabilityHistory = mutableMapOf<String, MutableList<Float>>()

    // Impact detector state.
    private var lastImpactMs = 0L

    private var impactBaselineX = 0f
    private var impactBaselineY = 0f
    private var impactBaselineZ = 0f
    private var impactBaselineInitialized = false

    // True when an impact has occurred since the current analysis window started.
    private var impactInCurrentWindow = false

    // Asset tagging + alarm configuration.
    @Volatile
    var machineId: String = "UNSPECIFIED"

    @Volatile
    var pointTag: String = "UNSPECIFIED"

    @Volatile
    var rpmHint: Float = 0f

    @Volatile
    var isoClass: IsoMachineClass = IsoMachineClass.NONE

    @Volatile
    var warnThresholdMs2: Float = DEFAULT_WARN_MS2

    @Volatile
    var alertThresholdMs2: Float = DEFAULT_ALERT_MS2

    // Optional rolling-element bearing geometry.
    @Volatile
    var bearingNumElements: Int = 0

    @Volatile
    var bearingBallDiameterMm: Float = 0f

    @Volatile
    var bearingPitchDiameterMm: Float = 0f

    @Volatile
    var bearingContactAngleDeg: Float = 0f

    // Mounting Method Library (design-debt Section 57) -- a static label
    // the operator picks, distinct from the dynamic stability check below.
    @Volatile
    private var mountingMethodBacking: String = "Unknown"

    var mountingMethod: String
        get() = mountingMethodBacking
        set(value) {
            val normalized = value.ifBlank { "Unknown" }
            if (mountingMethodBacking != normalized) {
                mountingMethodBacking = normalized
                // A physical mounting change invalidates both pre-flight
                // stability evidence and static calibration.
                lastMountingCheck = null
                lastCalibration = null
            }
        }

    // Orientation Library (design-debt Section 58) -- which physical
    // machine direction each phone axis was assigned to.
    @Volatile
    var axisRoleX: String = "Unspecified"

    @Volatile
    var axisRoleY: String = "Unspecified"

    @Volatile
    var axisRoleZ: String = "Unspecified"

    // Most recent "Check Mounting" result this session (design-debt
    // Section 17), or null if it hasn't been run yet. Deliberately not
    // reset in startDeployment() -- the check is a pre-flight step, not
    // part of a single deployment's lifecycle -- but it's also not
    // timestamped/expired, so treat a check from long ago as possibly
    // stale if the phone may have been remounted since.
    @Volatile
    var lastMountingCheck: MountingCheckResult? = null
        private set

    // Most recent calibration this session (design-debt Section 33), or
    // null if "Run Calibration" hasn't been pressed yet. Same lifetime
    // caveat as lastMountingCheck: in-memory only, not timestamped/expired.
    @Volatile
    var lastCalibration: CalibrationRecord? = null
        private set


    /**
     * Connect the deployment-owned network stream. The transport remains owned
     * by SensorEngine so foreground-service migration does not duplicate the
     * high-rate streaming path.
     */
    fun connectNetworkEndpoint(serverUrl: String) {
        streamClient.connectToInternetEndpoint(serverUrl)
    }

    @Synchronized
    fun startDeployment(
        sensors: Set<Sensor>,
        intervalMs: Long,
        enableNetwork: Boolean,
        enableDisk: Boolean
    ): Boolean {
        if (sensors.isEmpty()) return false

        activeSensors.clear()
        activeSensors.addAll(sensors)

        val source = acquisitionSourceFactory.createAndroidSensorSource(activeSensors)
        return startDeployment(
            AcquisitionDeploymentRequest(
                source = source,
                intervalMs = intervalMs,
                enableNetwork = enableNetwork,
                enableDisk = enableDisk
            )
        )
    }

    @Synchronized
    fun startDeployment(
        request: AcquisitionDeploymentRequest
    ): Boolean {
        if (isRunning) return false

        if (request.source.descriptor.kind != AcquisitionSourceKind.ANDROID_SENSOR) {
            activeSensors.clear()
        }

        lastCaptureTimeNs.clear()
        acquisitionClockMapper.reset()

        captureIntervalMs = request.intervalMs.coerceAtLeast(1L)
        isNetworkEnabled = request.enableNetwork
        isDiskLoggingEnabled = request.enableDisk

        // Prefer raw acceleration for condition monitoring when the selected
        // provider is the Android Sensor framework. Non-Android providers may
        // expose vibration channels later without depending on android.hardware.Sensor.
        vibrationSource = if (request.source.descriptor.kind == AcquisitionSourceKind.ANDROID_SENSOR) {
            resolveAccelSource(activeSensors)
        } else {
            null
        }

        // Reset analysis state.
        vibrationAnalyzer.reset()
        lastResult = null
        lastQualityScore = null

        sessionId = "SESSION-" +
                SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) +
                "-" + UUID.randomUUID().toString().take(8).uppercase(Locale.US)
        sessionStartMs = System.currentTimeMillis()
        // Retain bounded spectrum/trend history across stop/restart cycles.
        // A new session appends to the same process-level history so the
        // dashboard cannot regress to an empty state after recording stops.
        sessionWindowCount = 0L
        sessionShockCount = 0L
        sessionBaseDir = null
        rawVibrationBuffer.clear()

        impactInCurrentWindow = false
        lastImpactMs = 0L

        impactBaselineX = 0f
        impactBaselineY = 0f
        impactBaselineZ = 0f
        impactBaselineInitialized = false

        analysisSampleCounter = 0L
        sampleCounter = 0L
        vibrationRawEventCount = 0L
        vibrationAcceptedEventCount = 0L
        firstVibrationTimestampNs = null
        firstAcceptedVibrationTimestampNs = null
        lastVibrationTimestampNs = null
        lastAcceptedVibrationTimestampNs = null
        lastVibrationGapNs = 0L
        maxVibrationGapNs = 0L

        vibrationSource?.let { source ->
            emitDiagnostic(
                "[ENGINE] Vibration analysis source: " +
                        "${source.name} (${source.vendor})"
            )
        } ?: emitDiagnostic(
            "[WARN] No usable accelerometer selected -- " +
                    "spectrum analysis disabled this session."
        )

        // Partial wake lock for long measurement sessions.
        //
        // Long-running process ownership is now provided by
        // SensorCaptureService. The wake lock remains an acquisition-level
        // CPU guarantee so sensor processing can continue while the screen is off.
        try {
            val pm =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            wakeLock =
                pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "com.research.sensormax:CbmDeployment"
                )

            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

            emitDiagnostic(
                "[ENGINE] Partial wake lock held -- " +
                        "CPU stays awake through screen-off during deployment."
            )
        } catch (e: Exception) {
            emitDiagnostic(
                "[WARN] Wake lock unavailable: ${e.message}"
            )
        }

        // CSV setup.
        if (isDiskLoggingEnabled) {

            val filesRoot =
                context.getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS
                )

            if (filesRoot == null) {
                return rollbackFailedStart("External storage unavailable")
            } else {

                try {

                    val baseDir =
                        File(
                            filesRoot,
                            "SensorMax_Master_Logs"
                        )

                    if (!baseDir.exists()) {
                        baseDir.mkdirs()
                    }

                    sessionBaseDir = baseDir

                    val mode =
                        if (isNetworkEnabled)
                            "stream_log"
                        else
                            "offline_log"

                    val tag =
                        "${sanitizeForFilename(machineId)}_" +
                                sanitizeForFilename(pointTag)

                    val stamp =
                        System.currentTimeMillis()

                    val deviceTag =
                        "${sanitizeForFilename(Build.MANUFACTURER)}_" +
                                sanitizeForFilename(Build.MODEL)

                    // RAW CSV
                    val newRawFile =
                        File(
                            baseDir,
                            "${deviceTag}_${mode}_${tag}_${stamp}_raw.csv"
                        )

                    rawRepository.start(
                        newRawFile,
                        "Schema_Version,Sensor_Timestamp_ns,Timestamp_ms,Machine_ID,Point,Source_Kind,Source_ID,Channel_ID,Sensor_Type,Sensor_Name,Unit,Val_0,Val_1,Val_2,Val_3,Val_4,Val_5\n"
                    )

                    // ANALYSIS CSV
                    val newAnalysisFile =
                        File(
                            baseDir,
                            "${deviceTag}_${mode}_${tag}_${stamp}_analysis.csv"
                        )

                    analysisRepository.start(
                        newAnalysisFile,
                        "Timestamp_ms,Machine_ID,Point,Sample_Rate_Hz,RMS_X_ms2,RMS_Y_ms2,RMS_Z_ms2,Overall_RMS_Accel_ms2,Overall_RMS_Velocity_mms,Overall_RMS_Displacement_um,Dominant_Axis,Dominant_Freq_Hz,Dominant_Accel_Amplitude_ms2,Envelope_Peak_Freq_Hz,ISO20816_Zone,Bearing_Match,RPM_Input,Impact_Event,Snapshot_Triggered\n"
                    )

                    diagnosticRepository.start(
                        File(baseDir, "${deviceTag}_${mode}_${tag}_${stamp}_diagnostic.log")
                    )

                    emitDiagnostic(
                        "[ENGINE] CSV pair initialized: " +
                                "${newRawFile.name} + " +
                                "${newAnalysisFile.name}"
                    )

                    emitDiagnostic(
                        "[ENGINE] App-private external storage -- " +
                                "copy publishes to Downloads/SensorMax_Master_Logs " +
                                "when deployment stops."
                    )

                } catch (e: Exception) {
                    return rollbackFailedStart("Storage initialization failed: ${e.message}")
                }
            }
        }

        // Acquisition is now source-neutral. Provider construction happens outside
        // this core lifecycle method; Android is retained only through the legacy wrapper below.
        val source = request.source
        acquisitionSource = source
        acquisitionSourceStates.clear()
        acquisitionSourceStates[source.descriptor.id] = AcquisitionSourceState.IDLE

        val sourceStarted = source.start(
            config = AcquisitionSourceConfig(captureIntervalMs),
            listener = acquisitionSourceListener
        )

        if (!sourceStarted) {
            return rollbackFailedStart("No selected acquisition source could be started")
        }

        emitDiagnostic(
            "[ENGINE] Acquisition source online: ${source.descriptor.id} | " +
                    "${source.descriptor.channels.size} acquisition channels"
        )

        isRunning = true
        recordingSessionLifecycle.begin(
            sessionId = sessionId,
            asset = machineId,
            point = pointTag,
            customLabel = customLabel,
            startedAtMs = sessionStartMs
        )
        return true
    }


    private fun rollbackFailedStart(reason: String): Boolean {
        emitDiagnostic("[FATAL] Deployment start rolled back: $reason")
        isRunning = false
        acquisitionSource?.stop()
        acquisitionSource = null
        acquisitionSourceStates.clear()
        rawRepository.close()
        analysisRepository.close()
        diagnosticRepository.close()
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        vibrationSource = null
        sessionBaseDir = null
        activeSensors.clear()
        lastCaptureTimeNs.clear()
        acquisitionClockMapper.reset()
        recordingSessionLifecycle.complete()
        return false
    }


    private fun handleAcquisitionSample(sample: AcquisitionSample) {

        if (!isRunning) return

        if (sample.values.isEmpty()) return

        val sensorTsNs = sample.timestampNs
        val sensorTsMs = sensorTsNs / 1_000_000L
        val wallClockTs = acquisitionClockMapper.resolve(
            monotonicNs = sensorTsNs,
            fallbackWallClockMs = sample.wallClockTimestampMs
        )
        val type = sample.androidSensorType ?: -1
        val channelKey = "${sample.sourceId}|${sample.channelId}"
        val isVibrationSample =
            sample.sourceKind == AcquisitionSourceKind.ANDROID_SENSOR &&
                    sample.channelId == vibrationSource?.let(::androidSensorChannelId)

        // Detect shocks before throttling.
        if (isVibrationSample && sample.values.size >= 3) {
            val priorVibrationTs = lastVibrationTimestampNs
            if (priorVibrationTs != null && sensorTsNs <= priorVibrationTs) {
                emitDiagnostic(
                    "[WARN] Non-monotonic vibration timestamp ignored: $sensorTsNs <= $priorVibrationTs"
                )
                return
            }

            if (priorVibrationTs == null) {
                firstVibrationTimestampNs = sensorTsNs
            }
            lastVibrationTimestampNs = sensorTsNs
            vibrationRawEventCount++

            updateImpactDetector(
                sample.values[0],
                sample.values[1],
                sample.values[2],
                sensorTsMs
            )
        }

        val captureIntervalNs =
            captureIntervalMs.coerceAtLeast(1L) * 1_000_000L
        val lastTsNs = lastCaptureTimeNs[channelKey]
        if (lastTsNs != null && sensorTsNs - lastTsNs < captureIntervalNs) {
            return
        }
        lastCaptureTimeNs[channelKey] = sensorTsNs

        // Keep the actual channel count in telemetry snapshots. Padding short
        // samples with zero invents measurements for channels the source did not emit.
        val telemetryValues = sample.values.copyOf()

        sampleCounter++

        sensorTelemetryRepository.append(
            SensorTelemetrySample(
                type = type,
                name = sample.name,
                vendor = sample.vendor,
                timestampMs = wallClockTs,
                values = telemetryValues,
                sourceKind = sample.sourceKind,
                sourceId = sample.sourceId,
                channelId = sample.channelId,
                unit = sample.unit
            )
        )

        if (isDiskLoggingEnabled) {
            try {
                val rawRecord = mutableListOf<Any?>(
                    2,
                    sensorTsNs,
                    wallClockTs,
                    machineId,
                    pointTag,
                    sample.sourceKind.name,
                    sample.sourceId,
                    sample.channelId,
                    sample.androidSensorType ?: -1,
                    sample.name,
                    sample.unit
                )
                for (index in 0 until 6) {
                    rawRecord.add(sample.values.getOrNull(index)?.takeIf { it.isFinite() })
                }
                rawRepository.append(
                    rawRecord.joinToString(",") { csvField(it) } + "\n"
                )

                if (sampleCounter % 200L == 0L) {
                    rawRepository.flush()
                }
            } catch (_: Exception) {
                // Keep the acquisition callback alive if disk writing fails.
            }
        }

        if (isNetworkEnabled) {
            try {
                streamClient.streamSensorData(
                    timestampMs = wallClockTs,
                    sensorTimestampNs = sensorTsNs,
                    machineId = machineId,
                    point = pointTag,
                    sourceKind = sample.sourceKind.name,
                    sourceId = sample.sourceId,
                    channelId = sample.channelId,
                    sensorType = sample.androidSensorType,
                    sensorName = sample.name,
                    unit = sample.unit,
                    vals = sample.values
                )
            } catch (e: Exception) {
                emitDiagnostic(
                    "[WARN] Sensor network stream failed: ${e.message}"
                )
            }
        }

        onSensorSample(sample.copySnapshot())

        if (isVibrationSample && sample.values.size >= 3) {
            vibrationAcceptedEventCount++
            if (firstAcceptedVibrationTimestampNs == null) {
                firstAcceptedVibrationTimestampNs = sensorTsNs
            }
            val priorAcceptedTs = lastAcceptedVibrationTimestampNs
            if (priorAcceptedTs != null) {
                lastVibrationGapNs = sensorTsNs - priorAcceptedTs
                maxVibrationGapNs = maxOf(maxVibrationGapNs, lastVibrationGapNs)
            }
            lastAcceptedVibrationTimestampNs = sensorTsNs

            if (rawVibrationBuffer.size() == 0) {
                experimentClock.markStart(sensorTsNs, wallClockTs)
            }
            rawVibrationBuffer.append(
                RawVibrationSample(
                    sensorTimestampNs = sensorTsNs,
                    wallClockTimestampMs = wallClockTs,
                    x = sample.values[0],
                    y = sample.values[1],
                    z = sample.values[2]
                )
            )

            processVibrationSample(
                sensorTsNs,
                sample.values
            )
        }
    }

    private fun androidSensorChannelId(sensor: Sensor): String =
        "${sensor.type}|${sensor.vendor}|${sensor.name}"

    /**
     * Captures the latest completed spectrum as a snapshot.
     */
    fun captureSnapshotNow(): Boolean {

        if (!isRunning) {
            return false
        }

        val result =
            lastResult ?: return false

        val ts =
            System.currentTimeMillis()

        writeAnalysisRow(
            ts,
            result,
            isSnapshot = true
        )

        writeSnapshotMetadata(
            ts,
            result
        )

        if (isNetworkEnabled) {

            streamAnalysis(
                ts,
                result
            )
        }

        emitDiagnostic(
            "[ENGINE] Snapshot captured for " +
                    "$machineId / $pointTag."
        )

        return true
    }


    /**
     * Section 26 (Snapshot/Measurement Package): writes one metadata.json
     * per snapshot press into the same SensorMax_Master_Logs folder as the
     * continuous CSVs, then publishes it to public Downloads the same way.
     * Does nothing when disk logging is off (sessionBaseDir stays null).
     *
     * Reuses the same bearing-match / ISO-zone helpers writeAnalysisRow()
     * uses, so a snapshot's metadata.json and its CSV row always agree.
     */
    private fun writeSnapshotMetadata(
        ts: Long,
        result: SpectrumResult
    ) {

        val baseDir = sessionBaseDir ?: return

        val dominantSpec = result.perAxis[result.dominantAxis]
        val isoZone = VibrationAnalyzer.classify(isoClass, result.overallRmsVelocityMmS)
        val bearingCode = bearingMatchCodeFor(dominantSpec)

        val advisoryState =
            if (isoClass == IsoMachineClass.NONE) {
                when {
                    result.overallRmsAccel >= alertThresholdMs2 -> "ALERT (user-configured threshold)"
                    result.overallRmsAccel >= warnThresholdMs2 -> "WARN (user-configured threshold)"
                    else -> "NORMAL (user-configured threshold)"
                }
            } else {
                "${isoZone.label} -- ${isoZone.advice}"
            }

        val metadata =
            SnapshotMetadata(
                asset = machineId,
                point = pointTag,
                customLabel = customLabel,
                timestampMs = ts,
                timestampIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ts)),

                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                androidApiLevel = Build.VERSION.SDK_INT,
                appVersion = getAppVersionString(),
                sensorName = vibrationSource?.name ?: "unknown",
                sensorVendor = vibrationSource?.vendor ?: "unknown",
                sensorVersion = vibrationSource?.version ?: 0,
                sensorMaxRangeMs2 = vibrationSource?.maximumRange ?: 0f,

                calibration = currentValidCalibration(),
                mountingMethod = mountingMethod,
                axisRoleX = axisRoleX,
                axisRoleY = axisRoleY,
                axisRoleZ = axisRoleZ,
                mountingCheck = currentValidMountingCheck(),

                operatingState = "not specified (no operating-state classifier yet)",
                rpmHint = rpmHint,

                actualSampleRateHz = result.sampleRateHz,
                fftResolutionHz = result.sampleRateHz / FFT_WINDOW_SIZE,
                fftWindowSize = FFT_WINDOW_SIZE,
                overallRmsAccelMs2 = result.overallRmsAccel,
                overallRmsVelocityMmS = result.overallRmsVelocityMmS,
                overallRmsDisplacementUm = result.overallRmsDisplacementUm,
                dominantAxis = result.dominantAxis.name,
                dominantFreqHz = dominantSpec?.peakFreqHz ?: 0f,
                peakAccelMs2 = dominantSpec?.peakAccelAmplitude ?: 0f,
                peakToPeakMs2 = dominantSpec?.peakToPeak ?: 0f,
                crestFactor = dominantSpec?.crestFactor ?: 0f,
                kurtosis = dominantSpec?.kurtosis ?: 0f,
                envelopePeakFreqHz = dominantSpec?.envelopePeakFreqHz ?: 0f,
                bearingMatch = bearingCode,
                sessionShockCount = sessionShockCount,

                baselineComparison = "not available (no baseline system yet)",
                isoMachineClass = isoClass.label,
                warnThresholdMs2 = warnThresholdMs2,
                alertThresholdMs2 = alertThresholdMs2,
                measurementQuality = lastQualityScore,
                advisoryState = advisoryState
            )

        try {

            val json =
                GsonBuilder()
                    .setPrettyPrinting()
                    // Explicit nulls: a session with no calibration or mounting
                    // check should say `"calibration": null`, not silently
                    // drop the key -- a missing key reads like a schema change.
                    .serializeNulls()
                    .create()
                    .toJson(metadata)

            val tag =
                "${sanitizeForFilename(machineId)}_${sanitizeForFilename(pointTag)}"

            val deviceTag =
                "${sanitizeForFilename(Build.MANUFACTURER)}_${sanitizeForFilename(Build.MODEL)}"

            val jsonFile =
                File(
                    baseDir,
                    "${deviceTag}_snapshot_${tag}_${ts}.json"
                )

            FileWriter(jsonFile).use { it.write(json) }

            publishToMediaStore(
                jsonFile,
                "application/json"
            )

            emitDiagnostic("[ENGINE] Snapshot metadata written: ${jsonFile.name}")

        } catch (e: Exception) {

            emitDiagnostic("[WARN] Snapshot metadata write failed: ${e.message}")
        }
    }


    /**
     * Releases all engine-owned resources, including auxiliary pre-flight
     * sensor threads that may exist outside an active deployment.
     */
    fun shutdown() {
        stopDeployment()
        cancelAuxiliarySensorTasks()
        streamClient.terminate()
    }

    private fun cancelAuxiliarySensorTasks() {
        mountingCheckHandler?.removeCallbacksAndMessages(null)
        mountingCheckListener?.let { listener ->
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
        }
        mountingCheckThread?.quitSafely()
        mountingCheckHandler = null
        mountingCheckListener = null
        mountingCheckThread = null

        calibrationHandler?.removeCallbacksAndMessages(null)
        calibrationListener?.let { listener ->
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
        }
        calibrationThread?.quitSafely()
        calibrationHandler = null
        calibrationListener = null
        calibrationThread = null
    }


    @Synchronized
    fun stopDeployment() {

        if (!isRunning) {
            acquisitionSource?.stop()
            acquisitionSource = null
            cancelAuxiliarySensorTasks()
            recordingSessionLifecycle.complete()
            return
        }

        isRunning = false
        val pauseMonotonicNs = SystemClock.elapsedRealtimeNanos()
        experimentClock.markPause(pauseMonotonicNs, System.currentTimeMillis())
        recordingSessionLifecycle.requestStop()

        try {

            acquisitionSource?.stop()
            acquisitionSource = null

            if (isDiskLoggingEnabled) {

                rawRepository.flush()
                rawRepository.close()

                analysisRepository.flush()
                analysisRepository.close()

                diagnosticRepository.flush()
                val endedAtMs = System.currentTimeMillis()
                writeSessionConditionSummary(endedAtMs)
                writeSensorTelemetrySummary(endedAtMs)
                diagnosticRepository.close()

                rawRepository.file?.let { publishToMediaStore(it, "text/csv") }
                analysisRepository.file?.let { publishToMediaStore(it, "text/csv") }
                diagnosticRepository.file?.let { publishToMediaStore(it, "text/plain") }
            }

            emitDiagnostic(
                "[ENGINE] Array disengaged. " +
                        "Captured $sampleCounter optimized points."
            )

            emitDiagnostic(
                "[ENGINE] Vibration stream: " +
                        "$vibrationAcceptedEventCount/$vibrationRawEventCount " +
                        "events used for FFT after throttling."
            )

            val health = acquisitionHealthSnapshot()
            emitDiagnostic(
                "[HEALTH] integrity=${health.acquisitionIntegrity} | " +
                        "Fs=${String.format(Locale.US, "%.2f", health.effectiveVibrationRateHz)} Hz | " +
                        "raw=${health.vibrationRawEvents} accepted=${health.vibrationAcceptedEvents} " +
                        "throttled=${health.vibrationThrottledEvents} | " +
                        "maxGap=${String.format(Locale.US, "%.1f", health.maxVibrationGapMs)} ms | " +
                        "disk=${if (health.diskLoggingEnabled) if (health.diskWriteHealthy) "OK" else "ERROR" else "OFF"} | " +
                        "netDrop=${health.droppedRawPackets + health.droppedAnalysisPackets}"
            )

            logSessionRecord()
            logRepeatability()

        } catch (_: Exception) {
        }

        recordingSessionLifecycle.complete()

        try {

            wakeLock?.let {

                if (it.isHeld) {
                    it.release()
                }
            }

        } catch (_: Exception) {
        }

        wakeLock = null



        vibrationSource = null

        cancelAuxiliarySensorTasks()

        if (isNetworkEnabled) {
            streamClient.terminate()
        }
    }


    /**
     * Session Record (design-debt Section 27): a plain-text summary of the
     * deployment that just ended. Asset/point/label are whatever the
     * operator typed in -- there is no asset library yet (Section 7/8 is
     * still P2), so those fields just echo the free-text fields already
     * used for CSV tagging. Baseline/Calibration IDs from the doc's
     * example are left out entirely rather than shown as fake values --
     * neither subsystem exists yet (Sections 12, 33).
     */
    private fun writeSensorTelemetrySummary(ts: Long) {
        val baseDir = sessionBaseDir ?: return
        val file = File(
            baseDir,
            "${sanitizeForFilename(machineId)}_${sanitizeForFilename(pointTag)}_${ts}_sensor_summary.json"
        )
        val payload = mapOf(
            "sessionId" to sessionId,
            "timestampMs" to ts,
            "sensors" to sensorTelemetryRepository.latestSnapshotSince(sessionStartMs).map {
                mapOf(
                    "type" to it.type,
                    "name" to it.name,
                    "vendor" to it.vendor,
                    "timestampMs" to it.timestampMs,
                    // JSON null means a channel was unavailable/non-finite; never serialize it as zero.
                    "values" to it.values.map { value -> value.takeIf { it.isFinite() } },
                    "label" to SensorTelemetryFormatter.label(it.type),
                    "unit" to SensorTelemetryFormatter.unit(it.type)
                )
            }
        )
        runCatching {
            FileWriter(file).use {
                it.write(GsonBuilder().setPrettyPrinting().create().toJson(payload))
            }
            publishToMediaStore(file, "application/json")
            emitDiagnostic("[SENSOR] Summary written: ${file.name}")
        }.onFailure {
            emitDiagnostic("[WARN] Sensor summary write failed: ${it.message}")
        }
    }

    private fun logSessionRecord() {

        val durationSec =
            (System.currentTimeMillis() - sessionStartMs) / 1000.0

        val result = lastResult

        val verdict =
            if (result != null) {
                VibrationAnalyzer.classify(
                    isoClass,
                    result.overallRmsVelocityMmS
                ).label
            } else {
                "NO DATA"
            }

        val fsText =
            if (result != null) String.format(Locale.US, "%.1f Hz", result.sampleRateHz) else "--"

        val qualityText =
            lastQualityScore?.let { "${it.score0to100}/100" } ?: "--"

        val mountingText =
            lastMountingCheck?.let {
                String.format(
                    Locale.US,
                    "%s stability, \u00B1%.1f\u00B0, %s",
                    if (it.ready) "READY" else "NOT READY",
                    it.orientationStabilityDeg,
                    it.movementDuringSetup
                )
            } ?: "not checked this session"

        val calibrationText =
            lastCalibration?.let {
                if (it.sensorName == vibrationSource?.name) {
                    String.format(Locale.US, "%s (noise %.3f m/s\u00B2 RSS)", it.calId, it.noiseMagnitude())
                } else {
                    "${it.calId} (for a different sensor -- not used)"
                }
            } ?: "not calibrated this session"

        emitDiagnostic(
            "[SESSION] $sessionId  |  Asset: $machineId  |  Point: $pointTag  |  " +
                    "RPM hint: $rpmHint  |  Duration: ${String.format(Locale.US, "%.1f", durationSec)}s  |  " +
                    "Windows: $sessionWindowCount  |  Actual Fs: $fsText  |  FFT: $FFT_WINDOW_SIZE  |  " +
                    "Shocks: $sessionShockCount  |  Quality: $qualityText  |  Result: $verdict"
        )

        emitDiagnostic(
            "[SESSION] Mounting: $mountingMethod  |  Axis roles: X=$axisRoleX, Y=$axisRoleY, Z=$axisRoleZ  |  " +
                    "Mounting check: $mountingText  |  Calibration: $calibrationText"
        )
    }


    private fun writeSessionConditionSummary(
        endedAtMs: Long
    ) {

        val baseDir =
            sessionBaseDir ?: return

        val result =
            lastResult

        val quality =
            lastQualityScore

        val durationSec =
            (endedAtMs - sessionStartMs) / 1000.0

        val isoZone =
            result?.let {
                VibrationAnalyzer.classify(
                    isoClass,
                    it.overallRmsVelocityMmS
                )
            } ?: IsoZone.UNCLASSIFIED

        val severity =
            if (result == null) {
                "NO DATA"
            } else {
                when {
                    result.overallRmsAccel >= alertThresholdMs2 ||
                            isoZone == IsoZone.D -> "ALERT"
                    result.overallRmsAccel >= warnThresholdMs2 ||
                            isoZone == IsoZone.C -> "WARNING"
                    else -> "OK"
                }
            }

        val dominant =
            result?.perAxis?.get(result.dominantAxis)

        val calibration =
            currentValidCalibration()

        val mounting =
            currentValidMountingCheck()

        val summary =
            SessionConditionSummary(
                sessionId = sessionId,
                asset = machineId,
                point = pointTag,
                customLabel = customLabel,
                startedAtMs = sessionStartMs,
                endedAtMs = endedAtMs,
                durationSec = durationSec,
                analysisWindows = sessionWindowCount,
                shockCount = sessionShockCount,
                actualSampleRateHz = result?.sampleRateHz ?: 0.0,
                overallRmsAccelMs2 = result?.overallRmsAccel,
                overallRmsVelocityMmS = result?.overallRmsVelocityMmS,
                overallRmsDisplacementUm = result?.overallRmsDisplacementUm,
                dominantAxis = result?.dominantAxis?.name,
                dominantFreqHz = dominant?.peakFreqHz,
                isoZone = isoZone.label,
                severity = severity,
                qualityScore0to100 = quality?.score0to100,
                sampleJitterMs = result?.timestampJitterMs,
                maxSampleGapMs = result?.maxSampleGapMs,
                calibrationId = calibration?.calId,
                mountingReady = mounting?.ready,
                mountingMethod = mountingMethod,
                axisRoleX = axisRoleX,
                axisRoleY = axisRoleY,
                axisRoleZ = axisRoleZ,
                acquisitionIntegrity = acquisitionHealthSnapshot().acquisitionIntegrity,
                vibrationRawEvents = vibrationRawEventCount,
                vibrationAcceptedEvents = vibrationAcceptedEventCount,
                vibrationThrottledEvents = (vibrationRawEventCount - vibrationAcceptedEventCount).coerceAtLeast(0L),
                maxVibrationGapMs = maxVibrationGapNs / 1_000_000.0,
                networkDroppedPackets = run {
                    val network = streamClient.healthSnapshot()
                    network.droppedRawPackets + network.droppedAnalysisPackets
                }
            )

        try {
            val tag =
                "${sanitizeForFilename(machineId)}_${sanitizeForFilename(pointTag)}"

            val deviceTag =
                "${sanitizeForFilename(Build.MANUFACTURER)}_${sanitizeForFilename(Build.MODEL)}"

            val summaryFile =
                conditionSummaryRepository.write(
                    baseDir,
                    "${deviceTag}_session_summary_${tag}_${endedAtMs}.json",
                    summary
                )

            publishToMediaStore(
                summaryFile,
                "application/json"
            )

            emitDiagnostic(
                "[SESSION] Condition summary recorded: ${summaryFile.name} · $severity · " +
                        "${quality?.score0to100 ?: "--"}/100"
            )
        } catch (e: Exception) {
            emitDiagnostic(
                "[WARN] Condition summary write failed: ${e.message}"
            )
        }
    }

    /**
     * Repeatability (design-debt Section 56): compares this deployment's
     * final reading against past deployments at the same machineId+point,
     * within this app session only (nothing is persisted once the app is
     * killed -- there is no database yet, Section 12/26).
     *
     * CV cutoffs (10% / 20%) are a plain documented default for this app,
     * not a value from the IAFMI CRV paper or ISO 17359.
     */
    private fun logRepeatability() {

        val result = lastResult ?: return

        val key = "$machineId|$pointTag"
        val history = repeatabilityHistory.getOrPut(key) { mutableListOf() }

        history.add(result.overallRmsVelocityMmS)
        while (history.size > REPEATABILITY_HISTORY_CAP) {
            history.removeAt(0)
        }

        if (history.size < 2) {
            return
        }

        val mean = history.average()
        val variance = history.sumOf { (it - mean) * (it - mean) } / history.size
        val std = sqrt(variance)
        val cv = if (mean > 1e-9) (std / mean) * 100.0 else 0.0
        val range = (history.max() - history.min())

        val classification =
            when {
                cv <= 10.0 -> "GOOD REPEATABILITY"
                cv <= 20.0 -> "QUESTIONABLE REPEATABILITY"
                else -> "POOR REPEATABILITY"
            }

        val readingsText =
            history.mapIndexed { i, v -> "R${i + 1}=${String.format(Locale.US, "%.2f", v)}" }
                .joinToString(" ")

        emitDiagnostic(
            "[REPEATABILITY] $machineId / $pointTag (n=${history.size}): $readingsText mm/s  |  " +
                    "mean ${String.format(Locale.US, "%.2f", mean)}  |  " +
                    "std ${String.format(Locale.US, "%.2f", std)}  |  " +
                    "CV ${String.format(Locale.US, "%.1f", cv)}%  |  " +
                    "range ${String.format(Locale.US, "%.2f", range)}  |  $classification" +
                    (if (classification != "GOOD REPEATABILITY") " -- check mounting or machine operating state before interpreting this result." else "")
        )
    }


    /** Same accelerometer-over-linear-acceleration preference used for the real vibration source, reused for the mounting-check burst so both agree on which sensor "is" the phone's vibration sensor. */
    private fun resolveAccelSource(sensors: Set<Sensor>): Sensor? =
        sensors.firstOrNull { it.type == Sensor.TYPE_ACCELEROMETER }
            ?: sensors.firstOrNull { it.type == Sensor.TYPE_LINEAR_ACCELERATION }


    /**
     * Mounting/orientation pre-check (design-debt Section 17): samples raw
     * accelerometer data for [durationMs] and hands the burst to
     * [MountingCheck.evaluate]. Independent of an active deployment -- it
     * registers and unregisters its own short-lived listener, so it can be
     * run before, after, or (harmlessly) during a deployment.
     *
     * [onResult] fires on a background thread, same as [onAnalysis] and
     * [onImpact] -- callers that touch views must post to the UI thread.
     */
    private fun cancelMountingCheckTask() {
        mountingCheckGeneration.incrementAndGet()
        mountingCheckHandler?.removeCallbacksAndMessages(null)
        mountingCheckListener?.let { listener ->
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
        }
        mountingCheckThread?.quitSafely()
        mountingCheckHandler = null
        mountingCheckListener = null
        mountingCheckThread = null
    }

    fun runMountingCheck(
        sensors: Set<Sensor>,
        durationMs: Long = 2000L,
        onResult: (MountingCheckResult?) -> Unit
    ) {
        val source = resolveAccelSource(sensors)
        if (source == null) {
            onResult(null)
            return
        }

        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val zs = mutableListOf<Float>()

        // Cancel an older check before starting a new one.
        cancelMountingCheckTask()
        val taskGeneration = mountingCheckGeneration.get()

        val checkThread = HandlerThread("MountingCheckThread").apply { start() }
        val checkHandler = Handler(checkThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size >= 3) {
                    xs.add(event.values[0])
                    ys.add(event.values[1])
                    zs.add(event.values[2])
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Intentionally unused.
            }
        }

        mountingCheckThread = checkThread
        mountingCheckHandler = checkHandler
        mountingCheckListener = listener

        sensorManager.registerListener(
            listener,
            source,
            SensorManager.SENSOR_DELAY_GAME,
            checkHandler
        )

        checkHandler.postDelayed({
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
            checkThread.quitSafely()
            if (mountingCheckThread === checkThread) {
                mountingCheckThread = null
                mountingCheckHandler = null
                mountingCheckListener = null
            }

            if (mountingCheckGeneration.get() != taskGeneration) {
                return@postDelayed
            }

            val result = MountingCheck.evaluate(
                xs.toFloatArray(),
                ys.toFloatArray(),
                zs.toFloatArray()
            )

            val taggedResult = result?.copy(
                sensorType = source.type,
                sensorId = source.id,
                sensorName = source.name,
                sensorVendor = source.vendor,
                sensorVersion = source.version,
                mountingMethod = mountingMethod
            )

            lastMountingCheck = taggedResult
            onResult(taggedResult)
        }, durationMs)
    }


    /**
     * Calibration System (design-debt Section 33): samples raw
     * accelerometer data for [durationMs] while the sensor is meant to be
     * at rest, computes per-axis bias/noise via [CalibrationCheck], and
     * assembles a full [CalibrationRecord] with the device/sensor identity
     * attached so it's never mistaken for a different phone's numbers.
     *
     * [onResult] fires on a background thread, same as the other engine callbacks.
     */
    private fun cancelCalibrationTask() {
        calibrationGeneration.incrementAndGet()
        calibrationHandler?.removeCallbacksAndMessages(null)
        calibrationListener?.let { listener ->
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
        }
        calibrationThread?.quitSafely()
        calibrationHandler = null
        calibrationListener = null
        calibrationThread = null
    }

    fun runCalibration(
        sensors: Set<Sensor>,
        operatorName: String,
        durationMs: Long = 3000L,
        onResult: (CalibrationRecord?) -> Unit
    ) {
        val source = resolveAccelSource(sensors)
        if (source == null) {
            onResult(null)
            return
        }

        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val zs = mutableListOf<Float>()
        val tsNs = mutableListOf<Long>()

        cancelCalibrationTask()
        val taskGeneration = calibrationGeneration.get()

        val calThread = HandlerThread("CalibrationThread").apply { start() }
        val calHandler = Handler(calThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size >= 3) {
                    xs.add(event.values[0])
                    ys.add(event.values[1])
                    zs.add(event.values[2])
                    tsNs.add(event.timestamp)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Intentionally unused.
            }
        }

        calibrationThread = calThread
        calibrationHandler = calHandler
        calibrationListener = listener

        sensorManager.registerListener(
            listener,
            source,
            SensorManager.SENSOR_DELAY_GAME,
            calHandler
        )

        calHandler.postDelayed({
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
            calThread.quitSafely()
            if (calibrationThread === calThread) {
                calibrationThread = null
                calibrationHandler = null
                calibrationListener = null
            }

            if (calibrationGeneration.get() != taskGeneration) {
                return@postDelayed
            }

            val stats = CalibrationCheck.evaluate(
                xs.toFloatArray(),
                ys.toFloatArray(),
                zs.toFloatArray(),
                tsNs.toLongArray()
            )
            if (stats == null) {
                onResult(null)
                return@postDelayed
            }

            if (!stats.stationary) {
                emitDiagnostic(
                    "[CALIBRATION] Rejected: stationary gate failed. " +
                            stats.validationMessages.joinToString(" | ")
                )
                onResult(null)
                return@postDelayed
            }

            val record = CalibrationRecord(
                calId = "CAL-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sensorName = source.name,
                sensorVendor = source.vendor,
                sensorVersion = source.version,
                sensorType = source.type,
                sensorId = source.id,
                mountingMethod = mountingMethod,
                dateEpochMs = System.currentTimeMillis(),
                operatorName = operatorName.ifBlank { "UNSPECIFIED" },
                biasX = stats.biasX,
                biasY = stats.biasY,
                biasZ = stats.biasZ,
                noiseX = stats.noiseX,
                noiseY = stats.noiseY,
                noiseZ = stats.noiseZ,
                declaredRangeMs2 = source.maximumRange,
                achievedSamplingHz = stats.achievedHz,
                notes = stats.validationMessages.joinToString("; ")
            )

            lastCalibration = record
            onResult(record)
        }, durationMs)
    }


    private fun currentValidCalibration(): CalibrationRecord? {
        val calibration = lastCalibration ?: return null
        val source = vibrationSource ?: return null
        return calibration.takeIf {
            it.sensorType == source.type &&
                    it.sensorId == source.id &&
                    it.sensorName == source.name &&
                    it.sensorVendor == source.vendor &&
                    it.sensorVersion == source.version &&
                    it.mountingMethod == mountingMethod
        }
    }

    private fun deepCopyAnalysisUpdate(update: AnalysisUpdate): AnalysisUpdate {
        val source = update.spectrum
        val copiedPerAxis = source.perAxis.mapValues { (_, axis) ->
            axis.copy(
                frequenciesHz = axis.frequenciesHz.copyOf(),
                accelAmplitude = axis.accelAmplitude.copyOf(),
                velocityAmplitude = axis.velocityAmplitude.copyOf(),
                displacementAmplitudeUm = axis.displacementAmplitudeUm.copyOf(),
                envelopeAmplitude = axis.envelopeAmplitude.copyOf()
            )
        }
        val copiedSpectrum = source.copy(
            perAxis = copiedPerAxis,
            trend = source.trend.copyOf()
        )
        return AnalysisUpdate(
            spectrum = copiedSpectrum,
            quality = update.quality
        )
    }

    private fun currentValidMountingCheck(): MountingCheckResult? {
        val check = lastMountingCheck ?: return null
        val source = vibrationSource ?: return null
        return check.takeIf {
            it.sensorType == source.type &&
                    it.sensorId == source.id &&
                    it.sensorName == source.name &&
                    it.sensorVendor == source.vendor &&
                    it.sensorVersion == source.version &&
                    it.mountingMethod == mountingMethod
        }
    }


    /**
     * Sends vibration data into the FFT analyzer.
     *
     * The timestamp comes from the acquisition source's monotonic timestamp,
     * so sample-rate estimation is based on actual sensor timing.
     */
    private fun processVibrationSample(
        timestampNs: Long,
        values: FloatArray
    ) {

        if (values.size < 3) {
            return
        }

        val result =
            vibrationAnalyzer.addSample(
                timestampNs,
                values[0],
                values[1],
                values[2]
            ) ?: return

        sessionWindowCount++

        val rawPeakAbs =
            maxOf(
                result.perAxis[Axis.X]?.rawPeakAbs ?: 0f,
                result.perAxis[Axis.Y]?.rawPeakAbs ?: 0f,
                result.perAxis[Axis.Z]?.rawPeakAbs ?: 0f
            )

        val requestedHz =
            if (captureIntervalMs > 0) 1000.0 / captureIntervalMs else 0.0

        // Only trust a calibration if it was run against THIS session's
        // actual vibration sensor -- a stale calibration for a different
        // sensor is worse than none (design-debt Section 33's own caution
        // against treating one sensor's numbers as universal).
        val validCalibration = currentValidCalibration()
        val validMountingCheck = currentValidMountingCheck()

        val quality =
            MeasurementQuality.evaluate(
                measuredHz = result.sampleRateHz,
                requestedHz = requestedHz,
                jitterMs = result.timestampJitterMs,
                maxGapMs = result.maxSampleGapMs,
                acceptedEvents = vibrationAcceptedEventCount,
                rawEvents = vibrationRawEventCount,
                rawPeakAbs = rawPeakAbs,
                sensorMaxRange = vibrationSource?.maximumRange ?: 0f,
                resolutionHz = result.sampleRateHz / FFT_WINDOW_SIZE,
                mountingCheck = validMountingCheck,
                calibration = validCalibration
            )

        lastResult = result
        lastQualityScore = quality

        val ts = System.currentTimeMillis()

        writeAnalysisRow(
            ts,
            result,
            isSnapshot = false
        )


        if (isNetworkEnabled) {

            streamAnalysis(
                ts,
                result
            )
        }


        val update = AnalysisUpdate(
            spectrum = result,
            quality = quality
        )

        val retainedUpdate = deepCopyAnalysisUpdate(update)
        spectrumHistoryRepository.append(retainedUpdate)
        trendHistoryRepository.append(result.overallRmsAccel)

        onAnalysis(update)

        /*
         * The impact flag applies to this completed
         * analysis window only.
         */
        impactInCurrentWindow = false
    }


    private fun writeAnalysisRow(
        ts: Long,
        result: SpectrumResult,
        isSnapshot: Boolean
    ) {

        if (!isDiskLoggingEnabled) {
            return
        }

        val dominantSpec =
            result.perAxis[result.dominantAxis]

        val isoZone =
            VibrationAnalyzer.classify(
                isoClass,
                result.overallRmsVelocityMmS
            )

        val bearingCode =
            bearingMatchCodeFor(
                dominantSpec
            )


        try {

            analysisRepository.append(

                "$ts," +
                        "$machineId," +
                        "$pointTag," +

                        String.format(
                            Locale.US,
                            "%.2f",
                            result.sampleRateHz
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            result.perAxis[Axis.X]?.rmsAccel ?: 0f
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            result.perAxis[Axis.Y]?.rmsAccel ?: 0f
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            result.perAxis[Axis.Z]?.rmsAccel ?: 0f
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            result.overallRmsAccel
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            result.overallRmsVelocityMmS
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.2f",
                            result.overallRmsDisplacementUm
                        ) + "," +

                        "${result.dominantAxis}," +

                        String.format(
                            Locale.US,
                            "%.2f",
                            dominantSpec?.peakFreqHz ?: 0f
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.4f",
                            dominantSpec?.peakAccelAmplitude ?: 0f
                        ) + "," +

                        String.format(
                            Locale.US,
                            "%.2f",
                            dominantSpec?.envelopePeakFreqHz ?: 0f
                        ) + "," +

                        "${isoZone.label}," +
                        "$bearingCode," +
                        "$rpmHint," +
                        "$impactInCurrentWindow," +
                        "$isSnapshot\n"
            )


            analysisSampleCounter++


            if (
                isSnapshot ||
                analysisSampleCounter % 20L == 0L
            ) {
                analysisRepository.flush()
            }

        } catch (_: Exception) {
            // Keep acquisition alive if analysis logging fails.
        }
    }


    /**
     * Returns a short bearing-match code:
     * BPFO / BPFI / BSF / FTF / NONE / N/A
     */
    private fun bearingMatchCodeFor(
        spec: AxisSpectrum?
    ): String {

        if (spec == null) {
            return "N/A"
        }

        val geometry =
            currentBearingGeometry()
                ?: return "N/A"

        val freqs =
            VibrationAnalyzer.bearingFrequencies(
                geometry,
                rpmHint
            ) ?: return "N/A"

        return VibrationAnalyzer.bearingMatchCode(
            spec.envelopePeakFreqHz,
            spec.freqResolutionHz,
            freqs
        )
    }


    private fun currentBearingGeometry():
            BearingGeometry? {

        if (
            bearingNumElements <= 0 ||
            bearingBallDiameterMm <= 0f ||
            bearingPitchDiameterMm <= 0f
        ) {
            return null
        }

        return BearingGeometry(
            bearingNumElements,
            bearingBallDiameterMm,
            bearingPitchDiameterMm,
            bearingContactAngleDeg
        )
    }


    private fun streamAnalysis(
        ts: Long,
        result: SpectrumResult
    ) {

        val dominantSpec =
            result.perAxis[result.dominantAxis]
                ?: return

        val isoZone =
            VibrationAnalyzer.classify(
                isoClass,
                result.overallRmsVelocityMmS
            )

        streamClient.streamAnalysisSnapshot(

            AnalysisSnapshot(

                ts = ts,

                machineId = machineId,

                point = pointTag,

                sampleRateHz =
                    result.sampleRateHz,

                overallRmsAccelMs2 =
                    result.overallRmsAccel,

                overallRmsVelocityMmS =
                    result.overallRmsVelocityMmS,

                overallRmsDisplacementUm =
                    result.overallRmsDisplacementUm,

                dominantAxis =
                    result.dominantAxis.name,

                dominantFreqHz =
                    dominantSpec.peakFreqHz,

                envelopePeakFreqHz =
                    dominantSpec.envelopePeakFreqHz,

                isoZone =
                    isoZone.label,

                bearingMatch =
                    bearingMatchCodeFor(
                        dominantSpec
                    )
            )
        )
    }


    /**
     * Fast transient / shock detector.
     *
     * The detector establishes a slowly moving baseline for
     * each axis, then measures the instantaneous deviation
     * from that baseline.
     *
     * This function is called BEFORE capture throttling,
     * so short shocks are not silently discarded.
     */
    private fun updateImpactDetector(
        x: Float,
        y: Float,
        z: Float,
        ts: Long
    ) {

        if (!impactBaselineInitialized) {

            impactBaselineX = x
            impactBaselineY = y
            impactBaselineZ = z

            impactBaselineInitialized = true

            return
        }


        // Deviation from slowly changing operating baseline.
        val dx =
            x - impactBaselineX

        val dy =
            y - impactBaselineY

        val dz =
            z - impactBaselineZ


        val deltaMagnitude =
            sqrt(
                (
                        dx * dx +
                                dy * dy +
                                dz * dz
                        ).toDouble()
            ).toFloat()


        // Slowly follow the normal operating state.
        impactBaselineX +=
            IMPACT_BASELINE_ALPHA * dx

        impactBaselineY +=
            IMPACT_BASELINE_ALPHA * dy

        impactBaselineZ +=
            IMPACT_BASELINE_ALPHA * dz


        val baselineMagnitude =
            sqrt(
                (
                        impactBaselineX *
                                impactBaselineX +

                                impactBaselineY *
                                impactBaselineY +

                                impactBaselineZ *
                                impactBaselineZ
                        ).toDouble()
            ).toFloat()


        /*
         * Threshold adapts to machine magnitude,
         * but never becomes smaller than 0.75 m/s².
         */
        val relativeThreshold =
            baselineMagnitude * 0.12f

        val threshold =
            maxOf(
                IMPACT_MIN_DELTA_MS2,
                relativeThreshold
            )


        if (
            deltaMagnitude >= threshold &&
            ts - lastImpactMs >= IMPACT_COOLDOWN_MS
        ) {

            lastImpactMs = ts

            impactInCurrentWindow = true

            sessionShockCount++

            val axisContribution =
                when {
                    abs(dx) >= abs(dy) && abs(dx) >= abs(dz) -> Axis.X
                    abs(dy) >= abs(dz) -> Axis.Y
                    else -> Axis.Z
                }

            onImpact(
                ImpactEvent(
                    magnitude = deltaMagnitude,
                    timestampMs = ts,
                    axisContribution = axisContribution,
                    baselineMagnitude = baselineMagnitude,
                    thresholdUsed = threshold,
                    cooldownMs = IMPACT_COOLDOWN_MS
                )
            )
        }
    }


    /**
     * Runtime application version lookup.
     *
     * Deliberately avoids BuildConfig so the engine is not coupled to
     * AGP's generated BuildConfig class.
     */
    private fun getAppVersionString(): String {
        return try {
            val packageInfo =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    0
                )

            val versionName =
                packageInfo.versionName ?: "unknown"

            val versionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toString()
                }

            "$versionName ($versionCode)"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** RFC 4180-style field quoting for local measurement CSV records. */
    private fun csvField(value: Any?): String {
        val text = when (value) {
            null -> ""
            is Float -> if (value.isFinite()) value.toString() else ""
            is Double -> if (value.isFinite()) value.toString() else ""
            else -> value.toString()
        }
        val escaped = text.replace("\"", "\"\"")
        return if (text.any { it == ',' || it == '\"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun sanitizeForFilename(
        s: String
    ): String {

        val trimmed =
            s.trim()

        val base =
            if (trimmed.isEmpty())
                "UNSPECIFIED"
            else
                trimmed

        return base.replace(
            Regex("[^A-Za-z0-9_-]"),
            "_"
        )
    }


    private fun emitDiagnostic(message: String) {
        diagnosticRepository.append(message)
        uiLogger(message)
    }


    /**
     * Best-effort copy into public Downloads.
     * Never blocks core logging if publishing fails.
     */
    private fun publishToMediaStore(
        file: File,
        mimeType: String
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            emitDiagnostic(
                "[INFO] Downloads publish needs Android 10+; " +
                        "file stays at ${file.absolutePath}"
            )

            return
        }


        try {

            val resolver =
                context.contentResolver


            val values =
                ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        file.name
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        mimeType
                    )

                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/SensorMax_Master_Logs"
                    )
                }


            val uri =
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )


            if (uri != null) {

                resolver.openOutputStream(
                    uri
                )?.use { out ->

                    file.inputStream().use { input ->

                        input.copyTo(out)
                    }
                }

                emitDiagnostic(
                    "[ENGINE] Published " +
                            "Downloads/SensorMax_Master_Logs/" +
                            file.name
                )

            } else {

                emitDiagnostic(
                    "[WARN] MediaStore insert returned null " +
                            "for ${file.name}"
                )
            }

        } catch (e: Exception) {

            emitDiagnostic(
                "[WARN] MediaStore publish skipped: " +
                        e.message
            )
        }
    }
}
