#!/usr/bin/env python3
"""
SensorMax Metadata JSON Exporter

Purpose
-------
Convert a SensorMax CSV export into a structured metadata/session JSON
document suitable for offline inspection, cloud ingestion, engineering
evidence packaging, and downstream processing.

AndroidApp is treated as the source of truth and is NOT modified here.

Current Android raw CSV schema
------------------------------

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

Current Android analysis CSV schema
-----------------------------------

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

Design rules
------------
- Never hard-code a specific phone model.
- Never hard-code another phone's calibration.
- Preserve machine/point context from the CSV.
- Preserve all source records.
- Explicitly mark unavailable provenance fields.
- Derive session statistics from actual data.
- Keep calibration separate from machine baseline.
- Do not present missing calibration as valid calibration.

Optional calibration input
---------------------------
A calibration JSON file may be supplied as the second command-line
argument:

    python export_json.py raw.csv calibration.json

The calibration JSON can be any JSON object containing a calibration
record. It will be copied under "calibration_profile" without silently
changing its values.

Usage
-----
    python export_json.py <csv>

or:

    python export_json.py <csv> <calibration_json>

Output
------
    <input_filename>.json
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pandas as pd


# ============================================================
# Constants
# ============================================================

SCHEMA_VERSION = "SensorMax.MetadataJSON.v1"

ANDROID_RAW_COLUMNS = [
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

ANDROID_ANALYSIS_COLUMNS = [
    "Timestamp_ms",
    "Machine_ID",
    "Point",
    "Sample_Rate_Hz",
    "RMS_X_ms2",
    "RMS_Y_ms2",
    "RMS_Z_ms2",
    "Overall_RMS_Accel_ms2",
    "Overall_RMS_Velocity_mms",
    "Overall_RMS_Displacement_um",
    "Dominant_Axis",
    "Dominant_Freq_Hz",
    "Dominant_Accel_Amplitude_ms2",
    "Envelope_Peak_Freq_Hz",
    "ISO20816_Zone",
    "Bearing_Match",
    "RPM_Input",
    "Impact_Event",
    "Snapshot_Triggered",
]


# ============================================================
# General helpers
# ============================================================

def safe_text(
    value: Any,
    default: str = "",
) -> str:
    """
    Safely convert a value to text.
    """

    if value is None:
        return default

    if isinstance(value, float) and math.isnan(value):
        return default

    text = str(value).strip()

    return text if text else default


def safe_float(
    value: Any,
    default: float | None = None,
) -> float | None:
    """
    Safely convert a value to float.
    """

    if value is None:
        return default

    try:

        number = float(value)

        if not math.isfinite(number):
            return default

        return number

    except (
        TypeError,
        ValueError,
    ):
        return default


def safe_int(
    value: Any,
    default: int | None = None,
) -> int | None:
    """
    Safely convert a value to integer.
    """

    number = safe_float(
        value,
        None,
    )

    if number is None:
        return default

    return int(number)


def json_safe(
    value: Any,
) -> Any:
    """
    Convert Pandas / NumPy scalar values and NaN-like values into
    JSON-safe Python values.

    This avoids NaN appearing in exported JSON.
    """

    if value is None:
        return None

    if isinstance(value, float):

        if math.isnan(value) or math.isinf(value):
            return None

        return value

    if hasattr(value, "item"):

        try:
            return json_safe(
                value.item()
            )
        except Exception:
            pass

    if isinstance(value, dict):

        return {
            str(k): json_safe(v)
            for k, v in value.items()
        }

    if isinstance(value, list):

        return [
            json_safe(v)
            for v in value
        ]

    return value


def utc_timestamp() -> str:
    """
    Creation timestamp for this JSON artifact.
    """

    return (
        datetime.now(timezone.utc)
        .isoformat()
    )


def format_epoch_ms(
    timestamp_ms: float | None,
) -> str | None:
    """
    Convert epoch milliseconds into an ISO-8601 UTC timestamp.
    """

    if timestamp_ms is None:
        return None

    try:

        dt = datetime.fromtimestamp(
            timestamp_ms / 1000.0,
            tz=timezone.utc,
        )

        return dt.isoformat()

    except (
        OverflowError,
        OSError,
        ValueError,
    ):
        return None


def unique_clean_values(
    series: pd.Series,
) -> list[str]:
    """
    Return sorted non-empty unique text values.
    """

    values = {
        safe_text(value)
        for value in series
        if safe_text(value)
    }

    return sorted(values)


# ============================================================
# Schema detection
# ============================================================

def detect_schema(
    df: pd.DataFrame,
) -> str:
    """
    Identify whether the input is the current SensorMax raw CSV,
    current analysis CSV, or an unknown/legacy CSV.
    """

    columns = set(
        str(column)
        for column in df.columns
    )

    if set(ANDROID_RAW_COLUMNS).issubset(
        columns
    ):
        return "android_raw_v1"

    if set(ANDROID_ANALYSIS_COLUMNS).issubset(
        columns
    ):
        return "android_analysis_v1"

    return "unknown"


# ============================================================
# Sampling / timing statistics
# ============================================================

def calculate_timing(
    df: pd.DataFrame,
) -> dict[str, Any]:
    """
    Calculate duration, sample count, effective rate, and timestamp
    information from Timestamp_ms.

    The Android timestamp is the exported wall-clock timestamp in the
    current CSV. This function does not pretend it is equivalent to
    SensorEvent.timestamp.
    """

    if "Timestamp_ms" not in df.columns:

        return {
            "duration_ms": None,
            "effective_sample_rate_hz": None,
            "first_timestamp_ms": None,
            "last_timestamp_ms": None,
            "first_timestamp_utc": None,
            "last_timestamp_utc": None,
            "sample_count": int(len(df)),
        }

    timestamps = pd.to_numeric(
        df["Timestamp_ms"],
        errors="coerce",
    ).dropna()

    if timestamps.empty:

        return {
            "duration_ms": None,
            "effective_sample_rate_hz": None,
            "first_timestamp_ms": None,
            "last_timestamp_ms": None,
            "first_timestamp_utc": None,
            "last_timestamp_utc": None,
            "sample_count": int(len(df)),
        }

    first_ts = float(
        timestamps.min()
    )

    last_ts = float(
        timestamps.max()
    )

    duration_ms = max(
        0.0,
        last_ts - first_ts,
    )

    if duration_ms > 0:

        effective_hz = (
            len(timestamps) /
            (duration_ms / 1000.0)
        )

    else:

        effective_hz = None

    return {
        "duration_ms": int(
            round(duration_ms)
        ),
        "effective_sample_rate_hz": (
            round(
                effective_hz,
                4,
            )
            if effective_hz is not None
            else None
        ),
        "first_timestamp_ms": int(
            round(first_ts)
        ),
        "last_timestamp_ms": int(
            round(last_ts)
        ),
        "first_timestamp_utc":
            format_epoch_ms(first_ts),
        "last_timestamp_utc":
            format_epoch_ms(last_ts),
        "sample_count": int(
            len(df)
        ),
    }


# ============================================================
# Asset / measurement context
# ============================================================

def extract_context(
    df: pd.DataFrame,
) -> dict[str, Any]:
    """
    Extract machine and measurement-point context.
    """

    context = {
        "machine_ids": [],
        "measurement_points": [],
    }

    if "Machine_ID" in df.columns:

        context["machine_ids"] = (
            unique_clean_values(
                df["Machine_ID"]
            )
        )

    if "Point" in df.columns:

        context["measurement_points"] = (
            unique_clean_values(
                df["Point"]
            )
        )

    return context


# ============================================================
# Sensor provenance
# ============================================================

def extract_sensor_context(
    df: pd.DataFrame,
) -> dict[str, Any]:
    """
    Extract sensor type/name information that is actually present
    in the exported dataset.
    """

    sensors: list[dict[str, Any]] = []

    if "Sensor_Type" not in df.columns:

        return {
            "sensors": sensors,
            "device_metadata_status":
                "NOT_AVAILABLE_IN_CSV",
        }

    grouped = df.groupby(
        [
            "Sensor_Type",
            "Sensor_Name",
        ],
        dropna=False,
    )

    for (
        (sensor_type, sensor_name),
        group,
    ) in grouped:

        stype = safe_int(
            sensor_type,
            0,
        )

        sensors.append(
            {
                "sensor_type": stype,
                "sensor_name": safe_text(
                    sensor_name,
                    "UNKNOWN",
                ),
                "sample_count": int(
                    len(group)
                ),
            }
        )

    sensors.sort(
        key=lambda item:
            (
                item["sensor_type"] or 0,
                item["sensor_name"],
            )
    )

    return {
        "sensors": sensors,
        "device_metadata_status":
            "NOT_AVAILABLE_IN_CSV",
    }


# ============================================================
# Optional device metadata
# ============================================================

def extract_optional_device_metadata(
    df: pd.DataFrame,
) -> dict[str, Any]:
    """
    Use device fields only when a future/exported CSV actually contains
    them. Never insert hard-coded device identity.

    The current Android raw CSV does not contain these fields, so the
    exported JSON explicitly reports them as unavailable.
    """

    manufacturer_columns = [
        "DeviceManufacturer",
        "Manufacturer",
    ]

    model_columns = [
        "DeviceModel",
        "Model",
    ]

    manufacturer = None

    model = None

    for column in manufacturer_columns:

        if column in df.columns:

            values = unique_clean_values(
                df[column]
            )

            if values:

                manufacturer = values[0]

                break

    for column in model_columns:

        if column in df.columns:

            values = unique_clean_values(
                df[column]
            )

            if values:

                model = values[0]

                break

    return {
        "manufacturer": manufacturer,
        "model": model,
        "status": (
            "AVAILABLE"
            if manufacturer or model
            else "NOT_AVAILABLE_IN_CSV"
        ),
    }


# ============================================================
# Analysis summary
# ============================================================

def extract_analysis_context(
    df: pd.DataFrame,
) -> dict[str, Any]:
    """
    Extract meaningful analysis-session context when the input is
    an Android analysis CSV.
    """

    result: dict[str, Any] = {
        "analysis_rows": int(
            len(df)
        ),
    }

    if "Sample_Rate_Hz" in df.columns:

        values = pd.to_numeric(
            df["Sample_Rate_Hz"],
            errors="coerce",
        ).dropna()

        result["sample_rate_hz"] = (
            round(
                float(values.mean()),
                4,
            )
            if not values.empty
            else None
        )

    if "RPM_Input" in df.columns:

        values = pd.to_numeric(
            df["RPM_Input"],
            errors="coerce",
        ).dropna()

        result["rpm_values"] = sorted(
            {
                round(
                    float(value),
                    3,
                )
                for value in values
                if math.isfinite(
                    float(value)
                )
                and float(value) > 0
            }
        )

    if "Dominant_Axis" in df.columns:

        result["dominant_axes"] = (
            unique_clean_values(
                df["Dominant_Axis"]
            )
        )

    if "ISO20816_Zone" in df.columns:

        result["iso_zones"] = (
            unique_clean_values(
                df["ISO20816_Zone"]
            )
        )

    if "Bearing_Match" in df.columns:

        result["bearing_matches"] = (
            unique_clean_values(
                df["Bearing_Match"]
            )
        )

    if "Impact_Event" in df.columns:

        values = (
            df["Impact_Event"]
            .astype(str)
            .str.strip()
            .str.lower()
        )

        result["impact_event_count"] = int(
            values.isin(
                [
                    "true",
                    "1",
                    "yes",
                ]
            ).sum()
        )

    return result


# ============================================================
# Calibration
# ============================================================

def load_calibration(
    calibration_path: Path | None,
) -> dict[str, Any]:
    """
    Load an optional calibration JSON.

    No synthetic calibration values are created.
    """

    if calibration_path is None:

        return {
            "status": "NOT_AVAILABLE",
            "reason": (
                "Current Android CSV export does not contain "
                "CalibrationRecord fields."
            ),
            "record": None,
        }

    if not calibration_path.exists():

        return {
            "status": "NOT_AVAILABLE",
            "reason": (
                f"Calibration file was not found: "
                f"{calibration_path}"
            ),
            "record": None,
        }

    try:

        with calibration_path.open(
            "r",
            encoding="utf-8",
        ) as f:

            record = json.load(f)

        return {
            "status": "AVAILABLE",
            "source_file":
                calibration_path.name,
            "record":
                json_safe(record),
        }

    except (
        OSError,
        json.JSONDecodeError,
    ) as exc:

        return {
            "status": "ERROR",
            "source_file":
                calibration_path.name,
            "reason":
                str(exc),
            "record": None,
        }


# ============================================================
# Raw telemetry
# ============================================================

def build_raw_telemetry(
    df: pd.DataFrame,
) -> list[dict[str, Any]]:
    """
    Preserve raw source records in JSON.

    For large engineering datasets this can create a large file,
    but it is intentional here because this exporter is an evidence
    / ingress format rather than a compressed analytics format.
    """

    records = (
        df.astype(object)
        .where(
            pd.notna(df),
            None,
        )
        .to_dict(
            orient="records"
        )
    )

    return [
        json_safe(record)
        for record in records
    ]


# ============================================================
# Provenance
# ============================================================

def build_provenance(
    csv_path: Path,
    schema: str,
    calibration_path: Path | None,
) -> dict[str, Any]:

    return {
        "source_file":
            csv_path.name,
        "source_schema":
            schema,
        "exported_at_utc":
            utc_timestamp(),
        "generator":
            "SensorMax LaptopSuite export_json.py",
        "android_app_modified":
            False,
        "calibration_source":
            (
                calibration_path.name
                if calibration_path
                else None
            ),
        "warnings": [
            (
                "Current Android raw CSV does not export "
                "device manufacturer/model."
            ),
            (
                "Current Android raw CSV does not export "
                "CalibrationRecord fields."
            ),
            (
                "No calibration values were invented or "
                "copied from another device."
            ),
        ],
    }


# ============================================================
# Main JSON builder
# ============================================================

def compile_to_json(
    csv_path: Path,
    calibration_path: Path | None = None,
) -> Path:

    if not csv_path.exists():

        raise FileNotFoundError(
            f"Target dataset not found: {csv_path}"
        )

    print(
        f"[*] Processing offline dataset: "
        f"{csv_path}"
    )

    df = pd.read_csv(
        csv_path
    )

    schema = detect_schema(
        df
    )

    print(
        f"[*] Detected schema: {schema}"
    )

    timing = calculate_timing(
        df
    )

    context = extract_context(
        df
    )

    sensor_context = (
        extract_sensor_context(
            df
        )
    )

    device_metadata = (
        extract_optional_device_metadata(
            df
        )
    )

    analysis_context = (
        extract_analysis_context(
            df
        )
    )

    calibration = load_calibration(
        calibration_path
    )

    # --------------------------------------------------------
    # Build metadata object
    # --------------------------------------------------------

    master_template = {

        "schema": {
            "name":
                "SensorMax Metadata JSON",

            "version":
                SCHEMA_VERSION,

            "source_schema":
                schema,

            "description":
                (
                    "Session metadata, provenance, sensor context, "
                    "calibration status, and source telemetry."
                ),
        },

        "metadata": {

            "acquisition_mode":
                "Offline_Storage",

            "source_file":
                csv_path.name,

            "exported_at_utc":
                utc_timestamp(),

            "session": {

                "duration_ms":
                    timing["duration_ms"],

                "sample_count":
                    timing["sample_count"],

                "effective_sample_rate_hz":
                    timing[
                        "effective_sample_rate_hz"
                    ],

                "first_timestamp_ms":
                    timing[
                        "first_timestamp_ms"
                    ],

                "last_timestamp_ms":
                    timing[
                        "last_timestamp_ms"
                    ],

                "first_timestamp_utc":
                    timing[
                        "first_timestamp_utc"
                    ],

                "last_timestamp_utc":
                    timing[
                        "last_timestamp_utc"
                    ],
            },

            "asset_context":
                context,

            "analysis_context":
                analysis_context,

            "device":
                device_metadata,

            "sensor_context":
                sensor_context,
        },

        "calibration_profile":
            calibration,

        "provenance":
            build_provenance(
                csv_path,
                schema,
                calibration_path,
            ),

        "sensor_telemetry":
            build_raw_telemetry(
                df
            ),
    }

    # --------------------------------------------------------
    # Output
    # --------------------------------------------------------

    output_path = (
        csv_path.with_suffix(
            ".json"
        )
    )

    with output_path.open(
        "w",
        encoding="utf-8",
    ) as json_file:

        json.dump(
            json_safe(
                master_template
            ),
            json_file,
            indent=4,
            ensure_ascii=False,
            allow_nan=False,
        )

        json_file.write(
            "\n"
        )

    print(
        "[SUCCESS] SensorMax JSON exported:"
    )

    print(
        f"          {output_path}"
    )

    print(
        f"          records={len(df)}"
    )

    print(
        f"          schema={schema}"
    )

    print(
        "          calibration=" +
        calibration["status"]
    )

    print(
        "          device=" +
        device_metadata["status"]
    )

    return output_path


# ============================================================
# CLI
# ============================================================

def build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        description=(
            "Convert a SensorMax CSV into a structured "
            "metadata/provenance JSON export."
        )
    )

    parser.add_argument(
        "csv_file",
        help=(
            "SensorMax raw or analysis CSV"
        ),
    )

    parser.add_argument(
        "calibration_json",
        nargs="?",
        default=None,
        help=(
            "Optional calibration JSON containing a "
            "CalibrationRecord"
        ),
    )

    return parser


def main() -> int:

    parser = build_parser()

    args = parser.parse_args()

    csv_path = (
        Path(
            args.csv_file
        )
        .expanduser()
        .resolve()
    )

    calibration_path = None

    if args.calibration_json:

        calibration_path = (
            Path(
                args.calibration_json
            )
            .expanduser()
            .resolve()
        )

    try:

        compile_to_json(
            csv_path,
            calibration_path,
        )

        return 0

    except FileNotFoundError as exc:

        print(
            f"[ERROR] {exc}"
        )

        return 1

    except pd.errors.EmptyDataError:

        print(
            "[ERROR] CSV file is empty."
        )

        return 1

    except pd.errors.ParserError as exc:

        print(
            f"[ERROR] CSV parsing failed: {exc}"
        )

        return 1

    except PermissionError as exc:

        print(
            f"[ERROR] Permission denied: {exc}"
        )

        return 1

    except Exception as exc:

        print(
            "[ERROR] JSON export failed: "
            f"{type(exc).__name__}: {exc}"
        )

        return 1


if __name__ == "__main__":
    raise SystemExit(
        main()
    )