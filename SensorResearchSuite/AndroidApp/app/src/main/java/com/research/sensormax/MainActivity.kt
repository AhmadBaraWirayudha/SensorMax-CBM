package com.research.sensormax

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
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
import kotlin.math.sqrt
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : Activity() {

    private lateinit var sensorEngine: SensorEngine
    private lateinit var streamClient: StreamClient
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrationChart: LineChart
    private lateinit var spectrumChart: LineChart
    private lateinit var trendChart: LineChart

    private var isDeployed = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStreamAndLog: Button
    private lateinit var btnOfflineLog: Button
    private lateinit var etTargetIp: EditText
    private lateinit var etTargetPort: EditText
    private lateinit var llSensorList: LinearLayout

    // Throttle UI Elements
    private lateinit var seekCaptureRate: SeekBar
    private lateinit var tvCaptureRate: TextView
    private var targetIntervalMs = 20L

    // Advanced Monitors
    private lateinit var tvMagData: TextView
    private lateinit var tvLightData: TextView
    private lateinit var tvGravityData: TextView
    private lateinit var tvActivityData: TextView // now doubles as the shock/impact-event readout

    // CbM analysis UI
    private lateinit var tvSeverityBanner: TextView
    private lateinit var tvOverallRms: TextView
    private lateinit var tvSampleRate: TextView
    private lateinit var tvQualityScore: TextView
    private lateinit var rgAxisSelect: RadioGroup
    private lateinit var etRpm: EditText
    private lateinit var tvDominantFreq: TextView
    private lateinit var tvFaultHint: TextView
    private lateinit var etMachineId: EditText
    private lateinit var etCustomTagLabel: EditText
    private lateinit var btnAddTag: Button
    private lateinit var btnDeleteTag: Button
    private lateinit var spinnerSavedTags: Spinner
    private val savedTags = mutableListOf<String>()
    private lateinit var tagAdapter: ArrayAdapter<String>
    private lateinit var etPointTag: EditText
    private lateinit var spinnerIsoClass: Spinner
    private lateinit var spinnerMountingMethod: Spinner
    private lateinit var spinnerAxisRoleX: Spinner
    private lateinit var spinnerAxisRoleY: Spinner
    private lateinit var spinnerAxisRoleZ: Spinner
    private lateinit var btnCheckMounting: Button
    private lateinit var tvMountingCheck: TextView
    private lateinit var etCalibrationOperator: EditText
    private lateinit var btnRunCalibration: Button
    private lateinit var tvCalibrationResult: TextView
    private lateinit var tvBearingGeometryHeader: TextView
    private lateinit var llBearingGeometryInputs: View
    private lateinit var tvSecondaryTelemetryHeader: TextView
    private lateinit var gridSecondaryTelemetry: View
    private lateinit var etWarnThreshold: EditText
    private lateinit var etAlertThreshold: EditText
    private lateinit var btnSnapshot: Button

    // Envelope spectrum (bearing-fault detection) + bearing geometry + waterfall
    private lateinit var envelopeChart: LineChart
    private lateinit var waterfallView: WaterfallView
    private lateinit var etBearingElements: EditText
    private lateinit var etBearingBallDiameter: EditText
    private lateinit var etBearingPitchDiameter: EditText
    private lateinit var etBearingContactAngle: EditText
    private lateinit var tvBearingHint: TextView

    private val selectedSensors = mutableSetOf<Sensor>()

    // Graphic Render Variables
    private var chartStartTimeMs = -1L
    private val LIVE_WAVEFORM_SECONDS = 2.0f
    private var chartEntryIndex = 0f
    private var lastChartRedrawTime = 0L
    private val CHART_REFRESH_RATE_MS = 33L // Limits UI rendering to ~30 FPS

    private var axisView: AxisView = AxisView.AUTO
    private var lastSpectrumResult: SpectrumResult? = null
    private var lastMeasuredHz: Double = 0.0

    /**
     * Spectrum-chart axis selection (design-debt Section 30). RSS adds an
     * orientation-independent vector-magnitude view on top of the original
     * AUTO/X/Y/Z choices -- see [VibrationAnalyzer.combinedRss].
     */
    private enum class AxisView { AUTO, X, Y, Z, RSS }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupRealTimeChart()
        setupSpectrumChart()
        setupEnvelopeChart()
        setupTrendChart()
        setupThrottlerUI()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        streamClient = StreamClient { msg -> appendLog(msg) }

        sensorEngine = SensorEngine(
            context = this,
            streamClient = streamClient,
            logger = { msg -> appendLog(msg) },
            onSensorSample = { type, values, timestampMs ->
                routeDataToDashboard(type, values, timestampMs)
            },
            onAnalysis = { update -> routeAnalysisToDashboard(update) },
            onImpact = { event -> routeImpactToDashboard(event) }
        )

        setupIsoSpinner()
        setupMountingSpinners()
        setupCollapsibleSections()
        setupCalibrationButton()
        setupAxisSelector()
        setupTagAndThresholdWatchers()
        setupTagManager()
        scanAndDisplayHardwareSensors()
        configureDeploymentButtons()
        configureSnapshotButton()

        appendLog("[INIT] Multi-Modal CbM Matrix Online.")
        appendLog("[INIT] SensorMax ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLog("[INIT] Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("[INIT] Android: ${Build.VERSION.RELEASE} | API ${Build.VERSION.SDK_INT}")
        refreshSeverityBanner()
    }

    private fun initializeViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStreamAndLog = findViewById(R.id.btnStreamAndLog)
        btnOfflineLog = findViewById(R.id.btnOfflineLog)
        etTargetIp = findViewById(R.id.etTargetIp)
        etTargetPort = findViewById(R.id.etTargetPort)
        llSensorList = findViewById(R.id.llSensorList)
        vibrationChart = findViewById(R.id.vibrationChart)
        spectrumChart = findViewById(R.id.spectrumChart)
        trendChart = findViewById(R.id.trendChart)

        tvMagData = findViewById(R.id.tvMagData)
        tvLightData = findViewById(R.id.tvLightData)
        tvGravityData = findViewById(R.id.tvGravityData)
        tvActivityData = findViewById(R.id.tvActivityData)

        seekCaptureRate = findViewById(R.id.seekCaptureRate)
        tvCaptureRate = findViewById(R.id.tvCaptureRate)

        tvSeverityBanner = findViewById(R.id.tvSeverityBanner)
        tvOverallRms = findViewById(R.id.tvOverallRms)
        tvSampleRate = findViewById(R.id.tvSampleRate)
        tvQualityScore = findViewById(R.id.tvQualityScore)
        rgAxisSelect = findViewById(R.id.rgAxisSelect)
        etRpm = findViewById(R.id.etRpm)
        tvDominantFreq = findViewById(R.id.tvDominantFreq)
        tvFaultHint = findViewById(R.id.tvFaultHint)
        etMachineId = findViewById(R.id.etMachineId)
        etPointTag = findViewById(R.id.etPointTag)
        etCustomTagLabel = findViewById(R.id.etCustomTagLabel)
        btnAddTag = findViewById(R.id.btnAddTag)
        btnDeleteTag = findViewById(R.id.btnDeleteTag)
        spinnerSavedTags = findViewById(R.id.spinnerSavedTags)
        spinnerIsoClass = findViewById(R.id.spinnerIsoClass)
        spinnerMountingMethod = findViewById(R.id.spinnerMountingMethod)
        spinnerAxisRoleX = findViewById(R.id.spinnerAxisRoleX)
        spinnerAxisRoleY = findViewById(R.id.spinnerAxisRoleY)
        spinnerAxisRoleZ = findViewById(R.id.spinnerAxisRoleZ)
        btnCheckMounting = findViewById(R.id.btnCheckMounting)
        tvMountingCheck = findViewById(R.id.tvMountingCheck)
        etCalibrationOperator = findViewById(R.id.etCalibrationOperator)
        btnRunCalibration = findViewById(R.id.btnRunCalibration)
        tvCalibrationResult = findViewById(R.id.tvCalibrationResult)
        tvBearingGeometryHeader = findViewById(R.id.tvBearingGeometryHeader)
        llBearingGeometryInputs = findViewById(R.id.llBearingGeometryInputs)
        tvSecondaryTelemetryHeader = findViewById(R.id.tvSecondaryTelemetryHeader)
        gridSecondaryTelemetry = findViewById(R.id.gridSecondaryTelemetry)
        etWarnThreshold = findViewById(R.id.etWarnThreshold)
        etAlertThreshold = findViewById(R.id.etAlertThreshold)
        btnSnapshot = findViewById(R.id.btnSnapshot)

        envelopeChart = findViewById(R.id.envelopeChart)
        waterfallView = findViewById(R.id.waterfallView)
        etBearingElements = findViewById(R.id.etBearingElements)
        etBearingBallDiameter = findViewById(R.id.etBearingBallDiameter)
        etBearingPitchDiameter = findViewById(R.id.etBearingPitchDiameter)
        etBearingContactAngle = findViewById(R.id.etBearingContactAngle)
        tvBearingHint = findViewById(R.id.tvBearingHint)
    }

    private fun setupThrottlerUI() {
        // Slider mapped from 5ms (200Hz) to 500ms (2Hz)
        seekCaptureRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                targetIntervalMs = (progress + 5).toLong()
                val hz = 1000 / targetIntervalMs
                tvCaptureRate.text = "Capture Interval: $targetIntervalMs ms (~$hz Hz requested)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupIsoSpinner() {
        val labels = IsoMachineClass.values().map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIsoClass.adapter = adapter
        spinnerIsoClass.setSelection(0) // IsoMachineClass.NONE
        spinnerIsoClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncTagsToEngine()
                refreshSeverityBanner()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Mounting Method Library (Section 57) + Orientation Library (Section
     * 58) spinners, and the "Check Mounting" pre-flight stability check
     * (Section 17). All three are plain-text/UI concerns -- the actual
     * stability math lives in [MountingCheck].
     */
    private fun setupMountingSpinners() {
        fun plainAdapter(items: List<String>): ArrayAdapter<String> {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            return adapter
        }

        val mountingMethods = listOf("Handheld", "Magnetic", "Tape", "Adhesive", "Rigid fixture", "Unknown")
        spinnerMountingMethod.adapter = plainAdapter(mountingMethods)
        spinnerMountingMethod.setSelection(mountingMethods.indexOf("Unknown"))

        val axisRoles = listOf("Unspecified", "Radial", "Axial", "Tangential", "Vertical", "Horizontal", "Custom")
        spinnerAxisRoleX.adapter = plainAdapter(axisRoles)
        spinnerAxisRoleY.adapter = plainAdapter(axisRoles)
        spinnerAxisRoleZ.adapter = plainAdapter(axisRoles)

        btnCheckMounting.setOnClickListener {
            if (!validateSensors()) return@setOnClickListener
            syncTagsToEngine()

            tvMountingCheck.text = "Mounting: checking (hold the phone still)..."
            tvMountingCheck.setTextColor(Color.parseColor("#475569"))

            sensorEngine.runMountingCheck(selectedSensors) { result ->
                uiHandler.post {
                    if (result == null) {
                        tvMountingCheck.text = "Mounting: could not run -- no accelerometer selected."
                        tvMountingCheck.setTextColor(Color.parseColor("#DC2626"))
                        return@post
                    }

                    val verticalRole = when (result.dominantAxis) {
                        Axis.X -> spinnerAxisRoleX.selectedItem?.toString()
                        Axis.Y -> spinnerAxisRoleY.selectedItem?.toString()
                        Axis.Z -> spinnerAxisRoleZ.selectedItem?.toString()
                    }
                    val anyMarkedVertical = listOf(
                        spinnerAxisRoleX.selectedItem?.toString(),
                        spinnerAxisRoleY.selectedItem?.toString(),
                        spinnerAxisRoleZ.selectedItem?.toString()
                    ).any { it == "Vertical" }
                    val mismatchNote = if (anyMarkedVertical && verticalRole != "Vertical") {
                        "  \u26A0 you marked a different axis as Vertical, but gravity is strongest on axis ${result.dominantAxis} right now."
                    } else {
                        ""
                    }

                    tvMountingCheck.text = String.format(
                        Locale.US,
                        "Mounting: %s  |  Stability \u00B1%.1f\u00B0  |  Movement: %s  |  Gravity axis: %s%s",
                        if (result.ready) "READY" else "NOT READY",
                        result.orientationStabilityDeg,
                        result.movementDuringSetup,
                        result.dominantAxis,
                        mismatchNote
                    )
                    tvMountingCheck.setTextColor(
                        if (result.ready) Color.parseColor("#059669") else Color.parseColor("#D97706")
                    )
                }
            }
        }
    }

    /**
     * Section 43 (UI Design Debt) calls out "too many controls visible
     * simultaneously" and asks for advanced settings to be collapsible.
     * Bearing geometry and secondary telemetry are the two call-outs
     * that fit that description without needing a multi-screen rewrite --
     * both start collapsed; tapping the header toggles visibility.
     */
    private fun setupCollapsibleSections() {
        fun wire(header: TextView, body: View, collapsedLabel: String, expandedLabel: String) {
            fun render(expanded: Boolean) {
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                header.text = if (expanded) expandedLabel else collapsedLabel
            }
            render(false)
            header.setOnClickListener {
                render(body.visibility != View.VISIBLE)
            }
        }

        wire(
            tvBearingGeometryHeader,
            llBearingGeometryInputs,
            "\u25B8 Bearing geometry (optional -- tap to enter)",
            "\u25BE Bearing geometry (tap to collapse)"
        )
        wire(
            tvSecondaryTelemetryHeader,
            gridSecondaryTelemetry,
            "\u25B8 3. Secondary Sensor Telemetry (tap to expand)",
            "\u25BE 3. Secondary Sensor Telemetry (tap to collapse)"
        )
    }

    /** "Run Calibration" (design-debt Section 33) -- same short-burst pattern as Check Mounting. */
    private fun setupCalibrationButton() {
        btnRunCalibration.setOnClickListener {
            if (!validateSensors()) return@setOnClickListener
            syncTagsToEngine()

            tvCalibrationResult.text = "Calibration: running (keep the sensor still & undisturbed)..."
            tvCalibrationResult.setTextColor(Color.parseColor("#475569"))

            val operatorName = etCalibrationOperator.text.toString()

            sensorEngine.runCalibration(selectedSensors, operatorName) { record ->
                uiHandler.post {
                    if (record == null) {
                        tvCalibrationResult.text = "Calibration: could not run -- no accelerometer selected."
                        tvCalibrationResult.setTextColor(Color.parseColor("#DC2626"))
                        return@post
                    }

                    val noiseMag = record.noiseMagnitude()
                    tvCalibrationResult.text = String.format(
                        Locale.US,
                        "Calibration: %s  |  Bias (%.2f, %.2f, %.2f) m/s\u00B2  |  Noise %.3f m/s\u00B2 RSS  |  Achieved %.1f Hz",
                        record.calId, record.biasX, record.biasY, record.biasZ, noiseMag, record.achievedSamplingHz
                    )
                    tvCalibrationResult.setTextColor(
                        if (noiseMag <= 0.15f) Color.parseColor("#059669") else Color.parseColor("#D97706")
                    )
                }
            }
        }
    }

    private fun setupAxisSelector() {
        rgAxisSelect.setOnCheckedChangeListener { _, checkedId ->
            axisView = when (checkedId) {
                R.id.rbAxisX -> AxisView.X
                R.id.rbAxisY -> AxisView.Y
                R.id.rbAxisZ -> AxisView.Z
                R.id.rbAxisRss -> AxisView.RSS
                else -> AxisView.AUTO
            }
            // History mixing two different views in one waterfall is more confusing than useful.
            waterfallView.clear()
            lastSpectrumResult?.let { updateSpectrumDisplay(it) }
        }
    }

    private fun setupTagAndThresholdWatchers() {
        val watcher = simpleWatcher {
            syncTagsToEngine()
            refreshSeverityBanner()
        }
        etMachineId.addTextChangedListener(watcher)
        etPointTag.addTextChangedListener(watcher)
        etRpm.addTextChangedListener(watcher)
        etWarnThreshold.addTextChangedListener(watcher)
        etAlertThreshold.addTextChangedListener(watcher)
        etBearingElements.addTextChangedListener(watcher)
        etBearingBallDiameter.addTextChangedListener(watcher)
        etBearingPitchDiameter.addTextChangedListener(watcher)
        etBearingContactAngle.addTextChangedListener(watcher)

        etWarnThreshold.setText(String.format(Locale.US, "%.1f", SensorEngine.DEFAULT_WARN_MS2))
        etAlertThreshold.setText(String.format(Locale.US, "%.1f", SensorEngine.DEFAULT_ALERT_MS2))
    }

    private fun simpleWatcher(onChanged: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChanged() }
    }

    private fun syncTagsToEngine() {
        sensorEngine.machineId = etMachineId.text.toString().ifBlank { "UNSPECIFIED" }
        sensorEngine.pointTag = etPointTag.text.toString().ifBlank { "UNSPECIFIED" }
        sensorEngine.rpmHint = etRpm.text.toString().toFloatOrNull() ?: 0f
        sensorEngine.isoClass = IsoMachineClass.values()[spinnerIsoClass.selectedItemPosition]
        sensorEngine.warnThresholdMs2 = etWarnThreshold.text.toString().toFloatOrNull() ?: SensorEngine.DEFAULT_WARN_MS2
        sensorEngine.alertThresholdMs2 = etAlertThreshold.text.toString().toFloatOrNull() ?: SensorEngine.DEFAULT_ALERT_MS2
        sensorEngine.bearingNumElements = etBearingElements.text.toString().toIntOrNull() ?: 0
        sensorEngine.bearingBallDiameterMm = etBearingBallDiameter.text.toString().toFloatOrNull() ?: 0f
        sensorEngine.bearingPitchDiameterMm = etBearingPitchDiameter.text.toString().toFloatOrNull() ?: 0f
        sensorEngine.bearingContactAngleDeg = etBearingContactAngle.text.toString().toFloatOrNull() ?: 0f
        sensorEngine.mountingMethod = spinnerMountingMethod.selectedItem?.toString() ?: "Unknown"
        sensorEngine.axisRoleX = spinnerAxisRoleX.selectedItem?.toString() ?: "Unspecified"
        sensorEngine.axisRoleY = spinnerAxisRoleY.selectedItem?.toString() ?: "Unspecified"
        sensorEngine.axisRoleZ = spinnerAxisRoleZ.selectedItem?.toString() ?: "Unspecified"
    }

    /** Mirrors what was just pushed to [sensorEngine] -- used locally for the spectrum chart's bearing limit lines. */
    private fun currentBearingGeometry(): BearingGeometry? {
        val n = etBearingElements.text.toString().toIntOrNull() ?: 0
        val ball = etBearingBallDiameter.text.toString().toFloatOrNull() ?: 0f
        val pitch = etBearingPitchDiameter.text.toString().toFloatOrNull() ?: 0f
        val angle = etBearingContactAngle.text.toString().toFloatOrNull() ?: 0f
        if (n <= 0 || ball <= 0f || pitch <= 0f) return null
        return BearingGeometry(n, ball, pitch, angle)
    }

    private fun configureSnapshotButton() {
        btnSnapshot.setOnClickListener {
            val ok = sensorEngine.captureSnapshotNow()
            if (ok) {
                Toast.makeText(this, "Snapshot captured: ${etMachineId.text} / ${etPointTag.text}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No spectrum yet -- hold steady a few seconds after starting a session.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun configureDeploymentButtons() {
        // MODE 1: Stream (Internet or USB ADB) + Local CSV Logging
        btnStreamAndLog.setOnClickListener {
            if (!isDeployed) {
                if (!validateSensors()) return@setOnClickListener
                syncTagsToEngine()
                val url = buildWebSocketUrl()

                streamClient.connectToInternetEndpoint(url)
                sensorEngine.startDeployment(
                    sensors = selectedSensors,
                    intervalMs = targetIntervalMs,
                    enableNetwork = true,
                    enableDisk = true
                )

                isDeployed = true
                toggleUIState(isStreaming = true)
            } else {
                haltDeployment()
            }
        }

        // MODE 2: Strictly Offline CSV Logging
        btnOfflineLog.setOnClickListener {
            if (!isDeployed) {
                if (!validateSensors()) return@setOnClickListener
                syncTagsToEngine()

                sensorEngine.startDeployment(
                    sensors = selectedSensors,
                    intervalMs = targetIntervalMs,
                    enableNetwork = false,
                    enableDisk = true
                )

                isDeployed = true
                toggleUIState(isStreaming = false)
            } else {
                haltDeployment()
            }
        }
    }

    private fun validateSensors(): Boolean {
        if (selectedSensors.isEmpty()) {
            appendLog("[ERROR] Hardware array empty. Select at least one node.")
            return false
        }
        return true
    }

    private fun buildWebSocketUrl(): String {
        val ipInput = etTargetIp.text.toString().trim()
        val portInput = etTargetPort.text.toString().trim()

        // Note: For ADB USB Streaming, set IP to ws://127.0.0.1
        // and run `adb reverse tcp:8765 tcp:8765` on your laptop terminal.
        return if (ipInput.startsWith("ws://") || ipInput.startsWith("wss://")) {
            if (portInput.isNotEmpty() && ipInput.indexOf(":", 6) == -1) "$ipInput:$portInput" else ipInput
        } else {
            val port = if (portInput.isNotEmpty()) portInput else "8765"
            "ws://$ipInput:$port"
        }
    }

    private fun toggleUIState(isStreaming: Boolean) {
        if (isStreaming) {
            btnStreamAndLog.text = "HALT STREAM & LOG SEQUENCE"
            btnStreamAndLog.setBackgroundColor(Color.RED)
            btnOfflineLog.isEnabled = false
            btnOfflineLog.setBackgroundColor(Color.GRAY)
            tvStatus.text = "Status: ACTIVE | Transmitting & Recording @ ${targetIntervalMs}ms"
        } else {
            btnOfflineLog.text = "HALT OFFLINE LOG SEQUENCE"
            btnOfflineLog.setBackgroundColor(Color.RED)
            btnStreamAndLog.isEnabled = false
            btnStreamAndLog.setBackgroundColor(Color.GRAY)
            tvStatus.text = "Status: ACTIVE | Stealth Offline Logging @ ${targetIntervalMs}ms"
        }
    }

    private fun haltDeployment() {
        sensorEngine.stopDeployment()
        isDeployed = false
        chartStartTimeMs = -1L
        chartEntryIndex = 0f
        lastChartRedrawTime = 0L

        btnStreamAndLog.isEnabled = true
        btnStreamAndLog.text = "1. STREAM & LOG (INTERNET / ADB USB)"
        btnStreamAndLog.setBackgroundColor(Color.parseColor("#059669"))

        btnOfflineLog.isEnabled = true
        btnOfflineLog.text = "2. OFFLINE LOG ONLY (NO NETWORK)"
        btnOfflineLog.setBackgroundColor(Color.parseColor("#DC2626"))

        tvStatus.text = "Status: Disengaged. Ready for deployment."
    }

    private fun routeDataToDashboard(
        type: Int,
        values: FloatArray,
        timestampMs: Long
    ) {
        uiHandler.post {

            if (
                type == Sensor.TYPE_ACCELEROMETER ||
                type == Sensor.TYPE_LINEAR_ACCELERATION
            ) {
                updateLiveChart(
                    values[0],
                    values[1],
                    values[2],
                    timestampMs
                )
                return@post
            }

            when (type) {
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (values.size >= 3) {
                        val fieldStrength = sqrt(
                            (
                                    values[0] * values[0] +
                                            values[1] * values[1] +
                                            values[2] * values[2]
                                    ).toDouble()
                        )

                        tvMagData.text =
                            String.format(
                                Locale.US,
                                "%.1f µT",
                                fieldStrength
                            )
                    }
                }

                Sensor.TYPE_LIGHT -> {
                    tvLightData.text =
                        String.format(
                            Locale.US,
                            "%.1f lx",
                            values[0]
                        )
                }

                Sensor.TYPE_GRAVITY -> {
                    if (values.size >= 3) {
                        tvGravityData.text =
                            String.format(
                                Locale.US,
                                "%.2f m/s²",
                                values[2]
                            )
                    }
                }
            }
        }
    }

    private fun routeAnalysisToDashboard(update: AnalysisUpdate) {
        uiHandler.post {
            val result = update.spectrum
            val quality = update.quality

            lastSpectrumResult = result
            lastMeasuredHz = result.sampleRateHz

            val nyquistHz = result.sampleRateHz / 2.0
            val resolutionHz = result.sampleRateHz / SensorEngine.FFT_WINDOW_SIZE
            // Requested vs. measured (design-debt Section 5.3): the capture
            // throttle below caps the request; the FFT always uses what was
            // actually measured (result.sampleRateHz), never this figure.
            val requestedHz = if (targetIntervalMs > 0) 1000.0 / targetIntervalMs else 0.0

            tvSampleRate.text = String.format(
                Locale.US,
                "Measured: %.1f Hz (requested ~%.1f Hz) | FFT: 0–%.1f Hz | Resolution: %.3f Hz/bin | Window: %d | Jitter: %.2f ms",
                result.sampleRateHz,
                requestedHz,
                nyquistHz,
                resolutionHz,
                SensorEngine.FFT_WINDOW_SIZE,
                result.timestampJitterMs
            )

            tvOverallRms.text = String.format(
                Locale.US,
                "Overall RMS: %.3f m/s² (%.3f g)  |  %.3f mm/s velocity (derived)  |  %.1f µm displacement (derived)",
                result.overallRmsAccel,
                result.overallRmsAccel / 9.80665f,
                result.overallRmsVelocityMmS,
                result.overallRmsDisplacementUm
            )

            // Measurement Quality (design-debt Section 16) -- kept separate
            // from machine-health state on purpose: this says how much to
            // trust the reading, not whether the machine looks healthy.
            // Only non-passing factors are named, to avoid a wall of
            // green checkmarks; Calibration/Mounting/Orientation are
            // still N/A (Sections 17, 33 aren't built yet) so they never
            // appear here even though they're part of the doc's full list.
            val notable = quality.notableFactors()
            tvQualityScore.text = if (notable.isEmpty()) {
                "Measurement quality: ${quality.score0to100}/100  |  all checked factors passing"
            } else {
                "Measurement quality: ${quality.score0to100}/100  |  " +
                        notable.joinToString("  |  ") { "${it.name}: ${it.grade}" }
            }
            tvQualityScore.setTextColor(
                when {
                    quality.score0to100 >= 80 -> Color.parseColor("#059669")
                    quality.score0to100 >= 50 -> Color.parseColor("#D97706")
                    else -> Color.parseColor("#DC2626")
                }
            )

            updateSpectrumDisplay(result)
            updateTrendChart(result.trend)
            refreshSeverityBanner()
        }
    }

    private fun routeImpactToDashboard(event: ImpactEvent) {
        uiHandler.post {
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvActivityData.text = String.format(
                Locale.US,
                "Shock @ %.2f m/s\u00B2 (axis %s, baseline %.2f, threshold %.2f) %s",
                event.magnitude, event.axisContribution, event.baselineMagnitude, event.thresholdUsed, timeStamp
            )
            tvActivityData.setTextColor(Color.parseColor("#DC2626"))
            appendLog(
                String.format(
                    Locale.US,
                    "[EVENT] Transient/impact detected: %.2f m/s\u00B2 (axis %s, threshold %.2f)",
                    event.magnitude, event.axisContribution, event.thresholdUsed
                )
            )
        }
    }

    private fun refreshSeverityBanner() {
        val result = lastSpectrumResult
        if (result == null) {
            tvSeverityBanner.text = "STATUS: Awaiting first spectrum window..."
            tvSeverityBanner.setBackgroundColor(Color.parseColor("#E2E8F0"))
            tvSeverityBanner.setTextColor(Color.parseColor("#334155"))
            return
        }

        val warn = etWarnThreshold.text.toString().toFloatOrNull() ?: SensorEngine.DEFAULT_WARN_MS2
        val alert = etAlertThreshold.text.toString().toFloatOrNull() ?: SensorEngine.DEFAULT_ALERT_MS2
        val isoClass = IsoMachineClass.values()[spinnerIsoClass.selectedItemPosition]
        val zone = VibrationAnalyzer.classify(isoClass, result.overallRmsVelocityMmS)

        val bg: String
        val verdict: String
        when {
            result.overallRmsAccel >= alert || zone == IsoZone.D -> { bg = "#DC2626"; verdict = "ALERT" }
            result.overallRmsAccel >= warn || zone == IsoZone.C -> { bg = "#D97706"; verdict = "WARNING" }
            else -> { bg = "#059669"; verdict = "OK" }
        }

        tvSeverityBanner.text = if (isoClass == IsoMachineClass.NONE) {
            String.format(
                Locale.US, "STATUS: %s  (Overall RMS %.3f m/s\u00B2 vs user-configured warn %.1f / alert %.1f)",
                verdict, result.overallRmsAccel, warn, alert
            )
        } else {
            "STATUS: $verdict  |  ISO 20816-3 ${zone.label} -- ${zone.advice}"
        }
        tvSeverityBanner.setBackgroundColor(Color.parseColor(bg))
        tvSeverityBanner.setTextColor(Color.WHITE)
    }

    private fun setupRealTimeChart() {
        vibrationChart.apply {
            description.isEnabled = false
            setTouchEnabled(false) // Disabled touch interactions to save massive CPU cycles
            isDragEnabled = false
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.WHITE)


            xAxis.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#CBD5E1")
                setDrawLabels(true)
                position = XAxis.XAxisPosition.BOTTOM

                textColor = Color.parseColor("#334155")
                textSize = 10f

                granularity = 0.2f
                labelCount = 6

                axisMinimum = 0f

                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return String.format(
                            Locale.US,
                            "%.1f s",
                            value
                        )
                    }
                }
            }

            axisLeft.apply {
                isEnabled = true
                setDrawGridLines(true)
                gridColor = Color.parseColor("#CBD5E1")
                setDrawLabels(true)
                textColor = Color.parseColor("#334155")
                textSize = 10f
            }
            axisRight.isEnabled = false
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#334155")
            }
        }

        val setX = LineDataSet(mutableListOf(), "X (m/s²)").apply { color = Color.parseColor("#DC2626"); setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false) }
        val setY = LineDataSet(mutableListOf(), "Y (m/s²)").apply { color = Color.parseColor("#059669"); setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false) }
        val setZ = LineDataSet(mutableListOf(), "Z (m/s²)").apply { color = Color.parseColor("#2563EB"); setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false) }

        vibrationChart.data = LineData(setX, setY, setZ)
    }

    private fun updateLiveChart(
        x: Float,
        y: Float,
        z: Float,
        timestampMs: Long
    ) {
        val data = vibrationChart.data ?: return

        if (chartStartTimeMs < 0L) {
            chartStartTimeMs = timestampMs
        }

        val t = (timestampMs - chartStartTimeMs) / 1000f

        val setX = data.getDataSetByIndex(0) as LineDataSet
        val setY = data.getDataSetByIndex(1) as LineDataSet
        val setZ = data.getDataSetByIndex(2) as LineDataSet

        data.addEntry(Entry(t, x), 0)
        data.addEntry(Entry(t, y), 1)
        data.addEntry(Entry(t, z), 2)

        val cutoff = t - LIVE_WAVEFORM_SECONDS

        while (
            setX.entryCount > 0 &&
            setX.getEntryForIndex(0).x < cutoff
        ) {
            setX.removeFirst()
        }

        while (
            setY.entryCount > 0 &&
            setY.getEntryForIndex(0).x < cutoff
        ) {
            setY.removeFirst()
        }

        while (
            setZ.entryCount > 0 &&
            setZ.getEntryForIndex(0).x < cutoff
        ) {
            setZ.removeFirst()
        }

        val now = System.currentTimeMillis()

        if (now - lastChartRedrawTime > CHART_REFRESH_RATE_MS) {
            lastChartRedrawTime = now

            // Find symmetric Y limit so the waveform is centered around zero.
            var maxAbs = 0f

            for (i in 0 until setX.entryCount) {
                maxAbs = maxOf(maxAbs, kotlin.math.abs(setX.getEntryForIndex(i).y))
            }

            for (i in 0 until setY.entryCount) {
                maxAbs = maxOf(maxAbs, kotlin.math.abs(setY.getEntryForIndex(i).y))
            }

            for (i in 0 until setZ.entryCount) {
                maxAbs = maxOf(maxAbs, kotlin.math.abs(setZ.getEntryForIndex(i).y))
            }

            val yLimit = maxOf(
                0.1f,
                maxAbs * 1.15f
            )

            vibrationChart.axisLeft.axisMinimum = -yLimit
            vibrationChart.axisLeft.axisMaximum = yLimit

            data.notifyDataChanged()
            vibrationChart.notifyDataSetChanged()

            vibrationChart.setVisibleXRangeMaximum(
                LIVE_WAVEFORM_SECONDS
            )

            vibrationChart.moveViewToX(t)
            vibrationChart.invalidate()
        }
    }

    private fun setupSpectrumChart() {
        spectrumChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.WHITE)

            xAxis.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E2E8F0")
                setDrawLabels(true)
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#64748B")
                textSize = 9f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        String.format(Locale.US, "%.0f Hz", value)
                }
            }
            axisLeft.apply {
                isEnabled = true
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E2E8F0")
                axisMinimum = 0f
                textColor = Color.parseColor("#64748B")
            }
            axisRight.isEnabled = false
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#334155")
            }
        }
    }

    /** Same styling as the spectrum chart -- this is the demodulated envelope spectrum, where bearing-fault energy actually shows up. */
    private fun setupEnvelopeChart() {
        envelopeChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.WHITE)

            xAxis.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E2E8F0")
                setDrawLabels(true)
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#64748B")
                textSize = 9f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        String.format(Locale.US, "%.0f Hz", value)
                }
            }
            axisLeft.apply {
                isEnabled = true
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E2E8F0")
                axisMinimum = 0f
                textColor = Color.parseColor("#64748B")
            }
            axisRight.isEnabled = false
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#334155")
            }
        }
    }

    /** Rebuilds the spectrum chart's data wholesale each window -- simpler and safer than mutating an existing dataset, and this only runs every ~256 samples, not per-frame. */
    private fun updateSpectrumDisplay(result: SpectrumResult) {
        val rpm = etRpm.text.toString().toFloatOrNull() ?: 0f

        // RSS synthesizes a full AxisSpectrum on the fly (see combinedRss) so
        // everything below -- charts, fault hint, bearing hint, waterfall --
        // works unchanged whichever view is selected.
        val (spectrum, axisLabel) = when (axisView) {
            AxisView.RSS -> {
                val sx = result.perAxis[Axis.X]
                val sy = result.perAxis[Axis.Y]
                val sz = result.perAxis[Axis.Z]
                if (sx == null || sy == null || sz == null) return
                VibrationAnalyzer.combinedRss(
                    sx, sy, sz,
                    result.overallRmsAccel, result.overallRmsVelocityMmS, result.overallRmsDisplacementUm
                ) to "RSS (vector)"
            }
            AxisView.X -> (result.perAxis[Axis.X] ?: return) to "X"
            AxisView.Y -> (result.perAxis[Axis.Y] ?: return) to "Y"
            AxisView.Z -> (result.perAxis[Axis.Z] ?: return) to "Z"
            AxisView.AUTO -> (result.perAxis[result.dominantAxis] ?: return) to "${result.dominantAxis} (auto)"
        }

        // -- Main acceleration spectrum, with 1X/2X/3X order markers --
        val entries = ArrayList<Entry>(spectrum.frequenciesHz.size)
        for (i in spectrum.frequenciesHz.indices) {
            entries.add(Entry(spectrum.frequenciesHz[i], spectrum.accelAmplitude[i]))
        }
        val newSet = LineDataSet(entries, "Axis $axisLabel spectrum (m/s²)").apply {
            color = Color.parseColor("#7C3AED")
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawFilled(true)
            fillColor = Color.parseColor("#7C3AED")
            fillAlpha = 60
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        spectrumChart.data = LineData(newSet)

        val nyquistHz =
            (result.sampleRateHz / 2.0)
                .coerceAtLeast(0.0)
                .toFloat()

        spectrumChart.xAxis.axisMinimum = 0f
        spectrumChart.xAxis.axisMaximum = nyquistHz

        spectrumChart.xAxis.removeAllLimitLines()
        if (rpm > 0f) {
            val oneX = rpm / 60f
            addLimitLine(spectrumChart, oneX, "1X", Color.parseColor("#DC2626"))
            addLimitLine(spectrumChart, oneX * 2, "2X", Color.parseColor("#D97706"))
            addLimitLine(spectrumChart, oneX * 3, "3X", Color.parseColor("#65A30D"))
        }
        spectrumChart.invalidate()

        // RPM is always a manual, operator-entered value today (design-debt
        // Section 62) -- there is no tachometer/optical/estimated source yet,
        // so the label says so rather than implying it was measured.
        val rpmLabel = if (rpm > 0f) String.format(Locale.US, "  |  RPM %.0f (manual entry)", rpm) else ""

        // Crest factor / kurtosis are time-domain stats that don't have a
        // meaningful equivalent for the synthetic RSS combination -- see
        // combinedRss() -- so they're only shown for a real single/auto axis.
        tvDominantFreq.text = if (axisView == AxisView.RSS) {
            String.format(
                Locale.US, "Dominant peak: %.2f Hz @ %.3f m/s\u00B2  (axis %s)%s",
                spectrum.peakFreqHz, spectrum.peakAccelAmplitude, axisLabel, rpmLabel
            )
        } else {
            String.format(
                Locale.US, "Dominant peak: %.2f Hz @ %.3f m/s\u00B2  (axis %s)  |  Crest %.2f  |  Kurtosis %.2f%s",
                spectrum.peakFreqHz, spectrum.peakAccelAmplitude, axisLabel, spectrum.crestFactor, spectrum.kurtosis, rpmLabel
            )
        }
        tvFaultHint.text = "Heuristic hint (not a diagnosis): " + VibrationAnalyzer.faultHint(spectrum, rpm)

        // -- Envelope (demodulated) spectrum + bearing frequency markers --
        val envEntries = ArrayList<Entry>(spectrum.frequenciesHz.size)
        for (i in spectrum.frequenciesHz.indices) {
            envEntries.add(Entry(spectrum.frequenciesHz[i], spectrum.envelopeAmplitude[i]))
        }
        val envSet = LineDataSet(envEntries, "Axis $axisLabel envelope").apply {
            color = Color.parseColor("#0D9488")
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawFilled(true)
            fillColor = Color.parseColor("#0D9488")
            fillAlpha = 60
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        envelopeChart.data = LineData(envSet)
        envelopeChart.xAxis.removeAllLimitLines()

        val geometry = currentBearingGeometry()
        val bearingFreqs = geometry?.let { VibrationAnalyzer.bearingFrequencies(it, rpm) }
        if (geometry != null && bearingFreqs != null) {
            addLimitLine(envelopeChart, bearingFreqs.ftfHz, "FTF", Color.parseColor("#2563EB"))
            addLimitLine(envelopeChart, bearingFreqs.bpfoHz, "BPFO", Color.parseColor("#DC2626"))
            addLimitLine(envelopeChart, bearingFreqs.bpfiHz, "BPFI", Color.parseColor("#D97706"))
            addLimitLine(envelopeChart, bearingFreqs.bsfHz, "BSF", Color.parseColor("#7C3AED"))
            // Never state a fault outright from one spectral line -- pair the
            // hint with an explicit "not a diagnosis" + the geometry's own
            // provenance (design-debt Sections 6.5, 18, Hard Rule 6).
            tvBearingHint.text = "Bearing hint (not a diagnosis): " +
                    VibrationAnalyzer.bearingHint(spectrum.envelopePeakFreqHz, spectrum.freqResolutionHz, bearingFreqs) +
                    "  [Geometry: ${geometry.geometrySource}]"
        } else {
            tvBearingHint.text = "Bearing hint (not a diagnosis): enter bearing geometry (elements, ball & pitch diameter) and RPM to unlock."
        }
        envelopeChart.invalidate()

        // -- Waterfall / cascade history (same view as the spectrum above) --
        waterfallView.pushSpectrum(spectrum.accelAmplitude)
    }

    private fun setupTagManager() {

        tagAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            savedTags
        )

        tagAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinnerSavedTags.adapter = tagAdapter


        btnAddTag.setOnClickListener {

            val label =
                etCustomTagLabel.text
                    .toString()
                    .trim()

            if (label.isEmpty()) {
                Toast.makeText(
                    this,
                    "Enter a tag label first.",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }


            if (!savedTags.contains(label)) {

                savedTags.add(label)
                tagAdapter.notifyDataSetChanged()

                spinnerSavedTags.setSelection(
                    savedTags.lastIndex
                )

                appendLog(
                    "[TAG] Added: $label"
                )
            }


            etCustomTagLabel.text.clear()
        }


        btnDeleteTag.setOnClickListener {

            val position =
                spinnerSavedTags.selectedItemPosition

            if (
                position >= 0 &&
                position < savedTags.size
            ) {

                val removed =
                    savedTags.removeAt(position)

                tagAdapter.notifyDataSetChanged()

                appendLog(
                    "[TAG] Deleted: $removed"
                )
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
                        position < savedTags.size
                    ) {

                        val selected =
                            savedTags[position]

                        etPointTag.setText(
                            selected
                        )
                    }
                }


                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }
    }

    private fun addLimitLine(chart: LineChart, freqHz: Float, label: String, lineColorInt: Int) {
        if (freqHz <= 0f) return
        val line = LimitLine(freqHz, label).apply {
            lineColor = lineColorInt
            lineWidth = 1f
            textColor = lineColorInt
            textSize = 9f
            enableDashedLine(10f, 6f, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        }
        chart.xAxis.addLimitLine(line)
    }

    private fun setupTrendChart() {
        trendChart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.WHITE)


            xAxis.apply {
                setDrawLabels(false)
                setDrawGridLines(false)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E2E8F0")
                textColor = Color.parseColor("#334155")
                textSize = 9f
                axisMinimum = 0f
            }

            axisRight.isEnabled = false
            legend.isEnabled = false
        }
    }

    private fun updateTrendChart(trend: FloatArray) {
        if (trend.isEmpty()) return

        val entries = ArrayList<Entry>(trend.size)
        var maxValue = 0f

        for (i in trend.indices) {
            val value = trend[i].coerceAtLeast(0f)
            entries.add(Entry(i.toFloat(), value))
            maxValue = maxOf(maxValue, value)
        }

        val newSet = LineDataSet(entries, "Overall RMS trend (m/s²)").apply {
            color = Color.parseColor("#EA580C")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#EA580C")
            fillAlpha = 40
        }

        trendChart.data = LineData(newSet)

        val yMax = maxOf(0.01f, maxValue * 1.15f)
        trendChart.axisLeft.axisMinimum = 0f
        trendChart.axisLeft.axisMaximum = yMax

        val visiblePoints = minOf(60f, trend.size.toFloat())
        trendChart.setVisibleXRangeMaximum(visiblePoints)
        trendChart.moveViewToX(trend.lastIndex.toFloat())

        trendChart.invalidate()
    }

    private fun scanAndDisplayHardwareSensors() {
        selectedSensors.clear()
        llSensorList.removeAllViews()
        val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val priorityTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ROTATION_VECTOR
        )
        val sortedList = sensorList.sortedByDescending { priorityTypes.contains(it.type) }

        for (sensor in sortedList) {
            val isPriority = priorityTypes.contains(sensor.type)
            if (isPriority) selectedSensors.add(sensor)

            val rateHz =
                if (sensor.minDelay > 0) 1_000_000f / sensor.minDelay else 0f
            val rateText =
                if (rateHz > 0f) String.format(Locale.US, "~%.1f Hz max", rateHz)
                else "event-based"

            val checkBox = CheckBox(this).apply {
                text = String.format(
                    Locale.US,
                    "%s [ID:%d]\n%s · v%d · Range: %.3f | Resolution: %.6f | %s",
                    sensor.name,
                    sensor.type,
                    sensor.vendor,
                    sensor.version,
                    sensor.maximumRange,
                    sensor.resolution,
                    rateText
                )
                isChecked = isPriority
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 12f
                includeFontPadding = true

                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedSensors.add(sensor)
                    else selectedSensors.remove(sensor)
                }
            }
            llSensorList.addView(checkBox)
        }
    }

    private fun appendLog(message: String) {
        uiHandler.post {
            val timeStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val currentText = tvLog.text.toString()
            val lines = currentText.split("\n")
            val constrainedText = if (lines.size > 30) lines.subList(lines.size - 30, lines.size).joinToString("\n") else currentText
            tvLog.text = "$constrainedText\n[$timeStamp]$message"
        }
    }
}