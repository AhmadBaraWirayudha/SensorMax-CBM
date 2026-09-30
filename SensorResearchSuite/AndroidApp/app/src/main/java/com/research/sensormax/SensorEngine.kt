package com.research.sensormax

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
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

class SensorEngine(
    private val context: Context,
    private val streamClient: StreamClient,
    private val logger: (String) -> Unit,
    private val onSensorSample: (Int, FloatArray, Long) -> Unit,
    private val onAnalysis: (AnalysisUpdate) -> Unit = {},
    private val onImpact: (ImpactEvent) -> Unit = {}
) : SensorEventListener {

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
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    private var isRunning = false

    @Volatile
    private var isNetworkEnabled = false

    @Volatile
    private var isDiskLoggingEnabled = false

    private var rawWriter: BufferedWriter? = null
    private var analysisWriter: BufferedWriter? = null
    private var rawFile: File? = null
    private var analysisFile: File? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
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

    private val activeSensors = mutableSetOf<Sensor>()

    // Requested capture interval.
    private var captureIntervalMs = 20L

    // Track each actual Sensor independently.
    private val lastCaptureTime = mutableMapOf<Sensor, Long>()

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
    private var sessionStartMs: Long = 0L
    private var sessionWindowCount: Long = 0L

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
    var mountingMethod: String = "Unknown"

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


    fun startDeployment(
        sensors: Set<Sensor>,
        intervalMs: Long,
        enableNetwork: Boolean,
        enableDisk: Boolean
    ) {
        if (isRunning) return

        activeSensors.clear()
        activeSensors.addAll(sensors)

        lastCaptureTime.clear()

        captureIntervalMs = intervalMs
        isNetworkEnabled = enableNetwork
        isDiskLoggingEnabled = enableDisk

        // Prefer the physical/raw accelerometer for condition monitoring.
        // Fall back to linear acceleration if the device has no accelerometer.
        vibrationSource = resolveAccelSource(activeSensors)

        // Reset analysis state.
        vibrationAnalyzer.reset()
        lastResult = null
        lastQualityScore = null

        sessionId = "SESSION-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        sessionStartMs = System.currentTimeMillis()
        sessionWindowCount = 0L

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

        if (vibrationSource == null) {
            logger(
                "[WARN] No usable accelerometer selected -- " +
                        "spectrum analysis disabled this session."
            )
        } else {
            logger(
                "[ENGINE] Vibration analysis source: " +
                        "${vibrationSource!!.name} " +
                        "(${vibrationSource!!.vendor})"
            )
        }

        // Dedicated sensor thread.
        sensorThread =
            HandlerThread("CbM_HardwareWorker").apply {
                start()
            }

        sensorHandler = Handler(sensorThread!!.looper)

        // Partial wake lock for long measurement sessions.
        //
        // TODO(SensorMax P1, design-debt Section 51): a wake lock keeps the
        // CPU on but not this Activity's process priority -- Android can
        // still kill or heavily throttle it in the background. Long/screen-
        // off deployments should eventually move this engine into a
        // foreground Service with a visible notification; deferred for now
        // since it needs its own manifest/permission/notification-channel
        // work, not a same-file change.
        try {
            val pm =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            wakeLock =
                pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "com.research.sensormax:CbmDeployment"
                )

            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

            logger(
                "[ENGINE] Partial wake lock held -- " +
                        "CPU stays awake through screen-off during deployment."
            )
        } catch (e: Exception) {
            logger(
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

                logger(
                    "[FATAL] Storage Error: external storage unavailable."
                )

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

                    rawWriter =
                        BufferedWriter(
                            FileWriter(newRawFile, true)
                        ).apply {

                            write(
                                "Timestamp_ms," +
                                        "Machine_ID," +
                                        "Point," +
                                        "Sensor_Type," +
                                        "Sensor_Name," +
                                        "Val_0," +
                                        "Val_1," +
                                        "Val_2," +
                                        "Val_3," +
                                        "Val_4," +
                                        "Val_5\n"
                            )
                        }

                    rawFile = newRawFile

                    // ANALYSIS CSV
                    val newAnalysisFile =
                        File(
                            baseDir,
                            "${deviceTag}_${mode}_${tag}_${stamp}_analysis.csv"
                        )

                    analysisWriter =
                        BufferedWriter(
                            FileWriter(
                                newAnalysisFile,
                                true
                            )
                        ).apply {

                            write(
                                "Timestamp_ms," +
                                        "Machine_ID," +
                                        "Point," +
                                        "Sample_Rate_Hz," +
                                        "RMS_X_ms2," +
                                        "RMS_Y_ms2," +
                                        "RMS_Z_ms2," +
                                        "Overall_RMS_Accel_ms2," +
                                        "Overall_RMS_Velocity_mms," +
                                        "Overall_RMS_Displacement_um," +
                                        "Dominant_Axis," +
                                        "Dominant_Freq_Hz," +
                                        "Dominant_Accel_Amplitude_ms2," +
                                        "Envelope_Peak_Freq_Hz," +
                                        "ISO20816_Zone," +
                                        "Bearing_Match," +
                                        "RPM_Input," +
                                        "Impact_Event," +
                                        "Snapshot_Triggered\n"
                            )
                        }

                    analysisFile = newAnalysisFile

                    logger(
                        "[ENGINE] CSV pair initialized: " +
                                "${newRawFile.name} + " +
                                "${newAnalysisFile.name}"
                    )

                    logger(
                        "[ENGINE] App-private external storage -- " +
                                "copy publishes to Downloads/SensorMax_Master_Logs " +
                                "when deployment stops."
                    )

                } catch (e: Exception) {

                    logger(
                        "[FATAL] Storage Error: ${e.message}"
                    )
                }
            }
        }

        // Tell Android to deliver events as quickly as the hardware/API allows.
        // We perform our own capture throttling using event.timestamp.
        isRunning = true

        for (sensor in activeSensors) {

            try {

                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    sensorHandler
                )

                logger(
                    "[ENGINE] Hardware node locked: " +
                            "${sensor.name} | " +
                            "${sensor.vendor}"
                )

            } catch (e: Exception) {

                logger(
                    "[WARN] Failed to register sensor " +
                            "${sensor.name}: ${e.message}"
                )
            }
        }
    }


    override fun onSensorChanged(event: SensorEvent) {

        if (!isRunning) return

        if (event.values.isEmpty()) return

        /*
         * Android sensor timestamp:
         * monotonic time from device boot.
         *
         * Use this for sampling/FFT timing.
         */
        val sensorTs =
            event.timestamp / 1_000_000L

        /*
         * Human-readable wall-clock timestamp.
         * Use this for exported CSV/network records.
         */
        val wallClockTs =
            System.currentTimeMillis()

        val sensor =
            event.sensor

        val type =
            sensor.type


        // =========================================================
        // 1. IMPACT DETECTOR
        //
        // IMPORTANT:
        // Run BEFORE capture throttling.
        //
        // A mechanical shock can be extremely short. If the
        // capture throttle runs first, that shock may be discarded.
        // =========================================================

        if (
            sensor == vibrationSource &&
            event.values.size >= 3
        ) {

            vibrationRawEventCount++

            updateImpactDetector(
                event.values[0],
                event.values[1],
                event.values[2],
                sensorTs
            )
        }


        // =========================================================
        // 2. CAPTURE THROTTLING
        // =========================================================

        val lastTs =
            lastCaptureTime[sensor] ?: 0L

        if (
            sensorTs - lastTs <
            captureIntervalMs
        ) {
            return
        }

        lastCaptureTime[sensor] =
            sensorTs


        // =========================================================
        // 3. NORMALIZE SENSOR ARRAY
        // =========================================================

        val normalizedValues =
            FloatArray(6) { 0.0f }

        for (i in event.values.indices) {

            if (i < 6) {
                normalizedValues[i] =
                    event.values[i]
            }
        }

        sampleCounter++


        // =========================================================
        // 4. RAW DISK LOGGING
        // =========================================================

        if (isDiskLoggingEnabled) {

            try {

                rawWriter?.write(
                    "$wallClockTs," +
                            "$machineId," +
                            "$pointTag," +
                            "$type," +
                            "${sensor.name}," +
                            "${normalizedValues[0]}," +
                            "${normalizedValues[1]}," +
                            "${normalizedValues[2]}," +
                            "${normalizedValues[3]}," +
                            "${normalizedValues[4]}," +
                            "${normalizedValues[5]}\n"
                )

                if (
                    sampleCounter % 200L ==
                    0L
                ) {
                    rawWriter?.flush()
                }

            } catch (_: Exception) {
                // Keep the sensor loop alive if disk writing fails.
            }
        }


        // =========================================================
        // 5. NETWORK STREAMING
        // =========================================================

        if (isNetworkEnabled) {

            try {

                streamClient.streamSensorData(
                    wallClockTs,
                    type,
                    sensor.name,
                    normalizedValues
                )

            } catch (e: Exception) {

                logger(
                    "[WARN] Sensor network stream failed: " +
                            e.message
                )
            }
        }


        // =========================================================
        // 6. GUI DASHBOARD
        // =========================================================

        onSensorSample(
            type,
            event.values,
            sensorTs
        )


        // =========================================================
        // 7. VIBRATION / FFT ANALYSIS
        //
        // ONLY the selected physical vibration sensor.
        // =========================================================

        if (
            sensor == vibrationSource &&
            event.values.size >= 3
        ) {

            vibrationAcceptedEventCount++

            processVibrationSample(
                sensorTs,
                event.values
            )
        }
    }


    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // Intentionally unused.
    }


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

        if (isNetworkEnabled) {

            streamAnalysis(
                ts,
                result
            )
        }

        logger(
            "[ENGINE] Snapshot captured for " +
                    "$machineId / $pointTag."
        )

        return true
    }


    fun stopDeployment() {

        if (!isRunning) {
            return
        }

        isRunning = false

        try {

            sensorManager.unregisterListener(
                this
            )

            if (isDiskLoggingEnabled) {

                rawWriter?.flush()
                rawWriter?.close()
                rawWriter = null

                analysisWriter?.flush()
                analysisWriter?.close()
                analysisWriter = null

                rawFile?.let {
                    publishToMediaStore(
                        it,
                        "text/csv"
                    )
                }

                analysisFile?.let {
                    publishToMediaStore(
                        it,
                        "text/csv"
                    )
                }
            }

            logger(
                "[ENGINE] Array disengaged. " +
                        "Captured $sampleCounter optimized points."
            )

            logger(
                "[ENGINE] Vibration stream: " +
                        "$vibrationAcceptedEventCount/$vibrationRawEventCount " +
                        "events used for FFT after throttling."
            )

            logSessionRecord()
            logRepeatability()

        } catch (_: Exception) {
        }


        try {

            wakeLock?.let {

                if (it.isHeld) {
                    it.release()
                }
            }

        } catch (_: Exception) {
        }

        wakeLock = null


        sensorThread?.quitSafely()

        sensorThread = null
        sensorHandler = null

        vibrationSource = null

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

        logger(
            "[SESSION] $sessionId  |  Asset: $machineId  |  Point: $pointTag  |  " +
                    "RPM hint: $rpmHint  |  Duration: ${String.format(Locale.US, "%.1f", durationSec)}s  |  " +
                    "Windows: $sessionWindowCount  |  Actual Fs: $fsText  |  FFT: $FFT_WINDOW_SIZE  |  " +
                    "Quality: $qualityText  |  Result: $verdict"
        )

        logger(
            "[SESSION] Mounting: $mountingMethod  |  Axis roles: X=$axisRoleX, Y=$axisRoleY, Z=$axisRoleZ  |  " +
                    "Mounting check: $mountingText  |  Calibration: $calibrationText"
        )
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

        logger(
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

            val result = MountingCheck.evaluate(
                xs.toFloatArray(),
                ys.toFloatArray(),
                zs.toFloatArray()
            )
            lastMountingCheck = result
            onResult(result)
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
        val ts = mutableListOf<Long>()

        val calThread = HandlerThread("CalibrationThread").apply { start() }
        val calHandler = Handler(calThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size >= 3) {
                    xs.add(event.values[0])
                    ys.add(event.values[1])
                    zs.add(event.values[2])
                    ts.add(event.timestamp / 1_000_000L)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Intentionally unused.
            }
        }

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

            val stats = CalibrationCheck.evaluate(
                xs.toFloatArray(),
                ys.toFloatArray(),
                zs.toFloatArray(),
                ts.toLongArray()
            )
            if (stats == null) {
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
                notes = ""
            )

            lastCalibration = record
            onResult(record)
        }, durationMs)
    }


    /**
     * Sends vibration data into the FFT analyzer.
     *
     * The timestamp comes from SensorEvent.timestamp,
     * so sample-rate estimation is based on actual sensor timing.
     */
    private fun processVibrationSample(
        ts: Long,
        values: FloatArray
    ) {

        if (values.size < 3) {
            return
        }

        val result =
            vibrationAnalyzer.addSample(
                ts,
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
        val validCalibration = lastCalibration?.takeIf { it.sensorName == vibrationSource?.name }

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
                mountingCheck = lastMountingCheck,
                calibration = validCalibration
            )

        lastResult = result
        lastQualityScore = quality


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


        onAnalysis(
            AnalysisUpdate(
                spectrum = result,
                quality = quality
            )
        )

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

            analysisWriter?.write(

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
                analysisWriter?.flush()
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

            logger(
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

                logger(
                    "[ENGINE] Published " +
                            "Downloads/SensorMax_Master_Logs/" +
                            file.name
                )

            } else {

                logger(
                    "[WARN] MediaStore insert returned null " +
                            "for ${file.name}"
                )
            }

        } catch (e: Exception) {

            logger(
                "[WARN] MediaStore publish skipped: " +
                        e.message
            )
        }
    }
}