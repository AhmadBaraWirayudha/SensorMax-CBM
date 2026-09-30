# SensorMax Research Suite

A sensor acquisition, analysis, visualization, calibration, and condition-monitoring workspace built around the `SensorMax Research` Android application and the desktop `LaptopSuite`.

The project is intentionally split into two sides:

```text
SensorMax AndroidApp
        │
        ├── local raw CSV logging
        └── WebSocket streaming
                │
                ▼
        LaptopSuite gateway (:8765)
                │
        ┌───────┼────────┬───────────────┐
        ▼       ▼        ▼               ▼
      Web HMI  Spectrum  Alarm       Offline tools
                │        monitor
                └────────┴───────────────┘
```

> **Architecture rule:** `SensorResearchSuite/AndroidApp/` is treated as the acquisition producer and is not refactored by the desktop-suite work described here.

## 1. Scope

SensorMax supports:

- raw multi-sensor acquisition;
- real-time WebSocket streaming;
- waveform and FFT visualization;
- vibration analysis;
- RMS / frequency / envelope features;
- impact-event monitoring;
- offline sensor characterization;
- data-quality checking;
- Excel and MATLAB export;
- HTML reporting;
- synthetic datasets for regression testing.

Desktop tools use explicit measurement context:

```text
Machine_ID
Point
Sensor_Type
Sensor_Name
```

The software does not use a specific phone model as the identity of the measured machine.

## 2. Structure

```text
SensorResearchSuite/
├── README.md
├── TUTORIAL.md
├── run_web_hmi.bat
├── run_desktop_hmi.bat
├── run_laptop_suite.sh
├── first_initialize.bat
├── import_and_process.bat
│
├── AndroidApp/
│   └── app/src/main/
│       └── ... Kotlin acquisition application ...
│
└── LaptopSuite/
    ├── requirements.txt
    ├── web_server.py
    ├── web_dashboard.html
    ├── sensor_studio.py
    ├── live_spectrum_studio.py
    ├── cli_explorer.py
    ├── realtime_alarm_monitor.py
    ├── data_analysis.py
    ├── anomaly_detector.py
    ├── digital_filters.py
    ├── vibration_modal_analysis.py
    ├── sensor_calibration.py
    ├── generate_html_report.py
    ├── matlab_exporter.py
    ├── export_converter.py
    ├── export_json.py
    ├── batch_dataset_processor.py
    ├── synthetic_sensor_generator.py
    ├── test_all_pipelines.py
    └── verify_suite_health.py
```

## 3. AndroidApp

The Android application is responsible for sensor acquisition, local recording, analysis snapshots, and live streaming.

### Current raw CSV schema

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

For the accelerometer path used by the desktop CBM tools:

```text
Val_0 = X
Val_1 = Y
Val_2 = Z
```

### Current analysis fields

```text
Timestamp_ms
Machine_ID
Point
Sample_Rate_Hz
RMS_X_ms2
RMS_Y_ms2
RMS_Z_ms2
Overall_RMS_Accel_ms2
Overall_RMS_Velocity_mms
Overall_RMS_Displacement_um
Dominant_Axis
Dominant_Freq_Hz
Dominant_Accel_Amplitude_ms2
Envelope_Peak_Freq_Hz
ISO20816_Zone
Bearing_Match
RPM_Input
Impact_Event
Snapshot_Triggered
```

### Android storage

Current master records are written under the application-facing external Documents area:

```text
SensorMax_Master_Logs/
```

Published copies are exposed under:

```text
Download/SensorMax_Master_Logs/
```

## 4. Desktop Transport

The current desktop transport is one WebSocket gateway:

```text
ws://127.0.0.1:8765
```

The gateway receives raw and analysis packets, broadcasts them to desktop clients, and can log streams under `LaptopSuite/Imported_Records/`.

The legacy UDP/port-5005 workflow is not part of the current architecture.

## 5. Core Desktop Tools

### `web_server.py`

WebSocket gateway on `8765`.

### `web_dashboard.html`

Browser HMI with raw/analysis counters, X/Y/Z/RSS waveform, browser FFT, RMS/frequency/envelope trends, machine/point context, and recording/export functions.

### `sensor_studio.py`

Desktop real-time sensor inspection application.

### `live_spectrum_studio.py`

Live waveform and FFT viewer with timestamp-based sampling-rate estimation.

### `cli_explorer.py`

Interactive terminal explorer:

```text
load <file.csv>
info
stats
plot x|y|z|rss
fft x|y|z|rss
analysis
export <file.csv>
clear
help
exit
```

### `realtime_alarm_monitor.py`

Real-time condition/alarm monitor using the same WebSocket gateway.

Default analysis thresholds:

```text
Warning: 2.0 m/s² RMS acceleration
Alert  : 4.0 m/s² RMS acceleration
```

Alarm events are written to:

```text
LaptopSuite/alarm_events.csv
```

## 6. Offline Analysis

### `data_analysis.py`

Timing/jitter, X/Y/Z statistics, RSS, FFT, RPM/harmonics, and Android analysis-summary inspection.

### `anomaly_detector.py`

Checks timestamps, gaps, duplicate/backward times, outliers, clipping, and abrupt sample jumps. It does not assign an unsupported universal machine-health score.

### `digital_filters.py`

Derived-data filtering: `ema`, `ma`, `lowpass`, `highpass`, `bandpass`, `none`.

Always preserve the original raw recording.

### `vibration_modal_analysis.py`

Spectral/modal screening and optional damping-ratio estimation. Results are screening estimates, not certified modal testing.

### `sensor_calibration.py`

Offline accelerometer/magnetometer characterization. Profiles are tied to the supplied dataset/device context and are not universal calibration values.

## 7. Reporting and Export

### `generate_html_report.py`

Creates a self-contained report with available metadata, timing, waveform, FFT, trends, and engineering notes.

### `matlab_exporter.py`

Exports time, X/Y/Z, RMS calculations, waveform plots, and MATLAB FFT into a `.m` script.

### `export_converter.py`

Converts SensorMax records into structured Excel workbooks.

### `export_json.py`

Creates structured metadata/provenance JSON.

### `batch_dataset_processor.py`

Compares repeated trials using RMS, peak-to-peak, sample rate, dominant/envelope frequency, RPM, ISO metadata, bearing-match metadata, and impact/snapshot counts where available.

## 8. Synthetic Testing

`synthetic_sensor_generator.py` provides deterministic test fixtures:

```text
stationary
walking
running
rotation_3d
tremor
machine_vibration
```

Example:

```bat
python LaptopSuite\synthetic_sensor_generator.py ^
  --profile machine_vibration ^
  --duration 30 ^
  --rate 100 ^
  --machine-id TEST-M01 ^
  --point MOTOR-DE-01 ^
  --seed 151101 ^
  --output test_machine_vibration.csv
```

Synthetic data must never be represented as measured equipment data.

## 9. Initialization and Health Check

Initialize the desktop environment:

```bat
first_initialize.bat
```

Run:

```bat
sensormax_env\Scripts\python.exe LaptopSuite\verify_suite_health.py
```

Integration test:

```bat
python LaptopSuite\test_all_pipelines.py
```

## 10. Recommended Workflow

```text
Initialize
   ↓
Connect Android
   ↓
Start gateway / Web HMI
   ↓
Acquire recording
   ↓
Preserve raw files
   ↓
Run data-quality checks
   ↓
Run offline analysis
   ↓
Compare repeated trials
   ↓
Generate HTML / Excel / JSON / MATLAB outputs
```

## 11. Data Integrity

Maintain this distinction:

```text
RAW      → immutable source recording
DERIVED  → filtered / transformed data
ANALYSIS → calculated features
REPORT   → presentation
```

Do not overwrite raw files with filtered or normalized output.

Retain `Machine_ID`, `Point`, `Sensor_Type`, `Sensor_Name`, and `Timestamp_ms` whenever possible.

## 12. Engineering Boundaries

SensorMax uses smartphone sensors for research and engineering experimentation.

An FFT peak is a spectral observation, not automatically a fault diagnosis. An envelope peak is an indicator requiring machine context. An ISO 20816 zone field is treated as advisory metadata in this suite. Alarm thresholds are configurable monitoring rules, not universal acceptance limits for every machine.

Use machine-specific limits, reference measurements, and appropriate instrumentation when results are required for formal maintenance, acceptance, certification, or safety decisions.

## 13. Android Boundary

Desktop modernization should normally occur under:

```text
SensorResearchSuite/LaptopSuite/
```

and in project-level launch/import scripts.

Avoid casual edits under:

```text
SensorResearchSuite/AndroidApp/app/src/main/
```

The Android application remains the acquisition producer.

## 14. Quick Commands

```bat
first_initialize.bat
run_web_hmi.bat
python LaptopSuite\web_server.py
python LaptopSuite\cli_explorer.py
python LaptopSuite\realtime_alarm_monitor.py
python LaptopSuite\data_analysis.py recording.csv
python LaptopSuite\sensor_calibration.py recording.csv
python LaptopSuite\synthetic_sensor_generator.py --profile machine_vibration
python LaptopSuite\test_all_pipelines.py
python LaptopSuite\verify_suite_health.py
```

See [`TUTORIAL.md`](TUTORIAL.md) for the end-to-end operating procedure.

## 15. License

See `LICENSE`.
