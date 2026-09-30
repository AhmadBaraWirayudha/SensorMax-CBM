#!/usr/bin/env python3
"""
SensorMax Dataset Quality Assurance & Anomaly Detector

Scans SensorMax raw CSV recordings for:
    - non-monotonic timestamps
    - large inter-sample gaps
    - duplicate timestamps
    - invalid / non-finite samples
    - configurable acceleration saturation / clipping
    - configurable per-axis statistical outliers
    - unusually large sample-to-sample acceleration jumps

Current SensorMax raw schema:
    Timestamp_ms,Machine_ID,Point,Sensor_Type,Sensor_Name,
    Val_0,Val_1,Val_2,Val_3,Val_4,Val_5

The detector also accepts the older positional layout used by the legacy
LaptopSuite tools when the current named schema is not present.

Engineering boundary:
This is a data-quality diagnostic. It does not determine whether a machine
is mechanically healthy, and it does not treat a phone MEMS accelerometer as
a certified industrial vibration instrument.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import sys
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run first_initialize.bat.")
    raise SystemExit(1)


ACCEL_SENSOR_TYPE = 1


@dataclass
class Sample:
    row_number: int
    timestamp_ms: float
    machine_id: str
    point: str
    sensor_type: int
    sensor_name: str
    x: float
    y: float
    z: float


@dataclass
class Anomaly:
    row_number: int
    timestamp_ms: float
    machine_id: str
    point: str
    anomaly_type: str
    axis: str
    value: Optional[float]
    threshold: Optional[float]
    detail: str


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def finite_float(value: Any) -> Optional[float]:
    try:
        number = float(value)
        return number if math.isfinite(number) else None
    except (TypeError, ValueError):
        return None


def text_or(value: Any, fallback: str = "UNSPECIFIED") -> str:
    if value is None:
        return fallback
    text = str(value).strip()
    return text if text else fallback


def normalize_sensor_type(value: Any) -> Optional[int]:
    if value is None:
        return None

    text = str(value).strip().lower()

    aliases = {
        "1": 1,
        "accelerometer": 1,
        "accel": 1,
        "linear_acceleration": 1,
    }

    if text in aliases:
        return aliases[text]

    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def parse_row(
    row_number: int,
    row: Dict[str, Any],
) -> Tuple[Optional[Sample], Optional[str]]:
    """Parse a current named-schema or compatible normalized row."""

    sensor_type = normalize_sensor_type(
        row.get("Sensor_Type") or row.get("sensor_type")
    )

    if sensor_type != ACCEL_SENSOR_TYPE:
        return None, None

    timestamp = finite_float(
        row.get("Timestamp_ms")
        if "Timestamp_ms" in row
        else row.get("timestamp")
    )

    x = finite_float(
        row.get("Val_0")
        if "Val_0" in row
        else row.get("Value_0")
    )
    y = finite_float(
        row.get("Val_1")
        if "Val_1" in row
        else row.get("Value_1")
    )
    z = finite_float(
        row.get("Val_2")
        if "Val_2" in row
        else row.get("Value_2")
    )

    if timestamp is None:
        return None, "invalid timestamp"

    if x is None or y is None or z is None:
        return None, "invalid accelerometer sample"

    return (
        Sample(
            row_number=row_number,
            timestamp_ms=timestamp,
            machine_id=text_or(
                row.get("Machine_ID") or row.get("machineId")
            ),
            point=text_or(
                row.get("Point") or row.get("point")
            ),
            sensor_type=sensor_type,
            sensor_name=text_or(
                row.get("Sensor_Name") or row.get("sensorName")
            ),
            x=x,
            y=y,
            z=z,
        ),
        None,
    )


def load_current_or_legacy_csv(
    csv_path: str,
) -> Tuple[List[Sample], Dict[str, Any]]:
    """Load accelerometer rows from the current schema or legacy layout."""

    if not os.path.exists(csv_path):
        raise FileNotFoundError(csv_path)

    samples: List[Sample] = []
    invalid_rows: List[Dict[str, Any]] = []
    schema = "unknown"

    with open(
        csv_path,
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as handle:
        reader = csv.reader(handle)
        first = next(reader, None)

        if first is None:
            raise ValueError("CSV file is empty.")

        header = [str(v).strip() for v in first]
        current_schema = {
            "Timestamp_ms",
            "Sensor_Type",
            "Val_0",
            "Val_1",
            "Val_2",
        }.issubset(set(header))

        if current_schema or "Timestamp_ms" in header or "Sensor_Type" in header:
            schema = "SensorMax current named schema"
            dict_reader = csv.DictReader(handle, fieldnames=header)

            for row_number, row in enumerate(dict_reader, start=2):
                sample, error = parse_row(row_number, row)
                if sample is not None:
                    samples.append(sample)
                elif error is not None:
                    invalid_rows.append(
                        {"row_number": row_number, "reason": error}
                    )
        else:
            schema = "legacy positional schema"

            def parse_legacy(values: List[str], row_number: int) -> None:
                if len(values) < 6:
                    invalid_rows.append(
                        {"row_number": row_number, "reason": "too few columns"}
                    )
                    return

                row = {
                    "Timestamp_ms": values[0],
                    "Sensor_Type": values[1],
                    "Sensor_Name": values[2] if len(values) > 2 else "",
                    "Val_0": values[3] if len(values) > 3 else "",
                    "Val_1": values[4] if len(values) > 4 else "",
                    "Val_2": values[5] if len(values) > 5 else "",
                }

                sample, error = parse_row(row_number, row)
                if sample is not None:
                    samples.append(sample)
                elif error is not None:
                    invalid_rows.append(
                        {"row_number": row_number, "reason": error}
                    )

            parse_legacy(first, 1)
            for row_number, values in enumerate(reader, start=2):
                parse_legacy(values, row_number)

    if not samples:
        raise ValueError(
            "No valid accelerometer samples were found in the dataset."
        )

    return samples, {
        "schema": schema,
        "invalid_rows": invalid_rows,
        "input_rows_with_invalid_content": len(invalid_rows),
    }


def detect_anomalies(
    samples: List[Sample],
    *,
    z_threshold: float = 4.0,
    gap_threshold_ms: float = 50.0,
    jump_threshold_ms2: Optional[float] = None,
    clipping_limit_ms2: Optional[float] = None,
) -> Tuple[List[Anomaly], Dict[str, Any]]:
    """Run deterministic data-quality checks on ordered samples."""

    anomalies: List[Anomaly] = []

    timestamps = np.asarray(
        [s.timestamp_ms for s in samples],
        dtype=float,
    )
    x = np.asarray([s.x for s in samples], dtype=float)
    y = np.asarray([s.y for s in samples], dtype=float)
    z = np.asarray([s.z for s in samples], dtype=float)

    # ------------------------------------------------------------------
    # Timestamp integrity
    # ------------------------------------------------------------------
    deltas = np.diff(timestamps)

    duplicate_indices = np.where(deltas == 0)[0]
    backwards_indices = np.where(deltas < 0)[0]
    gap_indices = np.where(deltas > gap_threshold_ms)[0]

    for idx in duplicate_indices:
        sample = samples[idx + 1]
        anomalies.append(
            Anomaly(
                row_number=sample.row_number,
                timestamp_ms=sample.timestamp_ms,
                machine_id=sample.machine_id,
                point=sample.point,
                anomaly_type="DUPLICATE_TIMESTAMP",
                axis="TIMESTAMP",
                value=float(deltas[idx]),
                threshold=0.0,
                detail="Timestamp is identical to the preceding sample.",
            )
        )

    for idx in backwards_indices:
        sample = samples[idx + 1]
        anomalies.append(
            Anomaly(
                row_number=sample.row_number,
                timestamp_ms=sample.timestamp_ms,
                machine_id=sample.machine_id,
                point=sample.point,
                anomaly_type="NON_MONOTONIC_TIMESTAMP",
                axis="TIMESTAMP",
                value=float(deltas[idx]),
                threshold=0.0,
                detail="Timestamp moved backwards relative to the preceding sample.",
            )
        )

    for idx in gap_indices:
        sample = samples[idx + 1]
        anomalies.append(
            Anomaly(
                row_number=sample.row_number,
                timestamp_ms=sample.timestamp_ms,
                machine_id=sample.machine_id,
                point=sample.point,
                anomaly_type="INTER_SAMPLE_GAP",
                axis="TIMESTAMP",
                value=float(deltas[idx]),
                threshold=gap_threshold_ms,
                detail="Inter-sample interval exceeded the configured gap threshold.",
            )
        )

    # ------------------------------------------------------------------
    # Optional configurable clipping / saturation
    # ------------------------------------------------------------------
    if clipping_limit_ms2 is not None:
        for axis_name, values in (
            ("X", x),
            ("Y", y),
            ("Z", z),
        ):
            indices = np.where(
                np.abs(values) >= clipping_limit_ms2
            )[0]

            for idx in indices:
                sample = samples[int(idx)]
                anomalies.append(
                    Anomaly(
                        row_number=sample.row_number,
                        timestamp_ms=sample.timestamp_ms,
                        machine_id=sample.machine_id,
                        point=sample.point,
                        anomaly_type="CLIPPING_SATURATION",
                        axis=axis_name,
                        value=float(values[idx]),
                        threshold=clipping_limit_ms2,
                        detail=(
                            "Absolute acceleration reached/exceeded the "
                            "operator-supplied clipping threshold."
                        ),
                    )
                )

    # ------------------------------------------------------------------
    # Statistical outliers per axis
    # ------------------------------------------------------------------
    for axis_name, values in (
        ("X", x),
        ("Y", y),
        ("Z", z),
    ):
        mean = float(np.mean(values))
        std = float(np.std(values))

        if std <= 0 or not math.isfinite(std):
            continue

        z_scores = np.abs(
            (values - mean) / std
        )

        indices = np.where(
            z_scores > z_threshold
        )[0]

        for idx in indices:
            sample = samples[int(idx)]
            anomalies.append(
                Anomaly(
                    row_number=sample.row_number,
                    timestamp_ms=sample.timestamp_ms,
                    machine_id=sample.machine_id,
                    point=sample.point,
                    anomaly_type="STATISTICAL_OUTLIER",
                    axis=axis_name,
                    value=float(values[idx]),
                    threshold=z_threshold,
                    detail=(
                        f"Axis value is {float(z_scores[idx]):.3f} sigma "
                        f"from the dataset mean."
                    ),
                )
            )

    # ------------------------------------------------------------------
    # Large sample-to-sample jumps
    # ------------------------------------------------------------------
    axis_arrays = {
        "X": x,
        "Y": y,
        "Z": z,
    }

    if jump_threshold_ms2 is not None:
        for axis_name, values in axis_arrays.items():
            jumps = np.abs(np.diff(values))
            indices = np.where(
                jumps >= jump_threshold_ms2
            )[0]

            for idx in indices:
                sample = samples[int(idx + 1)]
                anomalies.append(
                    Anomaly(
                        row_number=sample.row_number,
                        timestamp_ms=sample.timestamp_ms,
                        machine_id=sample.machine_id,
                        point=sample.point,
                        anomaly_type="LARGE_SAMPLE_JUMP",
                        axis=axis_name,
                        value=float(jumps[idx]),
                        threshold=jump_threshold_ms2,
                        detail=(
                            "Absolute sample-to-sample change reached/exceeded "
                            "the configured jump threshold."
                        ),
                    )
                )

    # ------------------------------------------------------------------
    # Summary metrics
    # ------------------------------------------------------------------
    finite_deltas = deltas[
        np.isfinite(deltas)
        & (deltas > 0)
    ]

    duration_ms = (
        float(timestamps[-1] - timestamps[0])
        if len(timestamps) > 1
        else 0.0
    )

    achieved_hz = (
        (len(timestamps) - 1) * 1000.0 / duration_ms
        if duration_ms > 0
        else None
    )

    counts = Counter(
        anomaly.anomaly_type
        for anomaly in anomalies
    )

    summary = {
        "sample_count": len(samples),
        "duration_ms": duration_ms,
        "achieved_sampling_hz": achieved_hz,
        "mean_interval_ms": (
            float(np.mean(finite_deltas))
            if finite_deltas.size
            else None
        ),
        "median_interval_ms": (
            float(np.median(finite_deltas))
            if finite_deltas.size
            else None
        ),
        "interval_std_ms": (
            float(np.std(finite_deltas))
            if finite_deltas.size
            else None
        ),
        "minimum_interval_ms": (
            float(np.min(finite_deltas))
            if finite_deltas.size
            else None
        ),
        "maximum_interval_ms": (
            float(np.max(finite_deltas))
            if finite_deltas.size
            else None
        ),
        "duplicate_timestamp_count": int(len(duplicate_indices)),
        "backwards_timestamp_count": int(len(backwards_indices)),
        "gap_count": int(len(gap_indices)),
        "outlier_count": int(counts.get("STATISTICAL_OUTLIER", 0)),
        "clipping_count": int(counts.get("CLIPPING_SATURATION", 0)),
        "large_jump_count": int(counts.get("LARGE_SAMPLE_JUMP", 0)),
        "anomaly_count": len(anomalies),
        "x_mean_ms2": float(np.mean(x)),
        "y_mean_ms2": float(np.mean(y)),
        "z_mean_ms2": float(np.mean(z)),
        "x_std_ms2": float(np.std(x)),
        "y_std_ms2": float(np.std(y)),
        "z_std_ms2": float(np.std(z)),
    }

    return anomalies, summary


def write_anomalies_csv(
    path: str,
    anomalies: List[Anomaly],
) -> None:
    with open(
        path,
        "w",
        newline="",
        encoding="utf-8",
    ) as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "Row_Number",
            "Timestamp_ms",
            "Machine_ID",
            "Point",
            "Anomaly_Type",
            "Axis",
            "Value",
            "Threshold",
            "Detail",
        ])

        for anomaly in anomalies:
            writer.writerow([
                anomaly.row_number,
                anomaly.timestamp_ms,
                anomaly.machine_id,
                anomaly.point,
                anomaly.anomaly_type,
                anomaly.axis,
                "" if anomaly.value is None else anomaly.value,
                "" if anomaly.threshold is None else anomaly.threshold,
                anomaly.detail,
            ])


def write_report_json(
    path: str,
    *,
    csv_path: str,
    load_metadata: Dict[str, Any],
    summary: Dict[str, Any],
    anomalies: List[Anomaly],
    parameters: Dict[str, Any],
) -> None:
    report = {
        "format": "SensorMaxDataQualityReport",
        "schema_version": 1,
        "created_utc": utc_now(),
        "source_csv": os.path.abspath(csv_path),
        "parameters": parameters,
        "input": load_metadata,
        "summary": summary,
        "anomalies": [asdict(item) for item in anomalies],
        "engineering_boundary": (
            "Data-quality diagnostic only. It is not a machine-condition "
            "diagnosis and does not establish certified instrument accuracy."
        ),
    }

    with open(
        path,
        "w",
        encoding="utf-8",
    ) as handle:
        json.dump(
            report,
            handle,
            indent=2,
            ensure_ascii=False,
        )


def print_report(
    csv_path: str,
    load_metadata: Dict[str, Any],
    summary: Dict[str, Any],
    anomalies: List[Anomaly],
    parameters: Dict[str, Any],
) -> None:
    print("=" * 72)
    print(" SENSORMAX DATASET QUALITY ASSURANCE / ANOMALY REPORT")
    print("=" * 72)
    print(f"Input dataset          : {os.path.abspath(csv_path)}")
    print(f"Input schema           : {load_metadata['schema']}")
    print(f"Accelerometer samples  : {summary['sample_count']:,}")

    hz = summary["achieved_sampling_hz"]
    print(
        "Achieved sampling rate: "
        + (f"{hz:.3f} Hz" if hz is not None else "n/a")
    )

    mean_dt = summary["mean_interval_ms"]
    median_dt = summary["median_interval_ms"]
    print(
        "Mean / median dt      : "
        + (
            f"{mean_dt:.3f} / {median_dt:.3f} ms"
            if mean_dt is not None and median_dt is not None
            else "n/a"
        )
    )

    print("-" * 72)
    print(
        f"Invalid source rows    : "
        f"{load_metadata['input_rows_with_invalid_content']:,}"
    )
    print(
        f"Duplicate timestamps   : "
        f"{summary['duplicate_timestamp_count']:,}"
    )
    print(
        f"Backwards timestamps   : "
        f"{summary['backwards_timestamp_count']:,}"
    )
    print(
        f"Inter-sample gaps      : "
        f"{summary['gap_count']:,}"
        f"  (>{parameters['gap_threshold_ms']} ms)"
    )
    print(
        f"Statistical outliers   : "
        f"{summary['outlier_count']:,}"
        f"  (> {parameters['z_threshold']}σ)"
    )
    print(
        f"Clipping/saturation    : "
        f"{summary['clipping_count']:,}"
    )
    print(
        f"Large sample jumps     : "
        f"{summary['large_jump_count']:,}"
    )

    print("-" * 72)
    print(
        "Axis mean              : "
        f"X={summary['x_mean_ms2']:+.5f}, "
        f"Y={summary['y_mean_ms2']:+.5f}, "
        f"Z={summary['z_mean_ms2']:+.5f} m/s²"
    )
    print(
        "Axis std (1σ)          : "
        f"X={summary['x_std_ms2']:.5f}, "
        f"Y={summary['y_std_ms2']:.5f}, "
        f"Z={summary['z_std_ms2']:.5f} m/s²"
    )

    print("-" * 72)
    if anomalies:
        print(
            f"ANOMALIES DETECTED     : {len(anomalies):,}"
        )

        counts = Counter(
            item.anomaly_type
            for item in anomalies
        )

        for name, count in sorted(counts.items()):
            print(f"  {name:<24}: {count:,}")
    else:
        print("ANOMALIES DETECTED     : 0")

    print("=" * 72)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "SensorMax dataset QA and anomaly detector."
        )
    )

    parser.add_argument(
        "csv_file",
        help="SensorMax raw CSV dataset.",
    )

    parser.add_argument(
        "--z-threshold",
        type=float,
        default=4.0,
        help="Per-axis statistical outlier threshold in sigma. Default: 4.0",
    )

    parser.add_argument(
        "--gap-threshold-ms",
        type=float,
        default=50.0,
        help="Flag inter-sample intervals above this value. Default: 50 ms",
    )

    parser.add_argument(
        "--clipping-limit-ms2",
        type=float,
        default=None,
        help=(
            "Optional operator-supplied absolute acceleration clipping limit. "
            "Disabled by default because full-scale range is device-specific."
        ),
    )

    parser.add_argument(
        "--jump-threshold-ms2",
        type=float,
        default=None,
        help=(
            "Optional absolute sample-to-sample axis-change threshold. "
            "Disabled by default."
        ),
    )

    parser.add_argument(
        "--output-csv",
        default=None,
        help="Anomaly CSV output. Default: <input>_anomalies.csv",
    )

    parser.add_argument(
        "--output-json",
        default=None,
        help="Report JSON output. Default: <input>_quality_report.json",
    )

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    if args.z_threshold <= 0:
        parser.error("--z-threshold must be > 0")

    if args.gap_threshold_ms <= 0:
        parser.error("--gap-threshold-ms must be > 0")

    if args.clipping_limit_ms2 is not None and args.clipping_limit_ms2 <= 0:
        parser.error("--clipping-limit-ms2 must be > 0")

    if args.jump_threshold_ms2 is not None and args.jump_threshold_ms2 <= 0:
        parser.error("--jump-threshold-ms2 must be > 0")

    try:
        samples, load_metadata = load_current_or_legacy_csv(
            args.csv_file
        )
    except Exception as exc:
        print(f"[ERROR] {exc}")
        return 1

    anomalies, summary = detect_anomalies(
        samples,
        z_threshold=args.z_threshold,
        gap_threshold_ms=args.gap_threshold_ms,
        jump_threshold_ms2=args.jump_threshold_ms2,
        clipping_limit_ms2=args.clipping_limit_ms2,
    )

    output_csv = (
        args.output_csv
        if args.output_csv
        else os.path.splitext(args.csv_file)[0]
        + "_anomalies.csv"
    )

    output_json = (
        args.output_json
        if args.output_json
        else os.path.splitext(args.csv_file)[0]
        + "_quality_report.json"
    )

    parameters = {
        "z_threshold": args.z_threshold,
        "gap_threshold_ms": args.gap_threshold_ms,
        "clipping_limit_ms2": args.clipping_limit_ms2,
        "jump_threshold_ms2": args.jump_threshold_ms2,
    }

    write_anomalies_csv(
        output_csv,
        anomalies,
    )

    write_report_json(
        output_json,
        csv_path=args.csv_file,
        load_metadata=load_metadata,
        summary=summary,
        anomalies=anomalies,
        parameters=parameters,
    )

    print_report(
        args.csv_file,
        load_metadata,
        summary,
        anomalies,
        parameters,
    )

    print(f"Anomaly CSV saved     : {os.path.abspath(output_csv)}")
    print(f"Quality report saved   : {os.path.abspath(output_json)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
