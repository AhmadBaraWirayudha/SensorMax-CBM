package com.research.sensormax

/**
 * SensorMax Snapshot / Measurement Package metadata.
 *
 * PURPOSE
 * -------
 * Every user-requested spectrum snapshot should carry enough metadata to
 * answer:
 *
 *     WHAT was measured?
 *     WHEN was it measured?
 *     WITH WHICH DEVICE?
 *     WITH WHICH SENSOR?
 *     AT WHICH POINT?
 *     UNDER WHICH SETUP?
 *     WITH WHICH calibration/mounting context?
 *     WHAT did the measurement produce?
 *     HOW trustworthy was the measurement?
 *     WHAT limitations remain?
 *
 *
 * IMPORTANT SCOPE
 * ---------------
 * This class is the METADATA side of the evidence package.
 *
 * The continuous deployment already produces:
 *
 *     *_raw.csv
 *     *_analysis.csv
 *
 * This object produces the structured metadata.json associated with a
 * user-requested snapshot.
 *
 * It does NOT itself:
 *
 *     - acquire sensor data
 *     - perform FFT
 *     - perform calibration
 *     - calculate ISO limits
 *     - write files
 *     - publish files to Downloads
 *
 * SensorEngine owns those operations.
 *
 *
 * DESIGN PRINCIPLES
 * -----------------
 *
 * 1. No fabricated engineering values.
 *
 *    Unknown information must remain explicitly unknown rather than being
 *    silently converted into a plausible-looking value.
 *
 * 2. Preserve provenance.
 *
 *    Device/sensor/calibration/mounting information belongs to the snapshot
 *    because the same numerical spectrum means less without knowing how it
 *    was obtained.
 *
 * 3. Separate measurement from advisory interpretation.
 *
 *    RMS, frequency and peak values are measurements/calculations.
 *
 *    ISO classification, bearing matching and fault hints are advisory
 *    interpretations and must not be presented as equivalent to a certified
 *    diagnostic measurement.
 *
 * 4. Preserve compatibility.
 *
 *    Existing constructor fields remain unchanged.
 *    New fields have defaults so existing named-argument construction does
 *    not have to change immediately.
 *
 * 5. Keep the schema explicit.
 *
 *    schemaVersion identifies the JSON structure.
 *    packageType identifies what the JSON represents.
 *
 * 6. Keep evidence boundaries visible.
 *
 *    A metadata JSON file must never imply that a raw waveform, baseline,
 *    operating state, calibration or mounting check existed when it did not.
 */


/**
 * Structured metadata for one user-requested measurement snapshot.
 */
data class SnapshotMetadata(

    // =====================================================================
    // SCHEMA / PACKAGE IDENTITY
    // =====================================================================

    /**
     * Schema revision for this JSON structure.
     *
     * This is NOT the app version.
     *
     * Example:
     *
     *     1
     *
     * allows future SensorMax versions to evolve the metadata schema while
     * old JSON files remain interpretable.
     */
    val schemaVersion: Int = SnapshotSchema.CURRENT_VERSION,

    /**
     * Explicit package type.
     *
     * This prevents a generic metadata.json file from becoming ambiguous
     * later if SensorMax gains other evidence-package formats.
     */
    val packageType: String = SnapshotSchema.PACKAGE_TYPE,


    // =====================================================================
    // IDENTITY
    // =====================================================================

    /**
     * Asset / machine identifier entered by the operator.
     */
    val asset: String,

    /**
     * Measurement point / location identifier entered by the operator.
     */
    val point: String,

    /**
     * Optional saved/custom operator label.
     */
    val customLabel: String,

    /**
     * Wall-clock timestamp in Unix epoch milliseconds.
     */
    val timestampMs: Long,

    /**
     * ISO-8601 rendering of [timestampMs].
     */
    val timestampIso: String,


    // =====================================================================
    // DEVICE + SENSOR IDENTITY
    // =====================================================================

    /**
     * Android Build.MANUFACTURER.
     */
    val deviceManufacturer: String,

    /**
     * Android Build.MODEL.
     */
    val deviceModel: String,

    /**
     * Android release string.
     */
    val androidRelease: String,

    /**
     * Android API level.
     */
    val androidApiLevel: Int,

    /**
     * SensorMax application version.
     *
     * Kept separate from [schemaVersion].
     */
    val appVersion: String,

    /**
     * Actual Android sensor name selected as the vibration source.
     */
    val sensorName: String,

    /**
     * Sensor vendor reported by Android.
     */
    val sensorVendor: String,

    /**
     * Android sensor version field.
     */
    val sensorVersion: Int,

    /**
     * Sensor.maximumRange reported by Android, in m/s².
     *
     * This is declared sensor metadata, not a physically induced range test.
     */
    val sensorMaxRangeMs2: Float,


    // =====================================================================
    // CALIBRATION + MOUNTING
    // =====================================================================

    /**
     * Device/sensor-specific calibration record, when one exists.
     *
     * Null means:
     *
     *     no calibration was available for this snapshot.
     */
    val calibration: CalibrationRecord?,

    /**
     * Operator-selected mounting method.
     *
     * Example:
     *
     *     Handheld
     *     Magnetic
     *     Tape
     *     Adhesive
     *     Rigid fixture
     *     Unknown
     */
    val mountingMethod: String,

    /**
     * Physical machine-direction assignment for phone X.
     */
    val axisRoleX: String,

    /**
     * Physical machine-direction assignment for phone Y.
     */
    val axisRoleY: String,

    /**
     * Physical machine-direction assignment for phone Z.
     */
    val axisRoleZ: String,

    /**
     * Most recent pre-flight mounting/stability result.
     */
    val mountingCheck: MountingCheckResult?,


    // =====================================================================
    // OPERATING CONTEXT
    // =====================================================================

    /**
     * Operating state at measurement time.
     *
     * Current SensorMax implementation does not have a real operating-state
     * classifier.
     *
     * Therefore SensorEngine should currently write an explicit text such as:
     *
     *     "not specified (no operating-state classifier yet)"
     *
     * rather than inventing:
     *
     *     "running"
     *     "idle"
     *     "startup"
     */
    val operatingState: String,

    /**
     * Operator-entered RPM hint.
     *
     * Zero generally means no RPM was supplied.
     */
    val rpmHint: Float,


    // =====================================================================
    // MEASUREMENT
    // =====================================================================

    /**
     * Actual sample rate inferred from SensorEvent timestamps.
     */
    val actualSampleRateHz: Double,

    /**
     * FFT bin spacing:
     *
     *     Fs / N
     */
    val fftResolutionHz: Double,

    /**
     * FFT window size.
     *
     * Current SensorMax default is 256.
     */
    val fftWindowSize: Int,

    /**
     * Overall triaxial RMS acceleration.
     */
    val overallRmsAccelMs2: Float,

    /**
     * Overall triaxial RMS velocity derived from acceleration spectrum.
     */
    val overallRmsVelocityMmS: Float,

    /**
     * Overall triaxial RMS displacement derived from acceleration spectrum.
     */
    val overallRmsDisplacementUm: Float,

    /**
     * Axis with the highest per-axis RMS acceleration.
     */
    val dominantAxis: String,

    /**
     * Dominant spectral peak frequency.
     */
    val dominantFreqHz: Float,

    /**
     * Dominant spectral peak acceleration amplitude.
     */
    val peakAccelMs2: Float,

    /**
     * Time-domain peak-to-peak acceleration.
     */
    val peakToPeakMs2: Float,

    /**
     * Crest factor:
     *
     *     peak / RMS
     */
    val crestFactor: Float,

    /**
     * Non-excess kurtosis.
     */
    val kurtosis: Float,

    /**
     * Envelope-spectrum peak frequency.
     *
     * This may be zero/unavailable when the envelope analysis is not
     * considered usable for the acquisition conditions.
     */
    val envelopePeakFreqHz: Float,

    /**
     * Bearing characteristic-frequency match code:
     *
     *     NONE
     *     BPFO
     *     BPFI
     *     BSF
     *     FTF
     *     N/A
     */
    val bearingMatch: String,

    /**
     * Number of transient/shock events detected during the deployment.
     */
    val sessionShockCount: Long,


    // =====================================================================
    // QUALITY / ADVISORY CONTEXT
    // =====================================================================

    /**
     * Baseline comparison.
     *
     * Current implementation should explicitly report that no baseline
     * subsystem exists instead of fabricating a comparison.
     */
    val baselineComparison: String,

    /**
     * Selected ISO machine class.
     */
    val isoMachineClass: String,

    /**
     * User-configured warning threshold.
     */
    val warnThresholdMs2: Float,

    /**
     * User-configured alert threshold.
     */
    val alertThresholdMs2: Float,

    /**
     * Measurement-quality score/details.
     *
     * Null means no quality result was available.
     */
    val measurementQuality: QualityScore?,

    /**
     * Human-readable advisory state.
     *
     * This remains an application advisory and is not equivalent to a
     * certified condition-monitoring diagnosis.
     */
    val advisoryState: String,


    // =====================================================================
    // ACQUISITION PROVENANCE
    // =====================================================================

    /**
     * Acquisition mode used during the deployment.
     *
     * Example:
     *
     *     stream_log
     *     offline_log
     *     unknown
     *
     * Default is explicit because older SensorEngine versions may not pass
     * this field yet.
     */
    val acquisitionMode: String = "unknown",

    /**
     * Whether network streaming was enabled.
     *
     * Null means that the older caller did not provide this metadata.
     *
     * This is intentionally nullable rather than false-by-default because
     * "false" would imply knowledge that may not exist in older snapshots.
     */
    val networkStreamingEnabled: Boolean? = null,

    /**
     * Whether disk logging was enabled.
     */
    val diskLoggingEnabled: Boolean? = null,

    /**
     * Number of samples/events observed by the vibration analyzer for the
     * snapshot's session/window context.
     *
     * Null preserves compatibility with callers that do not have this
     * information.
     */
    val acceptedVibrationEvents: Long? = null,

    /**
     * Number of raw vibration-source events observed before throttling.
     */
    val rawVibrationEvents: Long? = null,

    /**
     * Timestamp jitter for the analysis window.
     */
    val timestampJitterMs: Float? = null,

    /**
     * Largest inter-sample gap in the analysis window.
     */
    val maxSampleGapMs: Float? = null,


    // =====================================================================
    // EVIDENCE / COMPLETENESS
    // =====================================================================

    /**
     * True when calibration exists for this same vibration sensor.
     *
     * This is metadata, not an independent calibration validity judgment.
     */
    val calibrationApplicable: Boolean =
        calibration != null,

    /**
     * True when a mounting check result exists.
     */
    val mountingCheckAvailable: Boolean =
        mountingCheck != null,

    /**
     * True when a numeric RPM hint was actually supplied.
     */
    val rpmAvailable: Boolean =
        rpmHint > 0f,

    /**
     * True when a baseline comparison is actually available.
     *
     * Current SensorMax implementation should normally leave this false
     * because no baseline subsystem exists yet.
     */
    val baselineAvailable: Boolean = false,

    /**
     * True when the application has an operating-state classifier.
     *
     * Current implementation should normally leave this false.
     */
    val operatingStateClassified: Boolean = false,

    /**
     * Explicit human-readable list of evidence limitations.
     *
     * Example:
     *
     *     "Operating state not classified"
     *     "No historical baseline"
     *     "Calibration not available"
     *
     * Do not fill this list with claims that were not actually observed.
     */
    val limitations: List<String> = emptyList(),

    /**
     * Optional list of files expected to belong to this evidence package.
     *
     * Example:
     *
     *     raw.csv
     *     analysis.csv
     *     metadata.json
     *
     * This is metadata only; SensorEngine remains responsible for creating
     * the files.
     */
    val packageArtifacts: List<String> = emptyList()
) {

    // =====================================================================
    // DERIVED / AUDIT HELPERS
    // =====================================================================

    /**
     * Returns true when the snapshot contains at least one meaningful
     * measurement result.
     *
     * This is intentionally conservative:
     *
     *     sample rate > 0
     *     FFT size > 0
     *
     * are necessary before treating the snapshot as a real analyzed result.
     */
    fun hasMeasurement(): Boolean =
        actualSampleRateHz > 0.0 &&
                fftWindowSize > 0


    /**
     * Returns true when the snapshot contains enough information to interpret
     * running-speed-related results.
     *
     * RPM must be known and positive.
     */
    fun hasRunningSpeedReference(): Boolean =
        rpmHint > 0f


    /**
     * Returns true when a bearing-frequency comparison can at least be
     * considered as having its prerequisites represented in metadata.
     *
     * This does NOT mean that a bearing fault has been detected.
     */
    fun hasBearingContext(): Boolean =
        rpmHint > 0f &&
                bearingMatch != "N/A" &&
                bearingMatch.isNotBlank()


    /**
     * Returns a compact evidence-status string suitable for logs or a UI.
     *
     * Example:
     *
     *     "MEASUREMENT=YES | CALIBRATION=YES | MOUNTING=YES |
     *      RPM=YES | BASELINE=NO | OPERATING_STATE=NO"
     */
    fun evidenceStatus(): String {

        return buildString {

            append(
                "MEASUREMENT="
            )

            append(
                if (
                    hasMeasurement()
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )

            append(
                " | CALIBRATION="
            )

            append(
                if (
                    calibrationApplicable
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )

            append(
                " | MOUNTING="
            )

            append(
                if (
                    mountingCheckAvailable
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )

            append(
                " | RPM="
            )

            append(
                if (
                    rpmAvailable
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )

            append(
                " | BASELINE="
            )

            append(
                if (
                    baselineAvailable
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )

            append(
                " | OPERATING_STATE="
            )

            append(
                if (
                    operatingStateClassified
                ) {
                    "YES"
                } else {
                    "NO"
                }
            )
        }
    }


    /**
     * Returns a new metadata object with common evidence limitations added
     * automatically.
     *
     * This does not invent values; it only documents missing subsystems.
     *
     * Typical current SensorMax result:
     *
     *     operating state classifier missing
     *     baseline system missing
     *     calibration missing
     *     mounting check missing
     */
    fun withAutomaticLimitations():
            SnapshotMetadata {

        val generated =
            mutableListOf<String>()

        if (
            !calibrationApplicable
        ) {

            generated.add(
                "No applicable calibration record for the active vibration sensor."
            )
        }

        if (
            !mountingCheckAvailable
        ) {

            generated.add(
                "No mounting/stability check result attached to this snapshot."
            )
        }

        if (
            !operatingStateClassified
        ) {

            generated.add(
                "Operating state is not automatically classified."
            )
        }

        if (
            !baselineAvailable
        ) {

            generated.add(
                "No historical baseline comparison is available."
            )
        }

        if (
            actualSampleRateHz <= 0.0
        ) {

            generated.add(
                "No valid measured sample rate is available."
            )
        }

        val merged =
            (
                    limitations +
                            generated
                    ).distinct()

        return copy(
            limitations =
                merged
        )
    }


    /**
     * Returns a stable identity string for human-readable cross-checking.
     *
     * Example:
     *
     *     DEVICE / SENSOR / ASSET / POINT
     *
     * This is NOT a cryptographic hash and must not be used as an integrity
     * signature.
     */
    fun measurementIdentity(): String {

        return listOf(
            deviceManufacturer,
            deviceModel,
            sensorName,
            sensorVendor,
            asset,
            point
        ).joinToString(
            " | "
        )
    }
}
