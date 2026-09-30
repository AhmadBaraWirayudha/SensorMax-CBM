#!/usr/bin/env python3
"""
SensorMax HTML Engineering Report Generator

Purpose
-------
Generate a self-contained HTML report from a SensorMax CSV dataset.

Supported inputs
----------------

1. Current Android RAW CSV

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

2. Current Android ANALYSIS CSV

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

AndroidApp is treated as the source of truth and is NOT modified.

Report content
---------------

RAW:
    - session metadata
    - machine / point
    - sensor inventory
    - timing
    - sample rate
    - X/Y/Z statistics
    - vector RSS
    - waveform overview
    - FFT
    - running-speed harmonics

ANALYSIS:
    - session metadata
    - analysis windows
    - RMS trends
    - dominant frequency
    - envelope frequency
    - ISO zone distribution
    - bearing-match distribution
    - impacts
    - snapshots

The report is an engineering visualization/reporting tool.
It does not claim machine diagnosis.
"""

from __future__ import annotations

import argparse
import csv
import html
import math
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy is required.")
    print("Install with:")
    print("    python -m pip install numpy")
    sys.exit(1)


# ============================================================
# Constants
# ============================================================

ACCELEROMETER_TYPE = 1

MAX_WAVEFORM_POINTS = 260

MAX_FFT_POINTS = 4096


# ============================================================
# Utility
# ============================================================

def safe_text(
    value: Any,
    default: str = "",
) -> str:

    if value is None:
        return default

    text = str(value).strip()

    if not text:
        return default

    return text


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


def esc(
    value: Any,
) -> str:

    return html.escape(
        safe_text(value)
    )


def mean(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.mean(values)
    )


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


def std(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.std(values)
    )


def minimum(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.min(values)
    )


def maximum(
    values: np.ndarray,
) -> float:

    if values.size == 0:
        return 0.0

    return float(
        np.max(values)
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


# ============================================================
# Schema
# ============================================================

def detect_schema(
    header: list[str],
) -> str:

    columns = {
        str(value).strip()
        for value in header
    }

    raw_required = {
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sensor_Type",
        "Sensor_Name",
        "Val_0",
        "Val_1",
        "Val_2",
    }

    analysis_required = {
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sample_Rate_Hz",
        "Overall_RMS_Accel_ms2",
        "Overall_RMS_Velocity_mms",
        "Dominant_Freq_Hz",
    }

    if raw_required.issubset(
        columns
    ):
        return "CURRENT_RAW"

    if analysis_required.issubset(
        columns
    ):
        return "CURRENT_ANALYSIS"

    return "UNKNOWN"


# ============================================================
# RAW CSV loading
# ============================================================

def load_raw(
    path: Path,
) -> dict[str, Any]:

    records: list[dict[str, Any]] = []

    with path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:

        reader = csv.DictReader(
            file
        )

        for row in reader:

            if not row:
                continue

            records.append(
                {
                    "timestamp_ms":
                        safe_float(
                            row.get(
                                "Timestamp_ms"
                            )
                        ),

                    "machine_id":
                        safe_text(
                            row.get(
                                "Machine_ID"
                            ),
                            "UNSPECIFIED",
                        ),

                    "point":
                        safe_text(
                            row.get(
                                "Point"
                            ),
                            "UNSPECIFIED",
                        ),

                    "sensor_type":
                        safe_int(
                            row.get(
                                "Sensor_Type"
                            )
                        ),

                    "sensor_name":
                        safe_text(
                            row.get(
                                "Sensor_Name"
                            ),
                            "Unknown Sensor",
                        ),

                    "v0":
                        safe_float(
                            row.get(
                                "Val_0"
                            )
                        ),

                    "v1":
                        safe_float(
                            row.get(
                                "Val_1"
                            )
                        ),

                    "v2":
                        safe_float(
                            row.get(
                                "Val_2"
                            )
                        ),

                    "v3":
                        safe_float(
                            row.get(
                                "Val_3"
                            )
                        ),

                    "v4":
                        safe_float(
                            row.get(
                                "Val_4"
                            )
                        ),

                    "v5":
                        safe_float(
                            row.get(
                                "Val_5"
                            )
                        ),
                }
            )

    return {
        "records": records
    }


# ============================================================
# ANALYSIS CSV loading
# ============================================================

def load_analysis(
    path: Path,
) -> dict[str, Any]:

    records: list[dict[str, Any]] = []

    with path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:

        reader = csv.DictReader(
            file
        )

        for row in reader:

            if not row:
                continue

            records.append(
                {
                    "timestamp_ms":
                        safe_float(
                            row.get(
                                "Timestamp_ms"
                            )
                        ),

                    "machine_id":
                        safe_text(
                            row.get(
                                "Machine_ID"
                            ),
                            "UNSPECIFIED",
                        ),

                    "point":
                        safe_text(
                            row.get(
                                "Point"
                            ),
                            "UNSPECIFIED",
                        ),

                    "sample_rate_hz":
                        safe_float(
                            row.get(
                                "Sample_Rate_Hz"
                            )
                        ),

                    "rms_x":
                        safe_float(
                            row.get(
                                "RMS_X_ms2"
                            )
                        ),

                    "rms_y":
                        safe_float(
                            row.get(
                                "RMS_Y_ms2"
                            )
                        ),

                    "rms_z":
                        safe_float(
                            row.get(
                                "RMS_Z_ms2"
                            )
                        ),

                    "overall_rms_accel":
                        safe_float(
                            row.get(
                                "Overall_RMS_Accel_ms2"
                            )
                        ),

                    "overall_rms_velocity":
                        safe_float(
                            row.get(
                                "Overall_RMS_Velocity_mms"
                            )
                        ),

                    "overall_rms_displacement":
                        safe_float(
                            row.get(
                                "Overall_RMS_Displacement_um"
                            )
                        ),

                    "dominant_axis":
                        safe_text(
                            row.get(
                                "Dominant_Axis"
                            )
                        ),

                    "dominant_freq":
                        safe_float(
                            row.get(
                                "Dominant_Freq_Hz"
                            )
                        ),

                    "dominant_amplitude":
                        safe_float(
                            row.get(
                                "Dominant_Accel_Amplitude_ms2"
                            )
                        ),

                    "envelope_peak":
                        safe_float(
                            row.get(
                                "Envelope_Peak_Freq_Hz"
                            )
                        ),

                    "iso_zone":
                        safe_text(
                            row.get(
                                "ISO20816_Zone"
                            )
                        ),

                    "bearing_match":
                        safe_text(
                            row.get(
                                "Bearing_Match"
                            )
                        ),

                    "rpm":
                        safe_float(
                            row.get(
                                "RPM_Input"
                            )
                        ),

                    "impact":
                        safe_text(
                            row.get(
                                "Impact_Event"
                            )
                        ),

                    "snapshot":
                        safe_text(
                            row.get(
                                "Snapshot_Triggered"
                            )
                        ),
                }
            )

    return {
        "records": records
    }


# ============================================================
# Timing
# ============================================================

def timing_summary(
    timestamps: np.ndarray,
) -> dict[str, float]:

    timestamps = timestamps[
        np.isfinite(timestamps)
    ]

    if timestamps.size < 2:

        return {
            "duration_s": 0.0,
            "rate_hz": 0.0,
            "mean_dt_ms": 0.0,
            "jitter_ms": 0.0,
            "min_dt_ms": 0.0,
            "max_dt_ms": 0.0,
        }

    timestamps = np.sort(
        timestamps
    )

    deltas = np.diff(
        timestamps
    )

    deltas = deltas[
        deltas > 0
    ]

    if deltas.size == 0:

        return {
            "duration_s": 0.0,
            "rate_hz": 0.0,
            "mean_dt_ms": 0.0,
            "jitter_ms": 0.0,
            "min_dt_ms": 0.0,
            "max_dt_ms": 0.0,
        }

    mean_dt = mean(
        deltas
    )

    return {
        "duration_s":
            max(
                0.0,
                (
                    timestamps[-1] -
                    timestamps[0]
                ) / 1000.0,
            ),

        "rate_hz":
            (
                1000.0 /
                mean_dt
                if mean_dt > 0
                else 0.0
            ),

        "mean_dt_ms":
            mean_dt,

        "jitter_ms":
            std(deltas),

        "min_dt_ms":
            minimum(deltas),

        "max_dt_ms":
            maximum(deltas),
    }


# ============================================================
# FFT
# ============================================================

def fft_result(
    values: np.ndarray,
    sample_rate_hz: float,
) -> dict[str, Any] | None:

    if (
        values.size < 8
        or
        sample_rate_hz <= 0
    ):
        return None

    n = 1

    while (
        n * 2 <= values.size
        and
        n * 2 <= MAX_FFT_POINTS
    ):
        n *= 2

    if n < 8:
        return None

    data = values[
        -n:
    ].astype(
        float,
        copy=True,
    )

    data -= np.mean(
        data
    )

    window = np.hanning(
        n
    )

    spectrum = np.fft.rfft(
        data *
        window
    )

    magnitude = (
        np.abs(
            spectrum
        ) /
        n
    )

    frequencies = np.fft.rfftfreq(
        n,
        d=1.0 /
        sample_rate_hz,
    )

    if magnitude.size <= 1:
        return None

    magnitude_for_peak = (
        magnitude.copy()
    )

    magnitude_for_peak[0] = 0.0

    index = int(
        np.argmax(
            magnitude_for_peak
        )
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

        "peak_frequency_hz":
            float(
                frequencies[index]
            ),

        "peak_amplitude":
            float(
                magnitude[index]
            ),
    }


# ============================================================
# SVG helpers
# ============================================================

def svg_waveform(
    values: np.ndarray,
    width: int = 900,
    height: int = 220,
) -> str:

    if values.size < 2:

        return (
            '<div class="chart-empty">'
            'Insufficient waveform data'
            '</div>'
        )

    step = max(
        1,
        int(
            math.ceil(
                values.size /
                MAX_WAVEFORM_POINTS
            )
        ),
    )

    sampled = values[
        ::step
    ]

    if sampled.size < 2:

        return (
            '<div class="chart-empty">'
            'Insufficient waveform data'
            '</div>'
        )

    low = float(
        np.min(sampled)
    )

    high = float(
        np.max(sampled)
    )

    span = max(
        high - low,
        1e-9,
    )

    points = []

    for index, value in enumerate(
        sampled
    ):

        x = (
            index /
            (sampled.size - 1) *
            width
        )

        y = (
            height -
            20 -
            (
                (
                    float(value) -
                    low
                ) /
                span
            ) *
            (
                height - 40
            )
        )

        points.append(
            f"{x:.1f},{y:.1f}"
        )

    return f"""
    <svg
        class="chart"
        viewBox="0 0 {width} {height}"
        preserveAspectRatio="none"
    >
        <line
            x1="0"
            y1="{height / 2:.1f}"
            x2="{width}"
            y2="{height / 2:.1f}"
            class="gridline"
        />

        <polyline
            points="{' '.join(points)}"
            class="wave"
        />

        <text
            x="8"
            y="18"
            class="axis-label"
        >
            MAX {high:.3f}
        </text>

        <text
            x="8"
            y="{height - 6}"
            class="axis-label"
        >
            MIN {low:.3f}
        </text>
    </svg>
    """


def svg_spectrum(
    result: dict[str, Any] | None,
    width: int = 900,
    height: int = 260,
    max_frequency_hz: float = 120.0,
) -> str:

    if result is None:

        return (
            '<div class="chart-empty">'
            'FFT unavailable'
            '</div>'
        )

    frequencies = result[
        "frequencies"
    ]

    magnitudes = result[
        "magnitude"
    ]

    visible = (
        frequencies <=
        max_frequency_hz
    )

    frequencies = frequencies[
        visible
    ]

    magnitudes = magnitudes[
        visible
    ]

    if magnitudes.size < 2:

        return (
            '<div class="chart-empty">'
            'Insufficient FFT data'
            '</div>'
        )

    peak = max(
        float(
            np.max(magnitudes)
        ),
        1e-12,
    )

    points = []

    for frequency, magnitude in zip(
        frequencies,
        magnitudes,
    ):

        x = (
            frequency /
            max_frequency_hz *
            width
        )

        y = (
            height -
            24 -
            (
                magnitude /
                peak
            ) *
            (
                height - 48
            )
        )

        points.append(
            f"{x:.1f},{y:.1f}"
        )

    return f"""
    <svg
        class="chart"
        viewBox="0 0 {width} {height}"
        preserveAspectRatio="none"
    >
        <polyline
            points="{' '.join(points)}"
            class="spectrum"
        />

        <line
            x1="0"
            y1="{height - 24}"
            x2="{width}"
            y2="{height - 24}"
            class="axis-line"
        />

        <text
            x="8"
            y="{height - 7}"
            class="axis-label"
        >
            0 Hz
        </text>

        <text
            x="{width - 58}"
            y="{height - 7}"
            class="axis-label"
        >
            {max_frequency_hz:.0f} Hz
        </text>

        <text
            x="8"
            y="16"
            class="axis-label"
        >
            Peak {result["peak_frequency_hz"]:.2f} Hz
        </text>
    </svg>
    """


def svg_trend(
    values: list[float],
    label: str,
    unit: str,
    width: int = 900,
    height: int = 240,
) -> str:

    if len(values) < 2:

        return (
            '<div class="chart-empty">'
            f'Insufficient {esc(label)} trend data'
            '</div>'
        )

    arr = np.asarray(
        values,
        dtype=float,
    )

    low = float(
        np.min(arr)
    )

    high = float(
        np.max(arr)
    )

    span = max(
        high - low,
        1e-9,
    )

    points = []

    for index, value in enumerate(
        arr
    ):

        x = (
            index /
            (
                len(arr) - 1
            ) *
            width
        )

        y = (
            height -
            24 -
            (
                (
                    value -
                    low
                ) /
                span
            ) *
            (
                height - 48
            )
        )

        points.append(
            f"{x:.1f},{y:.1f}"
        )

    return f"""
    <svg
        class="chart"
        viewBox="0 0 {width} {height}"
        preserveAspectRatio="none"
    >
        <polyline
            points="{' '.join(points)}"
            class="trend"
        />

        <text
            x="8"
            y="16"
            class="axis-label"
        >
            {esc(label)}
        </text>

        <text
            x="{width - 135}"
            y="16"
            class="axis-label"
        >
            MAX {high:.3f} {esc(unit)}
        </text>

        <text
            x="8"
            y="{height - 7}"
            class="axis-label"
        >
            MIN {low:.3f} {esc(unit)}
        </text>
    </svg>
    """


# ============================================================
# Raw report sections
# ============================================================

def build_raw_report_data(
    raw: dict[str, Any],
) -> dict[str, Any]:

    records = raw[
        "records"
    ]

    if not records:

        return {
            "machine_ids": [],
            "points": [],
            "timestamps": np.array([]),
            "accel": [],
            "timing": timing_summary(
                np.array([])
            ),
            "sensors": [],
            "fft": {},
        }

    machine_ids = sorted(
        {
            record["machine_id"]
            for record in records
            if record["machine_id"]
        }
    )

    points = sorted(
        {
            record["point"]
            for record in records
            if record["point"]
        }
    )

    sensor_groups = {}

    for record in records:

        key = (
            record["sensor_type"],
            record["sensor_name"],
        )

        sensor_groups.setdefault(
            key,
            [],
        ).append(
            record
        )

    accel_records = [
        record
        for record in records
        if (
            record["sensor_type"] ==
            ACCELEROMETER_TYPE
        )
    ]

    if not accel_records:

        accel_records = [
            record
            for record in records
            if (
                "accelerometer"
                in
                record["sensor_name"].lower()
            )
        ]

    timestamps = np.asarray(
        [
            record["timestamp_ms"]
            for record in accel_records
        ],
        dtype=float,
    )

    x = np.asarray(
        [
            record["v0"]
            for record in accel_records
        ],
        dtype=float,
    )

    y = np.asarray(
        [
            record["v1"]
            for record in accel_records
        ],
        dtype=float,
    )

    z = np.asarray(
        [
            record["v2"]
            for record in accel_records
        ],
        dtype=float,
    )

    rss = np.sqrt(
        x ** 2 +
        y ** 2 +
        z ** 2
    )

    timing = timing_summary(
        timestamps
    )

    fft_x = fft_result(
        x,
        timing["rate_hz"],
    )

    fft_y = fft_result(
        y,
        timing["rate_hz"],
    )

    fft_z = fft_result(
        z,
        timing["rate_hz"],
    )

    fft_map = {
        "X": fft_x,
        "Y": fft_y,
        "Z": fft_z,
    }

    return {
        "machine_ids":
            machine_ids,

        "points":
            points,

        "timestamps":
            timestamps,

        "x":
            x,

        "y":
            y,

        "z":
            z,

        "rss":
            rss,

        "timing":
            timing,

        "sensors":
            sensor_groups,

        "fft":
            fft_map,

        "records":
            records,
    }


# ============================================================
# Analysis report sections
# ============================================================

def build_analysis_report_data(
    analysis: dict[str, Any],
) -> dict[str, Any]:

    records = analysis[
        "records"
    ]

    machine_ids = sorted(
        {
            record["machine_id"]
            for record in records
            if record["machine_id"]
        }
    )

    points = sorted(
        {
            record["point"]
            for record in records
            if record["point"]
        }
    )

    return {
        "machine_ids":
            machine_ids,

        "points":
            points,

        "records":
            records,

        "sample_rates":
            [
                record["sample_rate_hz"]
                for record in records
            ],

        "rms_velocity":
            [
                record[
                    "overall_rms_velocity"
                ]
                for record in records
            ],

        "rms_accel":
            [
                record[
                    "overall_rms_accel"
                ]
                for record in records
            ],

        "dominant_freq":
            [
                record[
                    "dominant_freq"
                ]
                for record in records
            ],

        "envelope_freq":
            [
                record[
                    "envelope_peak"
                ]
                for record in records
            ],
    }


# ============================================================
# HTML components
# ============================================================

def stat_card(
    label: str,
    value: str,
    unit: str = "",
) -> str:

    return f"""
    <div class="stat-card">
        <div class="stat-label">{esc(label)}</div>
        <div class="stat-value">
            {esc(value)}
            <span class="stat-unit">
                {esc(unit)}
            </span>
        </div>
    </div>
    """


def info_row(
    label: str,
    value: str,
) -> str:

    return f"""
    <div class="info-row">
        <div class="info-label">
            {esc(label)}
        </div>
        <div class="info-value">
            {esc(value)}
        </div>
    </div>
    """


def page_style() -> str:

    return """
    <style>

    :root {
        --background: #08111f;
        --panel: #111c2d;
        --panel2: #0d1726;
        --border: #26364d;
        --text: #f8fafc;
        --muted: #91a4bb;
        --cyan: #38bdf8;
        --green: #4ade80;
        --yellow: #facc15;
        --red: #f87171;
        --purple: #c084fc;
    }

    * {
        box-sizing: border-box;
    }

    body {
        margin: 0;
        background:
            linear-gradient(
                180deg,
                #08111f 0%,
                #0b1423 100%
            );
        color: var(--text);
        font-family:
            -apple-system,
            BlinkMacSystemFont,
            "Segoe UI",
            Roboto,
            Arial,
            sans-serif;
        line-height: 1.45;
    }

    .page {
        max-width: 1180px;
        margin: 0 auto;
        padding: 28px;
    }

    header {
        border-bottom: 1px solid var(--border);
        padding-bottom: 20px;
        margin-bottom: 22px;
    }

    h1 {
        margin: 0;
        font-size: 30px;
        letter-spacing: 0.2px;
    }

    h2 {
        margin: 0 0 12px 0;
        font-size: 18px;
    }

    h3 {
        margin: 0 0 10px 0;
        font-size: 14px;
    }

    .subtitle {
        margin-top: 6px;
        color: var(--muted);
        font-size: 13px;
    }

    .classification {
        margin-top: 12px;
        display: inline-block;
        border: 1px solid var(--border);
        border-radius: 999px;
        padding: 6px 11px;
        color: var(--cyan);
        background: #0d1c2d;
        font-size: 12px;
        font-weight: 700;
    }

    .grid {
        display: grid;
        grid-template-columns:
            repeat(
                4,
                minmax(
                    0,
                    1fr
                )
            );
        gap: 10px;
    }

    .two {
        display: grid;
        grid-template-columns:
            repeat(
                2,
                minmax(
                    0,
                    1fr
                )
            );
        gap: 14px;
    }

    .panel {
        background: var(--panel);
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 16px;
        margin-bottom: 14px;
    }

    .stat-card {
        background: var(--panel2);
        border: 1px solid var(--border);
        border-radius: 9px;
        padding: 13px;
        min-height: 82px;
    }

    .stat-label {
        color: var(--muted);
        font-size: 11px;
        margin-bottom: 5px;
    }

    .stat-value {
        font-size: 22px;
        font-weight: 800;
    }

    .stat-unit {
        color: var(--muted);
        font-size: 11px;
        font-weight: 500;
        margin-left: 3px;
    }

    .info-grid {
        border: 1px solid var(--border);
        border-radius: 8px;
        overflow: hidden;
    }

    .info-row {
        display: grid;
        grid-template-columns:
            190px
            minmax(0, 1fr);
        border-bottom: 1px solid var(--border);
    }

    .info-row:last-child {
        border-bottom: 0;
    }

    .info-label,
    .info-value {
        padding: 9px 11px;
    }

    .info-label {
        background: #0d1726;
        color: var(--muted);
    }

    .info-value {
        overflow-wrap: anywhere;
    }

    .chart-shell {
        background: #091320;
        border: 1px solid var(--border);
        border-radius: 9px;
        overflow: hidden;
        min-height: 220px;
    }

    .chart {
        width: 100%;
        height: 240px;
        display: block;
    }

    .gridline {
        stroke: #213247;
        stroke-width: 1;
        stroke-dasharray: 4 4;
    }

    .axis-line {
        stroke: #31455f;
        stroke-width: 1;
    }

    .wave {
        fill: none;
        stroke: var(--cyan);
        stroke-width: 1.8;
        vector-effect: non-scaling-stroke;
    }

    .spectrum {
        fill: none;
        stroke: var(--purple);
        stroke-width: 1.9;
        vector-effect: non-scaling-stroke;
    }

    .trend {
        fill: none;
        stroke: var(--green);
        stroke-width: 1.9;
        vector-effect: non-scaling-stroke;
    }

    .axis-label {
        fill: var(--muted);
        font-size: 11px;
    }

    .chart-empty {
        min-height: 220px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--muted);
        font-size: 12px;
    }

    table {
        width: 100%;
        border-collapse: collapse;
        font-size: 12px;
    }

    th,
    td {
        text-align: left;
        padding: 9px;
        border-bottom: 1px solid var(--border);
    }

    th {
        color: var(--muted);
        font-weight: 700;
    }

    .pass {
        color: var(--green);
    }

    .warn {
        color: var(--yellow);
    }

    .fail {
        color: var(--red);
    }

    .note {
        color: var(--muted);
        font-size: 11px;
        margin-top: 9px;
    }

    .footer {
        color: var(--muted);
        font-size: 11px;
        border-top: 1px solid var(--border);
        margin-top: 24px;
        padding-top: 16px;
        text-align: center;
    }

    code {
        font-family:
            Consolas,
            "Courier New",
            monospace;
        color: #cbd5e1;
    }

    @media (max-width: 900px) {

        .grid {
            grid-template-columns:
                repeat(
                    2,
                    minmax(
                        0,
                        1fr
                    )
                );
        }

        .two {
            grid-template-columns: 1fr;
        }

    }

    @media (max-width: 600px) {

        .page {
            padding: 14px;
        }

        .grid {
            grid-template-columns: 1fr;
        }

        .info-row {
            grid-template-columns: 1fr;
        }

    }

    </style>
    """


# ============================================================
# RAW HTML
# ============================================================

def render_raw_html(
    csv_path: Path,
    data: dict[str, Any],
    rpm: float,
) -> str:

    records = data[
        "records"
    ]

    if not records:

        return """
        <div class="panel">
            <h2>No usable RAW records</h2>
            <div class="note">
                The supplied CSV did not contain usable SensorMax
                records.
            </div>
        </div>
        """

    timing = data[
        "timing"
    ]

    x = data["x"]

    y = data["y"]

    z = data["z"]

    rss = data["rss"]

    sensor_groups = data[
        "sensors"
    ]

    # --------------------------------------------------------
    # Dominant axis
    # --------------------------------------------------------

    axis_values = {
        "X":
            x,
        "Y":
            y,
        "Z":
            z,
    }

    axis_rms = {
        axis:
            rms(values)
        for axis, values
        in axis_values.items()
    }

    dominant_axis = max(
        axis_rms,
        key=axis_rms.get,
    )

    dominant_fft = data[
        "fft"
    ].get(
        dominant_axis
    )

    # --------------------------------------------------------
    # Cards
    # --------------------------------------------------------

    cards = "".join(
        [
            stat_card(
                "Total CSV records",
                f"{len(records):,}",
                "",
            ),

            stat_card(
                "Vibration samples",
                f"{len(x):,}",
                "",
            ),

            stat_card(
                "Duration",
                f"{timing['duration_s']:.2f}",
                "s",
            ),

            stat_card(
                "Effective sample rate",
                f"{timing['rate_hz']:.2f}",
                "Hz",
            ),

            stat_card(
                "Overall RSS RMS",
                f"{rms(rss):.4f}",
                "m/s²",
            ),

            stat_card(
                "Dynamic RMS",
                f"{rms(rss - np.mean(rss)):.4f}",
                "m/s²",
            ),

            stat_card(
                "Dominant axis",
                dominant_axis,
                "",
            ),

            stat_card(
                "Dominant frequency",
                (
                    f"{dominant_fft['peak_frequency_hz']:.3f}"
                    if dominant_fft
                    else "N/A"
                ),
                "Hz",
            ),
        ]
    )

    # --------------------------------------------------------
    # Context
    # --------------------------------------------------------

    context = "".join(
        [
            info_row(
                "Source file",
                csv_path.name,
            ),

            info_row(
                "Machine ID",
                ", ".join(
                    data["machine_ids"]
                )
                or "UNSPECIFIED",
            ),

            info_row(
                "Measurement point",
                ", ".join(
                    data["points"]
                )
                or "UNSPECIFIED",
            ),

            info_row(
                "Sensor types",
                str(
                    len(
                        sensor_groups
                    )
                ),
            ),

            info_row(
                "Mean sample interval",
                f"{timing['mean_dt_ms']:.3f} ms",
            ),

            info_row(
                "Timestamp jitter",
                f"±{timing['jitter_ms']:.3f} ms",
            ),

            info_row(
                "Maximum sample gap",
                f"{timing['max_dt_ms']:.3f} ms",
            ),

            info_row(
                "Report generated",
                datetime.now().strftime(
                    "%Y-%m-%d %H:%M:%S"
                ),
            ),
        ]
    )

    # --------------------------------------------------------
    # Axis table
    # --------------------------------------------------------

    axis_rows = ""

    for axis, values in axis_values.items():

        stats = {
            "min":
                minimum(values),

            "max":
                maximum(values),

            "mean":
                mean(values),

            "std":
                std(values),

            "rms":
                rms(values),

            "p2p":
                peak_to_peak(values),
        }

        axis_rows += f"""
        <tr>
            <td><strong>{axis}</strong></td>
            <td>{stats["min"]:.5f}</td>
            <td>{stats["max"]:.5f}</td>
            <td>{stats["mean"]:.5f}</td>
            <td>{stats["std"]:.5f}</td>
            <td>{stats["rms"]:.5f}</td>
            <td>{stats["p2p"]:.5f}</td>
        </tr>
        """

    # --------------------------------------------------------
    # Sensor table
    # --------------------------------------------------------

    sensor_rows = ""

    for (
        (sensor_type, sensor_name),
        sensor_records,
    ) in sorted(
        sensor_groups.items(),
        key=lambda item:
            (
                item[0][0],
                item[0][1],
            ),
    ):

        sensor_rows += f"""
        <tr>
            <td>{esc(sensor_type)}</td>
            <td>{esc(sensor_name)}</td>
            <td>{len(sensor_records):,}</td>
        </tr>
        """

    # --------------------------------------------------------
    # FFT charts
    # --------------------------------------------------------

    fft_sections = ""

    for axis in (
        "X",
        "Y",
        "Z",
    ):

        fft_sections += f"""
        <div class="panel">
            <h2>FFT Spectrum — Axis {axis}</h2>
            <div class="chart-shell">
                {svg_spectrum(data["fft"].get(axis))}
            </div>
        </div>
        """

    # --------------------------------------------------------
    # Harmonics
    # --------------------------------------------------------

    if rpm > 0:

        one_x = rpm / 60.0

        harmonic_rows = ""

        for multiplier in (
            1,
            2,
            3,
        ):

            harmonic_rows += f"""
            <tr>
                <td>{multiplier}X</td>
                <td>
                    {one_x * multiplier:.3f} Hz
                </td>
            </tr>
            """

        harmonic_section = f"""
        <div class="panel">
            <h2>Running-Speed Context</h2>

            <div class="grid">

                {stat_card(
                    "RPM",
                    f"{rpm:.0f}",
                    "rpm",
                )}

                {stat_card(
                    "1X",
                    f"{one_x:.3f}",
                    "Hz",
                )}

                {stat_card(
                    "2X",
                    f"{one_x * 2:.3f}",
                    "Hz",
                )}

                {stat_card(
                    "3X",
                    f"{one_x * 3:.3f}",
                    "Hz",
                )}

            </div>

            <div style="margin-top:12px">

                <table>
                    <thead>
                        <tr>
                            <th>Harmonic</th>
                            <th>Frequency</th>
                        </tr>
                    </thead>

                    <tbody>
                        {harmonic_rows}
                    </tbody>
                </table>

            </div>

            <div class="note">
                Running-speed markers provide spectral context.
                They are not standalone fault diagnoses.
            </div>

        </div>
        """

    else:

        harmonic_section = """
        <div class="panel">
            <h2>Running-Speed Context</h2>
            <div class="note">
                No RPM was supplied. Use --rpm to calculate
                1X / 2X / 3X running-speed reference frequencies.
            </div>
        </div>
        """

    # --------------------------------------------------------
    # Build page
    # --------------------------------------------------------

    return f"""
    <div class="grid">
        {cards}
    </div>

    <div class="panel">

        <h2>Measurement Context</h2>

        <div class="info-grid">
            {context}
        </div>

    </div>


    <div class="panel">

        <h2>Vibration Axis Statistics</h2>

        <table>

            <thead>
                <tr>
                    <th>Axis</th>
                    <th>Min</th>
                    <th>Max</th>
                    <th>Mean</th>
                    <th>Std</th>
                    <th>RMS</th>
                    <th>Peak-to-Peak</th>
                </tr>
            </thead>

            <tbody>
                {axis_rows}
            </tbody>

        </table>

    </div>


    <div class="two">

        <div class="panel">

            <h2>Axis X Waveform</h2>

            <div class="chart-shell">
                {svg_waveform(x)}
            </div>

        </div>


        <div class="panel">

            <h2>Axis Y Waveform</h2>

            <div class="chart-shell">
                {svg_waveform(y)}
            </div>

        </div>

    </div>


    <div class="panel">

        <h2>Axis Z Waveform</h2>

        <div class="chart-shell">
            {svg_waveform(z)}
        </div>

    </div>


    <div class="panel">

        <h2>Sensor Inventory</h2>

        <table>

            <thead>
                <tr>
                    <th>Sensor Type</th>
                    <th>Sensor Name</th>
                    <th>Records</th>
                </tr>
            </thead>

            <tbody>
                {sensor_rows}
            </tbody>

        </table>

    </div>


    {harmonic_section}


    <div class="two">
        {''.join(fft_sections)}
    </div>


    <div class="panel">

        <h2>Engineering Notes</h2>

        <div class="note">
            The RAW dataset contains sensor acquisition records.
            Frequency-domain results are calculated here for reporting.
        </div>

        <div class="note">
            RSS is a vector magnitude representation:
            sqrt(X² + Y² + Z²). It is not a physical machine axis.
        </div>

        <div class="note">
            Frequency peaks and harmonic relationships provide
            engineering evidence but do not constitute a confirmed
            machine fault diagnosis by themselves.
        </div>

    </div>
    """


# ============================================================
# ANALYSIS HTML
# ============================================================

def render_analysis_html(
    csv_path: Path,
    data: dict[str, Any],
) -> str:

    records = data[
        "records"
    ]

    if not records:

        return """
        <div class="panel">
            <h2>No analysis windows</h2>
            <div class="note">
                The supplied analysis CSV contains no usable records.
            </div>
        </div>
        """

    sample_rates = np.asarray(
        data["sample_rates"],
        dtype=float,
    )

    rms_velocity = np.asarray(
        data["rms_velocity"],
        dtype=float,
    )

    rms_accel = np.asarray(
        data["rms_accel"],
        dtype=float,
    )

    dominant_freq = np.asarray(
        data["dominant_freq"],
        dtype=float,
    )

    envelope_freq = np.asarray(
        data["envelope_freq"],
        dtype=float,
    )

    timestamps = np.asarray(
        [
            record["timestamp_ms"]
            for record in records
        ],
        dtype=float,
    )

    timing = timing_summary(
        timestamps
    )

    valid_rpm = [
        record["rpm"]
        for record in records
        if record["rpm"] > 0
    ]

    iso_zones = sorted(
        {
            record["iso_zone"]
            for record in records
            if record["iso_zone"]
        }
    )

    bearing_matches = sorted(
        {
            record["bearing_match"]
            for record in records
            if record["bearing_match"]
        }
    )

    impact_count = sum(
        1
        for record in records
        if record["impact"].lower()
        in {
            "true",
            "1",
            "yes",
        }
    )

    snapshot_count = sum(
        1
        for record in records
        if record["snapshot"].lower()
        in {
            "true",
            "1",
            "yes",
        }
    )

    # --------------------------------------------------------
    # Summary
    # --------------------------------------------------------

    cards = "".join(
        [
            stat_card(
                "Analysis windows",
                f"{len(records):,}",
            ),

            stat_card(
                "Session duration",
                f"{timing['duration_s']:.2f}",
                "s",
            ),

            stat_card(
                "Mean sample rate",
                f"{mean(sample_rates):.2f}",
                "Hz",
            ),

            stat_card(
                "Mean RMS velocity",
                f"{mean(rms_velocity):.3f}",
                "mm/s",
            ),

            stat_card(
                "Maximum RMS velocity",
                f"{maximum(rms_velocity):.3f}",
                "mm/s",
            ),

            stat_card(
                "Mean dominant frequency",
                f"{mean(dominant_freq):.3f}",
                "Hz",
            ),

            stat_card(
                "Mean envelope peak",
                f"{mean(envelope_freq):.3f}",
                "Hz",
            ),

            stat_card(
                "Impact events",
                str(impact_count),
            ),
        ]
    )

    # --------------------------------------------------------
    # Context
    # --------------------------------------------------------

    context = "".join(
        [
            info_row(
                "Source file",
                csv_path.name,
            ),

            info_row(
                "Machine ID",
                ", ".join(
                    data["machine_ids"]
                )
                or "UNSPECIFIED",
            ),

            info_row(
                "Measurement point",
                ", ".join(
                    data["points"]
                )
                or "UNSPECIFIED",
            ),

            info_row(
                "RPM values",
                ", ".join(
                    f"{value:.0f}"
                    for value in sorted(
                        set(
                            valid_rpm
                        )
                    )
                )
                or "N/A",
            ),

            info_row(
                "ISO zones observed",
                ", ".join(
                    iso_zones
                )
                or "N/A",
            ),

            info_row(
                "Bearing match codes",
                ", ".join(
                    bearing_matches
                )
                or "N/A",
            ),

            info_row(
                "Snapshots",
                str(
                    snapshot_count
                ),
            ),

            info_row(
                "Report generated",
                datetime.now().strftime(
                    "%Y-%m-%d %H:%M:%S"
                ),
            ),
        ]
    )

    # --------------------------------------------------------
    # RMS trends
    # --------------------------------------------------------

    velocity_trend = svg_trend(
        data[
            "rms_velocity"
        ],
        "RMS Velocity",
        "mm/s",
    )

    accel_trend = svg_trend(
        data[
            "rms_accel"
        ],
        "RMS Acceleration",
        "m/s²",
    )

    frequency_trend = svg_trend(
        data[
            "dominant_freq"
        ],
        "Dominant Frequency",
        "Hz",
    )

    envelope_trend = svg_trend(
        data[
            "envelope_freq"
        ],
        "Envelope Peak",
        "Hz",
    )

    # --------------------------------------------------------
    # Analysis window table
    # --------------------------------------------------------

    table_rows = ""

    # Display newest/most useful records without making
    # the report enormous.
    visible_records = records[
        -200:
    ]

    for index, record in enumerate(
        visible_records,
        start=max(
            1,
            len(records) -
            len(visible_records) +
            1,
        ),
    ):

        iso_class = (
            record["iso_zone"]
            or "N/A"
        )

        table_rows += f"""
        <tr>
            <td>{index}</td>
            <td>{record["sample_rate_hz"]:.2f}</td>
            <td>{record["overall_rms_accel"]:.4f}</td>
            <td>{record["overall_rms_velocity"]:.4f}</td>
            <td>{record["dominant_axis"] or "N/A"}</td>
            <td>{record["dominant_freq"]:.3f}</td>
            <td>{record["envelope_peak"]:.3f}</td>
            <td>{esc(iso_class)}</td>
            <td>{esc(record["bearing_match"] or "N/A")}</td>
            <td>{esc(record["impact"] or "false")}</td>
        </tr>
        """

    return f"""
    <div class="grid">
        {cards}
    </div>


    <div class="panel">

        <h2>Analysis Session Context</h2>

        <div class="info-grid">
            {context}
        </div>

    </div>


    <div class="two">

        <div class="panel">

            <h2>RMS Velocity Trend</h2>

            <div class="chart-shell">
                {velocity_trend}
            </div>

        </div>


        <div class="panel">

            <h2>RMS Acceleration Trend</h2>

            <div class="chart-shell">
                {accel_trend}
            </div>

        </div>

    </div>


    <div class="two">

        <div class="panel">

            <h2>Dominant Frequency Trend</h2>

            <div class="chart-shell">
                {frequency_trend}
            </div>

        </div>


        <div class="panel">

            <h2>Envelope Peak Trend</h2>

            <div class="chart-shell">
                {envelope_trend}
            </div>

        </div>

    </div>


    <div class="panel">

        <h2>Analysis Windows</h2>

        <div style="overflow-x:auto">

            <table>

                <thead>

                    <tr>
                        <th>#</th>
                        <th>Sample Rate</th>
                        <th>RMS Accel</th>
                        <th>RMS Velocity</th>
                        <th>Dominant Axis</th>
                        <th>Dominant Freq</th>
                        <th>Envelope Peak</th>
                        <th>ISO Zone</th>
                        <th>Bearing Match</th>
                        <th>Impact</th>
                    </tr>

                </thead>

                <tbody>
                    {table_rows}
                </tbody>

            </table>

        </div>

        <div class="note">
            Showing up to the most recent 200 analysis windows.
            The original CSV remains the complete source dataset.
        </div>

    </div>


    <div class="panel">

        <h2>Engineering Interpretation Boundary</h2>

        <div class="note">
            ISO zone, bearing-match code, dominant-frequency and
            impact information are reported as supplied by the
            Android analysis pipeline.
        </div>

        <div class="note">
            This report does not independently validate the
            Android signal-processing calculations.
        </div>

        <div class="note">
            A single frequency peak is not sufficient evidence
            for a confirmed mechanical fault.
        </div>

    </div>
    """


# ============================================================
# Full HTML document
# ============================================================

def build_document(
    title: str,
    subtitle: str,
    body: str,
) -> str:

    generated = (
        datetime.now().strftime(
            "%Y-%m-%d %H:%M:%S"
        )
    )

    return f"""<!DOCTYPE html>

<html lang="en">

<head>

    <meta charset="UTF-8">

    <meta
        name="viewport"
        content="width=device-width, initial-scale=1.0"
    >

    <title>
        {esc(title)}
    </title>

    {page_style()}

</head>


<body>

    <div class="page">

        <header>

            <h1>
                SensorMax Engineering Report
            </h1>

            <div class="subtitle">
                {esc(subtitle)}
            </div>

            <div class="classification">
                ENGINEERING EVIDENCE REPORT
            </div>

        </header>


        {body}


        <div class="footer">

            SensorMax LaptopSuite
            |
            Generated {esc(generated)}

            <br>

            This report is a measurement and analysis artifact,
            not a standalone machine-fault diagnosis.

        </div>

    </div>

</body>

</html>
"""


# ============================================================
# Main generator
# ============================================================

def generate_report(
    csv_path: str,
    output_html: str = "",
    rpm: float = 0.0,
) -> Path:

    input_path = (
        Path(
            csv_path
        )
        .expanduser()
        .resolve()
    )

    if not input_path.exists():

        raise FileNotFoundError(
            f"File not found: {input_path}"
        )

    with input_path.open(
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

    if not header:

        raise ValueError(
            "CSV file is empty."
        )

    schema = detect_schema(
        header
    )

    if schema == "CURRENT_RAW":

        data = load_raw(
            input_path
        )

        report_data = (
            build_raw_report_data(
                data
            )
        )

        body = render_raw_html(
            input_path,
            report_data,
            rpm,
        )

        title = (
            "SensorMax Raw Measurement Report"
        )

        subtitle = (
            f"RAW acquisition dataset — {input_path.name}"
        )

    elif schema == "CURRENT_ANALYSIS":

        data = load_analysis(
            input_path
        )

        report_data = (
            build_analysis_report_data(
                data
            )
        )

        body = render_analysis_html(
            input_path,
            report_data,
        )

        title = (
            "SensorMax Analysis Session Report"
        )

        subtitle = (
            f"Analysis dataset — {input_path.name}"
        )

    else:

        raise ValueError(
            "Unsupported CSV schema. "
            "Expected a current SensorMax raw or analysis CSV."
        )

    if output_html:

        output_path = (
            Path(
                output_html
            )
            .expanduser()
            .resolve()
        )

    else:

        output_path = (
            input_path.with_name(
                input_path.stem +
                "_SensorMax_Report.html"
            )
        )

    output_path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    document = build_document(
        title,
        subtitle,
        body,
    )

    output_path.write_text(
        document,
        encoding="utf-8",
    )

    print(
        "[SUCCESS] SensorMax HTML report generated:"
    )

    print(
        f"          {output_path}"
    )

    print(
        f"          Schema: {schema}"
    )

    return output_path


# ============================================================
# CLI
# ============================================================

def build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        description=(
            "Generate a self-contained SensorMax "
            "engineering HTML report from a CSV."
        )
    )

    parser.add_argument(
        "csv_file",
        help=(
            "SensorMax raw or analysis CSV"
        ),
    )

    parser.add_argument(
        "--output",
        default="",
        help=(
            "Output HTML path"
        ),
    )

    parser.add_argument(
        "--rpm",
        type=float,
        default=0.0,
        help=(
            "Optional RPM for RAW 1X/2X/3X context"
        ),
    )

    return parser


def main() -> int:

    parser = build_parser()

    args = parser.parse_args()

    try:

        generate_report(
            args.csv_file,
            args.output,
            args.rpm,
        )

        return 0

    except FileNotFoundError as exc:

        print(
            f"[ERROR] {exc}"
        )

        return 1

    except ValueError as exc:

        print(
            f"[ERROR] {exc}"
        )

        return 1

    except Exception as exc:

        print(
            "[ERROR] Report generation failed: "
            f"{type(exc).__name__}: {exc}"
        )

        return 1


if __name__ == "__main__":

    raise SystemExit(
        main()
    )