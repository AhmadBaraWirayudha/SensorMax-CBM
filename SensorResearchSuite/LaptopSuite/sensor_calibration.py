#!/usr/bin/env python3
"""
SensorMax Offline Calibration / Characterization Tool

Purpose
-------
Analyze a stationary SensorMax recording and generate a calibration /
characterization profile for the specific machine/device/sensor context.

IMPORTANT
---------
This is an OFFLINE laptop-side tool.

It does NOT modify AndroidApp.
It does NOT inject calibration values into the Android runtime.
It does NOT create a universal calibration profile for all phones.

Current raw CSV schema supported:

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

Legacy positional CSVs are also accepted when possible.

Sensor type conventions used by the current Android project:
    1 = Accelerometer
    2 = Magnetometer

Accelerometer characterization
------------------------------
For a stationary recording, the tool calculates:

    mean X/Y/Z
    noise sigma X/Y/Z
    RSS noise
    magnitude mean
    magnitude standard deviation
    achieved sample rate
    sample count
    duration

The tool optionally reports gravity-referenced offsets when the operator
specifies the physical orientation.

Magnetometer characterization
-----------------------------
For a rotation dataset, the tool estimates hard-iron offsets with a
bounding-box center method and reports field-strength statistics.

Engineering boundary
--------------------
This is characterization/calibration support, not laboratory traceable
metrology. A phone MEMS sensor should not be presented as a certified
industrial vibration transducer solely from these calculations.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    print(
        "Error: numpy is required. "
        "Run first_initialize.bat."
    )
    sys.exit(1)


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_GRAVITY_MS2 = 9.80665

ACCEL_SENSOR_TYPE = 1
MAG_SENSOR_TYPE = 2

MIN_SAMPLES_ACCEL = 10
MIN_SAMPLES_MAG = 10


# ---------------------------------------------------------------------------
# Generic helpers
# ---------------------------------------------------------------------------

def utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat(
        timespec="seconds"
    )


def finite_float(value: Any) -> Optional[float]:
    try:
        number = float(value)

        if math.isfinite(number):
            return number

    except (TypeError, ValueError):
        pass

    return None


def text_or(
    value: Any,
    fallback: str = "UNSPECIFIED",
) -> str:
    if value is None:
        return fallback

    value = str(value).strip()

    return value if value else fallback


def safe_mean(values: Iterable[float]) -> float:
    data = list(values)

    if not data:
        return float("nan")

    return float(
        np.mean(
            np.asarray(
                data,
                dtype=float,
            )
        )
    )


def safe_std(values: Iterable[float]) -> float:
    data = list(values)

    if not data:
        return float("nan")

    return float(
        np.std(
            np.asarray(
                data,
                dtype=float,
            ),
            ddof=0,
        )
    )


def finite_or_none(value: float) -> Optional[float]:
    return (
        float(value)
        if math.isfinite(value)
        else None
    )


# ---------------------------------------------------------------------------
# CSV loading
# ---------------------------------------------------------------------------

def detect_current_schema(fieldnames: Optional[List[str]]) -> bool:
    if not fieldnames:
        return False

    required = {
        "Timestamp_ms",
        "Sensor_Type",
        "Val_0",
        "Val_1",
        "Val_2",
    }

    return required.issubset(
        set(fieldnames)
    )


def normalize_sensor_type(value: Any) -> Optional[int]:
    """
    Accept:
        1 / "1"
        2 / "2"
        Accelerometer
        Magnetometer
    """

    if value is None:
        return None

    text = str(value).strip().lower()

    if text in {
        "1",
        "accelerometer",
        "accel",
        "linear_acceleration",
    }:
        return ACCEL_SENSOR_TYPE

    if text in {
        "2",
        "magnetometer",
        "mag",
        "magnetic_field",
    }:
        return MAG_SENSOR_TYPE

    try:
        return int(float(text))
    except (ValueError, TypeError):
        return None


def load_sensor_rows(
    csv_path: str,
) -> Tuple[
    List[Dict[str, Any]],
    Dict[str, str],
]:
    """
    Load current SensorMax CSV and selected legacy positional formats.

    Returns:
        rows, metadata
    """

    if not os.path.exists(csv_path):
        raise FileNotFoundError(
            f"Dataset not found: {csv_path}"
        )

    rows: List[Dict[str, Any]] = []
    metadata: Dict[str, str] = {}

    with open(
        csv_path,
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as handle:

        reader = csv.reader(handle)

        first = next(
            reader,
            None,
        )

        if first is None:
            raise ValueError(
                "CSV file is empty."
            )

        is_header = (
            "Timestamp_ms" in first
            or "Sensor_Type" in first
        )

        if is_header:

            header = [
                str(v).strip()
                for v in first
            ]

            dict_reader = csv.DictReader(
                handle,
                fieldnames=header,
            )

            for row in dict_reader:

                if not row:
                    continue

                rows.append(
                    {
                        str(k).strip():
                        v
                        for k, v in row.items()
                        if k is not None
                    }
                )

            metadata["schema"] = (
                "SensorMax current named schema"
                if detect_current_schema(header)
                else "named CSV schema"
            )

        else:
            # Legacy positional format.
            #
            # Existing old files used positions resembling:
            # timestamp, sensor_type, sensor_name, x, y, z, ...
            #
            # Keep this only as compatibility behavior.
            metadata["schema"] = (
                "legacy positional schema"
            )

            def add_legacy(
                values: List[str],
            ) -> None:

                if len(values) < 6:
                    return

                rows.append(
                    {
                        "Timestamp_ms": values[0],
                        "Sensor_Type": values[1],
                        "Sensor_Name": values[2]
                        if len(values) > 2
                        else "",
                        "Val_0": values[3]
                        if len(values) > 3
                        else "",
                        "Val_1": values[4]
                        if len(values) > 4
                        else "",
                        "Val_2": values[5]
                        if len(values) > 5
                        else "",
                    }
                )

            add_legacy(first)

            for values in reader:
                add_legacy(values)

    if not rows:
        raise ValueError(
            "No data rows were found."
        )

    return rows, metadata


# ---------------------------------------------------------------------------
# Sensor extraction
# ---------------------------------------------------------------------------

def get_axis_values(
    rows: List[Dict[str, Any]],
    sensor_type: int,
) -> Dict[str, Any]:

    timestamps: List[int] = []
    x: List[float] = []
    y: List[float] = []
    z: List[float] = []

    machine_ids = set()
    points = set()
    sensor_names = set()

    for row in rows:

        stype = normalize_sensor_type(
            row.get("Sensor_Type")
            or row.get("sensor_type")
        )

        if stype != sensor_type:
            continue

        xv = finite_float(
            row.get("Val_0")
            if "Val_0" in row
            else row.get("Value_0")
        )

        yv = finite_float(
            row.get("Val_1")
            if "Val_1" in row
            else row.get("Value_1")
        )

        zv = finite_float(
            row.get("Val_2")
            if "Val_2" in row
            else row.get("Value_2")
        )

        tv = finite_float(
            row.get("Timestamp_ms")
            or row.get("timestamp")
        )

        if (
            xv is None
            or yv is None
            or zv is None
        ):
            continue

        x.append(xv)
        y.append(yv)
        z.append(zv)

        if tv is not None:
            timestamps.append(
                int(tv)
            )

        machine = row.get(
            "Machine_ID"
        )

        if machine:
            machine_ids.add(
                str(machine).strip()
            )

        point = row.get(
            "Point"
        )

        if point:
            points.add(
                str(point).strip()
            )

        sensor_name = row.get(
            "Sensor_Name"
        )

        if sensor_name:
            sensor_names.add(
                str(sensor_name).strip()
            )

    return {
        "timestamps_ms": timestamps,
        "x": np.asarray(
            x,
            dtype=float,
        ),
        "y": np.asarray(
            y,
            dtype=float,
        ),
        "z": np.asarray(
            z,
            dtype=float,
        ),
        "machine_ids": sorted(
            machine_ids
        ),
        "points": sorted(
            points
        ),
        "sensor_names": sorted(
            sensor_names
        ),
    }


# ---------------------------------------------------------------------------
# Timing characterization
# ---------------------------------------------------------------------------

def timing_characterization(
    timestamps_ms: List[int],
) -> Dict[str, Any]:

    if len(timestamps_ms) < 2:
        return {
            "duration_ms": None,
            "achieved_sampling_hz": None,
            "median_dt_ms": None,
            "mean_dt_ms": None,
            "std_dt_ms": None,
            "min_dt_ms": None,
            "max_dt_ms": None,
            "valid_intervals": 0,
        }

    ts = np.asarray(
        timestamps_ms,
        dtype=float,
    )

    # Ignore non-forward timestamp jumps.
    dt = np.diff(ts)
    valid_dt = dt[
        np.isfinite(dt) & (dt > 0)
    ]

    if valid_dt.size == 0:
        return {
            "duration_ms": None,
            "achieved_sampling_hz": None,
            "median_dt_ms": None,
            "mean_dt_ms": None,
            "std_dt_ms": None,
            "min_dt_ms": None,
            "max_dt_ms": None,
            "valid_intervals": 0,
        }

    elapsed_ms = (
        float(ts[-1] - ts[0])
    )

    achieved_hz = (
        (len(ts) - 1)
        * 1000.0
        / elapsed_ms
        if elapsed_ms > 0
        else None
    )

    return {
        "duration_ms": (
            int(elapsed_ms)
            if elapsed_ms >= 0
            else None
        ),
        "achieved_sampling_hz": (
            finite_or_none(
                float(achieved_hz)
            )
            if achieved_hz is not None
            else None
        ),
        "median_dt_ms": finite_or_none(
            float(
                np.median(valid_dt)
            )
        ),
        "mean_dt_ms": finite_or_none(
            float(
                np.mean(valid_dt)
            )
        ),
        "std_dt_ms": finite_or_none(
            float(
                np.std(valid_dt)
            )
        ),
        "min_dt_ms": finite_or_none(
            float(
                np.min(valid_dt)
            )
        ),
        "max_dt_ms": finite_or_none(
            float(
                np.max(valid_dt)
            )
        ),
        "valid_intervals": int(
            valid_dt.size
        ),
    }


# ---------------------------------------------------------------------------
# Accelerometer calibration
# ---------------------------------------------------------------------------

def calibrate_accelerometer(
    data: Dict[str, Any],
    expected_g: float,
    gravity_axis: str,
) -> Optional[Dict[str, Any]]:

    x = data["x"]
    y = data["y"]
    z = data["z"]

    n = len(x)

    print(
        "\n--- Accelerometer Static Characterization ---"
    )

    if n < MIN_SAMPLES_ACCEL:
        print(
            f"Insufficient accelerometer samples: "
            f"{n} < {MIN_SAMPLES_ACCEL}"
        )
        return None

    mean_x = safe_mean(x)
    mean_y = safe_mean(y)
    mean_z = safe_mean(z)

    noise_x = safe_std(x)
    noise_y = safe_std(y)
    noise_z = safe_std(z)

    centered_rss_noise = math.sqrt(
        noise_x ** 2
        + noise_y ** 2
        + noise_z ** 2
    )

    magnitude = np.sqrt(
        x * x
        + y * y
        + z * z
    )

    magnitude_mean = safe_mean(
        magnitude
    )

    magnitude_std = safe_std(
        magnitude
    )

    gravity_axis = gravity_axis.lower()

    gravity_referenced_offsets = {
        "x": mean_x,
        "y": mean_y,
        "z": mean_z,
    }

    if gravity_axis == "x":
        gravity_referenced_offsets["x"] = (
            mean_x - expected_g
        )
    elif gravity_axis == "-x":
        gravity_referenced_offsets["x"] = (
            mean_x + expected_g
        )
    elif gravity_axis == "y":
        gravity_referenced_offsets["y"] = (
            mean_y - expected_g
        )
    elif gravity_axis == "-y":
        gravity_referenced_offsets["y"] = (
            mean_y + expected_g
        )
    elif gravity_axis == "z":
        gravity_referenced_offsets["z"] = (
            mean_z - expected_g
        )
    elif gravity_axis == "-z":
        gravity_referenced_offsets["z"] = (
            mean_z + expected_g
        )
    elif gravity_axis == "none":
        pass
    else:
        raise ValueError(
            "gravity_axis must be one of "
            "none, x, -x, y, -y, z, -z"
        )

    timing = timing_characterization(
        data["timestamps_ms"]
    )

    result = {
        "sensor": "Accelerometer",
        "sensor_type": ACCEL_SENSOR_TYPE,
        "sample_count": int(n),

        "raw_axis_mean_ms2": [
            float(mean_x),
            float(mean_y),
            float(mean_z),
        ],

        "noise_std_ms2": [
            float(noise_x),
            float(noise_y),
            float(noise_z),
        ],

        "noise_rss_ms2": float(
            centered_rss_noise
        ),

        "stationary_magnitude_mean_ms2": float(
            magnitude_mean
        ),

        "stationary_magnitude_std_ms2": float(
            magnitude_std
        ),

        "expected_gravity_ms2": float(
            expected_g
        ),

        "gravity_axis": gravity_axis,

        "gravity_referenced_offsets_ms2": [
            float(
                gravity_referenced_offsets["x"]
            ),
            float(
                gravity_referenced_offsets["y"]
            ),
            float(
                gravity_referenced_offsets["z"]
            ),
        ],

        "timing": timing,
    }

    print(
        f"Samples analyzed : {n}"
    )

    print(
        "Raw axis mean    : "
        f"X={mean_x:+.5f} "
        f"Y={mean_y:+.5f} "
        f"Z={mean_z:+.5f} m/s²"
    )

    print(
        "Noise 1σ         : "
        f"X={noise_x:.5f} "
        f"Y={noise_y:.5f} "
        f"Z={noise_z:.5f} m/s²"
    )

    print(
        f"Noise RSS        : "
        f"{centered_rss_noise:.5f} m/s²"
    )

    print(
        f"|a| mean         : "
        f"{magnitude_mean:.5f} m/s²"
    )

    print(
        f"|a| std          : "
        f"{magnitude_std:.5f} m/s²"
    )

    achieved_hz = timing[
        "achieved_sampling_hz"
    ]

    if achieved_hz is not None:
        print(
            f"Achieved rate    : "
            f"{achieved_hz:.3f} Hz"
        )

    print(
        "Gravity reference: "
        f"{gravity_axis}"
    )

    return result


# ---------------------------------------------------------------------------
# Magnetometer hard-iron characterization
# ---------------------------------------------------------------------------

def calibrate_magnetometer(
    data: Dict[str, Any],
) -> Optional[Dict[str, Any]]:

    x = data["x"]
    y = data["y"]
    z = data["z"]

    n = len(x)

    print(
        "\n--- Magnetometer Hard-Iron Characterization ---"
    )

    if n < MIN_SAMPLES_MAG:
        print(
            f"Insufficient magnetometer samples: "
            f"{n} < {MIN_SAMPLES_MAG}"
        )
        return None

    min_x = float(np.min(x))
    max_x = float(np.max(x))

    min_y = float(np.min(y))
    max_y = float(np.max(y))

    min_z = float(np.min(z))
    max_z = float(np.max(z))

    offset_x = (
        max_x + min_x
    ) / 2.0

    offset_y = (
        max_y + min_y
    ) / 2.0

    offset_z = (
        max_z + min_z
    ) / 2.0

    corrected_x = x - offset_x
    corrected_y = y - offset_y
    corrected_z = z - offset_z

    field_strength = np.sqrt(
        corrected_x ** 2
        + corrected_y ** 2
        + corrected_z ** 2
    )

    result = {
        "sensor": "Magnetometer",
        "sensor_type": MAG_SENSOR_TYPE,
        "sample_count": int(n),

        "hard_iron_offsets_uT": [
            float(offset_x),
            float(offset_y),
            float(offset_z),
        ],

        "raw_axis_min_uT": [
            min_x,
            min_y,
            min_z,
        ],

        "raw_axis_max_uT": [
            max_x,
            max_y,
            max_z,
        ],

        "corrected_field_strength_mean_uT": float(
            np.mean(field_strength)
        ),

        "corrected_field_strength_std_uT": float(
            np.std(field_strength)
        ),

        "corrected_field_strength_min_uT": float(
            np.min(field_strength)
        ),

        "corrected_field_strength_max_uT": float(
            np.max(field_strength)
        ),
    }

    print(
        f"Samples analyzed : {n}"
    )

    print(
        "Hard-iron offset : "
        f"X={offset_x:+.3f} "
        f"Y={offset_y:+.3f} "
        f"Z={offset_z:+.3f} µT"
    )

    print(
        "Field strength   : "
        f"mean={np.mean(field_strength):.3f} "
        f"std={np.std(field_strength):.3f} µT"
    )

    return result


# ---------------------------------------------------------------------------
# Profile assembly
# ---------------------------------------------------------------------------

def build_profile(
    *,
    csv_path: str,
    metadata: Dict[str, str],
    rows: List[Dict[str, Any]],
    accel: Optional[Dict[str, Any]],
    magnetometer: Optional[Dict[str, Any]],
    operator: str,
    mounting_method: str,
    notes: str,
) -> Dict[str, Any]:

    machine_ids = set()
    points = set()
    sensor_names = set()

    for row in rows:

        machine = row.get(
            "Machine_ID"
        )

        if machine:
            machine_ids.add(
                str(machine).strip()
            )

        point = row.get(
            "Point"
        )

        if point:
            points.add(
                str(point).strip()
            )

        sensor_name = row.get(
            "Sensor_Name"
        )

        if sensor_name:
            sensor_names.add(
                str(sensor_name).strip()
            )

    profile = {
        "format": "SensorMaxCalibrationProfile",
        "schema_version": 1,

        "provenance": {
            "created_utc": utc_iso(),
            "source_csv": os.path.abspath(
                csv_path
            ),
            "input_schema": metadata.get(
                "schema",
                "unknown",
            ),
            "operator": text_or(
                operator,
                "UNSPECIFIED",
            ),
            "mounting_method": text_or(
                mounting_method,
                "UNSPECIFIED",
            ),
            "notes": text_or(
                notes,
                "",
            ),
        },

        "identity_context": {
            "machine_ids": sorted(
                machine_ids
            ),
            "points": sorted(
                points
            ),
            "sensor_names": sorted(
                sensor_names
            ),
        },

        "accelerometer": accel,

        "magnetometer": magnetometer,

        "engineering_boundary": (
            "Offline sensor characterization only. "
            "Profile is tied to the supplied dataset/device "
            "context and must not be treated as a universal "
            "calibration for another device."
        ),
    }

    return profile


# ---------------------------------------------------------------------------
# Human-readable summary
# ---------------------------------------------------------------------------

def print_summary(
    profile: Dict[str, Any],
) -> None:

    print(
        "\n============================================================"
    )

    print(
        " SensorMax CALIBRATION / CHARACTERIZATION SUMMARY"
    )

    print(
        "============================================================"
    )

    provenance = profile[
        "provenance"
    ]

    print(
        f"Created UTC       : "
        f"{provenance['created_utc']}"
    )

    print(
        f"Input schema      : "
        f"{provenance['input_schema']}"
    )

    print(
        f"Operator          : "
        f"{provenance['operator']}"
    )

    print(
        f"Mounting method   : "
        f"{provenance['mounting_method']}"
    )

    identity = profile[
        "identity_context"
    ]

    print(
        f"Machine IDs       : "
        f"{', '.join(identity['machine_ids']) or 'UNSPECIFIED'}"
    )

    print(
        f"Points            : "
        f"{', '.join(identity['points']) or 'UNSPECIFIED'}"
    )

    accel = profile[
        "accelerometer"
    ]

    if accel is not None:

        print(
            f"Accel samples     : "
            f"{accel['sample_count']}"
        )

        print(
            f"Accel noise RSS   : "
            f"{accel['noise_rss_ms2']:.5f} m/s²"
        )

        print(
            f"Accel magnitude   : "
            f"{accel['stationary_magnitude_mean_ms2']:.5f} "
            f"± "
            f"{accel['stationary_magnitude_std_ms2']:.5f} m/s²"
        )

        hz = accel[
            "timing"
        ][
            "achieved_sampling_hz"
        ]

        if hz is not None:
            print(
                f"Achieved rate     : "
                f"{hz:.3f} Hz"
            )

    mag = profile[
        "magnetometer"
    ]

    if mag is not None:

        print(
            f"Mag samples       : "
            f"{mag['sample_count']}"
        )

        offsets = mag[
            "hard_iron_offsets_uT"
        ]

        print(
            "Mag hard-iron     : "
            f"X={offsets[0]:+.3f}, "
            f"Y={offsets[1]:+.3f}, "
            f"Z={offsets[2]:+.3f} µT"
        )

    print(
        "============================================================"
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        description=(
            "Offline SensorMax calibration and "
            "sensor characterization."
        )
    )

    parser.add_argument(
        "csv_file",
        help=(
            "Path to current SensorMax raw CSV "
            "or compatible legacy CSV."
        ),
    )

    parser.add_argument(
        "--save-json",
        default=None,
        help=(
            "Output calibration profile JSON. "
            "Default: <input>_calibration.json"
        ),
    )

    parser.add_argument(
        "--operator",
        default="UNSPECIFIED",
        help="Operator name stored in provenance.",
    )

    parser.add_argument(
        "--mounting",
        default="stationary_flat_surface",
        help=(
            "Description of the calibration mounting/setup."
        ),
    )

    parser.add_argument(
        "--gravity-axis",
        choices=[
            "none",
            "x",
            "-x",
            "y",
            "-y",
            "z",
            "-z",
        ],
        default="z",
        help=(
            "Axis physically aligned with +g or -g "
            "during accelerometer static test. "
            "Default: z"
        ),
    )

    parser.add_argument(
        "--gravity",
        type=float,
        default=DEFAULT_GRAVITY_MS2,
        help=(
            "Expected gravitational acceleration in m/s². "
            f"Default: {DEFAULT_GRAVITY_MS2}"
        ),
    )

    parser.add_argument(
        "--no-magnetometer",
        action="store_true",
        help=(
            "Skip magnetometer characterization."
        ),
    )

    parser.add_argument(
        "--notes",
        default="",
        help=(
            "Optional operator notes."
        ),
    )

    return parser


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:

    parser = build_parser()
    args = parser.parse_args()

    if not os.path.exists(
        args.csv_file
    ):

        print(
            f"[ERROR] File not found: "
            f"{args.csv_file}"
        )

        return 2

    if args.gravity <= 0:

        print(
            "[ERROR] --gravity must be > 0."
        )

        return 2

    try:

        rows, metadata = load_sensor_rows(
            args.csv_file
        )

    except Exception as exc:

        print(
            f"[ERROR] Could not load dataset: "
            f"{exc}"
        )

        return 1

    accel_data = get_axis_values(
        rows,
        ACCEL_SENSOR_TYPE,
    )

    mag_data = get_axis_values(
        rows,
        MAG_SENSOR_TYPE,
    )

    print(
        "============================================================"
    )

    print(
        " SensorMax OFFLINE CALIBRATION ENGINE"
    )

    print(
        "============================================================"
    )

    print(
        f"Input dataset     : "
        f"{os.path.abspath(args.csv_file)}"
    )

    print(
        f"Input rows        : "
        f"{len(rows)}"
    )

    print(
        f"Detected schema   : "
        f"{metadata.get('schema', 'unknown')}"
    )

    print(
        "AndroidApp status : LOCKED / UNCHANGED"
    )

    print(
        "============================================================"
    )

    accel = calibrate_accelerometer(
        accel_data,
        expected_g=args.gravity,
        gravity_axis=args.gravity_axis,
    )

    magnetometer = None

    if not args.no_magnetometer:

        magnetometer = calibrate_magnetometer(
            mag_data
        )

    output_path = (
        args.save_json
        if args.save_json
        else os.path.splitext(
            args.csv_file
        )[0]
        + "_calibration.json"
    )

    profile = build_profile(
        csv_path=args.csv_file,
        metadata=metadata,
        rows=rows,
        accel=accel,
        magnetometer=magnetometer,
        operator=args.operator,
        mounting_method=args.mounting,
        notes=args.notes,
    )

    with open(
        output_path,
        "w",
        encoding="utf-8",
    ) as handle:

        json.dump(
            profile,
            handle,
            indent=2,
            ensure_ascii=False,
        )

    print_summary(
        profile
    )

    print(
        f"\n[SUCCESS] Calibration profile saved to:"
    )

    print(
        f"          {os.path.abspath(output_path)}"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(
        main()
    )