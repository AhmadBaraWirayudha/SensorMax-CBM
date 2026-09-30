# SensorMax Research Suite Tutorial

Practical operating guide for the current SensorMax architecture.

Assumptions:

- Windows is the primary desktop environment;
- the Android application is already built or installable;
- `AndroidApp` remains unchanged;
- desktop communication uses WebSocket `8765`;
- raw Android data follows the current SensorMax CSV schema.

## 1. Understand the Data Flow

```text
ANDROID
SensorEngine
    │
    ├── raw CSV
    ├── analysis CSV
    └── live WebSocket packets
              │
              ▼
DESKTOP
web_server.py :8765
    │
    ├── Web HMI
    ├── live spectrum
    ├── alarm monitor
    └── imported-record processing
              │
              ▼
OFFLINE ENGINEERING
analysis / filters / modal / calibration
              │
              ▼
EXPORT
HTML / Excel / JSON / MATLAB
```

## 2. First-Time Installation

Open Command Prompt in the project root:

```bat
cd /d C:\path\to\Sensor-Acquistion-main\SensorResearchSuite
```

Run:

```bat
first_initialize.bat
```

Then verify:

```bat
sensormax_env\Scripts\python.exe LaptopSuite\verify_suite_health.py
```

## 3. Connect the Android Device

Enable USB debugging and connect the device.

```bat
adb devices
```

Create the reverse tunnel:

```bat
adb reverse tcp:8765 tcp:8765
```

Verify:

```bat
adb reverse --list
```

## 4. Start the Web HMI

Recommended Windows workflow:

```bat
run_web_hmi.bat
```

Check that port `8765` is listening:

```bat
netstat -ano | findstr :8765
```

The browser dashboard uses:

```text
ws://127.0.0.1:8765
```

## 5. Start a Measurement

On Android, set the actual:

```text
Machine_ID
Point
sensor/acquisition settings
```

Start recording. Enable streaming when live monitoring is required.

Do not use the phone model as the machine identity.

## 6. Understand Raw Data

Current raw schema:

```text
Timestamp_ms,
Machine_ID,
Point,
Sensor_Type,
Sensor_Name,
Val_0,
Val_1,
Val_2,
Val_3,
Val_4,
Val_5
```

For accelerometer data:

```text
Sensor_Type = 1
Val_0 = X
Val_1 = Y
Val_2 = Z
```

Example:

```text
1720000000000,PUMP-01,DE-BEARING,1,Accelerometer,0.12,0.35,9.74,,,,
```

## 7. Understand Analysis Data

Useful derived fields include:

```text
Overall_RMS_Accel_ms2
Overall_RMS_Velocity_mms
Overall_RMS_Displacement_um
Dominant_Axis
Dominant_Freq_Hz
Envelope_Peak_Freq_Hz
ISO20816_Zone
Bearing_Match
RPM_Input
Impact_Event
Snapshot_Triggered
```

Raw and analysis files should both be preserved.

## 8. Inspect Live Waveforms

Run:

```bat
python LaptopSuite\sensor_studio.py
```

or use the browser Web HMI.

Confirm these first:

```text
connection
packet count
machine
point
sample-rate behavior
waveform
```

Interpret FFT or condition indicators only after acquisition looks valid.

## 9. Live Spectrum

Run:

```bat
python LaptopSuite\live_spectrum_studio.py
```

Inspect X/Y/Z and the spectrum.

A peak at frequency `f` means a periodic component near `f`. It does not, by itself, identify a fault.

## 10. CLI Inspection

Run:

```bat
python LaptopSuite\cli_explorer.py recording.csv
```

Useful commands:

```text
info
stats
plot x
plot y
plot z
plot rss
fft x
analysis
```

## 11. Quality Check Before Interpretation

Run:

```bat
python LaptopSuite\anomaly_detector.py recording.csv
```

Inspect timestamp gaps, duplicate/backward timestamps, large outliers, clipping, and abrupt sample jumps.

Bad acquisition can create plausible but incorrect spectral results.

## 12. Offline Analysis

Run:

```bat
python LaptopSuite\data_analysis.py recording.csv
```

Review timing, jitter, X/Y/Z statistics, RSS, FFT, RPM references, harmonics, and available Android analysis summaries.

Prefer timestamps for sample-rate estimation instead of assuming a nominal configured rate.

## 13. Derived Filtering

EMA:

```bat
python LaptopSuite\digital_filters.py recording.csv --filter ema --param 0.2
```

Low-pass:

```bat
python LaptopSuite\digital_filters.py recording.csv --filter lowpass --param 20 --sensor-type 1
```

Band-pass:

```bat
python LaptopSuite\digital_filters.py recording.csv --filter bandpass --param 5 --high-cutoff 50 --sensor-type 1
```

Never overwrite the source raw file.

## 14. Modal / Resonance Screening

Run:

```bat
python LaptopSuite\vibration_modal_analysis.py recording.csv
```

Outputs:

```text
recording_modal_report.json
recording_spectrum.csv
```

Treat damping/modal values as screening estimates, not certified modal testing.

## 15. Offline Calibration Characterization

For a stationary accelerometer test:

```bat
python LaptopSuite\sensor_calibration.py recording.csv
```

For a Z-axis +gravity setup:

```bat
python LaptopSuite\sensor_calibration.py recording.csv ^
  --gravity-axis z ^
  --mounting "stationary, Z-axis +gravity"
```

The tool reports per-axis mean/noise, RSS noise, stationary magnitude, and achieved sampling rate. Magnetometer hard-iron characterization is available when magnetometer data exists.

The output is a context-specific profile, not a universal calibration package.

## 16. Import Android Records

Use:

```bat
import_and_process.bat
```

The current importer targets package:

```text
com.research.sensormax
```

and the current master-log path.

Processing flow:

```text
Android master logs
       ↓
local Imported_Records session
       ↓
export_converter.py
       ↓
export_json.py
       ↓
batch_dataset_processor.py
```

Keep test sessions separate.

## 17. Compare Repeated Trials

For repeated tests, compare:

```text
RMS
peak-to-peak
sample rate
Dominant_Freq_Hz
envelope frequency
RPM
ISO metadata
bearing-match metadata
impact count
snapshot count
```

Focus on repeatability and trend versus operating condition rather than a single isolated number.

## 18. Real-Time Alarm Monitoring

Start the gateway:

```bat
run_web_hmi.bat
```

In a second terminal:

```bat
python LaptopSuite\realtime_alarm_monitor.py
```

Defaults:

```text
WARNING = 2.0 m/s² RMS acceleration
ALERT   = 4.0 m/s² RMS acceleration
```

Custom values:

```bat
python LaptopSuite\realtime_alarm_monitor.py --warn 2.0 --alert 4.0
```

Log:

```text
LaptopSuite/alarm_events.csv
```

An alarm is a monitoring event, not an automatic diagnosis.

## 19. Generate an HTML Report

Run:

```bat
python LaptopSuite\generate_html_report.py recording.csv
```

The report can include metadata, timing, statistics, waveform, FFT, RPM/harmonics, trends, and engineering notes.

## 20. Generate MATLAB Output

Run:

```bat
python LaptopSuite\matlab_exporter.py recording.csv --output sensormax_data.m
```

The `.m` script contains the time vector, accelerometer channels, RMS calculations, waveform plots, and FFT.

## 21. Generate Excel / JSON

Excel:

```bat
python LaptopSuite\export_converter.py recording.csv
```

JSON provenance:

```bat
python LaptopSuite\export_json.py recording.csv
```

Retain the raw source CSV beside the exports.

## 22. Synthetic Dataset Regression Test

Generate known test data:

```bat
python LaptopSuite\synthetic_sensor_generator.py ^
  --profile machine_vibration ^
  --duration 30 ^
  --rate 100 ^
  --machine-id TEST-M01 ^
  --point TEST-P01 ^
  --seed 151101 ^
  --output test_machine_vibration.csv
```

Then:

```bat
python LaptopSuite\data_analysis.py test_machine_vibration.csv
python LaptopSuite\vibration_modal_analysis.py test_machine_vibration.csv
```

Synthetic files are software test fixtures, not machine measurements.

## 23. Full Integration Test

Run:

```bat
python LaptopSuite\test_all_pipelines.py
python LaptopSuite\verify_suite_health.py
```

Use the integration test after desktop changes and the health check before a measurement campaign.

## 24. Complete First Experiment

```text
Prepare environment
      ↓
adb devices
      ↓
adb reverse tcp:8765 tcp:8765
      ↓
run_web_hmi.bat
      ↓
configure Machine_ID + Point on Android
      ↓
record measurement
      ↓
preserve raw recording
      ↓
run anomaly_detector.py
      ↓
run data_analysis.py
      ↓
run modal / filter tools when required
      ↓
compare repeated trials
      ↓
export report / Excel / JSON / MATLAB
```

## 25. Interpreting Frequency Content

If the FFT shows:

```text
12 Hz
24 Hz
36 Hz
```

check:

```text
machine running speed
1X relationship
harmonics
frequency stability across trials
amplitude trend
waveform consistency
mounting / sensor artifacts
```

Do not label a spectral line as a specific fault without machine context.

## 26. Interpreting RMS Trends

A repeated-trial sequence is often more informative than one isolated measurement:

```text
Baseline → Load 1 → Load 2 → Load 3
```

Look for stable changes with operating condition and repeatability.

Do not treat a configurable software alarm threshold as a universal failure limit.

## 27. ISO 20816 Metadata

`ISO20816_Zone` may be displayed by the desktop tools.

Treat it as analysis metadata. Applying an industrial standard to a smartphone measurement still depends on machine class, measurement method, sensor characteristics, mounting, bandwidth, and other conditions.

## 28. Bearing Match

`Bearing_Match` is an analysis result to investigate.

A spectral match alone does not prove bearing failure.

Use together:

```text
frequency
speed
bearing geometry
harmonics
trend
waveform
```

## 29. File Discipline

Recommended experiment structure:

```text
Experiment_YYYY-MM-DD/
├── raw/
├── analysis/
├── derived/
├── calibration/
├── reports/
└── qa/
```

The exact folder names can vary. Keep raw, analysis, derived data, reports, and QA outputs distinguishable.

## 30. Troubleshooting

### Gateway does not connect

```bat
netstat -ano | findstr :8765
```

Then start:

```bat
python LaptopSuite\web_server.py
```

### No Android stream

Check:

```bat
adb devices
adb reverse --list
```

Make sure the reverse tunnel includes `8765`.

### No accelerometer waveform

Verify the CSV contains:

```text
Sensor_Type = 1
Val_0
Val_1
Val_2
```

### Analysis exists but raw waveform is missing

Load the corresponding raw CSV. An analysis CSV contains derived values, not necessarily the complete raw waveform.

### Package error

Run:

```bat
first_initialize.bat
python LaptopSuite\verify_suite_health.py
```

## 31. Development Boundary

The desktop modernization work is intended to occur outside the Android acquisition source:

```text
LaptopSuite/
launch scripts/
import/export utilities/
desktop tests/
```

Keep:

```text
AndroidApp/app/src/main/
```

as the acquisition producer unless a separate Android change is explicitly required.

## 32. Final Operating Principle

```text
MEASURE
   ↓
PRESERVE RAW DATA
   ↓
CHECK DATA QUALITY
   ↓
ANALYZE
   ↓
COMPARE / TREND
   ↓
REPORT
```

Keep the acquisition record, derived calculations, and engineering interpretation traceable as separate layers.
