#!/usr/bin/env python3
"""
SensorMax Digital Signal Processing (DSP) / Filtering Suite

Offline filtering for the current SensorMax CSV schema.

Current schema:
    Timestamp_ms,Machine_ID,Point,Sensor_Type,Sensor_Name,
    Val_0,Val_1,Val_2,Val_3,Val_4,Val_5

Supported filters:
    ema        Exponential moving average
    ma         Moving average
    lowpass    2nd-order Butterworth-style biquad, forward/reverse
    highpass   2nd-order Butterworth-style biquad, forward/reverse
    bandpass   2nd-order constant-skirt-gain biquad, forward/reverse

The tool does NOT modify AndroidApp and does not assume a specific phone model.

Important:
    Filtering changes the signal. Preserve the original raw recording and use
    the filtered output as a derived dataset. Filter settings are stored in the
    output metadata section.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from collections import defaultdict
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run first_initialize.bat.")
    raise SystemExit(1)


CURRENT_HEADER = [
    "Timestamp_ms",
    "Machine_ID",
    "Point",
    "Sensor_Type",
    "Sensor_Name",
    "Val_0",
    "Val_1",
    "Val_2",
    "Val_3",
    "Val_4",
    "Val_5",
]

AXIS_COLUMNS = ["Val_0", "Val_1", "Val_2"]


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------


def finite_float(value) -> Optional[float]:
    try:
        v = float(value)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def normalize_sensor_type(value) -> Optional[int]:
    if value is None:
        return None

    text = str(value).strip().lower()

    aliases = {
        "accelerometer": 1,
        "accel": 1,
        "magnetometer": 2,
        "mag": 2,
    }

    if text in aliases:
        return aliases[text]

    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def detect_header(first_row: Sequence[str]) -> bool:
    required = {"Timestamp_ms", "Sensor_Type", "Val_0", "Val_1", "Val_2"}
    return required.issubset(set(first_row))


def load_csv(path: str) -> Tuple[List[Dict[str, str]], List[str], str]:
    if not os.path.exists(path):
        raise FileNotFoundError(path)

    rows: List[Dict[str, str]] = []

    with open(path, "r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        first = next(reader, None)

        if first is None:
            raise ValueError("CSV is empty.")

        if detect_header(first):
            header = [h.strip() for h in first]
            dict_reader = csv.DictReader(handle, fieldnames=header)

            for row in dict_reader:
                if row:
                    rows.append({str(k): (v or "") for k, v in row.items() if k})

            return rows, header, "current"

        # Compatibility with the legacy positional schema.
        header = list(CURRENT_HEADER)

        def positional_to_dict(values: Sequence[str]) -> Optional[Dict[str, str]]:
            if len(values) < 6:
                return None

            mapped = {
                "Timestamp_ms": values[0],
                "Machine_ID": "UNSPECIFIED",
                "Point": "UNSPECIFIED",
                "Sensor_Type": values[1],
                "Sensor_Name": values[2],
                "Val_0": values[3],
                "Val_1": values[4] if len(values) > 4 else "",
                "Val_2": values[5] if len(values) > 5 else "",
            }

            for i in range(3, 6):
                mapped[f"Val_{i}"] = values[3 + i] if len(values) > 3 + i else ""

            return mapped

        first_mapped = positional_to_dict(first)
        if first_mapped:
            rows.append(first_mapped)

        for values in reader:
            mapped = positional_to_dict(values)
            if mapped:
                rows.append(mapped)

    return rows, header, "legacy"


# ---------------------------------------------------------------------------
# DSP primitives
# ---------------------------------------------------------------------------


def moving_average(data: np.ndarray, window: int) -> np.ndarray:
    if len(data) == 0:
        return data.copy()

    window = max(1, int(window))
    kernel = np.ones(window, dtype=float) / float(window)
    return np.convolve(data, kernel, mode="same")


def exponential_moving_average(data: np.ndarray, alpha: float) -> np.ndarray:
    """Preserve the original tool's convention: alpha weights the history."""
    if len(data) == 0:
        return data.copy()

    if not 0.0 <= alpha <= 1.0:
        raise ValueError("EMA alpha must be between 0 and 1.")

    out = np.empty_like(data, dtype=float)
    out[0] = data[0]

    for i in range(1, len(data)):
        out[i] = alpha * out[i - 1] + (1.0 - alpha) * data[i]

    return out


def biquad_coefficients(
    filter_type: str,
    cutoff_hz: float,
    fs_hz: float,
    bandwidth_hz: Optional[float] = None,
) -> Tuple[float, float, float, float, float]:
    """RBJ cookbook biquad coefficients, normalized by a0."""
    if fs_hz <= 0:
        raise ValueError("Sampling frequency must be positive.")

    nyquist = fs_hz / 2.0

    if cutoff_hz <= 0 or cutoff_hz >= nyquist:
        raise ValueError(
            f"Cutoff must be > 0 and < Nyquist ({nyquist:.6g} Hz)."
        )

    w0 = 2.0 * math.pi * cutoff_hz / fs_hz
    cos_w0 = math.cos(w0)
    sin_w0 = math.sin(w0)
    q = math.sqrt(0.5)

    if filter_type == "bandpass":
        if bandwidth_hz is None:
            bandwidth_hz = cutoff_hz
        if bandwidth_hz <= 0:
            raise ValueError("Band-pass bandwidth must be positive.")
        alpha = sin_w0 * math.sinh(
            math.log(2.0) / 2.0 * bandwidth_hz * w0 / max(sin_w0, 1e-12)
        )
    else:
        alpha = sin_w0 / (2.0 * q)

    if filter_type == "lowpass":
        b0 = (1.0 - cos_w0) / 2.0
        b1 = 1.0 - cos_w0
        b2 = (1.0 - cos_w0) / 2.0
    elif filter_type == "highpass":
        b0 = (1.0 + cos_w0) / 2.0
        b1 = -(1.0 + cos_w0)
        b2 = (1.0 + cos_w0) / 2.0
    elif filter_type == "bandpass":
        b0 = sin_w0 / 2.0
        b1 = 0.0
        b2 = -sin_w0 / 2.0
    else:
        raise ValueError(f"Unsupported biquad type: {filter_type}")

    a0 = 1.0 + alpha
    a1 = -2.0 * cos_w0
    a2 = 1.0 - alpha

    return (
        b0 / a0,
        b1 / a0,
        b2 / a0,
        a1 / a0,
        a2 / a0,
    )


def biquad_filter(
    data: np.ndarray,
    coeffs: Tuple[float, float, float, float, float],
) -> np.ndarray:
    if len(data) == 0:
        return data.copy()

    b0, b1, b2, a1, a2 = coeffs
    out = np.empty(len(data), dtype=float)

    out[0] = b0 * data[0]

    if len(data) > 1:
        out[1] = (
            b0 * data[1]
            + b1 * data[0]
            - a1 * out[0]
        )

    for i in range(2, len(data)):
        out[i] = (
            b0 * data[i]
            + b1 * data[i - 1]
            + b2 * data[i - 2]
            - a1 * out[i - 1]
            - a2 * out[i - 2]
        )

    return out


def zero_phase_biquad(
    data: np.ndarray,
    coeffs: Tuple[float, float, float, float, float],
) -> np.ndarray:
    """Forward/reverse filtering to approximate zero phase."""
    if len(data) < 4:
        return data.copy()

    pad = min(len(data) - 1, max(3, min(30, len(data) // 4)))

    left = 2.0 * data[0] - data[1:pad + 1][::-1]
    right = 2.0 * data[-1] - data[-pad - 1:-1][::-1]
    padded = np.concatenate([left, data, right])

    forward = biquad_filter(padded, coeffs)
    reverse = biquad_filter(forward[::-1], coeffs)[::-1]

    return reverse[pad:-pad]


# ---------------------------------------------------------------------------
# Sampling-rate estimation
# ---------------------------------------------------------------------------


def estimate_fs(timestamps_ms: Sequence[float]) -> Optional[float]:
    if len(timestamps_ms) < 2:
        return None

    ts = np.asarray(timestamps_ms, dtype=float)
    dt = np.diff(ts)
    valid = dt[np.isfinite(dt) & (dt > 0)]

    if len(valid) == 0:
        return None

    median_dt_ms = float(np.median(valid))

    if median_dt_ms <= 0:
        return None

    return 1000.0 / median_dt_ms


# ---------------------------------------------------------------------------
# Filtering
# ---------------------------------------------------------------------------


def apply_filter(
    values: np.ndarray,
    filter_type: str,
    param: float,
    fs_hz: Optional[float],
    high_cutoff: Optional[float] = None,
) -> np.ndarray:
    if filter_type == "none":
        return values.copy()

    if filter_type == "ema":
        return exponential_moving_average(values, param)

    if filter_type == "ma":
        return moving_average(values, max(1, int(param)))

    if fs_hz is None:
        raise ValueError("A valid sampling rate is required for biquad filters.")

    if filter_type == "bandpass":
        low = param
        high = high_cutoff

        if high is None:
            raise ValueError("Band-pass requires --high-cutoff.")

        if high <= low:
            raise ValueError("--high-cutoff must be greater than --param.")

        # Apply two Butterworth-style sections: high-pass then low-pass.
        hp = biquad_coefficients("highpass", low, fs_hz)
        lp = biquad_coefficients("lowpass", high, fs_hz)
        return zero_phase_biquad(
            zero_phase_biquad(values, hp),
            lp,
        )

    coeffs = biquad_coefficients(
        filter_type,
        param,
        fs_hz,
    )

    return zero_phase_biquad(
        values,
        coeffs,
    )


# ---------------------------------------------------------------------------
# Dataset processing
# ---------------------------------------------------------------------------


def normalize_row(row: Dict[str, str], index: int) -> Dict[str, str]:
    out = {key: row.get(key, "") for key in CURRENT_HEADER}

    # Preserve additional fields where possible.
    for key, value in row.items():
        if key not in out:
            out[key] = value

    if not out["Machine_ID"]:
        out["Machine_ID"] = "UNSPECIFIED"

    if not out["Point"]:
        out["Point"] = "UNSPECIFIED"

    if not out["Sensor_Name"]:
        out["Sensor_Name"] = "UNSPECIFIED"

    return out


def process_dataset(
    rows: List[Dict[str, str]],
    filter_type: str,
    param: float,
    high_cutoff: Optional[float],
    sensor_type: Optional[int],
) -> Tuple[List[Dict[str, str]], Dict[str, object]]:
    normalized = [normalize_row(row, i) for i, row in enumerate(rows)]

    # Only process valid numeric 3-axis streams. Other sensors pass through.
    groups: Dict[Tuple[str, str, int, str], List[Tuple[int, float, float, float, float]]] = defaultdict(list)

    for index, row in enumerate(normalized):
        stype = normalize_sensor_type(row.get("Sensor_Type"))

        if sensor_type is not None and stype != sensor_type:
            continue

        ts = finite_float(row.get("Timestamp_ms"))
        x = finite_float(row.get("Val_0"))
        y = finite_float(row.get("Val_1"))
        z = finite_float(row.get("Val_2"))

        if stype is None or ts is None or x is None or y is None or z is None:
            continue

        key = (
            row.get("Machine_ID", "UNSPECIFIED"),
            row.get("Point", "UNSPECIFIED"),
            stype,
            row.get("Sensor_Name", "UNSPECIFIED"),
        )

        groups[key].append((index, ts, x, y, z))

    metadata: Dict[str, object] = {
        "filter_type": filter_type,
        "parameter": param,
        "high_cutoff_hz": high_cutoff,
        "processed_groups": 0,
        "processed_samples": 0,
        "group_sampling_rates_hz": [],
        "notes": (
            "Filtered dataset derived from raw SensorMax data; "
            "original raw values must be retained separately."
        ),
    }

    for key, samples in groups.items():
        if len(samples) < 3:
            continue

        # Ensure chronological processing within the group.
        samples = sorted(samples, key=lambda item: item[1])

        indices = [item[0] for item in samples]
        timestamps = [item[1] for item in samples]

        fs_hz = estimate_fs(timestamps)

        if fs_hz is None:
            continue

        x = np.asarray([item[2] for item in samples], dtype=float)
        y = np.asarray([item[3] for item in samples], dtype=float)
        z = np.asarray([item[4] for item in samples], dtype=float)

        fx = apply_filter(x, filter_type, param, fs_hz, high_cutoff)
        fy = apply_filter(y, filter_type, param, fs_hz, high_cutoff)
        fz = apply_filter(z, filter_type, param, fs_hz, high_cutoff)

        for local_i, row_index in enumerate(indices):
            row = normalized[row_index]
            row["Val_0"] = f"{fx[local_i]:.8f}"
            row["Val_1"] = f"{fy[local_i]:.8f}"
            row["Val_2"] = f"{fz[local_i]:.8f}"

        metadata["processed_groups"] = int(metadata["processed_groups"]) + 1
        metadata["processed_samples"] = int(metadata["processed_samples"]) + len(samples)
        metadata["group_sampling_rates_hz"].append({
            "machine_id": key[0],
            "point": key[1],
            "sensor_type": key[2],
            "sensor_name": key[3],
            "sample_count": len(samples),
            "estimated_hz": fs_hz,
        })

    return normalized, metadata


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------


def write_output(
    path: str,
    rows: List[Dict[str, str]],
    source_schema: str,
    metadata: Dict[str, object],
) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)

    # Use current canonical columns first. Preserve unknown input columns after them.
    extra = []
    seen = set(CURRENT_HEADER)
    for row in rows:
        for key in row:
            if key not in seen and key not in extra:
                extra.append(key)

    fieldnames = CURRENT_HEADER + extra

    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)

        # Metadata is deliberately explicit instead of hiding provenance in the file name.
        writer.writerow(["# SensorMax_Derived_Dataset"])
        writer.writerow(["# source_schema", source_schema])
        for key, value in metadata.items():
            if isinstance(value, (dict, list)):
                writer.writerow([f"# {key}", repr(value)])
            else:
                writer.writerow([f"# {key}", value])
        writer.writerow([])
        writer.writerow(fieldnames)

        for row in rows:
            writer.writerow([row.get(field, "") for field in fieldnames])


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Offline SensorMax DSP/filtering engine."
    )

    parser.add_argument(
        "csv_file",
        help="Input SensorMax raw CSV.",
    )

    parser.add_argument(
        "--filter",
        choices=[
            "ema",
            "ma",
            "lowpass",
            "highpass",
            "bandpass",
            "none",
        ],
        default="ema",
        help="Filter type. Default: ema.",
    )

    parser.add_argument(
        "--param",
        type=float,
        default=0.2,
        help=(
            "EMA alpha, MA window, or low cutoff Hz depending on filter type. "
            "Default: 0.2."
        ),
    )

    parser.add_argument(
        "--high-cutoff",
        type=float,
        default=None,
        help="Upper cutoff Hz for bandpass.",
    )

    parser.add_argument(
        "--sensor-type",
        type=int,
        default=None,
        help=(
            "Process only this Sensor_Type. Example: 1 for accelerometer. "
            "Default: all compatible 3-axis sensors."
        ),
    )

    parser.add_argument(
        "--output",
        default=None,
        help=(
            "Output CSV path. Default: <input>_filtered.csv"
        ),
    )

    return parser


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    args = build_parser().parse_args()

    if not os.path.exists(args.csv_file):
        print(f"[ERROR] File not found: {args.csv_file}")
        return 2

    if args.filter in {"ema", "ma", "lowpass", "highpass", "bandpass"} and args.param <= 0:
        print("[ERROR] --param must be > 0.")
        return 2

    if args.filter == "ema" and args.param > 1:
        print("[ERROR] EMA --param must be between 0 and 1.")
        return 2

    if args.filter == "bandpass" and args.high_cutoff is None:
        print("[ERROR] bandpass requires --high-cutoff.")
        return 2

    if args.filter == "bandpass" and args.high_cutoff <= args.param:
        print("[ERROR] --high-cutoff must be greater than --param.")
        return 2

    output = (
        args.output
        if args.output
        else os.path.splitext(args.csv_file)[0] + "_filtered.csv"
    )

    try:
        rows, _header, source_schema = load_csv(args.csv_file)

        filtered_rows, metadata = process_dataset(
            rows,
            filter_type=args.filter,
            param=args.param,
            high_cutoff=args.high_cutoff,
            sensor_type=args.sensor_type,
        )

        write_output(
            output,
            filtered_rows,
            source_schema,
            metadata,
        )

    except Exception as exc:
        print(f"[ERROR] DSP filtering failed: {exc}")
        return 1

    print("============================================================")
    print(" SensorMax DSP FILTERING COMPLETE")
    print("============================================================")
    print(f"Input             : {os.path.abspath(args.csv_file)}")
    print(f"Filter            : {args.filter}")
    print(f"Parameter         : {args.param}")

    if args.high_cutoff is not None:
        print(f"High cutoff       : {args.high_cutoff} Hz")

    print(f"Processed groups  : {metadata['processed_groups']}")
    print(f"Processed samples : {metadata['processed_samples']}")
    print(f"Output            : {os.path.abspath(output)}")
    print("AndroidApp        : UNCHANGED")
    print("============================================================")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
