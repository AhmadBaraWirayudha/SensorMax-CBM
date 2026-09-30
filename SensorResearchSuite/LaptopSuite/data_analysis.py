#!/usr/bin/env python3
"""
SensorMax Engineering Data Analysis Tool

Purpose
-------
Analyze SensorMax raw or analysis CSV files from the LaptopSuite.

AndroidApp is treated as the source of truth and is NOT modified.

Supported RAW schema
--------------------

Timestamp_ms
Machine_ID
Point
Sensor_Type
Sensor_Name
Val_0
Val_1
Val_2
Val_3
Val_4
Val_5

Supported ANALYSIS schema
-------------------------

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

The tool reports:
    - record count
    - machine / measurement-point context
    - duration
    - effective sampling rate
    - timestamp jitter
    - axis statistics
    - RMS
    - peak-to-peak
    - FFT
    - dominant frequency
    - running-speed harmonics
    - analysis-session statistics

Important
---------
This tool performs engineering analysis and reporting.
It does NOT claim machine diagnosis.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from pathlib import Path
from typing import Any

try:
    import numpy as np
except ImportError:
    print(
        "ERROR: numpy is required."
    )
    print(
        "Install with:"
    )
    print(
        "    python -m pip install numpy"
    )
    sys.exit(1)


# ============================================================
# Constants
# ============================================================

ACCELEROMETER_TYPE = 1

DEFAULT_RPM = 0.0

MAX_FFT_POINTS = 4096


# ============================================================
# Utility helpers
# ============================================================

def safe_float(
    value: Any,
    default: float = 0.0,
) -> float:

    try:

        result = float(value)

        if not math.isfinite(result):
            return default

        return result

    except (
        TypeError,
        ValueError,
    ):

        return default


def safe_int(
    value: Any,
    default: int = 0,
) -> int:

    try:

        return int(
            float(value)
        )

    except (
        TypeError,
        ValueError,
    ):

        return default


def clean_text(
    value: Any,
    default: str = "",
) -> str:

    if value is None:
        return default

    text = str(value).strip()

    return text if text else default


def rms(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.sqrt(
            np.mean(
                values ** 2
            )
        )
    )


def standard_deviation(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.std(
            values
        )
    )


def peak_to_peak(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.max(values) -
        np.min(values)
    )


def mean_or_zero(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.mean(values)
    )


def median_or_zero(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.median(values)
    )


def is_power_of_two(
    value: int,
) -> bool:

    return (
        value > 0
        and
        (value & (value - 1)) == 0
    )


def largest_power_of_two(
    count: int,
    maximum: int = MAX_FFT_POINTS,
) -> int:

    n = 1

    while (
        n * 2 <= count
        and
        n * 2 <= maximum
    ):

        n *= 2

    return n


# ============================================================
# Schema detection
# ============================================================

def detect_schema(
    fieldnames: list[str],
) -> str:

    fields = {
        field.strip()
        for field in fieldnames
    }

    current_raw = {
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sensor_Type",
        "Sensor_Name",
        "Val_0",
        "Val_1",
        "Val_2",
    }

    current_analysis = {
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sample_Rate_Hz",
        "Overall_RMS_Accel_ms2",
        "Overall_RMS_Velocity_mms",
        "Dominant_Freq_Hz",
    }

    if current_raw.issubset(fields):
        return "CURRENT_RAW"

    if current_analysis.issubset(fields):
        return "CURRENT_ANALYSIS"

    return "LEGACY_OR_UNKNOWN"


# ============================================================
# RAW data loading
# ============================================================

def load_raw_csv(
    path: Path,
) -> dict[str, Any]:

    timestamps: list[float] = []

    values_x: list[float] = []

    values_y: list[float] = []

    values_z: list[float] = []

    sensor_types: list[int] = []

    sensor_names: set[str] = set()

    machine_ids: set[str] = set()

    points: set[str] = set()

    record_count = 0

    vibration_count = 0

    with path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:

        reader = csv.DictReader(
            file
        )

        fieldnames = (
            reader.fieldnames
            or []
        )

        schema = detect_schema(
            fieldnames
        )

        for row in reader:

            if not row:
                continue

            record_count += 1

            timestamp = safe_float(
                row.get(
                    "Timestamp_ms"
                )
            )

            sensor_type = safe_int(
                row.get(
                    "Sensor_Type"
                )
            )

            sensor_name = clean_text(
                row.get(
                    "Sensor_Name"
                ),
                "Unknown Sensor",
            )

            machine_id = clean_text(
                row.get(
                    "Machine_ID"
                ),
                "UNSPECIFIED",
            )

            point = clean_text(
                row.get(
                    "Point"
                ),
                "UNSPECIFIED",
            )

            sensor_types.append(
                sensor_type
            )

            sensor_names.add(
                sensor_name
            )

            machine_ids.add(
                machine_id
            )

            points.add(
                point
            )

            # Only the actual vibration accelerometer is
            # used for raw FFT/statistical vibration analysis.
            if (
                sensor_type ==
                ACCELEROMETER_TYPE
                or
                "accelerometer"
                in sensor_name.lower()
            ):

                vibration_count += 1

                timestamps.append(
                    timestamp
                )

                values_x.append(
                    safe_float(
                        row.get(
                            "Val_0"
                        )
                    )
                )

                values_y.append(
                    safe_float(
                        row.get(
                            "Val_1"
                        )
                    )
                )

                values_z.append(
                    safe_float(
                        row.get(
                            "Val_2"
                        )
                    )
                )

    return {
        "schema": schema,
        "record_count": record_count,
        "vibration_count": vibration_count,
        "timestamps":
            np.asarray(
                timestamps,
                dtype=float,
            ),
        "x":
            np.asarray(
                values_x,
                dtype=float,
            ),
        "y":
            np.asarray(
                values_y,
                dtype=float,
            ),
        "z":
            np.asarray(
                values_z,
                dtype=float,
            ),
        "sensor_types":
            sorted(
                set(
                    sensor_types
                )
            ),
        "sensor_names":
            sorted(
                sensor_names
            ),
        "machine_ids":
            sorted(
                machine_ids
            ),
        "points":
            sorted(
                points
            ),
    }


# ============================================================
# ANALYSIS data loading
# ============================================================

def load_analysis_csv(
    path: Path,
) -> dict[str, Any]:

    rows: list[dict[str, Any]] = []

    with path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:

        reader = csv.DictReader(
            file
        )

        fieldnames = (
            reader.fieldnames
            or []
        )

        schema = detect_schema(
            fieldnames
        )

        for row in reader:

            if row:
                rows.append(
                    row
                )

    return {
        "schema": schema,
        "rows": rows,
    }


# ============================================================
# Timestamp analysis
# ============================================================

def analyze_timestamps(
    timestamps: np.ndarray,
) -> dict[str, float]:

    valid = timestamps[
        np.isfinite(timestamps)
    ]

    if valid.size < 2:

        return {
            "duration_s": 0.0,
            "mean_interval_ms": 0.0,
            "median_interval_ms": 0.0,
            "jitter_std_ms": 0.0,
            "min_interval_ms": 0.0,
            "max_interval_ms": 0.0,
            "effective_rate_hz": 0.0,
        }

    sorted_ts = np.sort(
        valid
    )

    deltas = np.diff(
        sorted_ts
    )

    deltas = deltas[
        deltas > 0
    ]

    if deltas.size == 0:

        return {
            "duration_s": 0.0,
            "mean_interval_ms": 0.0,
            "median_interval_ms": 0.0,
            "jitter_std_ms": 0.0,
            "min_interval_ms": 0.0,
            "max_interval_ms": 0.0,
            "effective_rate_hz": 0.0,
        }

    mean_dt = float(
        np.mean(deltas)
    )

    duration_s = (
        float(
            sorted_ts[-1] -
            sorted_ts[0]
        ) / 1000.0
    )

    effective_rate = (
        1000.0 / mean_dt
        if mean_dt > 0
        else 0.0
    )

    return {
        "duration_s":
            duration_s,

        "mean_interval_ms":
            mean_dt,

        "median_interval_ms":
            float(
                np.median(deltas)
            ),

        "jitter_std_ms":
            float(
                np.std(deltas)
            ),

        "min_interval_ms":
            float(
                np.min(deltas)
            ),

        "max_interval_ms":
            float(
                np.max(deltas)
            ),

        "effective_rate_hz":
            effective_rate,
    }


# ============================================================
# Axis statistics
# ============================================================

def axis_statistics(
    values: np.ndarray,
) -> dict[str, float]:

    if values.size == 0:

        return {
            "min": 0.0,
            "max": 0.0,
            "mean": 0.0,
            "median": 0.0,
            "std": 0.0,
            "rms": 0.0,
            "peak_to_peak": 0.0,
        }

    return {
        "min":
            float(
                np.min(values)
            ),

        "max":
            float(
                np.max(values)
            ),

        "mean":
            mean_or_zero(values),

        "median":
            median_or_zero(values),

        "std":
            standard_deviation(values),

        "rms":
            rms(values),

        "peak_to_peak":
            peak_to_peak(values),
    }


# ============================================================
# FFT
# ============================================================

def calculate_fft(
    values: np.ndarray,
    sample_rate_hz: float,
) -> dict[str, Any] | None:

    if (
        values.size < 8
        or
        sample_rate_hz <= 0
    ):

        return None

    n = largest_power_of_two(
        values.size
    )

    if not is_power_of_two(n):

        return None

    data = values[
        -n:
    ].astype(
        float,
        copy=True,
    )

    # Remove DC component.
    data -= np.mean(
        data
    )

    # Hann window.
    window = np.hanning(
        n
    )

    windowed = (
        data *
        window
    )

    spectrum = np.fft.rfft(
        windowed
    )

    magnitude = (
        np.abs(
            spectrum
        ) /
        n
    )

    frequencies = (
        np.fft.rfftfreq(
            n,
            d=1.0 /
            sample_rate_hz,
        )
    )

    if magnitude.size <= 1:

        return None

    # Ignore DC when finding the dominant vibration frequency.
    peak_region = magnitude.copy()

    peak_region[0] = 0.0

    peak_index = int(
        np.argmax(
            peak_region
        )
    )

    peak_frequency = float(
        frequencies[
            peak_index
        ]
    )

    peak_amplitude = float(
        magnitude[
            peak_index
        ]
    )

    return {
        "n":
            n,

        "resolution_hz":
            sample_rate_hz / n,

        "frequencies":
            frequencies,

        "magnitude":
            magnitude,

        "peak_index":
            peak_index,

        "peak_frequency_hz":
            peak_frequency,

        "peak_amplitude":
            peak_amplitude,
    }


# ============================================================
# Running-speed harmonics
# ============================================================

def harmonic_report(
    peak_frequency_hz: float,
    rpm: float,
) -> dict[str, float]:

    if rpm <= 0:

        return {
            "one_x_hz": 0.0,
            "two_x_hz": 0.0,
            "three_x_hz": 0.0,
            "peak_over_one_x": 0.0,
        }

    one_x = (
        rpm / 60.0
    )

    return {
        "one_x_hz":
            one_x,

        "two_x_hz":
            one_x * 2.0,

        "three_x_hz":
            one_x * 3.0,

        "peak_over_one_x":
            (
                peak_frequency_hz /
                one_x
                if one_x > 0
                else 0.0
            ),
    }


# ============================================================
# Report: RAW
# ============================================================

def print_raw_report(
    path: Path,
    data: dict[str, Any],
    rpm: float,
) -> None:

    print()
    print(
        "=" * 68
    )

    print(
        "  SENSORMAX ENGINEERING DATASET REPORT"
    )

    print(
        "=" * 68
    )

    print(
        f"File                    : {path.name}"
    )

    print(
        f"Schema                  : {data['schema']}"
    )

    print(
        f"Total CSV Records       : {data['record_count']}"
    )

    print(
        f"Vibration Records       : {data['vibration_count']}"
    )

    print(
        f"Machine ID              : "
        f"{', '.join(data['machine_ids']) or 'UNSPECIFIED'}"
    )

    print(
        f"Measurement Point       : "
        f"{', '.join(data['points']) or 'UNSPECIFIED'}"
    )

    print(
        f"Sensor Name(s)          : "
        f"{', '.join(data['sensor_names']) or 'UNKNOWN'}"
    )

    print()

    timing = analyze_timestamps(
        data["timestamps"]
    )

    print(
        "--- Acquisition Timing ---"
    )

    print(
        f"Duration                : "
        f"{timing['duration_s']:.3f} s"
    )

    print(
        f"Effective Sample Rate   : "
        f"{timing['effective_rate_hz']:.3f} Hz"
    )

    print(
        f"Mean Interval           : "
        f"{timing['mean_interval_ms']:.3f} ms"
    )

    print(
        f"Median Interval         : "
        f"{timing['median_interval_ms']:.3f} ms"
    )

    print(
        f"Timestamp Jitter (Std)  : "
        f"±{timing['jitter_std_ms']:.3f} ms"
    )

    print(
        f"Min Interval            : "
        f"{timing['min_interval_ms']:.3f} ms"
    )

    print(
        f"Max Interval            : "
        f"{timing['max_interval_ms']:.3f} ms"
    )

    if data["vibration_count"] == 0:

        print()
        print(
            "No vibration accelerometer records found."
        )

        print(
            "=" * 68
        )

        return

    x = data["x"]

    y = data["y"]

    z = data["z"]

    print()

    print(
        "--- Summary Statistics Per Vibration Axis ---"
    )

    for label, values in (
        ("X", x),
        ("Y", y),
        ("Z", z),
    ):

        stats = axis_statistics(
            values
        )

        print(
            (
                f"Axis {label}: "
                f"Min={stats['min']:.6f}, "
                f"Max={stats['max']:.6f}, "
                f"Mean={stats['mean']:.6f}, "
                f"Median={stats['median']:.6f}, "
                f"Std={stats['std']:.6f}, "
                f"RMS={stats['rms']:.6f}, "
                f"P-P={stats['peak_to_peak']:.6f}"
            )
        )

    # --------------------------------------------------------
    # Vector RSS
    # --------------------------------------------------------

    rss = np.sqrt(
        x ** 2 +
        y ** 2 +
        z ** 2
    )

    dynamic_rss = (
        rss -
        np.mean(rss)
    )

    rss_stats = axis_statistics(
        rss
    )

    dynamic_stats = axis_statistics(
        dynamic_rss
    )

    print()

    print(
        "--- Vector Resultant ---"
    )

    print(
        f"RSS Mean                : "
        f"{rss_stats['mean']:.6f} m/s²"
    )

    print(
        f"RSS RMS                 : "
        f"{rss_stats['rms']:.6f} m/s²"
    )

    print(
        f"RSS Peak-to-Peak        : "
        f"{rss_stats['peak_to_peak']:.6f} m/s²"
    )

    print(
        f"Dynamic RSS RMS         : "
        f"{dynamic_stats['rms']:.6f} m/s²"
    )

    # --------------------------------------------------------
    # FFT
    # --------------------------------------------------------

    fs = timing[
        "effective_rate_hz"
    ]

    if fs <= 0:

        print()
        print(
            "FFT skipped: invalid effective sample rate."
        )

        print(
            "=" * 68
        )

        return

    print()

    print(
        "--- FFT Analysis ---"
    )

    print(
        f"FFT Sample Rate         : "
        f"{fs:.3f} Hz"
    )

    best_fft = None

    best_axis = None

    for label, values in (
        ("X", x),
        ("Y", y),
        ("Z", z),
    ):

        result = calculate_fft(
            values,
            fs,
        )

        if result is None:

            print(
                f"Axis {label}: FFT unavailable"
            )

            continue

        print(
            f"Axis {label}: "
            f"Peak={result['peak_frequency_hz']:.3f} Hz, "
            f"Amplitude={result['peak_amplitude']:.6f}, "
            f"Resolution={result['resolution_hz']:.6f} Hz/bin, "
            f"N={result['n']}"
        )

        if (
            best_fft is None
            or
            result["peak_amplitude"] >
            best_fft["peak_amplitude"]
        ):

            best_fft = result

            best_axis = label

    if best_fft is not None:

        print()

        print(
            "--- Dominant Vibration Component ---"
        )

        print(
            f"Dominant Axis           : "
            f"{best_axis}"
        )

        print(
            f"Dominant Frequency      : "
            f"{best_fft['peak_frequency_hz']:.3f} Hz"
        )

        print(
            f"Dominant Amplitude      : "
            f"{best_fft['peak_amplitude']:.6f}"
        )

        print(
            f"FFT Resolution          : "
            f"{best_fft['resolution_hz']:.6f} Hz/bin"
        )

        if rpm > 0:

            harmonics = harmonic_report(
                best_fft[
                    "peak_frequency_hz"
                ],
                rpm,
            )

            print()

            print(
                "--- Running-Speed Context ---"
            )

            print(
                f"RPM Input               : "
                f"{rpm:.1f}"
            )

            print(
                f"1X                     : "
                f"{harmonics['one_x_hz']:.3f} Hz"
            )

            print(
                f"2X                     : "
                f"{harmonics['two_x_hz']:.3f} Hz"
            )

            print(
                f"3X                     : "
                f"{harmonics['three_x_hz']:.3f} Hz"
            )

            print(
                f"Peak / 1X              : "
                f"{harmonics['peak_over_one_x']:.3f}X"
            )

    print()

    print(
        "NOTE: Frequency peaks are measurement/analysis results, "
        "not standalone fault diagnoses."
    )

    print(
        "=" * 68
    )


# ============================================================
# Report: ANALYSIS
# ============================================================

def print_analysis_report(
    path: Path,
    data: dict[str, Any],
) -> None:

    rows = data["rows"]

    print()
    print(
        "=" * 68
    )

    print(
        "  SENSORMAX ANALYSIS SESSION REPORT"
    )

    print(
        "=" * 68
    )

    print(
        f"File                    : {path.name}"
    )

    print(
        f"Schema                  : {data['schema']}"
    )

    print(
        f"Analysis Windows        : {len(rows)}"
    )

    if not rows:

        print(
            "No analysis records found."
        )

        print(
            "=" * 68
        )

        return

    machine_ids = sorted(
        {
            clean_text(
                row.get(
                    "Machine_ID"
                )
            )
            for row in rows
            if clean_text(
                row.get(
                    "Machine_ID"
                )
            )
        }
    )

    points = sorted(
        {
            clean_text(
                row.get(
                    "Point"
                )
            )
            for row in rows
            if clean_text(
                row.get(
                    "Point"
                )
            )
        }
    )

    sample_rates = np.asarray(
        [
            safe_float(
                row.get(
                    "Sample_Rate_Hz"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    rms_accel = np.asarray(
        [
            safe_float(
                row.get(
                    "Overall_RMS_Accel_ms2"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    rms_velocity = np.asarray(
        [
            safe_float(
                row.get(
                    "Overall_RMS_Velocity_mms"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    displacement = np.asarray(
        [
            safe_float(
                row.get(
                    "Overall_RMS_Displacement_um"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    dominant_frequency = np.asarray(
        [
            safe_float(
                row.get(
                    "Dominant_Freq_Hz"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    envelope_frequency = np.asarray(
        [
            safe_float(
                row.get(
                    "Envelope_Peak_Freq_Hz"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    rpm = np.asarray(
        [
            safe_float(
                row.get(
                    "RPM_Input"
                )
            )
            for row in rows
        ],
        dtype=float,
    )

    print(
        f"Machine ID              : "
        f"{', '.join(machine_ids) or 'UNSPECIFIED'}"
    )

    print(
        f"Measurement Point       : "
        f"{', '.join(points) or 'UNSPECIFIED'}"
    )

    print()

    print(
        "--- Analysis Statistics ---"
    )

    print(
        f"Sample Rate Mean        : "
        f"{mean_or_zero(sample_rates):.3f} Hz"
    )

    print(
        f"RMS Acceleration Mean   : "
        f"{mean_or_zero(rms_accel):.6f} m/s²"
    )

    print(
        f"RMS Acceleration Max    : "
        f"{float(np.max(rms_accel)):.6f} m/s²"
    )

    print(
        f"RMS Velocity Mean       : "
        f"{mean_or_zero(rms_velocity):.6f} mm/s"
    )

    print(
        f"RMS Velocity Max        : "
        f"{float(np.max(rms_velocity)):.6f} mm/s"
    )

    print(
        f"Displacement Mean       : "
        f"{mean_or_zero(displacement):.6f} µm"
    )

    print(
        f"Dominant Frequency Mean : "
        f"{mean_or_zero(dominant_frequency):.6f} Hz"
    )

    print(
        f"Dominant Frequency Max  : "
        f"{float(np.max(dominant_frequency)):.6f} Hz"
    )

    print(
        f"Envelope Peak Mean      : "
        f"{mean_or_zero(envelope_frequency):.6f} Hz"
    )

    valid_rpm = rpm[
        rpm > 0
    ]

    if valid_rpm.size:

        print(
            f"RPM Mean                : "
            f"{mean_or_zero(valid_rpm):.3f}"
        )

        print(
            f"RPM Min                 : "
            f"{float(np.min(valid_rpm)):.3f}"
        )

        print(
            f"RPM Max                 : "
            f"{float(np.max(valid_rpm)):.3f}"
        )

    iso_zones = sorted(
        {
            clean_text(
                row.get(
                    "ISO20816_Zone"
                )
            )
            for row in rows
            if clean_text(
                row.get(
                    "ISO20816_Zone"
                )
            )
        }
    )

    bearing_matches = sorted(
        {
            clean_text(
                row.get(
                    "Bearing_Match"
                )
            )
            for row in rows
            if clean_text(
                row.get(
                    "Bearing_Match"
                )
            )
        }
    )

    impact_count = sum(
        1
        for row in rows
        if clean_text(
            row.get(
                "Impact_Event"
            )
        ).lower()
        in {
            "true",
            "1",
            "yes",
        }
    )

    snapshot_count = sum(
        1
        for row in rows
        if clean_text(
            row.get(
                "Snapshot_Triggered"
            )
        ).lower()
        in {
            "true",
            "1",
            "yes",
        }
    )

    print()

    print(
        "--- Session Context ---"
    )

    print(
        f"ISO Zones               : "
        f"{', '.join(iso_zones) or 'N/A'}"
    )

    print(
        f"Bearing Match Codes     : "
        f"{', '.join(bearing_matches) or 'N/A'}"
    )

    print(
        f"Impact Events           : "
        f"{impact_count}"
    )

    print(
        f"Snapshots               : "
        f"{snapshot_count}"
    )

    print()

    print(
        "NOTE: This report summarizes Android analysis output. "
        "It does not independently validate the Android calculations."
    )

    print(
        "=" * 68
    )


# ============================================================
# Main
# ============================================================

def analyze_file(
    filepath: str,
    rpm: float = DEFAULT_RPM,
) -> int:

    path = (
        Path(
            filepath
        )
        .expanduser()
        .resolve()
    )

    if not path.exists():

        print(
            f"File not found: {path}"
        )

        return 1

    if not path.is_file():

        print(
            f"Not a file: {path}"
        )

        return 1

    try:

        with path.open(
            "r",
            encoding="utf-8-sig",
            newline="",
        ) as file:

            reader = csv.reader(
                file
            )

            header = next(
                reader,
                None,
            )

    except (
        OSError,
        UnicodeError,
    ) as exc:

        print(
            f"Unable to read file: {exc}"
        )

        return 1

    if not header:

        print(
            "CSV file is empty."
        )

        return 1

    schema = detect_schema(
        header
    )

    print(
        f"[INFO] Detected schema: {schema}"
    )

    try:

        if schema == "CURRENT_ANALYSIS":

            data = load_analysis_csv(
                path
            )

            print_analysis_report(
                path,
                data,
            )

        else:

            data = load_raw_csv(
                path
            )

            print_raw_report(
                path,
                data,
                rpm,
            )

        return 0

    except (
        OSError,
        ValueError,
        csv.Error,
    ) as exc:

        print(
            f"Analysis failed: {exc}"
        )

        return 1

    except Exception as exc:

        print(
            f"Unexpected analysis error: "
            f"{type(exc).__name__}: {exc}"
        )

        return 1


# ============================================================
# CLI
# ============================================================

def build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        description=(
            "Analyze a SensorMax raw or analysis CSV."
        )
    )

    parser.add_argument(
        "csv_file",
        help=(
            "Path to SensorMax CSV file"
        ),
    )

    parser.add_argument(
        "--rpm",
        type=float,
        default=DEFAULT_RPM,
        help=(
            "Optional machine RPM for 1X/2X/3X "
            "running-speed context"
        ),
    )

    return parser


def main() -> int:

    parser = build_parser()

    args = parser.parse_args()

    return analyze_file(
        args.csv_file,
        args.rpm,
    )


if __name__ == "__main__":

    raise SystemExit(
        main()
    )