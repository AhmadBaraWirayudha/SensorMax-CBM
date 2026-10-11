package com.research.sensormax

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.exp
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var sensorEngine: SensorEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var deploymentController: MeasurementDeploymentController

    // Real MPAndroidChart views from activity_main.xml
    private lateinit var vibrationChart: SensorMaxLineChart
    private lateinit var spectrumChart: SensorMaxLineChart
    private lateinit var trendChart: SensorMaxLineChart
    private lateinit var envelopeChart: SensorMaxLineChart

    // Real custom view from activity_main.xml
    private lateinit var waterfallView: WaterfallView

    private var isDeployed = false

    private val uiHandler =
        Handler(Looper.getMainLooper())

    private var lastHealthIntegrity = ""

    private var pendingProfileTranslation: SensorMaxExperimentFile.Definition? = null

    private val experimentProfileFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importExperimentProfile(uri)
        }

    private val translatedProfileZipPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val definition = pendingProfileTranslation
            pendingProfileTranslation = null
            if (uri != null && definition != null) writeTranslatedProfileZip(uri, definition)
        }

    private var pendingBleStart: (() -> Unit)? = null
    private var pendingAudioStart: (() -> Unit)? = null
    private var pendingLocationStart: (() -> Unit)? = null
    private var pendingCameraStart: (() -> Unit)? = null
    private var pendingDepthStart: (() -> Unit)? = null

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it } && BleGattPermissions.hasRequiredPermissions(this)
            val action = pendingBleStart
            pendingBleStart = null
            if (granted && action != null) {
                action.invoke()
            } else if (!granted) {
                appendLog("[BLE] Required Bluetooth permissions were not granted.")
                Toast.makeText(
                    this,
                    getString(R.string.permission_bluetooth_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAudioStart
            pendingAudioStart = null
            if (granted && action != null) {
                action.invoke()
            } else if (!granted) {
                appendLog("[AUDIO] Microphone permission was not granted.")
                Toast.makeText(
                    this,
                    getString(R.string.permission_microphone_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it } && LocationAcquisitionPermissions.hasRequiredPermission(this)
            val action = pendingLocationStart
            pendingLocationStart = null
            if (granted && action != null) {
                if (LocationAcquisitionPermissions.isLocationEnabled(this)) {
                    action.invoke()
                } else {
                    appendLog("[LOCATION] Location services are disabled.")
                    Toast.makeText(
                        this,
                        getString(R.string.location_services_enable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (!granted) {
                appendLog("[LOCATION] Location permission was not granted.")
                Toast.makeText(
                    this,
                    getString(R.string.permission_location_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingCameraStart
            pendingCameraStart = null
            if (granted && action != null) {
                action.invoke()
            } else if (!granted) {
                appendLog("[CAMERA] Camera permission was not granted.")
                Toast.makeText(
                    this,
                    getString(R.string.permission_camera_photometric_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    private val depthPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDepthStart
            pendingDepthStart = null
            if (granted && action != null) {
                action.invoke()
            } else if (!granted) {
                appendLog("[DEPTH] Camera permission was not granted.")
                Toast.makeText(
                    this,
                    getString(R.string.permission_camera_depth_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val captureObserver = object : SensorCaptureService.Observer {
        override fun onLog(message: String) {
            appendLog(message)
        }

        override fun onSensorSample(sample: AcquisitionSample) {
            routeDataToDashboard(sample)
        }

        override fun onAnalysis(update: AnalysisUpdate) {
            routeAnalysisToDashboard(update)
        }

        override fun onImpact(event: ImpactEvent) {
            routeImpactToDashboard(event)
        }

        override fun onSessionState(
            session: RecordingSession,
            history: List<AnalysisUpdate>
        ) {
            restoreEngineSessionState(session, history)
        }

        override fun onTelemetryState(samples: List<SensorTelemetrySample>) {
            samples.forEach {
                queueSensorTelemetry(
                    AcquisitionSample(
                        sourceId = it.sourceId,
                        sourceKind = it.sourceKind,
                        channelId = it.channelId.ifBlank { "${it.type}|${it.vendor}|${it.name}" },
                        name = it.name,
                        vendor = it.vendor,
                        timestampNs = it.timestampMs * 1_000_000L,
                        wallClockTimestampMs = it.timestampMs,
                        values = it.values.copyOf(),
                        androidSensorType = it.type.takeIf { value -> value >= 0 }
                    )
                )
            }
        }

        override fun onHealthState(snapshot: AcquisitionHealthSnapshot) {
            if (snapshot.acquisitionIntegrity != lastHealthIntegrity) {
                lastHealthIntegrity = snapshot.acquisitionIntegrity
                appendLog(
                    "[HEALTH] ${snapshot.acquisitionIntegrity} · " +
                            "Fs ${String.format(java.util.Locale.US, "%.1f", snapshot.effectiveVibrationRateHz)} Hz · " +
                            "gap ${String.format(java.util.Locale.US, "%.1f", snapshot.maxVibrationGapMs)} ms"
                )
            }
        }
    }

    private val deploymentCallbacks = object : MeasurementDeploymentController.Callbacks {
        override fun onServiceReady(engine: SensorEngine) {
            sensorEngine = engine
            isDeployed = engine.isDeploymentRunning

            // The language selector recreates the Activity. Reapply localized notification copy
            // and render the true service state so the UI cannot look idle while acquisition runs.
            deploymentController.refreshNotificationLocale(
                title = getString(R.string.notification_title),
                channelName = getString(R.string.notification_channel_name),
                channelDescription = getString(R.string.notification_channel_description),
                ready = getString(R.string.notification_ready_summary),
                measurementActiveStream = getString(R.string.notification_measurement_active_stream),
                measurementActiveOffline = getString(R.string.notification_measurement_active_offline),
                streamingActive = getString(R.string.notification_streaming_active),
                offlineActive = getString(R.string.notification_offline_active)
            )

            if (isDeployed && ::tvStatus.isInitialized && ::btnStreamAndLog.isInitialized) {
                appendLog("[SERVICE] Reattached to active measurement session.")
                restoreActiveIntervalFromEngine(engine)
                toggleUIState(isStreaming = deploymentController.isStreamingDeployment)
            } else if (::tvStatus.isInitialized) {
                resetDeploymentUiState(stopped = false)
            }
        }

        override fun onServiceLost() {
            appendLog("[SERVICE] Measurement service disconnected.")
        }

        override fun onDeploymentStateChanged(running: Boolean) {
            isDeployed = running
        }

        override fun onControllerLog(message: String) {
            appendLog(message)
        }
    }

    // ============================================================
    // CORE STATUS / CONTROLS
    // ============================================================

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private lateinit var btnStreamAndLog: Button
    private lateinit var btnOfflineLog: Button
    private lateinit var btnSnapshot: Button
    private lateinit var btnImportExperimentProfile: Button

    private var lastImportedExperimentProfile: SensorMaxExperimentFile.Definition? = null

    private lateinit var etTargetIp: EditText
    private lateinit var etTargetPort: EditText

    private lateinit var llSensorList: LinearLayout
    private lateinit var spinnerAcquisitionSource: Spinner
    private lateinit var llBleAcquisitionConfig: LinearLayout
    private lateinit var llCameraAcquisitionConfig: LinearLayout
    private lateinit var tvCameraAcquisitionNote: TextView
    private lateinit var llDepthAcquisitionConfig: LinearLayout
    private lateinit var tvDepthAcquisitionNote: TextView
    private lateinit var llNetworkAcquisitionConfig: LinearLayout
    private lateinit var etNetworkListenPort: EditText
    private lateinit var tvNetworkAcquisitionNote: TextView
    private lateinit var cbNetworkAllowLan: CheckBox
    private lateinit var spinnerLanguagePreference: Spinner
    private lateinit var spinnerMeasurementSystem: Spinner
    private lateinit var tvWarnThresholdLabel: TextView
    private lateinit var tvAlertThresholdLabel: TextView
    private lateinit var tvWaveformNote: TextView
    private lateinit var tvTrendUnitLabel: TextView
    private lateinit var spinnerUsbOtgDevices: Spinner
    private lateinit var btnRefreshUsbOtg: Button
    private lateinit var btnReadUsbOtg: Button
    private lateinit var btnStopUsbOtg: Button
    private lateinit var tvUsbOtgStatus: TextView
    private lateinit var tvUsbOtgRawData: TextView
    private val usbOtgDevices = mutableListOf<UsbDevice>()
    private var usbRawReader: UsbOtgRawBulkReader? = null
    private var usbPermissionReceiverRegistered = false
    private var pendingUsbOtgDevice: UsbDevice? = null
    private val usbRawLog = StringBuilder()
    private val usbPermissionAction: String by lazy { "$packageName.SENSORMAX_USB_PERMISSION" }
    private var measurementSystem: MeasurementSystem = MeasurementSystem.METRIC
    private var ignorePreferenceEvents = true
    private var suppressInputWatcherActions = false
    private var endpointBuildRejected = false
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            val device = usbDeviceFromIntent(intent) ?: pendingUsbOtgDevice
            pendingUsbOtgDevice = null
            if (device == null) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                startUsbRawReader(device)
            } else {
                tvUsbOtgStatus.text = getString(R.string.otg_permission_denied)
            }
        }
    }
    private lateinit var spinnerBleProfile: Spinner
    private lateinit var etBleDeviceAddress: EditText
    private lateinit var tvBleProfileNote: TextView

    private var acquisitionSourceMode = AcquisitionSourceKind.ANDROID_SENSOR

    // ============================================================
    // CAPTURE RATE
    // ============================================================

    private lateinit var seekCaptureRate: SeekBar
    private lateinit var tvCaptureRate: TextView

    private var targetIntervalMs = 20L

    // ============================================================
    // SECONDARY SENSOR TELEMETRY
    // ============================================================

    private lateinit var tvMagData: TextView
    private lateinit var tvLightData: TextView
    private lateinit var tvGravityData: TextView
    private lateinit var tvActivityData: TextView
    private lateinit var gridSecondaryTelemetry: TableLayout
    private val telemetryValueViews = mutableMapOf<String, TextView>()
    private val pendingTelemetry = mutableMapOf<String, SensorTelemetrySample>()
    private var telemetryUiScheduled = false

    private companion object {
        const val TELEMETRY_UI_INTERVAL_MS = 100L
        const val MAX_DIAGNOSTIC_LOG_LINES = 30
    }

    // ============================================================
    // CBM ANALYSIS UI
    // ============================================================

    private lateinit var tvSeverityBanner: TextView
    private lateinit var tvOverallRms: TextView
    private lateinit var tvSampleRate: TextView
    private lateinit var tvQualityScore: TextView

    private lateinit var rgAxisSelect: RadioGroup

    private lateinit var etRpm: EditText
    private lateinit var tvDominantFreq: TextView
    private lateinit var tvFaultHint: TextView

    // Detail lines under the summary values (filled together with the values)
    private lateinit var tvOverallRmsDetail: TextView
    private lateinit var tvQualityDetail: TextView
    private lateinit var tvSampleRateDetail: TextView
    private lateinit var tvDominantFreqDetail: TextView
    private lateinit var tvSamplingDiagnostics: TextView
    private lateinit var tvSensorMatrixLabel: TextView

    // Scrolling body of the screen (foldable cards are its children)
    private lateinit var scrollDiagnosticBody: android.widget.ScrollView

    // ============================================================
    // ASSET / TAGGING
    // ============================================================

    private lateinit var etMachineId: EditText
    private lateinit var etCustomTagLabel: EditText
    private lateinit var etPointTag: EditText

    private lateinit var btnAddTag: Button
    private lateinit var btnDeleteTag: Button

    private lateinit var spinnerSavedTags: Spinner

    private lateinit var assetPointRepository: AssetPointRepository

    private val savedPoints =
        mutableListOf<AssetPoint>()

    private lateinit var tagAdapter: ArrayAdapter<String>

    // ============================================================
    // ISO / MOUNTING / AXIS ROLES
    // ============================================================

    private lateinit var spinnerIsoClass: Spinner
    private lateinit var spinnerMountingMethod: Spinner

    private lateinit var spinnerAxisRoleX: Spinner
    private lateinit var spinnerAxisRoleY: Spinner
    private lateinit var spinnerAxisRoleZ: Spinner

    private lateinit var btnCheckMounting: Button
    private lateinit var tvMountingCheck: TextView

    // ============================================================
    // CALIBRATION
    // ============================================================

    private lateinit var etCalibrationOperator: EditText
    private lateinit var btnRunCalibration: Button
    private lateinit var tvCalibrationResult: TextView

    // ============================================================
    // COLLAPSIBLE SECTIONS
    // ============================================================

    private lateinit var tvBearingGeometryHeader: TextView
    private lateinit var llBearingGeometryInputs: View

    private lateinit var tvSecondaryTelemetryHeader: TextView

    // ============================================================
    // THRESHOLDS
    // ============================================================

    private lateinit var etWarnThreshold: EditText
    private lateinit var etAlertThreshold: EditText

    // ============================================================
    // BEARING GEOMETRY
    // ============================================================

    private lateinit var etBearingElements: EditText
    private lateinit var etBearingBallDiameter: EditText
    private lateinit var etBearingPitchDiameter: EditText
    private lateinit var etBearingContactAngle: EditText

    private lateinit var tvBearingHint: TextView

    // ============================================================
    // SENSOR SELECTION
    // ============================================================

    private val selectedSensors =
        mutableSetOf<Sensor>()

    private val sensorCheckBoxes = mutableMapOf<Sensor, CheckBox>()

    // ============================================================
    // LIVE WAVEFORM DISPLAY
    // ============================================================

    private var chartStartTimeMs = -1L

    // Keeps the live waveform visually continuous across stop/restart cycles.
    // A new recording starts after the previous visible trace rather than
    // jumping back to X=0 and making the old trace appear to vanish.
    private var chartSessionOffsetSec = 0f

    /**
     * Visible time span of live waveform.
     *
     * Display only.
     * Does not modify acquisition or FFT data.
     */
    private val LIVE_WAVEFORM_SECONDS = 14.0f

    /**
     * Fixed vertical display range.
     *
     * Display only.
     * Raw sensor values remain untouched.
     */
    private val WAVEFORM_Y_LIMIT_MS2 = 15f

    /**
     * Display-only low-pass filter cutoff.
     */
    private val LIVE_FILTER_CUTOFF_HZ = 10f

    /**
     * GUI redraw throttle.
     */
    private var lastChartRedrawTime = 0L

    private val CHART_REFRESH_RATE_MS = 33L

    // ============================================================
    // DISPLAY-ONLY LOW-PASS FILTER
    // ============================================================

    private val liveWaveformFilter =
        LiveWaveformFilter(
            LIVE_FILTER_CUTOFF_HZ
        )

    // ============================================================
    // SPECTRUM STATE
    // ============================================================

    private var axisView =
        AxisView.AUTO

    private var lastSpectrumResult:
            SpectrumResult? = null
    private var lastQualityScore: QualityScore? = null

    private var lastMeasuredHz =
        0.0

    private var analysisWindowUiCount =
        0L

    private enum class AxisView {
        AUTO,
        X,
        Y,
        Z,
        RSS
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        installSplashScreen()

        super.onCreate(
            savedInstanceState
        )

        SensorMaxPreferences.applySavedLanguage(this)
        measurementSystem = SensorMaxPreferences.units(this)

        setContentView(
            R.layout.activity_main
        )

        deploymentController = MeasurementDeploymentController(
            context = this,
            observer = captureObserver,
            callbacks = deploymentCallbacks
        )

        initializeViews()
        setupDisplayPreferences()
        setupUsbOtgControls()

        setupRealTimeChart()
        setupSpectrumChart()
        setupEnvelopeChart()
        setupTrendChart()
        setupThrottlerUI()

        sensorManager =
            getSystemService(
                Context.SENSOR_SERVICE
            ) as SensorManager

        setupIsoSpinner()
        setupMountingSpinners()
        setupCollapsibleSections()
        setupCardAccordions()
        setupCalibrationButton()
        setupAxisSelector()
        setupTagAndThresholdWatchers()
        applyMeasurementUnitLabels()
        cbNetworkAllowLan.setOnCheckedChangeListener { _, _ -> refreshNetworkExposureNote() }
        refreshNetworkExposureNote()

        assetPointRepository =
            AssetPointRepository(this)

        setupTagManager()

        scanAndDisplayHardwareSensors()
        setupAcquisitionSourceControls()

        configureDeploymentButtons()
        configureSnapshotButton()
        configureExperimentProfileImport()

        appendLog(
            "[INIT] Multi-Modal CbM Matrix Online."
        )

        appendLog(
            "[INIT] SensorMax"
        )

        appendLog(
            "[INIT] Device: " +
                    "${Build.MANUFACTURER} ${Build.MODEL}"
        )

        appendLog(
            "[INIT] Android: " +
                    "${Build.VERSION.RELEASE} | " +
                    "API ${Build.VERSION.SDK_INT}"
        )

        appendLog(
            "[INIT] Live waveform: raw accelerometer -> " +
                    "${LIVE_FILTER_CUTOFF_HZ} Hz LPF -> " +
                    "${LIVE_WAVEFORM_SECONDS}s display -> " +
                    "±${WAVEFORM_Y_LIMIT_MS2} m/s²"
        )
        appendLog("[INIT] Generic telemetry: all selected sensors.")

        refreshSeverityBanner()
    }

    override fun onStart() {
        super.onStart()
        deploymentController.onStart()
    }

    override fun onStop() {
        deploymentController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        usbRawReader?.close()
        usbRawReader = null
        if (usbPermissionReceiverRegistered) {
            runCatching { unregisterReceiver(usbPermissionReceiver) }
            usbPermissionReceiverRegistered = false
        }
        uiHandler.removeCallbacksAndMessages(null)
        synchronized(pendingTelemetry) {
            pendingTelemetry.clear()
            telemetryUiScheduled = false
        }
        super.onDestroy()
    }

    // ============================================================
    // VIEW INITIALIZATION
    // ============================================================

    private fun initializeViews() {

        tvStatus =
            findViewById(R.id.tvStatus)

        tvLog =
            findViewById(R.id.tvLog)

        tvLog.movementMethod =
            ScrollingMovementMethod()

        btnStreamAndLog =
            findViewById(
                R.id.btnStreamAndLog
            )

        btnOfflineLog =
            findViewById(
                R.id.btnOfflineLog
            )

        btnSnapshot =
            findViewById(
                R.id.btnSnapshot
            )

        etTargetIp =
            findViewById(
                R.id.etTargetIp
            )

        etTargetPort =
            findViewById(
                R.id.etTargetPort
            )

        llSensorList =
            findViewById(
                R.id.llSensorList
            )

        spinnerAcquisitionSource =
            findViewById(R.id.spinnerAcquisitionSource)

        llBleAcquisitionConfig =
            findViewById(R.id.llBleAcquisitionConfig)

        llCameraAcquisitionConfig =
            findViewById(R.id.llCameraAcquisitionConfig)

        tvCameraAcquisitionNote =
            findViewById(R.id.tvCameraAcquisitionNote)
        llDepthAcquisitionConfig =
            findViewById(R.id.llDepthAcquisitionConfig)
        tvDepthAcquisitionNote =
            findViewById(R.id.tvDepthAcquisitionNote)

        llNetworkAcquisitionConfig =
            findViewById(R.id.llNetworkAcquisitionConfig)

        etNetworkListenPort =
            findViewById(R.id.etNetworkListenPort)

        tvNetworkAcquisitionNote =
            findViewById(R.id.tvNetworkAcquisitionNote)
        cbNetworkAllowLan = findViewById(R.id.cbNetworkAllowLan)
        spinnerLanguagePreference = findViewById(R.id.spinnerLanguagePreference)
        spinnerMeasurementSystem = findViewById(R.id.spinnerMeasurementSystem)
        tvWarnThresholdLabel = findViewById(R.id.tvWarnThresholdLabel)
        tvAlertThresholdLabel = findViewById(R.id.tvAlertThresholdLabel)
        tvWaveformNote = findViewById(R.id.tvWaveformNote)
        tvTrendUnitLabel = findViewById(R.id.tvTrendUnitLabel)
        spinnerUsbOtgDevices = findViewById(R.id.spinnerUsbOtgDevices)
        btnRefreshUsbOtg = findViewById(R.id.btnRefreshUsbOtg)
        btnReadUsbOtg = findViewById(R.id.btnReadUsbOtg)
        btnStopUsbOtg = findViewById(R.id.btnStopUsbOtg)
        tvUsbOtgStatus = findViewById(R.id.tvUsbOtgStatus)
        tvUsbOtgRawData = findViewById(R.id.tvUsbOtgRawData)

        spinnerBleProfile =
            findViewById(R.id.spinnerBleProfile)

        etBleDeviceAddress =
            findViewById(R.id.etBleDeviceAddress)

        tvBleProfileNote =
            findViewById(R.id.tvBleProfileNote)

        // REAL MPANDROIDCHART VIEWS
        vibrationChart =
            findViewById(
                R.id.vibrationChart
            )

        spectrumChart =
            findViewById(
                R.id.spectrumChart
            )

        trendChart =
            findViewById(
                R.id.trendChart
            )

        envelopeChart =
            findViewById(
                R.id.envelopeChart
            )

        // REAL CUSTOM VIEW
        waterfallView =
            findViewById(
                R.id.waterfallView
            )

        tvMagData =
            findViewById(
                R.id.tvMagData
            )

        tvLightData =
            findViewById(
                R.id.tvLightData
            )

        tvGravityData =
            findViewById(
                R.id.tvGravityData
            )

        tvActivityData =
            findViewById(
                R.id.tvActivityData
            )

        gridSecondaryTelemetry =
            findViewById(R.id.gridSecondaryTelemetry)
        gridSecondaryTelemetry.visibility = View.VISIBLE

        tvOverallRmsDetail = findViewById(R.id.tvOverallRmsDetail)
        tvQualityDetail = findViewById(R.id.tvQualityDetail)
        tvSampleRateDetail = findViewById(R.id.tvSampleRateDetail)
        tvDominantFreqDetail = findViewById(R.id.tvDominantFreqDetail)
        tvSamplingDiagnostics = findViewById(R.id.tvSamplingDiagnostics)
        tvSensorMatrixLabel = findViewById(R.id.tvSensorMatrixLabel)
        scrollDiagnosticBody = findViewById(R.id.scrollDiagnosticBody)

        seekCaptureRate =
            findViewById(
                R.id.seekCaptureRate
            )

        tvCaptureRate =
            findViewById(
                R.id.tvCaptureRate
            )

        tvSeverityBanner =
            findViewById(
                R.id.tvSeverityBanner
            )

        tvOverallRms =
            findViewById(
                R.id.tvOverallRms
            )

        tvSampleRate =
            findViewById(
                R.id.tvSampleRate
            )

        tvQualityScore =
            findViewById(
                R.id.tvQualityScore
            )

        rgAxisSelect =
            findViewById(
                R.id.rgAxisSelect
            )

        etRpm =
            findViewById(
                R.id.etRpm
            )

        tvDominantFreq =
            findViewById(
                R.id.tvDominantFreq
            )

        tvFaultHint =
            findViewById(
                R.id.tvFaultHint
            )

        etMachineId =
            findViewById(
                R.id.etMachineId
            )

        etPointTag =
            findViewById(
                R.id.etPointTag
            )

        etCustomTagLabel =
            findViewById(
                R.id.etCustomTagLabel
            )

        btnAddTag =
            findViewById(
                R.id.btnAddTag
            )

        btnDeleteTag =
            findViewById(
                R.id.btnDeleteTag
            )

        spinnerSavedTags =
            findViewById(
                R.id.spinnerSavedTags
            )

        spinnerIsoClass =
            findViewById(
                R.id.spinnerIsoClass
            )

        spinnerMountingMethod =
            findViewById(
                R.id.spinnerMountingMethod
            )

        spinnerAxisRoleX =
            findViewById(
                R.id.spinnerAxisRoleX
            )

        spinnerAxisRoleY =
            findViewById(
                R.id.spinnerAxisRoleY
            )

        spinnerAxisRoleZ =
            findViewById(
                R.id.spinnerAxisRoleZ
            )

        btnCheckMounting =
            findViewById(
                R.id.btnCheckMounting
            )

        tvMountingCheck =
            findViewById(
                R.id.tvMountingCheck
            )

        etCalibrationOperator =
            findViewById(
                R.id.etCalibrationOperator
            )

        btnRunCalibration =
            findViewById(
                R.id.btnRunCalibration
            )

        tvCalibrationResult =
            findViewById(
                R.id.tvCalibrationResult
            )

        tvBearingGeometryHeader =
            findViewById(
                R.id.tvBearingGeometryHeader
            )

        llBearingGeometryInputs =
            findViewById(
                R.id.llBearingGeometryInputs
            )

        tvSecondaryTelemetryHeader =
            findViewById(
                R.id.tvSecondaryTelemetryHeader
            )

        gridSecondaryTelemetry =
            findViewById(
                R.id.gridSecondaryTelemetry
            )

        etWarnThreshold =
            findViewById(
                R.id.etWarnThreshold
            )

        etAlertThreshold =
            findViewById(
                R.id.etAlertThreshold
            )

        etBearingElements =
            findViewById(
                R.id.etBearingElements
            )

        etBearingBallDiameter =
            findViewById(
                R.id.etBearingBallDiameter
            )

        etBearingPitchDiameter =
            findViewById(
                R.id.etBearingPitchDiameter
            )

        etBearingContactAngle =
            findViewById(
                R.id.etBearingContactAngle
            )

        tvBearingHint =
            findViewById(
                R.id.tvBearingHint
            )
    }

    // ============================================================
    // CAPTURE RATE
    // ============================================================

    private fun setupThrottlerUI() {

        seekCaptureRate.setOnSeekBarChangeListener(
            object :
                SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    targetIntervalMs =
                        (progress + 5).toLong()

                    val hz =
                        1000.0 /
                                targetIntervalMs

                    tvCaptureRate.text =
                        String.format(
                            Locale.US,
                            getString(R.string.capture_interval_format),
                            targetIntervalMs,
                            hz
                        )
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
            }
        )

        // Synchronize display immediately
        seekCaptureRate.progress =
            (targetIntervalMs - 5L)
                .toInt()
                .coerceIn(
                    0,
                    seekCaptureRate.max
                )
    }

    // ============================================================
    // ISO SPINNER
    // ============================================================

    private fun setupIsoSpinner() {

        val labels =
            IsoMachineClass.values()
                .map {
                    it.label
                }

        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                labels
            )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinnerIsoClass.adapter =
            adapter

        spinnerIsoClass.setSelection(
            0
        )

        spinnerIsoClass.onItemSelectedListener =
            object :
                AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    syncTagsToEngine()
                    refreshSeverityBanner()
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }
    }

    // ============================================================
    // MOUNTING / ORIENTATION
    // ============================================================

    private fun setupMountingSpinners() {

        fun plainAdapter(
            items: List<String>
        ): ArrayAdapter<String> {

            val adapter =
                ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    items
                )

            adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )

            return adapter
        }

        val mountingMethods =
            listOf(
                "Handheld",
                "Magnetic",
                "Tape",
                "Adhesive",
                "Rigid fixture",
                "Unknown"
            )

        spinnerMountingMethod.adapter =
            plainAdapter(
                mountingMethods
            )

        spinnerMountingMethod.setSelection(
            mountingMethods.lastIndex
        )

        val axisRoles =
            listOf(
                "Unspecified",
                "Radial",
                "Axial",
                "Tangential",
                "Vertical",
                "Horizontal",
                "Custom"
            )

        spinnerAxisRoleX.adapter =
            plainAdapter(
                axisRoles
            )

        spinnerAxisRoleY.adapter =
            plainAdapter(
                axisRoles
            )

        spinnerAxisRoleZ.adapter =
            plainAdapter(
                axisRoles
            )

        btnCheckMounting.setOnClickListener {

            if (!::sensorEngine.isInitialized || !deploymentController.isServiceReady) {
                Toast.makeText(this, getString(R.string.measurement_service_connecting), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!validateSensors()) {
                return@setOnClickListener
            }

            syncTagsToEngine()

            tvMountingCheck.text =
                "Mounting check in progress...\n" +
                        "Hold the sensor still."

            tvMountingCheck.setTextColor(
                getColor(R.color.sm_text_info)
                )

            sensorEngine.runMountingCheck(
                selectedSensors
            ) { result ->

                uiHandler.post {

                    if (result == null) {

                        tvMountingCheck.text =
                            "Mounting check failed.\n" +
                                    "No usable accelerometer."

                        tvMountingCheck.setTextColor(
                            getColor(R.color.sm_text_bad)
                        )

                        return@post
                    }

                    val verticalRole =
                        when (
                            result.dominantAxis
                        ) {

                            Axis.X ->
                                spinnerAxisRoleX
                                    .selectedItem
                                    ?.toString()

                            Axis.Y ->
                                spinnerAxisRoleY
                                    .selectedItem
                                    ?.toString()

                            Axis.Z ->
                                spinnerAxisRoleZ
                                    .selectedItem
                                    ?.toString()
                        }

                    val anyMarkedVertical =
                        listOf(
                            spinnerAxisRoleX.selectedItem?.toString(),
                            spinnerAxisRoleY.selectedItem?.toString(),
                            spinnerAxisRoleZ.selectedItem?.toString()
                        ).any {
                            it == "Vertical"
                        }

                    val mismatchNote =
                        if (
                            anyMarkedVertical &&
                            verticalRole != "Vertical"
                        ) {

                            " Gravity axis differs from the marked Vertical axis."

                        } else {
                            ""
                        }

                    tvMountingCheck.text =
                        String.format(
                            Locale.US,
                            "Mounting: %s\n" +
                                    "Stability: ±%.1f°\n" +
                                    "Movement: %s\n" +
                                    "Gravity-dominant axis: %s%s",
                            if (result.ready) {
                                "READY"
                            } else {
                                "NOT READY"
                            },
                            result.orientationStabilityDeg,
                            result.movementDuringSetup,
                            result.dominantAxis,
                            mismatchNote
                        )

                    tvMountingCheck.setTextColor(
                        if (result.ready) {

                            getColor(R.color.sm_text_ok)

                        } else {

                            getColor(R.color.sm_text_warn)
                        }
                    )
                }
            }
        }
    }

    // ============================================================
    // COLLAPSIBLE SECTIONS
    // ============================================================

    private fun setupCollapsibleSections() {

        fun wire(
            header: TextView,
            body: View,
            collapsedLabel: String,
            expandedLabel: String
        ) {

            fun render(
                expanded: Boolean
            ) {

                body.visibility =
                    if (expanded) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                header.text =
                    if (expanded) {
                        expandedLabel
                    } else {
                        collapsedLabel
                    }
            }

            render(
                body == gridSecondaryTelemetry
            )

            header.setOnClickListener {

                render(
                    body.visibility !=
                            View.VISIBLE
                )
            }
        }

        wire(
            tvBearingGeometryHeader,
            llBearingGeometryInputs,
            getString(R.string.bearing_geometry_collapsed),
            getString(R.string.bearing_geometry_expanded)
        )

        wire(
            tvSecondaryTelemetryHeader,
            gridSecondaryTelemetry,
            getString(R.string.telemetry_collapsed),
            getString(R.string.telemetry_expanded)
        )
    }

    // ============================================================
    // FOLDABLE CARDS
    // ============================================================

    /**
     * Cards tagged "card:<key>" in res/layout/section_*.xml fold their body when the header is tapped.
     * Contract with the layout: the card is tagged "card:<key>" and has one child tagged "card_header"
     * (holding a "card_chevron") and one child tagged "card_body". The open/closed state is remembered
     * per card. If anything goes wrong every card simply stays open, which is the previous behaviour.
     */
    private fun setupCardAccordions() {
        try {
            val content = scrollDiagnosticBody.getChildAt(0) as? android.view.ViewGroup ?: return
            val prefs = getSharedPreferences("sensormax_ui", MODE_PRIVATE)

            for (i in 0 until content.childCount) {
                val card = content.getChildAt(i) as? android.view.ViewGroup ?: continue
                val tag = card.tag as? String ?: continue
                if (!tag.startsWith("card:")) continue

                val header = card.findViewWithTag<View>("card_header") ?: continue
                val body = card.findViewWithTag<View>("card_body") ?: continue
                val chevron = header.findViewWithTag<View>("card_chevron")
                val prefKey = "card_expanded_" + tag.removePrefix("card:")

                fun render(expanded: Boolean, animate: Boolean) {
                    body.visibility = if (expanded) View.VISIBLE else View.GONE

                    val rotation = if (expanded) 180f else 0f
                    if (animate) {
                        chevron?.animate()?.rotation(rotation)?.setDuration(160L)?.start()
                    } else {
                        chevron?.rotation = rotation
                    }

                    androidx.core.view.ViewCompat.setStateDescription(
                        header,
                        getString(
                            if (expanded) R.string.card_state_expanded else R.string.card_state_collapsed
                        )
                    )
                }

                render(prefs.getBoolean(prefKey, true), animate = false)

                header.setOnClickListener {
                    val expanded = body.visibility != View.VISIBLE
                    render(expanded, animate = true)
                    prefs.edit().putBoolean(prefKey, expanded).apply()
                }
            }
        } catch (e: Exception) {
            appendLog("[WARN] Foldable cards unavailable: ${e.message}")
        }
    }

    /** Opens the folded card that contains [target], scrolls the target into view and focuses it. */
    private fun focusField(target: View) {
        var node: Any? = target.parent
        while (node is View) {
            val tag = node.tag as? String
            if (tag != null && tag.startsWith("card:")) {
                val body = node.findViewWithTag<View>("card_body")
                if (body != null && body.visibility != View.VISIBLE) {
                    node.findViewWithTag<View>("card_header")?.performClick()
                }
                break
            }
            node = node.parent
        }

        target.post {
            val rect = android.graphics.Rect()
            target.getDrawingRect(rect)
            scrollDiagnosticBody.offsetDescendantRectToMyCoords(target, rect)
            scrollDiagnosticBody.smoothScrollTo(0, (rect.top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
        }
    }

    /** Shows [value] in this detail line, or hides the line while there is nothing to show. */
    private fun TextView.setDetail(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ============================================================
    // CALIBRATION
    // ============================================================

    private fun setupCalibrationButton() {

        btnRunCalibration.setOnClickListener {

            if (!::sensorEngine.isInitialized || !deploymentController.isServiceReady) {
                Toast.makeText(this, getString(R.string.measurement_service_connecting), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!validateSensors()) {
                return@setOnClickListener
            }

            syncTagsToEngine()

            tvCalibrationResult.text =
                "Calibration in progress...\n" +
                        "Keep the sensor still and undisturbed."

            tvCalibrationResult.setTextColor(
                getColor(R.color.sm_text_info)
                )

            val operatorName =
                etCalibrationOperator
                    .text
                    .toString()
                    .trim()

            sensorEngine.runCalibration(
                selectedSensors,
                operatorName
            ) { record ->

                uiHandler.post {

                    if (record == null) {

                        tvCalibrationResult.text =
                            "Calibration rejected.\n" +
                                    "Keep the sensor still, mounted consistently, and retry."

                        tvCalibrationResult.setTextColor(
                            getColor(R.color.sm_text_bad)
                        )

                        return@post
                    }

                    val noiseMag =
                        record.noiseMagnitude()

                    tvCalibrationResult.text =
                        String.format(
                            Locale.US,
                            "Calibration ID: %s\n" +
                                    "Static mean: X %.3f · Y %.3f · Z %.3f m/s²\n" +
                                    "Noise RSS: %.3f m/s²\n" +
                                    "Achieved sampling: %.1f Hz",
                            record.calId,
                            record.biasX,
                            record.biasY,
                            record.biasZ,
                            noiseMag,
                            record.achievedSamplingHz
                        )

                    tvCalibrationResult.setTextColor(
                        when {

                            noiseMag <= 0.15f ->
                                getColor(R.color.sm_text_ok)

                            noiseMag <= 0.30f ->
                                getColor(R.color.sm_text_warn)

                            else ->
                                getColor(R.color.sm_text_bad)
                        }
                    )
                }
            }
        }
    }

    private fun setupUsbOtgControls() {
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(usbPermissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbPermissionReceiverRegistered = true
        btnRefreshUsbOtg.setOnClickListener { refreshUsbOtgDevices() }
        btnReadUsbOtg.setOnClickListener { requestSelectedUsbRead() }
        btnStopUsbOtg.setOnClickListener {
            usbRawReader?.close()
            tvUsbOtgStatus.setText(R.string.otg_reader_idle)
        }
        refreshUsbOtgDevices()
    }

    private fun refreshUsbOtgDevices() {
        stopUsbReader(clearText = false)
        usbOtgDevices.clear()
        try {
            usbOtgDevices.addAll(UsbOtgDeviceCatalog.list(this).sortedWith(compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }))
            val labels = usbOtgDevices.map { device ->
                val name = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.deviceName
                String.format(Locale.US, "%s · VID:%04X PID:%04X", name, device.vendorId, device.productId)
            }.ifEmpty { listOf(getString(R.string.otg_no_devices)) }
            spinnerUsbOtgDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            tvUsbOtgStatus.text = getString(R.string.otg_devices_found, usbOtgDevices.size)
        } catch (failure: Exception) {
            spinnerUsbOtgDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(getString(R.string.otg_no_devices)))
            tvUsbOtgStatus.text = getString(R.string.otg_reader_not_started, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun requestSelectedUsbRead() {
        val device = usbOtgDevices.getOrNull(spinnerUsbOtgDevices.selectedItemPosition)
        if (device == null) {
            tvUsbOtgStatus.setText(R.string.otg_select_device)
            return
        }
        if (UsbOtgDeviceCatalog.hasPermission(this, device)) {
            startUsbRawReader(device)
            return
        }
        pendingUsbOtgDevice = device
        val intent = Intent(usbPermissionAction).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val permissionIntent = PendingIntent.getBroadcast(this, device.deviceId, intent, flags)
        tvUsbOtgStatus.setText(R.string.otg_permission_pending)
        (getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, permissionIntent)
    }

    private fun startUsbRawReader(device: UsbDevice) {
        stopUsbReader(clearText = true)
        try {
            usbRawReader = UsbOtgRawBulkReader(
                context = this,
                device = device,
                onChunk = ::appendUsbRawChunk,
                onState = { state, detail -> runOnUiThread {
                    tvUsbOtgStatus.text = when (state) {
                        UsbOtgReaderState.READING -> getString(R.string.otg_reader_reading)
                        UsbOtgReaderState.STOPPED -> getString(R.string.otg_reader_stopped)
                        UsbOtgReaderState.DISCONNECTED -> getString(R.string.otg_reader_disconnected)
                        UsbOtgReaderState.ERROR -> getString(R.string.otg_reader_error, detail ?: "Unknown error")
                    }
                } }
            ).also { it.start() }
        } catch (failure: Exception) {
            tvUsbOtgStatus.text = getString(R.string.otg_reader_not_started, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun appendUsbRawChunk(chunk: UsbOtgRawChunk) {
        val previewLength = minOf(48, chunk.bytes.size)
        val hex = chunk.bytes.take(previewLength).joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
        val suffix = if (chunk.bytes.size > previewLength) " …" else ""
        val line = String.format(Locale.US, "[%d] %d byte(s): %s%s\n", chunk.timestampMs, chunk.bytes.size, hex, suffix)
        runOnUiThread {
            usbRawLog.append(line)
            if (usbRawLog.length > 8000) usbRawLog.delete(0, usbRawLog.length - 8000)
            tvUsbOtgRawData.text = usbRawLog.toString()
        }
    }

    private fun stopUsbReader(clearText: Boolean) {
        usbRawReader?.close()
        usbRawReader = null
        if (clearText) {
            usbRawLog.clear()
            tvUsbOtgRawData.setText(R.string.otg_raw_empty)
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun setupDisplayPreferences() {
        // `android:entries` keeps the language list localized; the unit list is built
        // here so it follows the active locale after AppCompat recreates this Activity.
        spinnerLanguagePreference.contentDescription = getString(R.string.language_selector_content_description)
        spinnerMeasurementSystem.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.units_metric), getString(R.string.units_imperial))
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerMeasurementSystem.contentDescription = getString(R.string.preferences_units_label)
        ignorePreferenceEvents = true
        spinnerLanguagePreference.setSelection(
            when (SensorMaxPreferences.language(this)) {
                SensorMaxLanguage.ENGLISH -> 0
                SensorMaxLanguage.INDONESIAN -> 1
                SensorMaxLanguage.SIMPLIFIED_CHINESE -> 2
                SensorMaxLanguage.SYSTEM -> 3
            }
        )
        spinnerMeasurementSystem.setSelection(if (measurementSystem == MeasurementSystem.METRIC) 0 else 1)
        ignorePreferenceEvents = false

        spinnerLanguagePreference.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignorePreferenceEvents) return
                val selected = when (position) {
                    1 -> SensorMaxLanguage.INDONESIAN
                    2 -> SensorMaxLanguage.SIMPLIFIED_CHINESE
                    3 -> SensorMaxLanguage.SYSTEM
                    else -> SensorMaxLanguage.ENGLISH
                }
                if (selected != SensorMaxPreferences.language(this@MainActivity)) {
                    SensorMaxPreferences.setLanguage(this@MainActivity, selected)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spinnerMeasurementSystem.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignorePreferenceEvents) return
                changeMeasurementSystem(if (position == 1) MeasurementSystem.IMPERIAL else MeasurementSystem.METRIC)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyMeasurementUnitLabels()
    }

    private fun changeMeasurementSystem(next: MeasurementSystem) {
        if (next == measurementSystem) return
        val previous = measurementSystem
        val previousDisplayFactor = if (previous == MeasurementSystem.METRIC) 1.0 else 1.0 / 0.3048
        val nextDisplayFactor = if (next == MeasurementSystem.METRIC) 1.0 else 1.0 / 0.3048
        val liveChartScale = nextDisplayFactor / previousDisplayFactor

        // Switch interpretation first: TextWatchers must parse converted entries in the new unit.
        measurementSystem = next
        // Convert all related fields as one UI transaction. Watchers must not
        // publish a temporary mix of metric and imperial values to SensorEngine.
        suppressInputWatcherActions = true
        try {
            convertAccelerationField(etWarnThreshold, previous, next)
            convertAccelerationField(etAlertThreshold, previous, next)
            convertLengthField(etBearingBallDiameter, previous, next)
            convertLengthField(etBearingPitchDiameter, previous, next)
        } finally {
            suppressInputWatcherActions = false
        }

        // Rescale the already visible waveform before adding newly converted samples.
        vibrationChart.data?.let { data ->
            for (index in 0 until data.dataSetCount) {
                val set = data.getDataSetByIndex(index)
                for (entryIndex in 0 until set.entryCount) {
                    val entry = set.getEntryForIndex(entryIndex)
                    entry.y *= liveChartScale.toFloat()
                }
                // IDataSet does not expose notifyDataSetChanged() on its interface.
                // Our LineChart uses LineDataSet; keep the concrete refresh guarded,
                // then let LineData recalculate all dataset bounds below.
                if (set is LineDataSet) set.notifyDataSetChanged()
            }
            data.notifyDataChanged()
            vibrationChart.notifyDataSetChanged()
        }

        SensorMaxPreferences.setUnits(this, next)
        applyMeasurementUnitLabels()
        applyWaveformAxisView()
        lastSpectrumResult?.let { result ->
            // The waterfall keeps canonical SI amplitudes and has no physical
            // unit axis; don't append a duplicate row during a display-only switch.
            updateSpectrumDisplay(result, updateWaterfall = false)
            if (::sensorEngine.isInitialized) updateTrendChart(sensorEngine.trendHistorySnapshot())
            lastQualityScore?.let { quality -> renderSummaryMetrics(result, quality) }
            refreshSeverityBanner()
        }
        vibrationChart.invalidate()
        // Display-only preference change: do not push rounded display fields back into
        // SensorEngine. Its thresholds and geometry remain in canonical SI/mm unchanged.
    }

    private fun convertAccelerationField(field: EditText, from: MeasurementSystem, to: MeasurementSystem) {
        val raw = field.text.toString()
        if (raw.isBlank()) {
            field.error = null
            return // Preserve intentionally blank threshold fields.
        }
        val input = MeasurementUnits.parseInputValue(raw)
        if (input == null || kotlin.math.abs(input) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        val enteredCanonicalSi = MeasurementUnits.accelerationToSi(input, from)
        if (!enteredCanonicalSi.isFinite() || kotlin.math.abs(enteredCanonicalSi) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        val canonicalSi = if (::sensorEngine.isInitialized) {
            when {
                field === etWarnThreshold -> sensorEngine.warnThresholdMs2.toDouble()
                field === etAlertThreshold -> sensorEngine.alertThresholdMs2.toDouble()
                else -> MeasurementUnits.accelerationToSi(input, from)
            }
        } else {
            MeasurementUnits.accelerationToSi(input, from)
        }
        val converted = MeasurementUnits.acceleration(canonicalSi, to)
        if (!converted.isFinite() || kotlin.math.abs(converted) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        field.error = null
        field.setText(formatInputValue(converted))
    }

    private fun convertLengthField(field: EditText, from: MeasurementSystem, to: MeasurementSystem) {
        val raw = field.text.toString()
        if (raw.isBlank()) {
            field.error = null
            return
        }
        val input = MeasurementUnits.parseInputValue(raw)
        if (input == null || kotlin.math.abs(input) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        val enteredCanonicalMm = MeasurementUnits.lengthToMm(input, from)
        if (!enteredCanonicalMm.isFinite() || kotlin.math.abs(enteredCanonicalMm) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        val canonicalMm = if (::sensorEngine.isInitialized) {
            when {
                field === etBearingBallDiameter -> sensorEngine.bearingBallDiameterMm.toDouble()
                field === etBearingPitchDiameter -> sensorEngine.bearingPitchDiameterMm.toDouble()
                else -> MeasurementUnits.lengthToMm(input, from)
            }
        } else {
            MeasurementUnits.lengthToMm(input, from)
        }
        val converted = MeasurementUnits.lengthMmToDisplay(canonicalMm, to)
        if (!converted.isFinite() || kotlin.math.abs(converted) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return
        }
        field.error = null
        field.setText(formatInputValue(converted))
    }

    private fun formatInputValue(value: Double): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun readAccelerationThresholdSi(field: EditText, defaultSi: Float): Float {
        val raw = field.text.toString()
        if (raw.isBlank()) {
            field.error = null
            return defaultSi
        }
        val parsed = MeasurementUnits.parseInputValue(raw)
        if (parsed == null || kotlin.math.abs(parsed) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            if (!::sensorEngine.isInitialized) return defaultSi
            return when {
                field === etWarnThreshold -> sensorEngine.warnThresholdMs2
                field === etAlertThreshold -> sensorEngine.alertThresholdMs2
                else -> defaultSi
            }
        }
        field.error = null
        return MeasurementUnits.accelerationToSi(parsed, measurementSystem).toFloat()
    }

    private fun readLengthMm(field: EditText): Float {
        val raw = field.text.toString()
        if (raw.isBlank()) {
            field.error = null
            return 0f
        }
        val parsed = MeasurementUnits.parseInputValue(raw)
        if (parsed == null || kotlin.math.abs(parsed) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            if (!::sensorEngine.isInitialized) return 0f
            return when {
                field === etBearingBallDiameter -> sensorEngine.bearingBallDiameterMm
                field === etBearingPitchDiameter -> sensorEngine.bearingPitchDiameterMm
                else -> 0f
            }
        }
        val canonicalMm = MeasurementUnits.lengthToMm(parsed, measurementSystem)
        if (!canonicalMm.isFinite() || kotlin.math.abs(canonicalMm) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            if (!::sensorEngine.isInitialized) return 0f
            return when {
                field === etBearingBallDiameter -> sensorEngine.bearingBallDiameterMm
                field === etBearingPitchDiameter -> sensorEngine.bearingPitchDiameterMm
                else -> 0f
            }
        }
        field.error = null
        return canonicalMm.toFloat()
    }

    private fun readInputFloat(field: EditText, blankFallback: Float, previousValue: Float): Float {
        val raw = field.text.toString()
        if (raw.isBlank()) {
            field.error = null
            return blankFallback
        }
        val parsed = MeasurementUnits.parseInputValue(raw)
        if (parsed == null || kotlin.math.abs(parsed) > Float.MAX_VALUE.toDouble()) {
            field.error = getString(R.string.numeric_input_invalid)
            return previousValue
        }
        field.error = null
        return parsed.toFloat()
    }

    private fun readInputInt(field: EditText, blankFallback: Int, previousValue: Int): Int {
        val raw = field.text.toString().trim()
        if (raw.isEmpty()) {
            field.error = null
            return blankFallback
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            field.error = getString(R.string.numeric_input_invalid)
            return previousValue
        }
        field.error = null
        return parsed
    }

    private fun displayAcceleration(value: Float): Float =
        MeasurementUnits.acceleration(value.toDouble(), measurementSystem).toFloat()

    private fun displayAcceleration(value: Double): Double =
        MeasurementUnits.acceleration(value, measurementSystem)

    private fun applyMeasurementUnitLabels() {
        val accelUnit = MeasurementUnits.accelerationUnit(measurementSystem)
        tvWarnThresholdLabel.text = getString(R.string.reference_warn_label_format, accelUnit)
        tvAlertThresholdLabel.text = getString(R.string.reference_alert_label_format, accelUnit)
        etWarnThreshold.hint = accelUnit
        etAlertThreshold.hint = accelUnit
        val lengthUnit = MeasurementUnits.lengthUnit(measurementSystem)
        etBearingBallDiameter.hint = lengthUnit
        etBearingPitchDiameter.hint = lengthUnit
        findViewById<TextView>(R.id.tvBearingBallLabel).text = getString(R.string.bearing_ball_label_format, lengthUnit)
        findViewById<TextView>(R.id.tvBearingPitchLabel).text = getString(R.string.bearing_pitch_label_format, lengthUnit)
        tvWaveformNote.text = getString(
            R.string.waveform_note_format,
            displayAcceleration(WAVEFORM_Y_LIMIT_MS2),
            accelUnit
        )
        tvTrendUnitLabel.text = getString(R.string.chart_rms_trend_label_format, accelUnit)
        if (tvOverallRms.text.isNullOrBlank() || tvOverallRms.text.toString().startsWith("--")) {
            tvOverallRms.text = "-- $accelUnit"
        }
    }

    private fun refreshNetworkExposureNote() {
        tvNetworkAcquisitionNote.text = getString(
            if (cbNetworkAllowLan.isChecked) R.string.network_note_lan else R.string.network_note_loopback
        )
    }

    private fun renderSummaryMetrics(result: SpectrumResult, quality: QualityScore) {
        val accelUnit = MeasurementUnits.accelerationUnit(measurementSystem)
        val velocityUnit = MeasurementUnits.velocityMmSUnit(measurementSystem)
        val displacementUnit = MeasurementUnits.displacementUmUnit(measurementSystem)
        tvOverallRms.text = String.format(Locale.US, "%.3f %s", displayAcceleration(result.overallRmsAccel), accelUnit)
        tvOverallRmsDetail.setDetail(
            String.format(
                Locale.US,
                getString(R.string.summary_detail_format),
                result.overallRmsAccel / 9.80665f,
                MeasurementUnits.velocityMmSToDisplay(result.overallRmsVelocityMmS.toDouble(), measurementSystem), velocityUnit,
                MeasurementUnits.displacementUmToDisplay(result.overallRmsDisplacementUm.toDouble(), measurementSystem), displacementUnit
            )
        )
        tvQualityScore.text = String.format(Locale.US, "%d / 100", quality.score0to100)
        val notable = quality.notableFactors()
        tvQualityDetail.setDetail(if (notable.isEmpty()) "All checked factors passing" else notable.joinToString(" · ") { "${it.name}: ${it.grade}" })
        tvQualityScore.setTextColor(when {
            quality.score0to100 >= 80 -> getColor(R.color.sm_text_ok)
            quality.score0to100 >= 50 -> getColor(R.color.sm_text_warn)
            else -> getColor(R.color.sm_text_bad)
        })
    }

    // ============================================================
    // AXIS SELECTOR
    // ============================================================

    private fun setupAxisSelector() {

        rgAxisSelect.setOnCheckedChangeListener {
                _,
                checkedId ->

            axisView =
                when (checkedId) {

                    R.id.rbAxisX ->
                        AxisView.X

                    R.id.rbAxisY ->
                        AxisView.Y

                    R.id.rbAxisZ ->
                        AxisView.Z

                    R.id.rbAxisRss ->
                        AxisView.RSS

                    else ->
                        AxisView.AUTO
                }

            // The selector controls the live triaxial display as well as the
            // FFT/spectrum view. AUTO keeps all three physical axes visible;
            // X/Y/Z isolates one physical axis; RSS shows resultant magnitude.
            applyWaveformAxisView()

            waterfallView.clear()

            lastSpectrumResult?.let {
                updateSpectrumDisplay(
                    it
                )
            }
        }

        applyWaveformAxisView()
    }

    private fun applyWaveformAxisView() {

        val data = vibrationChart.data ?: return

        if (data.dataSetCount < 4) {
            return
        }

        val xSet = data.getDataSetByIndex(0)
        val ySet = data.getDataSetByIndex(1)
        val zSet = data.getDataSetByIndex(2)
        val rssSet = data.getDataSetByIndex(3)

        when (axisView) {
            AxisView.AUTO -> {
                xSet.isVisible = true
                ySet.isVisible = true
                zSet.isVisible = true
                rssSet.isVisible = false

                vibrationChart.axisLeft.axisMinimum =
                    -displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
                vibrationChart.axisLeft.axisMaximum =
                    displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
            }

            AxisView.X -> {
                xSet.isVisible = true
                ySet.isVisible = false
                zSet.isVisible = false
                rssSet.isVisible = false

                vibrationChart.axisLeft.axisMinimum =
                    -displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
                vibrationChart.axisLeft.axisMaximum =
                    displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
            }

            AxisView.Y -> {
                xSet.isVisible = false
                ySet.isVisible = true
                zSet.isVisible = false
                rssSet.isVisible = false

                vibrationChart.axisLeft.axisMinimum =
                    -displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
                vibrationChart.axisLeft.axisMaximum =
                    displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
            }

            AxisView.Z -> {
                xSet.isVisible = false
                ySet.isVisible = false
                zSet.isVisible = true
                rssSet.isVisible = false

                vibrationChart.axisLeft.axisMinimum =
                    -displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
                vibrationChart.axisLeft.axisMaximum =
                    displayAcceleration(WAVEFORM_Y_LIMIT_MS2)
            }

            AxisView.RSS -> {
                xSet.isVisible = false
                ySet.isVisible = false
                zSet.isVisible = false
                rssSet.isVisible = true

                vibrationChart.axisLeft.axisMinimum =
                    0f
                vibrationChart.axisLeft.axisMaximum =
                    displayAcceleration(WAVEFORM_Y_LIMIT_MS2 * sqrt(3f))
            }
        }

        applyWaveformAxisColors(data)
        data.notifyDataChanged()
        vibrationChart.notifyDataSetChanged()
        vibrationChart.invalidate()
    }

    /** Keep the line, highlight and legend colors synchronized with the RGB axis controls. */
    private fun applyWaveformAxisColors(data: LineData) {
        val axisColors = intArrayOf(
            getColor(R.color.sm_axis_x),
            getColor(R.color.sm_axis_y),
            getColor(R.color.sm_axis_z),
            getColor(R.color.sm_axis_rss)
        )
        val count = minOf(data.dataSetCount, axisColors.size)
        for (index in 0 until count) {
            val set = data.getDataSetByIndex(index) as? LineDataSet ?: continue
            val axisColor = axisColors[index]
            set.color = axisColor
            set.setCircleColor(axisColor)
            set.highLightColor = axisColor
        }
        vibrationChart.legend.textColor = getColor(R.color.sm_text_primary)
    }

    // ============================================================
    // WATCHERS
    // ============================================================

    private fun setupTagAndThresholdWatchers() {

        val watcher =
            simpleWatcher {
                if (!suppressInputWatcherActions) {
                    if (::sensorEngine.isInitialized) {
                        syncTagsToEngine()
                    }
                    if (::tvSeverityBanner.isInitialized) {
                        refreshSeverityBanner()
                    }
                }
            }

        etMachineId.addTextChangedListener(
            watcher
        )

        etPointTag.addTextChangedListener(
            watcher
        )

        etRpm.addTextChangedListener(
            watcher
        )

        etWarnThreshold.addTextChangedListener(
            watcher
        )

        etAlertThreshold.addTextChangedListener(
            watcher
        )

        etBearingElements.addTextChangedListener(
            watcher
        )

        etBearingBallDiameter.addTextChangedListener(
            watcher
        )

        etBearingPitchDiameter.addTextChangedListener(
            watcher
        )

        etBearingContactAngle.addTextChangedListener(
            watcher
        )

        /*
         * IMPORTANT:
         *
         * Do NOT call setText() here.
         *
         * The XML intentionally leaves these fields empty so:
         *
         * 1. Android does not present pre-filled values.
         * 2. The operator explicitly enters thresholds.
         * 3. SensorEngine still owns its internal fallback defaults
         *    when the operator leaves a field blank.
         */
    }

    private fun simpleWatcher(
        onChanged: () -> Unit
    ): TextWatcher =
        object :
            TextWatcher {

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
            }

            override fun afterTextChanged(
                s: Editable?
            ) {
                onChanged()
            }
        }

    // ============================================================
    // ENGINE CONFIGURATION
    // ============================================================

    private fun syncTagsToEngine() {

        if (!::sensorEngine.isInitialized) {
            return
        }

        sensorEngine.machineId =
            etMachineId.text
                .toString()
                .trim()
                .ifBlank {
                    "UNSPECIFIED"
                }

        sensorEngine.pointTag =
            etPointTag.text
                .toString()
                .trim()
                .ifBlank {
                    "UNSPECIFIED"
                }

        sensorEngine.customLabel =
            etCustomTagLabel.text
                .toString()
                .trim()

        sensorEngine.rpmHint = readInputFloat(
            field = etRpm,
            blankFallback = 0f,
            previousValue = sensorEngine.rpmHint
        )

        val isoPosition =
            spinnerIsoClass.selectedItemPosition

        sensorEngine.isoClass =
            IsoMachineClass.values()
                .getOrElse(
                    isoPosition
                ) {
                    IsoMachineClass.NONE
                }

        /*
         * Blank threshold fields deliberately fall back
         * to SensorEngine's configured defaults.
         *
         * No UI autofill is performed.
         */

        sensorEngine.warnThresholdMs2 = readAccelerationThresholdSi(etWarnThreshold, SensorEngine.DEFAULT_WARN_MS2)
        sensorEngine.alertThresholdMs2 = readAccelerationThresholdSi(etAlertThreshold, SensorEngine.DEFAULT_ALERT_MS2)

        sensorEngine.bearingNumElements = readInputInt(
            field = etBearingElements,
            blankFallback = 0,
            previousValue = sensorEngine.bearingNumElements
        )

        sensorEngine.bearingBallDiameterMm = readLengthMm(etBearingBallDiameter)
        sensorEngine.bearingPitchDiameterMm = readLengthMm(etBearingPitchDiameter)

        sensorEngine.bearingContactAngleDeg = readInputFloat(
            field = etBearingContactAngle,
            blankFallback = 0f,
            previousValue = sensorEngine.bearingContactAngleDeg
        )

        sensorEngine.mountingMethod =
            spinnerMountingMethod
                .selectedItem
                ?.toString()
                ?: "Unknown"

        sensorEngine.axisRoleX =
            spinnerAxisRoleX
                .selectedItem
                ?.toString()
                ?: "Unspecified"

        sensorEngine.axisRoleY =
            spinnerAxisRoleY
                .selectedItem
                ?.toString()
                ?: "Unspecified"

        sensorEngine.axisRoleZ =
            spinnerAxisRoleZ
                .selectedItem
                ?.toString()
                ?: "Unspecified"
    }

    // ============================================================
    // BEARING GEOMETRY
    // ============================================================

    private fun currentBearingGeometry():
            BearingGeometry? {

        val n =
            etBearingElements.text
                .toString()
                .trim()
                .toIntOrNull()
                ?: 0

        val ball = readLengthMm(etBearingBallDiameter)
        val pitch = readLengthMm(etBearingPitchDiameter)

        val angle =
            MeasurementUnits.parseInputValue(etBearingContactAngle.text.toString())?.toFloat() ?: 0f

        if (
            n <= 0 ||
            ball <= 0f ||
            pitch <= 0f
        ) {
            return null
        }

        return BearingGeometry(
            numElements = n,
            ballDiameterMm = ball,
            pitchDiameterMm = pitch,
            contactAngleDeg = angle
        )
    }

    // ============================================================
    // SNAPSHOT
    // ============================================================

    private fun configureSnapshotButton() {

        btnSnapshot.setOnClickListener {

            if (!isDeployed) {

                Toast.makeText(
                    this,
                    getString(R.string.measurement_session_required),
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            if (!deploymentController.isServiceReady || !::sensorEngine.isInitialized) {
                Toast.makeText(
                    this,
                    getString(R.string.measurement_service_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val ok =
                sensorEngine
                    .captureSnapshotNow()

            if (ok) {

                val machine =
                    etMachineId.text
                        .toString()
                        .trim()
                        .ifBlank {
                            "UNSPECIFIED"
                        }

                val point =
                    etPointTag.text
                        .toString()
                        .trim()
                        .ifBlank {
                            "UNSPECIFIED"
                        }

                Toast.makeText(
                    this,
                    getString(R.string.snapshot_captured_format, machine, point),
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                Toast.makeText(
                    this,
                    getString(R.string.spectrum_not_ready),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // DEPLOYMENT
    // ============================================================

    private fun configureExperimentProfileImport() {
        btnImportExperimentProfile = findViewById(R.id.btnImportExperimentProfile)
        btnImportExperimentProfile.setOnClickListener {
            experimentProfileFilePicker.launch(arrayOf("application/xml", "text/xml", "*/*"))
        }
    }

    private fun importExperimentProfile(uri: android.net.Uri) {
        appendLog("[PROFILE] Importing experiment definition...")
        Thread {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val name = queryDisplayName(uri) ?: "imported.legacy.xml"
                    SensorMaxExperimentFile.parse(input, name)
                } ?: error("Cannot open selected file")
            }.onSuccess { definition ->
                lastImportedExperimentProfile = definition
                runOnUiThread {
                    appendLog("[PROFILE] ${definition.title.ifBlank { definition.sourceName }} · format ${definition.formatVersion}")
                    appendLog("[PROFILE] ${definition.sensors.size} sensor bindings · ${definition.containers.size} data containers · ${definition.analysisModules.size} analysis modules")
                    if (definition.unsupportedFeatures.isEmpty()) {
                        appendLog("[PROFILE] Profile validated; mapped Android sensors are available for application.")
                    } else {
                        appendLog("[PROFILE] Imported as profile-only configuration; unsupported: ${definition.unsupportedFeatures.joinToString()}")
                    }
                    showExperimentProfileSummary(definition)
                }
            }.onFailure { error ->
                runOnUiThread {
                    appendLog("[PROFILE] Import failed: ${error.message ?: error.javaClass.simpleName}")
                    Toast.makeText(this, getString(R.string.experiment_import_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun exportTranslatedProfile(definition: SensorMaxExperimentFile.Definition) {
        pendingProfileTranslation = definition
        val translated = SensorMaxExperimentTranslator.translate(definition)
        val base = translated.xmlFileName.substringBeforeLast('.')
        translatedProfileZipPicker.launch("${base}_SensorMax_Translated.zip")
    }

    private fun writeTranslatedProfileZip(uri: android.net.Uri, definition: SensorMaxExperimentFile.Definition) {
        runCatching {
            val translated = SensorMaxExperimentTranslator.translate(definition)
            val bytes = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(translated.xmlFileName))
                    zip.write(translated.xml.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(translated.kotlinFileName))
                    zip.write(translated.kotlin.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                buffer.toByteArray()
            }
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Cannot open destination")
            appendLog("[PROFILE] Exported SensorMax XML + Kotlin translation.")
            Toast.makeText(this, getString(R.string.translation_exported), Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            appendLog("[PROFILE] Translation export failed: ${error.message ?: error.javaClass.simpleName}")
            Toast.makeText(this, getString(R.string.translation_export_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun applyExperimentProfile(definition: SensorMaxExperimentFile.Definition) {
        var appliedSensors = 0
        definition.requiredAndroidSensorTypes.forEach { type ->
            sensorCheckBoxes.entries
                .filter { it.key.type == type && !it.value.isChecked }
                .forEach { (_, checkBox) ->
                    checkBox.isChecked = true
                    appliedSensors++
                }
        }

        definition.requestedCaptureRateHz?.let { requestedHz ->
            if (requestedHz > 0.0) {
                targetIntervalMs = (1000.0 / requestedHz)
                    .roundToLong()
                    .coerceIn(5L, seekCaptureRate.max.toLong() + 5L)
                seekCaptureRate.progress =
                    (targetIntervalMs - 5L).toInt()
            }
        }

        appendLog(
            "[PROFILE] Applied mapping: $appliedSensors sensor checkbox(es) changed" +
                (definition.requestedCaptureRateHz?.let { " · requested %.2f Hz".format(Locale.US, it) } ?: "")
        )
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun showExperimentProfileSummary(definition: SensorMaxExperimentFile.Definition) {
        val sensorNames = definition.sensors.joinToString("\n") { sensor ->
            val mapped = sensor.androidSensorType?.toString() ?: getString(R.string.profile_unmapped)
            "• ${sensor.type} → $mapped (${sensor.outputs.values.joinToString()})"
        }.ifBlank { getString(R.string.profile_no_sensor_bindings) }
        val analysisNames = definition.analysisModules.joinToString(", ") { it.type }
            .ifBlank { getString(R.string.profile_none) }
        val unsupported = definition.unsupportedFeatures.joinToString("\n") { "• $it" }
            .ifBlank { getString(R.string.profile_none) }

        val summary = getString(
            R.string.profile_summary_format,
            definition.title.ifBlank { definition.sourceName },
            definition.formatVersion,
            definition.sourceFormat,
            sensorNames,
            analysisNames,
            unsupported,
            definition.sha256
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.profile_import_dialog_title)
            .setMessage(summary)
            .setNegativeButton(R.string.profile_close, null)
            .setNeutralButton(R.string.profile_export) { _, _ ->
                exportTranslatedProfile(definition)
            }

        if (definition.requiredAndroidSensorTypes.isNotEmpty() || definition.requestedCaptureRateHz != null) {
            builder.setPositiveButton(R.string.profile_apply) { _, _ ->
                applyExperimentProfile(definition)
            }
        }

        builder.show()
    }

    private fun setupAcquisitionSourceControls() {
        val sourceLabels = listOf(
            getString(R.string.source_android_sensor),
            getString(R.string.source_ble_gatt),
            getString(R.string.source_audio_microphone),
            getString(R.string.source_location_gnss),
            getString(R.string.source_camera_photometric),
            getString(R.string.source_depth_tof),
            getString(R.string.source_network_tcp_ndjson)
        )
        spinnerAcquisitionSource.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sourceLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val profiles = BleGattProfileCatalog.runtimeProfiles
        val profileLabels = profiles.map { it.displayName }
        spinnerBleProfile.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            profileLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        fun refreshAcquisitionSourceUi() {
            acquisitionSourceMode = when (spinnerAcquisitionSource.selectedItemPosition) {
                1 -> AcquisitionSourceKind.BLE_GATT
                2 -> AcquisitionSourceKind.AUDIO
                3 -> AcquisitionSourceKind.LOCATION
                4 -> AcquisitionSourceKind.CAMERA
                5 -> AcquisitionSourceKind.DEPTH
                6 -> AcquisitionSourceKind.NETWORK
                else -> AcquisitionSourceKind.ANDROID_SENSOR
            }

            val bleSelected = acquisitionSourceMode == AcquisitionSourceKind.BLE_GATT
            val cameraSelected = acquisitionSourceMode == AcquisitionSourceKind.CAMERA
            val depthSelected = acquisitionSourceMode == AcquisitionSourceKind.DEPTH
            val networkSelected = acquisitionSourceMode == AcquisitionSourceKind.NETWORK
            val nonAndroidSelected = acquisitionSourceMode != AcquisitionSourceKind.ANDROID_SENSOR
            llBleAcquisitionConfig.visibility = if (bleSelected) View.VISIBLE else View.GONE
            llCameraAcquisitionConfig.visibility = if (cameraSelected) View.VISIBLE else View.GONE
            llDepthAcquisitionConfig.visibility = if (depthSelected) View.VISIBLE else View.GONE
            llNetworkAcquisitionConfig.visibility = if (networkSelected) View.VISIBLE else View.GONE
            llSensorList.visibility = if (nonAndroidSelected) View.GONE else View.VISIBLE
            tvSensorMatrixLabel.visibility = llSensorList.visibility
            if (depthSelected) {
                tvDepthAcquisitionNote.setText(R.string.depth_acquisition_note)
            }
            if (networkSelected) {
                refreshNetworkExposureNote()
            }

            if (bleSelected) {
                val selected = profiles.getOrNull(spinnerBleProfile.selectedItemPosition)
                tvBleProfileNote.text = selected?.let {
                    getString(R.string.ble_profile_note_format, it.displayName)
                } ?: getString(R.string.ble_profile_unavailable)
            }
        }

        spinnerAcquisitionSource.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    refreshAcquisitionSourceUi()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    refreshAcquisitionSourceUi()
                }
            }

        spinnerBleProfile.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    refreshAcquisitionSourceUi()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    refreshAcquisitionSourceUi()
                }
            }

        refreshAcquisitionSourceUi()
    }

    private enum class DeploymentStartResult {
        STARTED,
        PENDING_PERMISSION,
        REJECTED
    }

    private fun startSelectedDeployment(
        enableNetwork: Boolean,
        endpoint: String?
    ): DeploymentStartResult {
        if (!::sensorEngine.isInitialized || !deploymentController.isServiceReady) {
            Toast.makeText(
                this,
                getString(R.string.measurement_service_connecting),
                Toast.LENGTH_SHORT
            ).show()
            return DeploymentStartResult.REJECTED
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.BLE_GATT) {
            val startAction = {
                if (startBleDeploymentNow(enableNetwork, endpoint)) {
                    resetLiveDisplayState()
                    toggleUIState(isStreaming = enableNetwork)
                }
            }
            val missing = BleGattPermissions.missingPermissions(this)
            if (missing.isNotEmpty()) {
                pendingBleStart = startAction
                blePermissionLauncher.launch(BleGattPermissions.requiredPermissions())
                appendLog("[BLE] Requested runtime permission(s): ${missing.joinToString()}")
                return DeploymentStartResult.PENDING_PERMISSION
            }
            return if (startBleDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.AUDIO) {
            val startAction = {
                if (startAudioDeploymentNow(enableNetwork, endpoint)) {
                    resetLiveDisplayState()
                    toggleUIState(isStreaming = enableNetwork)
                }
            }
            val missing = AudioAcquisitionPermissions.missingPermissions(this)
            if (missing.isNotEmpty()) {
                pendingAudioStart = startAction
                audioPermissionLauncher.launch(AudioAcquisitionPermissions.requiredPermissions().single())
                appendLog("[AUDIO] Requested runtime microphone permission")
                return DeploymentStartResult.PENDING_PERMISSION
            }
            return if (startAudioDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.LOCATION) {
            val startAction = {
                if (startLocationDeploymentNow(enableNetwork, endpoint)) {
                    resetLiveDisplayState()
                    toggleUIState(isStreaming = enableNetwork)
                }
            }
            val missing = LocationAcquisitionPermissions.missingPermissions(this)
            if (missing.isNotEmpty()) {
                pendingLocationStart = startAction
                locationPermissionLauncher.launch(LocationAcquisitionPermissions.requiredPermissions())
                appendLog("[LOCATION] Requested runtime location permission")
                return DeploymentStartResult.PENDING_PERMISSION
            }
            if (!LocationAcquisitionPermissions.isLocationEnabled(this)) {
                appendLog("[LOCATION] Location services are disabled.")
                Toast.makeText(
                    this,
                    getString(R.string.location_services_enable),
                    Toast.LENGTH_SHORT
                ).show()
                return DeploymentStartResult.REJECTED
            }
            return if (startLocationDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.DEPTH) {
            val startAction = {
                if (startDepthDeploymentNow(enableNetwork, endpoint)) {
                    resetLiveDisplayState()
                    toggleUIState(isStreaming = enableNetwork)
                }
            }
            if (!DepthAcquisitionPermissions.hasRequiredPermission(this)) {
                pendingDepthStart = startAction
                depthPermissionLauncher.launch(DepthAcquisitionPermissions.requiredPermission())
                appendLog("[DEPTH] Requested runtime camera permission")
                return DeploymentStartResult.PENDING_PERMISSION
            }
            return if (startDepthDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.CAMERA) {
            val startAction = {
                if (startCameraDeploymentNow(enableNetwork, endpoint)) {
                    resetLiveDisplayState()
                    toggleUIState(isStreaming = enableNetwork)
                }
            }
            if (!CameraAcquisitionPermissions.hasRequiredPermission(this)) {
                pendingCameraStart = startAction
                cameraPermissionLauncher.launch(CameraAcquisitionPermissions.requiredPermission())
                appendLog("[CAMERA] Requested runtime camera permission")
                return DeploymentStartResult.PENDING_PERMISSION
            }
            return if (startCameraDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (acquisitionSourceMode == AcquisitionSourceKind.NETWORK) {
            return if (startNetworkDeploymentNow(enableNetwork, endpoint)) {
                resetLiveDisplayState()
                toggleUIState(isStreaming = enableNetwork)
                DeploymentStartResult.STARTED
            } else {
                DeploymentStartResult.REJECTED
            }
        }

        if (!validateSensors()) {
            return DeploymentStartResult.REJECTED
        }

        syncTagsToEngine()
        return if (deploymentController.startDeployment(
            sensors = selectedSensors,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true,
            endpoint = endpoint
        )) {
            DeploymentStartResult.STARTED
        } else {
            DeploymentStartResult.REJECTED
        }
    }

    private fun startBleDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val profiles = BleGattProfileCatalog.runtimeProfiles
        val selected = profiles.getOrNull(spinnerBleProfile.selectedItemPosition)
            ?: return false

        val address = etBleDeviceAddress.text
            .toString()
            .trim()
            .ifBlank { null }

        if (address == null) {
            appendLog("[BLE] A MAC address is required for the current runnable profile.")
            Toast.makeText(
                this,
                getString(R.string.enter_ble_address),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val profile = selected.copy(deviceAddress = address)
        val source = deploymentController.createBleGattSource(profile)
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )

        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )

        if (started) {
            appendLog("[BLE] Deployment request accepted: ${profile.displayName}")
        }

        return started
    }

    private fun startAudioDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val source = deploymentController.createAudioSource(
            AudioAcquisitionProfile()
        )
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )
        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )
        if (started) {
            appendLog("[AUDIO] Microphone acquisition deployment accepted")
        }
        return started
    }

    private fun startLocationDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val source = deploymentController.createLocationSource(
            LocationAcquisitionProfile()
        )
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )
        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )
        if (started) {
            appendLog("[LOCATION] Location / GNSS acquisition deployment accepted")
        }
        return started
    }

    private fun startDepthDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val source = deploymentController.createDepthSource(
            DepthAcquisitionProfile()
        )
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )
        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )
        if (started) {
            appendLog("[DEPTH] Camera2 DEPTH16 acquisition deployment accepted")
        }
        return started
    }

    private fun startNetworkDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val portText = etNetworkListenPort.text.toString().trim()
        val port = portText.toIntOrNull() ?: NetworkAcquisitionProfile().port
        if (port !in 1024..65_535) {
            appendLog("[NETWORK-IN] Invalid listener port: $portText")
            Toast.makeText(this, R.string.network_listener_port_invalid, Toast.LENGTH_SHORT).show()
            return false
        }

        val bindHost = if (cbNetworkAllowLan.isChecked) "0.0.0.0" else "127.0.0.1"
        val source = deploymentController.createNetworkSource(
            NetworkAcquisitionProfile(bindHost = bindHost, port = port)
        )
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )
        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )
        if (started) {
            appendLog("[NETWORK-IN] TCP/NDJSON listener deployment accepted on $bindHost:$port")
        }
        return started
    }

    private fun startCameraDeploymentNow(
        enableNetwork: Boolean,
        endpoint: String?
    ): Boolean {
        val source = deploymentController.createCameraSource(
            CameraAcquisitionProfile()
        )
        val request = AcquisitionDeploymentRequest(
            source = source,
            intervalMs = targetIntervalMs,
            enableNetwork = enableNetwork,
            enableDisk = true
        )
        val started = deploymentController.startDeployment(
            request = request,
            endpoint = endpoint
        )
        if (started) {
            appendLog("[CAMERA] Camera photometric acquisition deployment accepted")
        }
        return started
    }

    private fun configureDeploymentButtons() {

        // --------------------------------------------------------
        // STREAM + LOCAL LOG
        // --------------------------------------------------------

        btnStreamAndLog.setOnClickListener {

            if (!isDeployed) {

                endpointBuildRejected = false
                val url = if (
                    acquisitionSourceMode == AcquisitionSourceKind.NETWORK && etTargetIp.text.toString().trim().isBlank()
                ) {
                    ""
                } else {
                    buildWebSocketUrl()
                }

                if (endpointBuildRejected) return@setOnClickListener

                if (url.isBlank() && acquisitionSourceMode != AcquisitionSourceKind.NETWORK) {
                    focusField(etTargetIp)

                    Toast.makeText(
                        this,
                        getString(R.string.enter_websocket_host),
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                when (startSelectedDeployment(enableNetwork = url.isNotBlank(), endpoint = url.ifBlank { null })) {
                    DeploymentStartResult.STARTED -> {
                        resetLiveDisplayState()
                        toggleUIState(isStreaming = true)
                    }
                    DeploymentStartResult.PENDING_PERMISSION -> return@setOnClickListener
                    DeploymentStartResult.REJECTED -> {
                        Toast.makeText(
                            this,
                            getString(R.string.deployment_rejected),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }

            } else {

                haltDeployment()
            }
        }

        // --------------------------------------------------------
        // OFFLINE LOG
        // --------------------------------------------------------

        btnOfflineLog.setOnClickListener {

            if (!isDeployed) {

                when (startSelectedDeployment(enableNetwork = false, endpoint = null)) {
                    DeploymentStartResult.STARTED -> {
                        resetLiveDisplayState()
                        toggleUIState(isStreaming = false)
                    }
                    DeploymentStartResult.PENDING_PERMISSION -> return@setOnClickListener
                    DeploymentStartResult.REJECTED -> {
                        Toast.makeText(
                            this,
                            getString(R.string.deployment_rejected),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }

            } else {

                haltDeployment()
            }
        }
    }

    /** Preserve the active measurement's actual interval when the Activity is recreated for locale changes. */
    private fun restoreActiveIntervalFromEngine(engine: SensorEngine) {
        val requestedIntervalMs = engine.acquisitionHealthSnapshot().requestedIntervalMs
        if (requestedIntervalMs <= 0L) return

        targetIntervalMs = requestedIntervalMs
        if (::seekCaptureRate.isInitialized) {
            val maxInterval = seekCaptureRate.max.toLong() + 5L
            val progress = (requestedIntervalMs - 5L).coerceIn(0L, maxInterval - 5L)
            seekCaptureRate.progress = progress.toInt()
            // Keep status text faithful to the running engine even if a future provider allows
            // an interval outside the slider's current selectable range.
            targetIntervalMs = requestedIntervalMs
        }
        if (::tvCaptureRate.isInitialized) {
            tvCaptureRate.text = String.format(
                Locale.US,
                getString(R.string.capture_interval_format),
                requestedIntervalMs,
                1000.0 / requestedIntervalMs
            )
        }
    }

    /** Render the active run mode without changing sensor data or analysis state. */
    private fun toggleUIState(isStreaming: Boolean) {
        isDeployed = true

        btnStreamAndLog.isEnabled = isStreaming
        btnOfflineLog.isEnabled = !isStreaming

        btnStreamAndLog.text = getString(
            if (isStreaming) R.string.action_stop_stream_log else R.string.action_stream_log
        )
        btnOfflineLog.text = getString(
            if (isStreaming) R.string.action_offline_log else R.string.action_stop_offline_log
        )

        btnStreamAndLog.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isStreaming) R.color.sm_danger_red else R.color.sm_action_disabled_fill
            )
        )
        btnOfflineLog.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isStreaming) R.color.sm_action_disabled_fill else R.color.sm_danger_red
            )
        )

        val statusResource = if (isStreaming) {
            R.string.status_active_stream_format
        } else {
            R.string.status_active_offline_format
        }
        tvStatus.text = String.format(
            Locale.US,
            getString(statusResource),
            targetIntervalMs,
            1000.0 / targetIntervalMs
        )
    }

    /** Stop the service deployment and restore action labels without clearing rendered history. */
    private fun haltDeployment() {
        if (!isDeployed) return

        try {
            deploymentController.stopDeployment()
        } catch (error: Exception) {
            appendLog("[WARN] Deployment stop: ${error.message}")
        }

        isDeployed = deploymentController.isDeployed
        if (isDeployed) {
            toggleUIState(isStreaming = deploymentController.isStreamingDeployment)
        } else {
            resetDeploymentUiState(stopped = true)
        }
    }

    private fun resetDeploymentUiState(stopped: Boolean) {
        btnStreamAndLog.isEnabled = true
        btnOfflineLog.isEnabled = true
        btnStreamAndLog.text = getString(R.string.action_stream_log)
        btnOfflineLog.text = getString(R.string.action_offline_log)
        btnStreamAndLog.setBackgroundColor(
            ContextCompat.getColor(this, R.color.sm_action_record_fill)
        )
        btnOfflineLog.setBackgroundColor(
            ContextCompat.getColor(this, R.color.sm_action_neutral_fill)
        )
        tvStatus.setText(if (stopped) R.string.status_stopped else R.string.status_ready)
    }

    private fun resetLiveDisplayState() {

        liveWaveformFilter.reset()

        // Preserve rendered history. Establish a new local time origin after
        // the last displayed waveform point so restarting a recording does
        // not jump the viewport back to X=0.
        chartSessionOffsetSec =
            latestWaveformX()

        chartStartTimeMs =
            -1L

        lastChartRedrawTime =
            0L

        analysisWindowUiCount =
            0L

        // Do not clear waterfall/history/condition summary on restart.
    }

    private fun latestWaveformX(): Float {

        val data =
            vibrationChart.data
                ?: return 0f

        var latest =
            0f

        for (index in 0 until data.dataSetCount) {
            val set =
                data.getDataSetByIndex(index)

            if (set.entryCount > 0) {
                latest =
                    maxOf(
                        latest,
                        set.getEntryForIndex(set.entryCount - 1).x
                    )
            }
        }

        return latest
    }

    private fun validateSensors(): Boolean {

        if (selectedSensors.isEmpty()) {

            appendLog(
                "[ERROR] No sensors selected."
            )

            Toast.makeText(
                this,
                getString(R.string.sensor_required),
                Toast.LENGTH_SHORT
            ).show()

            focusField(llSensorList)

            return false
        }

        return true
    }

    private fun buildWebSocketUrl(): String {
        endpointBuildRejected = false
        val result = WebSocketEndpointBuilder.build(
            hostInput = etTargetIp.text.toString().trim(),
            portInput = etTargetPort.text.toString().trim(),
            allowInsecure = BuildConfig.DEBUG
        )
        val error = result.error
        if (error != null) {
            endpointBuildRejected = true
            val message = when (error) {
                WebSocketEndpointBuilder.Error.INSECURE_RELEASE -> R.string.network_wss_required
                WebSocketEndpointBuilder.Error.INVALID_PORT -> R.string.network_websocket_port_invalid
                WebSocketEndpointBuilder.Error.INVALID_URL -> R.string.network_websocket_url_invalid
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return ""
        }
        return result.url
    }

    // ============================================================
    // SENSOR DATA -> DASHBOARD
    // ============================================================

    private fun routeDataToDashboard(sample: AcquisitionSample) {
        val type = sample.androidSensorType ?: -1
        val values = sample.values
        val selectedPrimaryType = when {
            selectedSensors.any { it.type == Sensor.TYPE_ACCELEROMETER } -> Sensor.TYPE_ACCELEROMETER
            selectedSensors.any { it.type == Sensor.TYPE_LINEAR_ACCELERATION } -> Sensor.TYPE_LINEAR_ACCELERATION
            else -> -1
        }
        if (type == selectedPrimaryType && values.size >= 3) {
            uiHandler.post {
                updateLiveChart(values[0], values[1], values[2], sample.timestampNs / 1_000_000L)
            }
        }

        queueSensorTelemetry(sample)
    }

    private fun queueSensorTelemetry(sample: AcquisitionSample) {
        val type = sample.androidSensorType ?: -1
        val key = "${sample.sourceKind}|${sample.sourceId}|${sample.channelId}"
        synchronized(pendingTelemetry) {
            pendingTelemetry[key] = SensorTelemetrySample(
                type = type,
                name = sample.name,
                vendor = sample.vendor,
                timestampMs = sample.wallClockTimestampMs,
                values = sample.values.copyOf(),
                sourceKind = sample.sourceKind,
                sourceId = sample.sourceId,
                channelId = sample.channelId,
                unit = sample.unit
            )
            if (telemetryUiScheduled) return
            telemetryUiScheduled = true
        }

        uiHandler.postDelayed({
            val batch = synchronized(pendingTelemetry) {
                val values = pendingTelemetry.values.toList()
                pendingTelemetry.clear()
                telemetryUiScheduled = false
                values
            }
            batch.forEach {
                renderSensorTelemetry(
                    type = it.type,
                    sensorName = it.name,
                    values = it.values,
                    timestampMs = it.timestampMs,
                    unit = it.unit,
                    sourceKind = it.sourceKind
                )
            }
        }, TELEMETRY_UI_INTERVAL_MS)
    }

    private fun renderSensorTelemetry(
        type: Int,
        sensorName: String?,
        values: FloatArray,
        timestampMs: Long,
        unit: String = "",
        sourceKind: AcquisitionSourceKind = AcquisitionSourceKind.ANDROID_SENSOR
    ) {
        if (values.isEmpty()) return

        when (type) {
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                tvMagData.text = SensorTelemetryFormatter.summary(type, values)
            Sensor.TYPE_LIGHT ->
                tvLightData.text = SensorTelemetryFormatter.summary(type, values)
            Sensor.TYPE_GRAVITY ->
                tvGravityData.text = SensorTelemetryFormatter.summary(type, values)
        }

        val fallbackLabel = if (sourceKind == AcquisitionSourceKind.ANDROID_SENSOR) {
            SensorTelemetryFormatter.label(type)
        } else {
            sourceKind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
        val identity = sensorName ?: fallbackLabel
        val key = "${sourceKind}|${type}|$identity"
        val valueView = telemetryValueViews.getOrPut(key) {
            val row = TableRow(this)
            row.setPadding(0, dp(6), 0, dp(6))
            row.gravity = android.view.Gravity.CENTER_VERTICAL

            // Same styles as the static rows in section_secondary_telemetry.xml
            val label = TextView(this, null, 0, R.style.SensorMax_Text_RowLabel).apply {
                text = if (sensorName.isNullOrBlank()) {
                    fallbackLabel
                } else {
                    "${fallbackLabel}\n$sensorName"
                }
                setPadding(0, 0, dp(8), 0)
            }

            val value = TextView(this, null, 0, R.style.SensorMax_Text_RowValue)

            row.addView(label, TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2f))
            gridSecondaryTelemetry.addView(row)
            value
        }

        valueView.text = if (sourceKind == AcquisitionSourceKind.ANDROID_SENSOR) {
            SensorTelemetryFormatter.summary(type, values)
        } else {
            val rendered = values.take(6).joinToString(" · ") { String.format(java.util.Locale.US, "%.4f", it) }
            if (unit.isBlank()) rendered else "$rendered $unit"
        }
        valueView.contentDescription = "$fallbackLabel $identity at $timestampMs ms"
    }

    private fun restoreEngineSessionState(
        session: RecordingSession,
        history: List<AnalysisUpdate>
    ) {
        uiHandler.post {
            if (history.isEmpty()) {
                if (session.state == RecordingSessionState.RUNNING) {
                    appendLog("[SESSION] ${session.sessionId} restored · no completed FFT window yet.")
                }
                return@post
            }

            // Rebuild UI state from engine-owned copies. This is deliberately
            // independent of the previous Activity's chart instances.
            waterfallView.clear()
            analysisWindowUiCount = 0L

            history.forEachIndexed { index, update ->
                val result = update.spectrum
                // updateSpectrumDisplay(last) below renders the newest row once;
                // do not pre-push that same frame into the waterfall.
                if (index < history.lastIndex) {
                    waterfallView.pushSpectrum(
                        result.perAxis[result.dominantAxis]?.accelAmplitude ?: FloatArray(0)
                    )
                }
            }

            val last = history.last()
            lastSpectrumResult = last.spectrum
            lastMeasuredHz = last.spectrum.sampleRateHz
            analysisWindowUiCount = history.size.toLong()

            updateSpectrumDisplay(last.spectrum)
            updateTrendChart(sensorEngine.trendHistorySnapshot())
            refreshSeverityBanner()

            appendLog(
                "[SESSION] ${session.sessionId} restored · ${history.size} analysis window(s) · state ${session.state}."
            )
        }
    }

    // ============================================================
    // ANALYSIS -> DASHBOARD
    // ============================================================

    private fun routeAnalysisToDashboard(
        update: AnalysisUpdate
    ) {

        uiHandler.post {

            val result =
                update.spectrum

            val quality =
                update.quality

            lastSpectrumResult =
                result
            lastQualityScore = quality

            lastMeasuredHz =
                result.sampleRateHz

            val nyquistHz =
                result.sampleRateHz /
                        2.0

            val resolutionHz =
                result.sampleRateHz /
                        SensorEngine.FFT_WINDOW_SIZE

            val requestedHz =
                if (
                    targetIntervalMs > 0
                ) {

                    1000.0 /
                            targetIntervalMs

                } else {
                    0.0
                }

            tvSampleRate.text =
                String.format(
                    Locale.US,
                    "%.1f Hz",
                    result.sampleRateHz
                )

            tvSampleRateDetail.setDetail(
                String.format(
                    Locale.US,
                    "Requested %.1f Hz\nNyquist %.1f Hz",
                    requestedHz,
                    nyquistHz
                )
            )

            tvSamplingDiagnostics.setDetail(
                String.format(
                    Locale.US,
                    "Resolution %.3f Hz/bin · Window %d · Jitter %.2f ms · Max gap %.2f ms",
                    resolutionHz,
                    SensorEngine.FFT_WINDOW_SIZE,
                    result.timestampJitterMs,
                    result.maxSampleGapMs
                )
            )

            renderSummaryMetrics(result, quality)

            updateSpectrumDisplay(
                result
            )

            updateTrendChart(
                sensorEngine.trendHistorySnapshot()
            )

            analysisWindowUiCount++

            if (
                analysisWindowUiCount == 1L ||
                analysisWindowUiCount % 10L == 0L
            ) {
                appendLog(
                    String.format(
                        Locale.US,
                        "[FFT] Window %d · Fs %.1f Hz · dominant %s · RMS %.3f m/s²",
                        analysisWindowUiCount,
                        result.sampleRateHz,
                        result.dominantAxis.name,
                        result.overallRmsAccel
                    )
                )

                appendConditionSummaryLog(
                    result,
                    quality
                )
            }

            refreshSeverityBanner()
        }
    }

    // ============================================================
    // IMPACT
    // ============================================================

    private fun routeImpactToDashboard(
        event: ImpactEvent
    ) {

        uiHandler.post {

            val timeStamp =
                SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                ).format(
                    Date()
                )

            val accelUnit = MeasurementUnits.accelerationUnit(measurementSystem)
            tvActivityData.text =
                String.format(
                    Locale.US,
                    getString(R.string.impact_event_format),
                    displayAcceleration(event.magnitude), accelUnit, event.axisContribution,
                    displayAcceleration(event.baselineMagnitude), accelUnit, displayAcceleration(event.thresholdUsed), accelUnit, timeStamp
                )

            tvActivityData.setTextColor(
                getColor(R.color.sm_text_bad)
                )

            appendLog(
                String.format(
                    Locale.US,
                    "[EVENT] Impact %.2f m/s² · axis %s · threshold %.2f",
                    event.magnitude,
                    event.axisContribution,
                    event.thresholdUsed
                )
            )
        }
    }

    private fun appendConditionSummaryLog(
        result: SpectrumResult,
        quality: QualityScore
    ) {

        val isoPosition =
            spinnerIsoClass.selectedItemPosition

        val isoClass =
            IsoMachineClass.values().getOrElse(isoPosition) {
                IsoMachineClass.NONE
            }

        val zone =
            VibrationAnalyzer.classify(
                isoClass,
                result.overallRmsVelocityMmS
            )

        val warn = readAccelerationThresholdSi(etWarnThreshold, SensorEngine.DEFAULT_WARN_MS2)
        val alert = readAccelerationThresholdSi(etAlertThreshold, SensorEngine.DEFAULT_ALERT_MS2)

        val severity =
            when {
                result.overallRmsAccel >= alert || zone == IsoZone.D -> "ALERT"
                result.overallRmsAccel >= warn || zone == IsoZone.C -> "WARNING"
                else -> "OK"
            }

        appendLog(
            String.format(
                Locale.US,
                "[CONDITION] %s · RMS %.3f m/s² · %.3f mm/s · Peak %.2f Hz · Quality %d/100 · %s",
                severity,
                result.overallRmsAccel,
                result.overallRmsVelocityMmS,
                result.perAxis[result.dominantAxis]?.peakFreqHz ?: 0f,
                quality.score0to100,
                zone.label
            )
        )
    }

    // ============================================================
    // SEVERITY
    // ============================================================

    private fun refreshSeverityBanner() {

        val result =
            lastSpectrumResult

        if (result == null) {

            tvSeverityBanner.text =
                "WAITING · First spectrum window not available"

            tvSeverityBanner.setBackgroundColor(
                getColor(R.color.sm_slate)
                )

            tvSeverityBanner.setTextColor(
                Color.WHITE
            )

            return
        }

        val warn = readAccelerationThresholdSi(etWarnThreshold, SensorEngine.DEFAULT_WARN_MS2)
        val alert = readAccelerationThresholdSi(etAlertThreshold, SensorEngine.DEFAULT_ALERT_MS2)

        val isoPosition =
            spinnerIsoClass.selectedItemPosition

        val isoClass =
            IsoMachineClass.values()
                .getOrElse(
                    isoPosition
                ) {
                    IsoMachineClass.NONE
                }

        val zone =
            VibrationAnalyzer.classify(
                isoClass,
                result.overallRmsVelocityMmS
            )

        val severity =
            when {

                result.overallRmsAccel >= alert ||
                        zone == IsoZone.D -> {

                    "ALERT"
                }

                result.overallRmsAccel >= warn ||
                        zone == IsoZone.C -> {

                    "WARNING"
                }

                else -> {

                    "OK"
                }
            }

        val bgColor =
            when (severity) {

                "ALERT" ->
                    getColor(R.color.sm_banner_alert)

                "WARNING" ->
                    getColor(R.color.sm_banner_warn)

                else ->
                    getColor(R.color.sm_banner_ok)
            }

        tvSeverityBanner.text =
            if (
                isoClass ==
                IsoMachineClass.NONE
            ) {

                String.format(
                    Locale.US,
                    "%s · RMS %.3f %s · Threshold mode",
                    severity,
                    displayAcceleration(result.overallRmsAccel),
                    MeasurementUnits.accelerationUnit(measurementSystem)
                )

            } else {

                "$severity · ${zone.label} · " +
                        "${zone.advice}"
            }

        tvSeverityBanner.setBackgroundColor(
            bgColor
        )

        tvSeverityBanner.setTextColor(
            Color.WHITE
        )
    }

    // ============================================================
    // LIVE WAVEFORM CHART
    // ============================================================

    private fun setupRealTimeChart() {

        vibrationChart.apply {

            description.isEnabled =
                false

            setTouchEnabled(
                false
            )

            isDragEnabled =
                false

            setScaleEnabled(
                false
            )

            setPinchZoom(
                false
            )

            setDrawGridBackground(
                false
            )

            setBackgroundColor(
                getColor(R.color.sm_chart_dark)
                )

            extraTopOffset =
                8f

            extraBottomOffset =
                8f

            extraLeftOffset =
                8f

            extraRightOffset =
                8f

            xAxis.apply {

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                setDrawLabels(
                    true
                )

                position =
                    XAxis.XAxisPosition.BOTTOM

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f

                granularity =
                    0.2f

                labelCount =
                    6

                axisMinimum =
                    0f

                valueFormatter =
                    object :
                        ValueFormatter() {

                        override fun getFormattedValue(
                            value: Float
                        ): String {

                            return String.format(
                                Locale.US,
                                "%.1f s",
                                value
                            )
                        }
                    }
            }

            axisLeft.apply {

                isEnabled =
                    true

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                setDrawLabels(
                    true
                )

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f

                axisMinimum =
                    -WAVEFORM_Y_LIMIT_MS2

                axisMaximum =
                    WAVEFORM_Y_LIMIT_MS2
            }

            axisRight.isEnabled =
                false

            legend.apply {

                isEnabled =
                    true

                textColor =
                    getColor(R.color.sm_text_light)

                textSize =
                    11f
            }
        }

        val setX =
            LineDataSet(
                mutableListOf(),
                "X"
            ).apply {

                // Keep the runtime trace identical to the X-axis selector token.
                color = getColor(R.color.sm_axis_x)
                setCircleColor(getColor(R.color.sm_axis_x))
                highLightColor = getColor(R.color.sm_axis_x)

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.6f

                setDrawValues(
                    false
                )
            }

        val setY =
            LineDataSet(
                mutableListOf(),
                "Y"
            ).apply {

                // Keep the runtime trace identical to the Y-axis selector token.
                color = getColor(R.color.sm_axis_y)
                setCircleColor(getColor(R.color.sm_axis_y))
                highLightColor = getColor(R.color.sm_axis_y)

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.6f

                setDrawValues(
                    false
                )
            }

        val setZ =
            LineDataSet(
                mutableListOf(),
                "Z"
            ).apply {

                // Keep the runtime trace identical to the Z-axis selector token.
                color = getColor(R.color.sm_axis_z)
                setCircleColor(getColor(R.color.sm_axis_z))
                highLightColor = getColor(R.color.sm_axis_z)

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.6f

                setDrawValues(
                    false
                )
            }

        val setRss =
            LineDataSet(
                mutableListOf(),
                "RSS"
            ).apply {

                color = getColor(R.color.sm_axis_rss)
                setCircleColor(getColor(R.color.sm_axis_rss))
                highLightColor = getColor(R.color.sm_axis_rss)

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.8f

                setDrawValues(
                    false
                )

                isVisible =
                    false
            }

        vibrationChart.data =
            LineData(
                setX,
                setY,
                setZ,
                setRss
            )

        vibrationChart.setEmptyStateText(getString(R.string.waveform_empty_state))
    }

    // ============================================================
    // LIVE WAVEFORM UPDATE
    // ============================================================

    private fun updateLiveChart(
        x: Float,
        y: Float,
        z: Float,
        timestampMs: Long
    ) {

        val data =
            vibrationChart.data
                ?: return

        val filtered =
            liveWaveformFilter.process(
                timestampMs,
                x,
                y,
                z
            )

        if (
            chartStartTimeMs < 0L
        ) {

            chartStartTimeMs =
                timestampMs
        }

        val t =
            chartSessionOffsetSec +
                    (
                            timestampMs -
                                    chartStartTimeMs
                            ) / 1000f

        val setX =
            data.getDataSetByIndex(
                0
            ) as LineDataSet

        val setY =
            data.getDataSetByIndex(
                1
            ) as LineDataSet

        val setZ =
            data.getDataSetByIndex(
                2
            ) as LineDataSet

        data.addEntry(
            Entry(
                t,
                displayAcceleration(filtered[0])
            ),
            0
        )

        data.addEntry(
            Entry(
                t,
                displayAcceleration(filtered[1])
            ),
            1
        )

        data.addEntry(
            Entry(
                t,
                displayAcceleration(filtered[2])
            ),
            2
        )

        val rss =
            sqrt(
                (
                    filtered[0] * filtered[0] +
                            filtered[1] * filtered[1] +
                            filtered[2] * filtered[2]
                ).toDouble()
            ).toFloat()

        data.addEntry(
            Entry(
                t,
                displayAcceleration(rss)
            ),
            3
        )

        val cutoff =
            t -
                    LIVE_WAVEFORM_SECONDS

        while (
            setX.entryCount > 0 &&
            setX.getEntryForIndex(
                0
            ).x < cutoff
        ) {
            setX.removeFirst()
        }

        while (
            setY.entryCount > 0 &&
            setY.getEntryForIndex(
                0
            ).x < cutoff
        ) {
            setY.removeFirst()
        }

        val setRss =
            data.getDataSetByIndex(
                3
            ) as LineDataSet

        while (
            setZ.entryCount > 0 &&
            setZ.getEntryForIndex(
                0
            ).x < cutoff
        ) {
            setZ.removeFirst()
        }

        while (
            setRss.entryCount > 0 &&
            setRss.getEntryForIndex(
                0
            ).x < cutoff
        ) {
            setRss.removeFirst()
        }

        val now =
            System.currentTimeMillis()

        if (
            now - lastChartRedrawTime >
            CHART_REFRESH_RATE_MS
        ) {

            lastChartRedrawTime =
                now

            data.notifyDataChanged()

            vibrationChart.notifyDataSetChanged()

            vibrationChart.setVisibleXRangeMaximum(
                LIVE_WAVEFORM_SECONDS
            )

            vibrationChart.moveViewToX(
                t
            )

            vibrationChart.invalidate()
        }
    }

    // ============================================================
    // SPECTRUM CHART
    // ============================================================

    private fun setupSpectrumChart() {

        spectrumChart.apply {

            description.isEnabled =
                false

            setTouchEnabled(
                true
            )

            isDragEnabled =
                true

            setScaleEnabled(
                true
            )

            setPinchZoom(
                true
            )

            setDrawGridBackground(
                false
            )

            setBackgroundColor(
                getColor(R.color.sm_chart_dark)
                )

            extraTopOffset =
                8f

            extraBottomOffset =
                8f

            extraLeftOffset =
                8f

            extraRightOffset =
                8f

            xAxis.apply {

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                setDrawLabels(
                    true
                )

                position =
                    XAxis.XAxisPosition.BOTTOM

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f

                valueFormatter =
                    object :
                        ValueFormatter() {

                        override fun getFormattedValue(
                            value: Float
                        ): String {

                            return String.format(
                                Locale.US,
                                "%.0f",
                                value
                            )
                        }
                    }
            }

            axisLeft.apply {

                isEnabled =
                    true

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                axisMinimum =
                    0f

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f
            }

            axisRight.isEnabled =
                false

            legend.apply {

                isEnabled =
                    true

                textColor =
                    getColor(R.color.sm_text_light)

                textSize =
                    11f
            }
        }

        spectrumChart.setEmptyStateText(getString(R.string.spectrum_empty_state))
    }

    // ============================================================
    // ENVELOPE CHART
    // ============================================================

    private fun setupEnvelopeChart() {

        envelopeChart.apply {

            description.isEnabled =
                false

            setTouchEnabled(
                true
            )

            isDragEnabled =
                true

            setScaleEnabled(
                true
            )

            setPinchZoom(
                true
            )

            setDrawGridBackground(
                false
            )

            setBackgroundColor(
                getColor(R.color.sm_chart_dark)
                )

            extraTopOffset =
                8f

            extraBottomOffset =
                8f

            extraLeftOffset =
                8f

            extraRightOffset =
                8f

            xAxis.apply {

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                setDrawLabels(
                    true
                )

                position =
                    XAxis.XAxisPosition.BOTTOM

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f

                valueFormatter =
                    object :
                        ValueFormatter() {

                        override fun getFormattedValue(
                            value: Float
                        ): String {

                            return String.format(
                                Locale.US,
                                "%.0f",
                                value
                            )
                        }
                    }
            }

            axisLeft.apply {

                isEnabled =
                    true

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                axisMinimum =
                    0f

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f
            }

            axisRight.isEnabled =
                false

            legend.apply {

                isEnabled =
                    true

                textColor =
                    getColor(R.color.sm_text_light)

                textSize =
                    11f
            }
        }

        envelopeChart.setEmptyStateText(getString(R.string.envelope_empty_state))
    }

    // ============================================================
    // SPECTRUM DISPLAY
    // ============================================================

    private fun updateSpectrumDisplay(
        result: SpectrumResult,
        updateWaterfall: Boolean = true
    ) {

        val rpm =
            MeasurementUnits.parseInputValue(etRpm.text.toString())?.toFloat() ?: 0f

        val spectrumAndLabel =
            when (axisView) {

                AxisView.RSS -> {

                    val sx =
                        result.perAxis[
                            Axis.X
                        ]

                    val sy =
                        result.perAxis[
                            Axis.Y
                        ]

                    val sz =
                        result.perAxis[
                            Axis.Z
                        ]

                    if (
                        sx == null ||
                        sy == null ||
                        sz == null
                    ) {
                        return
                    }

                    VibrationAnalyzer
                        .combinedRss(
                            sx,
                            sy,
                            sz,
                            result.overallRmsAccel,
                            result.overallRmsVelocityMmS,
                            result.overallRmsDisplacementUm
                        ) to "RSS"
                }

                AxisView.X ->
                    (
                            result.perAxis[
                                Axis.X
                            ] ?: return
                            ) to "X"

                AxisView.Y ->
                    (
                            result.perAxis[
                                Axis.Y
                            ] ?: return
                            ) to "Y"

                AxisView.Z ->
                    (
                            result.perAxis[
                                Axis.Z
                            ] ?: return
                            ) to "Z"

                AxisView.AUTO ->
                    (
                            result.perAxis[
                                result.dominantAxis
                            ] ?: return
                            ) to
                            "${result.dominantAxis} · Auto"
            }

        val spectrum =
            spectrumAndLabel.first

        val axisLabel =
            spectrumAndLabel.second

        // --------------------------------------------------------
        // ACCELERATION SPECTRUM
        // --------------------------------------------------------

        val entries =
            ArrayList<Entry>(
                spectrum.frequenciesHz.size
            )

        for (
        i in spectrum.frequenciesHz.indices
        ) {

            entries.add(
                Entry(
                    spectrum.frequenciesHz[i],
                    displayAcceleration(spectrum.accelAmplitude[i])
                )
            )
        }

        val newSet =
            LineDataSet(
                entries,
                getString(R.string.chart_acceleration_label_format, axisLabel, MeasurementUnits.accelerationUnit(measurementSystem))
            ).apply {

                color =
                    spectrumColor(
                        axisView,
                        result.dominantAxis
                    )

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.8f

                setDrawFilled(
                    true
                )

                fillColor =
                    getColor(R.color.sm_info_cyan)

                fillAlpha =
                    45

                setDrawValues(
                    false
                )

                mode =
                    LineDataSet.Mode.LINEAR
            }

        spectrumChart.data =
            LineData(
                newSet
            )

        val nyquistHz =
            (
                    result.sampleRateHz /
                            2.0
                    )
                .coerceAtLeast(
                    0.0
                )
                .toFloat()

        spectrumChart.xAxis.axisMinimum =
            0f

        spectrumChart.xAxis.axisMaximum =
            nyquistHz

        spectrumChart.xAxis.removeAllLimitLines()

        if (
            rpm > 0f
        ) {

            val oneX =
                rpm / 60f

            addLimitLine(
                spectrumChart,
                oneX,
                "1X",
                getColor(R.color.sm_danger)
                )

            addLimitLine(
                spectrumChart,
                oneX * 2f,
                "2X",
                getColor(R.color.sm_warning)
                )

            addLimitLine(
                spectrumChart,
                oneX * 3f,
                "3X",
                getColor(R.color.sm_lime)
                )
        }

        spectrumChart.setEmptyStateText(
            null
        )
        spectrumChart.invalidate()

        // --------------------------------------------------------
        // DOMINANT FREQUENCY
        // --------------------------------------------------------

        tvDominantFreq.text =
            String.format(
                Locale.US,
                "%.2f Hz",
                spectrum.peakFreqHz
            )

        tvDominantFreqDetail.setDetail(
            if (
                axisView ==
                AxisView.RSS
            ) {

                String.format(
                    Locale.US,
                    getString(R.string.dominant_rss_detail_format),
                    displayAcceleration(spectrum.peakAccelAmplitude),
                    MeasurementUnits.accelerationUnit(measurementSystem),
                    axisLabel
                )

            } else {

                String.format(
                    Locale.US,
                    getString(R.string.dominant_axis_detail_format),
                    displayAcceleration(spectrum.peakAccelAmplitude),
                    MeasurementUnits.accelerationUnit(measurementSystem),
                    axisLabel,
                    spectrum.crestFactor,
                    spectrum.kurtosis
                )
            }
        )

        tvFaultHint.text =
            "Screening hint · " +
                    VibrationAnalyzer.faultHint(
                        spectrum,
                        rpm
                    )

        // --------------------------------------------------------
        // ENVELOPE
        // --------------------------------------------------------

        val envEntries =
            ArrayList<Entry>(
                spectrum.frequenciesHz.size
            )

        for (
        i in spectrum.frequenciesHz.indices
        ) {

            envEntries.add(
                Entry(
                    spectrum.frequenciesHz[i],
                    displayAcceleration(spectrum.envelopeAmplitude[i])
                )
            )
        }

        val envSet =
            LineDataSet(
                envEntries,
                getString(R.string.chart_envelope_label_format, axisLabel, MeasurementUnits.accelerationUnit(measurementSystem))
            ).apply {

                color =
                    getColor(R.color.sm_teal)

                setDrawCircles(
                    false
                )

                lineWidth =
                    1.8f

                setDrawFilled(
                    true
                )

                fillColor =
                    getColor(R.color.sm_teal_dark)

                fillAlpha =
                    45

                setDrawValues(
                    false
                )

                mode =
                    LineDataSet.Mode.LINEAR
            }

        envelopeChart.data =
            LineData(
                envSet
            )

        envelopeChart.xAxis.removeAllLimitLines()

        val geometry =
            currentBearingGeometry()

        val bearingFreqs =
            geometry?.let {
                if (
                    rpm > 0f
                ) {
                    VibrationAnalyzer
                        .bearingFrequencies(
                            it,
                            rpm
                        )
                } else {
                    null
                }
            }

        if (
            geometry != null &&
            bearingFreqs != null
        ) {

            addLimitLine(
                envelopeChart,
                bearingFreqs.ftfHz,
                "FTF",
                getColor(R.color.sm_blue_500)
                )

            addLimitLine(
                envelopeChart,
                bearingFreqs.bpfoHz,
                "BPFO",
                getColor(R.color.sm_danger)
                )

            addLimitLine(
                envelopeChart,
                bearingFreqs.bpfiHz,
                "BPFI",
                getColor(R.color.sm_warning)
                )

            addLimitLine(
                envelopeChart,
                bearingFreqs.bsfHz,
                "BSF",
                getColor(R.color.sm_purple)
                )

            tvBearingHint.text =
                "Bearing screening · " +
                        VibrationAnalyzer
                            .bearingHint(
                                spectrum.envelopePeakFreqHz,
                                spectrum.freqResolutionHz,
                                bearingFreqs
                            ) +
                        "\nGeometry: " +
                        geometry.geometrySource

        } else {

            tvBearingHint.text =
                "Bearing screening unavailable.\n" +
                        "Enter RPM and complete bearing geometry."
        }

        envelopeChart.setEmptyStateText(
            null
        )
        envelopeChart.invalidate()

        // --------------------------------------------------------
        // WATERFALL
        // --------------------------------------------------------

        if (updateWaterfall) {
            // WaterfallView consumes canonical SI amplitudes and displays a
            // dimensionless relative spectrum; keep its history unit-neutral.
            waterfallView.pushSpectrum(spectrum.accelAmplitude)
        }
    }

    // ============================================================
    // TAG MANAGER
    // ============================================================

    private fun setupTagManager() {

        savedPoints.clear()
        savedPoints.addAll(
            assetPointRepository.load()
        )

        tagAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                savedPoints.map { it.displayLabel() }.toMutableList()
            )

        tagAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinnerSavedTags.adapter =
            tagAdapter

        btnAddTag.setOnClickListener {

            val assetName =
                etMachineId.text
                    .toString()
                    .trim()

            val pointName =
                etPointTag.text
                    .toString()
                    .trim()

            val customLabel =
                etCustomTagLabel.text
                    .toString()
                    .trim()

            if (assetName.isBlank() || pointName.isBlank()) {
                Toast.makeText(
                    this,
                    getString(R.string.asset_point_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val saved = runCatching {
                assetPointRepository.upsert(
                    assetNameInput = assetName,
                    pointNameInput = pointName,
                    customLabelInput = customLabel
                )
            }.getOrElse { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.asset_point_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val existingIndex =
                savedPoints.indexOfFirst { it.id == saved.id }

            if (existingIndex >= 0) {
                savedPoints[existingIndex] = saved
            } else {
                savedPoints.add(saved)
            }

            refreshTagAdapter()

            spinnerSavedTags.setSelection(
                savedPoints.indexOfFirst { it.id == saved.id }
                    .coerceAtLeast(0)
            )

            appendLog(
                "[TAG] Saved: ${saved.displayLabel()}"
            )

            etCustomTagLabel.text.clear()
        }

        btnDeleteTag.setOnClickListener {

            val position =
                spinnerSavedTags.selectedItemPosition

            if (
                position >= 0 &&
                position < savedPoints.size
            ) {
                val selected = savedPoints[position]

                if (assetPointRepository.delete(selected.id)) {
                    savedPoints.removeAt(position)
                    refreshTagAdapter()
                    appendLog(
                        "[TAG] Deleted: ${selected.displayLabel()}"
                    )
                }
            }
        }

        spinnerSavedTags.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (
                        position >= 0 &&
                        position < savedPoints.size
                    ) {
                        val selected = savedPoints[position]
                        etMachineId.setText(selected.assetName)
                        etPointTag.setText(selected.pointName)
                        etCustomTagLabel.setText(selected.customLabel)
                        syncTagsToEngine()
                    }
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }
    }

    private fun refreshTagAdapter() {
        tagAdapter.clear()
        tagAdapter.addAll(
            savedPoints.map { it.displayLabel() }
        )
        tagAdapter.notifyDataSetChanged()
    }

    private fun spectrumColor(
        axis: AxisView,
        dominantAxis: Axis
    ): Int {

        return when (axis) {
            AxisView.X -> getColor(R.color.sm_danger)
            AxisView.Y -> getColor(R.color.sm_green)
            AxisView.Z -> getColor(R.color.sm_blue_500)
            AxisView.RSS -> getColor(R.color.sm_teal)
            AxisView.AUTO -> {
                when (dominantAxis) {
                    Axis.X -> getColor(R.color.sm_danger)
                    Axis.Y -> getColor(R.color.sm_green)
                    Axis.Z -> getColor(R.color.sm_blue_500)
                }
            }
        }
    }

    // ============================================================
    // LIMIT LINE
    // ============================================================

    private fun addLimitLine(
        chart: LineChart,
        freqHz: Float,
        label: String,
        lineColorInt: Int
    ) {

        if (
            !freqHz.isFinite() ||
            freqHz <= 0f
        ) {
            return
        }

        val currentMax =
            chart.xAxis.axisMaximum

        if (
            currentMax > 0f &&
            freqHz > currentMax
        ) {
            return
        }

        val line =
            LimitLine(
                freqHz,
                label
            ).apply {

                lineColor =
                    lineColorInt

                lineWidth =
                    1f

                textColor =
                    lineColorInt

                textSize =
                    10f

                enableDashedLine(
                    10f,
                    6f,
                    0f
                )

                labelPosition =
                    LimitLine.LimitLabelPosition.RIGHT_TOP
            }

        chart.xAxis.addLimitLine(
            line
        )
    }

    // ============================================================
    // TREND
    // ============================================================

    private fun setupTrendChart() {

        trendChart.apply {

            description.isEnabled =
                false

            setTouchEnabled(
                false
            )

            isDragEnabled =
                false

            setScaleEnabled(
                false
            )

            setDrawGridBackground(
                false
            )

            setBackgroundColor(
                getColor(R.color.sm_chart_dark)
                )

            extraTopOffset =
                8f

            extraBottomOffset =
                8f

            extraLeftOffset =
                8f

            extraRightOffset =
                8f

            xAxis.apply {

                setDrawLabels(
                    false
                )

                setDrawGridLines(
                    false
                )
            }

            axisLeft.apply {

                setDrawGridLines(
                    true
                )

                gridColor =
                    getColor(R.color.sm_chart_grid)

                textColor =
                    getColor(R.color.sm_text_muted)

                textSize =
                    10f

                axisMinimum =
                    0f
            }

            axisRight.isEnabled =
                false

            legend.isEnabled =
                false
        }

        trendChart.setEmptyStateText(getString(R.string.trend_empty_state))
    }

    private fun updateTrendChart(
        trend: FloatArray
    ) {

        if (
            trend.isEmpty()
        ) {
            trendChart.setEmptyStateText(getString(R.string.trend_empty_state))
            return
        }

        trendChart.setEmptyStateText(
            null
        )

        val entries =
            ArrayList<Entry>(
                trend.size
            )

        var maxValue =
            0f

        for (
        i in trend.indices
        ) {

            val value = displayAcceleration(trend[i].coerceAtLeast(0f))

            entries.add(
                Entry(
                    i.toFloat(),
                    value
                )
            )

            maxValue =
                maxOf(
                    maxValue,
                    value
                )
        }

        val newSet =
            LineDataSet(
                entries,
                getString(R.string.chart_rms_trend_label_format, MeasurementUnits.accelerationUnit(measurementSystem))
            ).apply {

                color =
                    getColor(R.color.sm_orange)

                setDrawCircles(
                    false
                )

                lineWidth =
                    2f

                setDrawValues(
                    false
                )

                setDrawFilled(
                    true
                )

                fillColor =
                    getColor(R.color.sm_orange_dark)

                fillAlpha =
                    35
            }

        trendChart.data =
            LineData(
                newSet
            )

        trendChart.axisLeft.axisMinimum =
            0f

        trendChart.axisLeft.axisMaximum =
            maxOf(
                0.01f,
                maxValue * 1.15f
            )

        val visiblePoints =
            minOf(
                60f,
                trend.size.toFloat()
            )

        trendChart.setVisibleXRangeMaximum(
            visiblePoints
        )

        trendChart.moveViewToX(
            trend.lastIndex.toFloat()
        )

        trendChart.invalidate()
    }

    // ============================================================
    // HARDWARE SENSOR SCAN
    // ============================================================

    private fun scanAndDisplayHardwareSensors() {

        selectedSensors.clear()
        sensorCheckBoxes.clear()

        llSensorList.removeAllViews()

        val sensorList =
            sensorManager.getSensorList(
                Sensor.TYPE_ALL
            )

        val sortedList = SensorSelectionPolicy.sort(sensorList)
        val recommendedSensors = SensorSelectionPolicy.recommended(sensorList)

        for (
        sensor in sortedList
        ) {

            val isPriority = recommendedSensors.contains(sensor)

            if (
                isPriority
            ) {

                selectedSensors.add(
                    sensor
                )
            }

            val rateHz =
                if (
                    sensor.minDelay > 0
                ) {

                    1_000_000f /
                            sensor.minDelay

                } else {
                    0f
                }

            val rateText =
                if (
                    rateHz > 0f
                ) {

                    String.format(
                        Locale.US,
                        "%.1f Hz max",
                        rateHz
                    )

                } else {

                    "event based"
                }

            val checkBox =
                CheckBox(
                    this
                ).apply {

                    text =
                        String.format(
                            Locale.US,
                            "%s\n%s · Type %d · v%d\n" +
                                    "Range %.3f · Resolution %.6f · %s",
                            sensor.name,
                            sensor.vendor,
                            sensor.type,
                            sensor.version,
                            sensor.maximumRange,
                            sensor.resolution,
                            rateText
                        )

                    isChecked =
                        isPriority

                    minHeight =
                        dp(48)

                    setPadding(
                        dp(8),
                        dp(8),
                        dp(8),
                        dp(8)
                    )

                    setTextColor(
                        getColor(R.color.sm_text_light)
                    )

                    textSize =
                        13f

                    includeFontPadding =
                        true

                    setOnCheckedChangeListener {
                            _,
                            checked ->

                        if (
                            checked
                        ) {

                            selectedSensors.add(
                                sensor
                            )

                        } else {

                            selectedSensors.remove(
                                sensor
                            )
                        }
                    }
                }

            sensorCheckBoxes[sensor] = checkBox

            llSensorList.addView(
                checkBox
            )
        }
    }

    // ============================================================
    // LOG
    // ============================================================

    private fun appendLog(
        message: String
    ) {
        uiHandler.post {
            if (!::tvLog.isInitialized) return@post

            val timeStamp = SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(Date())

            // Replace the one-line boot hint with the first real event so the log
            // shows actual diagnostic words immediately after launch.
            val currentText = tvLog.text.toString()
            val previousLines = if (
                currentText.isBlank() || currentText == getString(R.string.log_initial)
            ) {
                emptyList()
            } else {
                currentText.split('\n').filter { it.isNotBlank() }
            }
            val lines = (previousLines + "[$timeStamp] $message")
                .takeLast(MAX_DIAGNOSTIC_LOG_LINES)

            tvLog.text = buildColorCodedDiagnosticLog(lines)
            tvLog.post {
                tvLog.layout?.let { layout ->
                    tvLog.scrollTo(0, layout.height)
                }
            }
        }
    }

    private fun buildColorCodedDiagnosticLog(lines: List<String>): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) result.append('\n')
            val lineStart = result.length
            result.append(line)
            applyDiagnosticLogStyles(result, line, lineStart)
        }
        return result
    }

    private fun applyDiagnosticLogStyles(
        text: SpannableStringBuilder,
        line: String,
        offset: Int
    ) {
        val lineEnd = offset + line.length
        val timestampMatch = Regex("^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\]")
            .find(line)
        timestampMatch?.let { match ->
            text.setSpan(
                ForegroundColorSpan(getColor(R.color.sm_text_secondary)),
                offset + match.range.first,
                offset + match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val tagMatch = Regex("^(?:\\[[0-9:.]+\\]\\s*)?\\[([A-Za-z0-9_-]+)\\]")
            .find(line) ?: return
        val tagGroup = tagMatch.groups[1] ?: return
        val tag = tagGroup.value.uppercase(Locale.ROOT)

        val colorResource = when (tag) {
            "FATAL", "ERROR", "CRITICAL", "EXCEPTION", "FAULT" -> R.color.sm_text_bad
            "WARN", "WARNING", "ALERT" -> R.color.sm_text_warn
            "OK", "SUCCESS", "READY", "CONNECTED" -> R.color.sm_text_ok
            "SENSOR", "SOURCE", "ACQUISITION", "SAMPLE", "AXIS" -> R.color.sm_axis_y
            "NETWORK", "NETWORK-IN", "WEBSOCKET", "WS", "TCP", "NDJSON" -> R.color.sm_axis_z
            "BLE", "AUDIO", "CAMERA", "DEPTH", "LOCATION", "USB", "OTG", "PERMISSION" -> R.color.sm_text_info
            "PROFILE", "EXPORT", "IMPORT", "SNAPSHOT", "FILE", "STORAGE" -> R.color.sm_purple
            "ENGINE", "SERVICE", "SESSION", "INIT", "SYSTEM", "DIAGNOSTIC", "HEALTH" -> R.color.sm_text_accent
            else -> R.color.sm_text_primary
        }

        val tagStart = offset + tagGroup.range.first
        val tagEnd = offset + tagGroup.range.last + 1
        val tagColor = getColor(colorResource)

        // Warnings/errors tint their complete line. Other events color the tag,
        // while the message body remains high-contrast and readable.
        when (tag) {
            "FATAL", "ERROR", "CRITICAL", "EXCEPTION", "FAULT",
            "WARN", "WARNING", "ALERT" -> text.setSpan(
                ForegroundColorSpan(tagColor),
                offset,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            else -> text.setSpan(
                ForegroundColorSpan(tagColor),
                tagStart,
                tagEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        text.setSpan(
            StyleSpan(Typeface.BOLD),
            tagStart,
            tagEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Keep timestamps muted even when the full warning/error line is tinted.
        timestampMatch?.let { match ->
            text.setSpan(
                ForegroundColorSpan(getColor(R.color.sm_text_secondary)),
                offset + match.range.first,
                offset + match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

}
